package com.devknow.retrieval.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重排序配置，前缀 {@code devknow.rerank}。
 *
 * <pre>
 * devknow:
 *   rerank:
 *     enabled: true
 *     model: BAAI/bge-reranker-v2-m3
 *     candidate-window: 50
 *     min-relevance-score: 0.4
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "devknow.rerank")
public class RerankProperties {

    /** 是否启用重排序。false 时检索走纯 RRF 路径，可用于 A/B 对照 */
    private boolean enabled = true;

    /** API Key。复用 SILICONFLOW_API_KEY，与向量化同平台 */
    private String apiKey;

    /** OpenAI 兼容的 rerank 端点前缀 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 模型标识 */
    private String model = "BAAI/bge-reranker-v2-m3";

    /** 单次 HTTP 超时（秒） */
    private int timeoutSeconds = 30;

    /**
     * 每一路取多少个候选进入候选池。
     *
     * <p><b>必须明显大于纯 RRF 的 20</b>：实测 {@code @WebMvcTest} 的权威文档
     * （Testing Spring Boot Applications）在词法路排第 <b>27</b>，窗口 20 时它进不了候选池，
     * rerank 再准也看不到它 —— 这是召回问题，不是排序问题。
     */
    private int candidateWindow = 50;

    /**
     * 送 rerank 的单条文本截断长度（字符）。
     *
     * <p>候选池最多 100 条，不截断的话请求体会到几百 KB。截断同时能减少噪声：
     * 相关度信号集中在 chunk 开头（heading_path + 首段），尾部多为无关细节。
     */
    private int maxDocumentChars = 800;

    /**
     * Agent 路径的相关度下限：top-1 精排分低于此值时，{@code SearchKnowledgeTool}
     * 判定为"命中了一堆噪声"，按 0 命中处理。
     *
     * <p><b>为什么需要它</b>：没有下限时，0.3 分和 0.9 分的结果一样被包装成
     * "检索到 N 条结果"喂给模型。模型于是得自己判断"这些到底相不相关"，
     * 而提示词只覆盖了"检索到 0 条"和"检索到相关内容"两种分支，
     * "检索到但不相关"这一支没有任何规则 —— 模型就掉进默认的"乐于助人"行为，
     * 改用通用知识作答。加下限是把"相不相关"这个判断从模型手里拿回确定性代码。
     *
     * <p><b>只作用于 Agent 路径</b>（{@code SearchKnowledgeTool}），
     * 不改 {@code RetrievalService} —— 第 4 周锁定的评估基线（Hit@1 0.80 等）因此不受影响。
     *
     * <p><b>0.4 的来由</b>：对当前语料（Spring Boot 官方文档 + 该仓库 issue/PR）抽样 9 条 query，
     * 库内确有答案的 top-1 落在 {@code 0.737 ~ 0.995}，明确库外的问题落在 {@code 0.002 ~ 0.010}，
     * 而触发这次问题的「如何实现异步任务」是 {@code 0.318}。
     * 0.4 能拦住它，离库外上限的余量极大。
     *
     * <p><b>⚠️ 样本很小</b>：换语料、或换 embedding / rerank 模型后分数量纲都会变，
     * 必须重新抽样定值。这也是它做成配置而非常量的原因。
     */
    private double minRelevanceScore = 0.4;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 只有"开关打开 且 有 key"时才走 rerank，否则静默退回 RRF */
    public boolean isUsable() {
        return enabled && hasApiKey();
    }
}
