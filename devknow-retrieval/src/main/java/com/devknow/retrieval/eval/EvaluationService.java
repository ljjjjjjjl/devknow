package com.devknow.retrieval.eval;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.dto.SearchResult;
import com.devknow.retrieval.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 评估跑批：把评估集里的 query 逐条打给检索，算 document 级指标。
 *
 * <p><b>为什么必须串行</b>：每条 query 都要在线调一次嵌入接口（第 4 周接 rerank 后还要多一次）。
 * 并行打过去会直接撞限流，评估数字会因为 429 而抖动 —— 那样测出来的不是检索质量，
 * 是网络状况。50 条串行约 20 秒，是值得付的代价。
 *
 * <p><b>单条失败不中断整批</b>：失败记进 {@link EvalItemResult#error()}，仍然算未命中。
 * 整批中断会让你在跑到第 40 条时丢掉前 39 条的结果。
 *
 * <p><b>检索窗口固定为 {@value #RETRIEVAL_WINDOW}</b>，指标截止位置固定为 1 / 5 / 20。
 * 窗口不跟着指标走，是为了让 {@code recallAt20} 能回答"答案在不在候选池"这个问题 ——
 * 它决定了某条失败该交给 rerank（在池里）还是交给召回改造（不在池里）。
 */
@Slf4j
@Service
public class EvaluationService {

    /** 评估集路径，随 jar 打包，部署后仍可跑回归 */
    private static final String DATASET_PATH = "eval/week4-queries.jsonl";

    /** 检索窗口：每次固定取这么多结果，指标再从里面按固定截止位置算 */
    private static final int RETRIEVAL_WINDOW = 20;

    /** 命中排名靠前于该位置才算 Hit@1 */
    private static final int HIT_AT_1 = 1;

    /** Recall@5 的截止位置，同时是 MRR 的计分范围 */
    private static final int RECALL_AT_5 = 5;

    private final RetrievalService retrievalService;

    /** 懒加载 + 缓存，避免评估集缺失时拖垮应用启动 */
    private volatile List<EvalQuery> cache;

    public EvaluationService(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 跑一遍完整评估集。检索窗口固定 {@value #RETRIEVAL_WINDOW}，不接受参数 ——
     * 窗口可变会让指标跨跑批不可比。
     */
    public EvalReport run() {
        List<EvalQuery> queries = dataset();
        List<EvalItemResult> items = new ArrayList<>(queries.size());
        for (EvalQuery query : queries) {
            items.add(runOne(query, RETRIEVAL_WINDOW));
        }

        List<EvalSummary> bySet = new ArrayList<>();
        for (Map.Entry<String, List<EvalItemResult>> entry : groupBySet(items).entrySet()) {
            bySet.add(summarize(entry.getKey(), entry.getValue()));
        }

        EvalReport report = new EvalReport(summarize("overall", items), bySet, items);
        EvalSummary overall = report.overall();
        log.info("评估完成: {} 条, Hit@1={}, Recall@5={}, Recall@20={}, 待rerank={}, MRR@5={}",
                overall.total(), overall.hitAt1(), overall.recallAt5(),
                overall.recallAt20(), overall.inPoolNotTop5(), overall.mrrAt5());
        return report;
    }

    public int datasetSize() {
        return dataset().size();
    }

    // ------------------------------------------------------------------

    private EvalItemResult runOne(EvalQuery query, int topK) {
        long start = System.nanoTime();
        try {
            List<SearchResult> results = retrievalService.search(query.query(), topK);
            List<Long> documentIds = dedupeDocuments(results);
            return new EvalItemResult(query.id(), query.set(), query.query(),
                    query.expectedDocumentIds(), documentIds,
                    firstHitRank(documentIds, query.expectedDocumentIds()),
                    elapsedMs(start), null);
        } catch (Exception e) {
            log.warn("评估 query 执行失败: id={}, query={}", query.id(), query.query(), e);
            return new EvalItemResult(query.id(), query.set(), query.query(),
                    query.expectedDocumentIds(), List.of(), null, elapsedMs(start),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 按 documentId 去重并保序。
     *
     * <p>返回列表的第 i 个元素就是"第 i 个不同的文档"，这样 {@code firstHitRank} 的返回值
     * 才等价于"用户往下翻几个才看到正确答案"。
     */
    private static List<Long> dedupeDocuments(List<SearchResult> results) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (SearchResult result : results) {
            if (result.documentId() != null) {
                ids.add(result.documentId());
            }
        }
        return List.copyOf(ids);
    }

    /** 第几个文档命中正确文档；全未命中返回 {@code null} */
    private static Integer firstHitRank(List<Long> found, List<Long> expected) {
        if (expected == null || expected.isEmpty()) {
            return null;
        }
        for (int i = 0; i < found.size(); i++) {
            if (expected.contains(found.get(i))) {
                return i + 1;
            }
        }
        return null;
    }

    private static Map<String, List<EvalItemResult>> groupBySet(List<EvalItemResult> items) {
        Map<String, List<EvalItemResult>> grouped = new LinkedHashMap<>();
        for (EvalItemResult item : items) {
            grouped.computeIfAbsent(item.set(), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private static EvalSummary summarize(String set, List<EvalItemResult> items) {
        if (items.isEmpty()) {
            return new EvalSummary(set, 0, 0, 0, 0, 0, 0, 0);
        }
        int hitAt1 = 0;
        int recallAt5 = 0;
        int recallAt20 = 0;
        int inPoolNotTop5 = 0;
        double reciprocalSum = 0;
        long totalMs = 0;

        for (EvalItemResult item : items) {
            totalMs += item.elapsedMs();
            Integer rank = item.hitRank();
            if (rank == null) {
                continue;
            }
            if (rank == HIT_AT_1) {
                hitAt1++;
            }
            if (rank <= RECALL_AT_5) {
                recallAt5++;
                reciprocalSum += 1.0 / rank;
            } else {
                inPoolNotTop5++;
            }
            if (rank <= RETRIEVAL_WINDOW) {
                recallAt20++;
            }
        }

        int total = items.size();
        return new EvalSummary(set, total,
                round4((double) hitAt1 / total),
                round4((double) recallAt5 / total),
                round4((double) recallAt20 / total),
                inPoolNotTop5,
                round4(reciprocalSum / total),
                round1((double) totalMs / total));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------

    private List<EvalQuery> dataset() {
        List<EvalQuery> local = cache;
        if (local == null) {
            synchronized (this) {
                local = cache;
                if (local == null) {
                    local = load();
                    cache = local;
                }
            }
        }
        return local;
    }

    private static List<EvalQuery> load() {
        ClassPathResource resource = new ClassPathResource(DATASET_PATH);
        if (!resource.exists()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "评估集不存在: " + DATASET_PATH);
        }
        List<EvalQuery> queries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                EvalQuery query = JsonUtils.fromJson(line, EvalQuery.class);
                if (query == null || query.query() == null) {
                    throw new IOException("第 " + lineNo + " 行不是合法 query");
                }
                queries.add(query);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "评估集读取失败: " + DATASET_PATH + " —— " + e.getMessage());
        }
        if (queries.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "评估集为空: " + DATASET_PATH);
        }
        return List.copyOf(queries);
    }
}
