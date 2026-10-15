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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 只有"开关打开 且 有 key"时才走 rerank，否则静默退回 RRF */
    public boolean isUsable() {
        return enabled && hasApiKey();
    }
}
