package com.devknow.retrieval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化配置，前缀 {@code devknow.embedding}。
 *
 * <pre>
 * devknow:
 *   embedding:
 *     enabled: true
 *     model: BAAI/bge-m3
 *     dimension: 1024
 *     batch-max-chunks: 100
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "devknow.embedding")
public class EmbeddingProperties {

    /** 是否启用向量化。false 时回填调度不创建任务，便于只跑采集 */
    private boolean enabled = true;

    /** API Key。走环境变量 SILICONFLOW_API_KEY，绝不硬编码 */
    private String apiKey;

    /** OpenAI 兼容的 embeddings 端点前缀 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 模型标识，会原样写入 document_chunk.embedding_model */
    private String model = "BAAI/bge-m3";

    /** 向量维度，必须与 V2 迁移里的 vector(1024) 一致 */
    private int dimension = 1024;

    /** 单次 HTTP 超时（秒） */
    private int timeoutSeconds = 30;

    /** 单批最大条数 */
    private int batchMaxChunks = 100;

    /**
     * 单批累计 token 上限。
     *
     * <p>按 {@code document_chunk.content_tokens} 累加判断，不重新计算 ——
     * 该列在切分时已由 {@code estimateTokens} 写入。
     */
    private int batchMaxTokens = 8192;

    /** 回填扫描间隔（毫秒） */
    private long scanIntervalMs = 600_000L;

    /** 单轮最多取多少个待办 chunk */
    private int scanLimitPerRound = 1000;

    /** 熔断阈值：自上次成功以来累积多少个死信后停止创建 EMBED 任务 */
    private int maxConsecutiveDead = 3;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
