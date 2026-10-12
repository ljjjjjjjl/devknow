package com.devknow.retrieval;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.embedding.EmbeddingService;
import com.devknow.common.embedding.PgVector;
import com.devknow.common.exception.BizException;
import com.devknow.domain.dto.ChunkSearchHit;
import com.devknow.domain.dto.SearchResult;
import com.devknow.domain.entity.Document;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.domain.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混合检索：向量一路 + 词法一路，用 RRF（Reciprocal Rank Fusion）融合。
 *
 * <p><b>为什么需要两路</b>（实测数据，来自本项目 8580 个 chunk）：
 * <ul>
 *   <li>词法路赢的场景：查 {@code @ConditionalOnMissingBean} 时它直接命中标题里写着该注解的 issue；
 *       向量路只能给出"弱相关"的几条</li>
 *   <li>向量路赢的场景：中文 query（"如何配置数据源"）检索英文语料，
 *       词法路返回 0（中文词素在英文索引里不存在），向量路精准命中 DataSource 配置文档</li>
 *   <li>两路都弱的场景：高频词 {@code @Configuration}（{@code configur} 覆盖 27% 语料），
 *       这时靠的是 rerank 而不是加一路检索</li>
 * </ul>
 *
 * <p><b>为什么用 RRF 而不是加权求和</b>：两路的分数尺度完全不同 ——
 * 向量路是 {@code 1-cosine}（0~1），词法路是 {@code ts_rank}（另一套量纲，
 * 且实测会出现两条无关 chunk 分数完全相同的情况）。
 * 加权平均需要归一化 + 调参；RRF 只用<b>排名</b>，{@code 1/(k+rank)} 天然把两路拉到同一尺度。
 *
 * <p>{@code k=60} 是 RRF 原论文的默认值：k 越大排名差异被压得越平，
 * k 越小头部越重要。60 是"头部集中"与"长尾覆盖"之间的平衡点。
 */
@Slf4j
@Service
public class RetrievalService {

    /** RRF 公式里的常数 k */
    private static final int RRF_K = 60;

    /** 每一路至少取多少个候选参与融合 */
    private static final int RRF_CANDIDATES = 20;

    private final EmbeddingService embeddingService;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public RetrievalService(EmbeddingService embeddingService,
                            DocumentChunkMapper chunkMapper,
                            DocumentMapper documentMapper) {
        this.embeddingService = embeddingService;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 混合检索入口。
     *
     * @param topK 最终返回条数
     * @throws BizException query 为 null 或空白时
     */
    public List<SearchResult> search(String query, int topK) {
        // 不校验的话会一路走到 embedOne → List.of(null) 抛 NPE，
        // 表现为 500 而不是 400，且堆栈位置与参数无关、难定位。
        // 校验放在服务层而不是 Controller：检索语义的约束只有一份。
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }
        float[] queryVec = embeddingService.embedOne(query);

        // 候选数不能小于 topK：否则两路各取 20 条，融合后最多只有 40 条，
        // 而 SearchRequest 允许 topK 到 50 —— 会出现"要 50 条只给 40 条"的静默截断。
        int candidates = Math.max(RRF_CANDIDATES, topK);

        List<ChunkSearchHit> vectorHits =
                chunkMapper.searchByVector(PgVector.of(queryVec), candidates);
        List<ChunkSearchHit> keywordHits =
                chunkMapper.searchByKeyword(query, candidates);

        log.debug("检索候选: query={}, vectorHits={}, keywordHits={}",
                query, vectorHits.size(), keywordHits.size());

        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, ChunkSearchHit> hitById = new LinkedHashMap<>();
        Map<Long, Integer> vectorRank = new HashMap<>();
        Map<Long, Integer> keywordRank = new HashMap<>();

        collect(vectorHits, rrfScores, hitById, vectorRank);
        collect(keywordHits, rrfScores, hitById, keywordRank);

        if (rrfScores.isEmpty()) {
            return List.of();
        }

        List<Long> topIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<Long, Double>comparingByKey()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();

        return enrich(topIds, hitById, rrfScores, vectorRank, keywordRank);
    }

    // ------------------------------------------------------------------

    /**
     * 把一路的命中累加进 RRF 分数。
     *
     * <p>{@code merge} 是<b>累加</b>不是覆盖：同一个 chunk 被两路都命中时分数叠加，
     * 这正是 RRF 让"共识"胜出的机制。
     *
     * <p>{@code putIfAbsent} 保证同一 chunk 只保留一份 content（两路的正文是同一条记录）。
     *
     * @param rankOf 输出参数：记录该 chunk 在这一路里的排名（从 1 开始）
     */
    private static void collect(List<ChunkSearchHit> hits,
                                Map<Long, Double> rrfScores,
                                Map<Long, ChunkSearchHit> hitById,
                                Map<Long, Integer> rankOf) {
        for (int i = 0; i < hits.size(); i++) {
            Long id = hits.get(i).chunkId();
            rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            hitById.putIfAbsent(id, hits.get(i));
            rankOf.putIfAbsent(id, i + 1);
        }
    }

    /**
     * 按最终顺序补齐 {@code document} 上的 title / url。
     *
     * <p>为什么不在检索 SQL 里 JOIN：
     * <ol>
     *   <li>JOIN 会让 LIMIT 的语义含糊（限制 chunk 还是 join 后的行）</li>
     *   <li>两路检索都要补同一批字段，写一处比写两遍 JOIN 好</li>
     * </ol>
     * 一次 {@code selectBatchIds} 解决，避免 N+1；先对 documentId 去重，
     * 因为同一个文档的多个 chunk 可能同时命中。
     */
    private List<SearchResult> enrich(List<Long> orderedIds,
                                      Map<Long, ChunkSearchHit> hitById,
                                      Map<Long, Double> scores,
                                      Map<Long, Integer> vectorRank,
                                      Map<Long, Integer> keywordRank) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Set<Long> docIds = orderedIds.stream()
                .map(id -> hitById.get(id).documentId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, Document> docs = documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        List<SearchResult> results = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            ChunkSearchHit hit = hitById.get(id);
            Document doc = docs.get(hit.documentId());
            results.add(new SearchResult(
                    id,
                    hit.documentId(),
                    doc == null ? null : doc.getTitle(),
                    hit.headingPath(),
                    hit.content(),
                    scores.get(id),
                    vectorRank.get(id),
                    keywordRank.get(id),
                    doc == null ? null : doc.getUrl()));
        }
        return results;
    }
}