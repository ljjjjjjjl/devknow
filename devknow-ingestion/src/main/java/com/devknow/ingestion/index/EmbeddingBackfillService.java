package com.devknow.ingestion.index;

import com.devknow.common.embedding.EmbeddingService;
import com.devknow.domain.entity.DocumentChunk;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.retrieval.config.EmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化回填：把尚未嵌入的 chunk 批量交给 {@link EmbeddingService} 并写回数据库。
 *
 * <p><b>整个回填机制的状态就是一条 SQL</b>：{@code WHERE embedding IS NULL}。
 * 它同时是待办列表、进度条、幂等键和断点位置 —— 所以这里不需要维护任何进度表。
 * 本轮扫到多少、成功多少只用于日志与返回值，真正的"还剩多少"由调用方查数据库。
 *
 * <p><b>三个不在这里做的事：</b>
 * <ul>
 *   <li><b>不重试</b> —— 重试交给 {@code ingestion_job} 的 {@code max_retry}</li>
 *   <li><b>不判空内容</b> —— {@code selectUnembeddedBySource} 已在 SQL 层用正则过滤</li>
 *   <li><b>不建任务</b> —— 由调度器负责创建 EMBED job</li>
 * </ul>
 */
@Slf4j
@Service
public class EmbeddingBackfillService {

    /**
     * 二分定位的最大递归深度。
     *
     * <p>单条"毒丸"数据会让它所在的整批请求失败。二分可以把定位代价从 O(n) 降到
     * O(log n) 次请求；深度上限只是防止异常情况下递归失控 ——
     * 从 100 条缩到 1 条只需 7 层，10 层足够。
     */
    private static final int MAX_SPLIT_DEPTH = 10;

    private final EmbeddingService embeddingService;
    private final DocumentChunkMapper documentChunkMapper;
    private final EmbeddingProperties properties;
    private final TransactionTemplate transactionTemplate;

    public EmbeddingBackfillService(EmbeddingService embeddingService,
                                    DocumentChunkMapper documentChunkMapper,
                                    EmbeddingProperties properties,
                                    TransactionTemplate transactionTemplate) {
        this.embeddingService = embeddingService;
        this.documentChunkMapper = documentChunkMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 处理一轮回填。
     *
     * @param sourceId       知识源 id（{@code ingestion_job.source_id} 是 NOT NULL，
     *                       所以回填按源进行）
     * @param limitPerRound  本轮最多取多少条待办；有界工作，避免一轮跑太久拖住调度器
     * @return 本轮<b>成功写入</b>的条数。剩余待办数由调用方自行查询
     */
    public int backfill(Long sourceId, int limitPerRound) {
        if (!properties.isEnabled()) {
            log.debug("向量化已禁用，跳过回填: sourceId={}", sourceId);
            return 0;
        }

        List<DocumentChunk> pending =
                documentChunkMapper.selectUnembeddedBySource(sourceId, limitPerRound);
        if (pending.isEmpty()) {
            return 0;
        }

        int written = 0;
        for (List<DocumentChunk> unit : splitUnits(pending)) {
            written += processUnit(unit, 0);
        }

        int notWritten = pending.size() - written;
        if (notWritten > 0) {
            log.warn("回填未全部写入: sourceId={}, 取到 {} 条, 成功 {} 条, 未写入 {} 条",
                    sourceId, pending.size(), written, notWritten);
        } else {
            log.info("回填完成: sourceId={}, 取到 {} 条, 全部写入", sourceId, written);
        }
        return written;
    }

    // ------------------------------------------------------------------
    // 分批
    // ------------------------------------------------------------------

    /**
     * 把扫描结果切成"一次 {@code embed()} 调用"的单元。
     *
     * <p><b>为什么在调用方切，而不是把 1000 条一次性丢给 {@code embed()}</b>：
     * HTTP 请求数<b>完全相同</b>（{@code embed()} 内部本来就按同一组上限切批），
     * 但切成单元换来两个实质好处：
     * <ol>
     *   <li>每个单元一个事务。一次失败只丢一个单元的进度，不会丢掉整轮 1000 条</li>
     *   <li>二分定位毒丸时起点是 100 条而不是 1000 条，重试的请求数少一个数量级</li>
     * </ol>
     * 上限直接复用 {@link EmbeddingProperties} 的 batch 配置，保证与 {@code embed()}
     * 内部口径一致 —— 两处不一致会导致"切出来的单元比模型能接受的还大"。
     *
     * <p>token 数取自 {@code document_chunk.content_tokens}（切分时已算好），
     * 不在这里重新估算。
     */
    private List<List<DocumentChunk>> splitUnits(List<DocumentChunk> chunks) {
        List<List<DocumentChunk>> units = new ArrayList<>();
        List<DocumentChunk> current = new ArrayList<>();
        int tokens = 0;

        for (DocumentChunk chunk : chunks) {
            int t = chunk.getContentTokens() == null ? 0 : chunk.getContentTokens();
            boolean overflow = !current.isEmpty()
                    && (current.size() + 1 > properties.getBatchMaxChunks()
                        || tokens + t > properties.getBatchMaxTokens());
            if (overflow) {
                units.add(current);
                current = new ArrayList<>();
                tokens = 0;
            }
            current.add(chunk);
            tokens += t;
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    // ------------------------------------------------------------------
    // 处理与失败隔离
    // ------------------------------------------------------------------

    /**
     * 处理一个单元；失败时二分定位。
     *
     * <p><b>为什么二分不算"自己重试"</b>：它不是"把同一个请求再发一次"，
     * 而是<b>识别哪一条数据有问题</b>。前者会掩盖问题并浪费配额，
     * 后者把"一条脏数据毒死一整批 100 条"降级为"只丢这一条"。
     *
     * @param depth 二分递归深度，超过 {@link #MAX_SPLIT_DEPTH} 时放弃本单元
     */
    private int processUnit(List<DocumentChunk> unit, int depth) {
        if (unit.isEmpty()) {
            return 0;
        }
        try {
            return embedAndSave(unit);
        } catch (Exception e) {
            if (unit.size() == 1) {
                DocumentChunk bad = unit.get(0);
                log.error("单个 chunk 向量化失败，跳过它: chunkId={}, documentId={}, tokens={}",
                        bad.getId(), bad.getDocumentId(), bad.getContentTokens(), e);
                return 0;
            }
            if (depth >= MAX_SPLIT_DEPTH) {
                log.error("二分定位达到深度上限 {}，放弃本单元 {} 条",
                        MAX_SPLIT_DEPTH, unit.size(), e);
                return 0;
            }
            int mid = unit.size() / 2;
            log.warn("单元向量化失败（{} 条），二分定位: depth={}, 原因={}",
                    unit.size(), depth, e.getMessage());
            return processUnit(unit.subList(0, mid), depth + 1)
                 + processUnit(unit.subList(mid, unit.size()), depth + 1);
        }
    }

    /**
     * 一次 {@code embed()} 调用 + 对应的写回，<b>同一事务</b>。
     *
     * <p>事务边界＝一个单元，所以事务内只有 1 次 HTTP 调用（约数百毫秒）加几十到一百条
     * UPDATE，不会出现"长事务持锁数分钟"的问题。
     */
    private int embedAndSave(List<DocumentChunk> unit) {
        Integer written = transactionTemplate.execute(status -> {
            List<String> texts = new ArrayList<>(unit.size());
            for (DocumentChunk chunk : unit) {
                texts.add(chunk.getContent());
            }

            List<float[]> vectors = embeddingService.embed(texts);
            if (vectors.size() != unit.size()) {
                // 走到这里说明 EmbeddingService 违反了接口契约（必须等长或抛异常）。
                // 此时绝不能继续按索引取向量 —— 会把错位的向量写进数据库。
                throw new IllegalStateException("向量数量与输入不符: 输入 " + unit.size()
                        + " 条，返回 " + vectors.size() + " 条");
            }

            String model = embeddingService.modelName();
            int saved = 0;
            for (int i = 0; i < unit.size(); i++) {
                Long chunkId = unit.get(i).getId();
                int affected = documentChunkMapper.updateEmbedding(
                        chunkId, toPgVector(vectors.get(i)), model);
                if (affected == 0) {
                    // 不是错误：这行已被其他执行流写过（并发或任务重入），
                    // 幂等条件 AND embedding IS NULL 按预期拦住了覆盖。
                    log.debug("chunk {} 已被写入向量，本次跳过", chunkId);
                } else {
                    saved += affected;
                }
            }
            return saved;
        });
        return written == null ? 0 : written;
    }

    /**
     * 转成 pgvector 的字面量格式：{@code [0.1,0.2,0.3]}。
     *
     * <p>统一<b>无空格</b>格式。pgvector 本身能接受带空格的写法，但
     * {@code updateEmbedding} 用 {@code CAST(... AS vector)} 解析这个字符串，
     * 无空格可省掉一次字符串扫描，也让日志里的向量更紧凑。
     */
    private static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
