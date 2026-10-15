package com.devknow.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 对话模型配置，前缀 {@code devknow.agent.chat}。
 *
 * <p>与 embedding / rerank 同平台（SiliconFlow）、同 key，不新增 provider。
 * 模型默认 {@code deepseek-ai/DeepSeek-V3}：中文能力够强，贴合"英文语料 + 中文提问"场景。
 */
@Data
@ConfigurationProperties(prefix = "devknow.agent.chat")
public class AgentChatProperties {

    /** API Key。复用环境变量 SILICONFLOW_API_KEY，绝不硬编码 */
    private String apiKey;

    /** OpenAI 兼容的 chat completions 端点前缀 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 默认模型；agent_config.model 为空时回退到它 */
    private String model = "deepseek-ai/DeepSeek-V3";

    /** 单次 HTTP 超时（秒）。生成式模型比 embedding 慢，放宽到 60 */
    private int timeoutSeconds = 60;

    /** 默认温度；agent_config.temperature 为空时回退到它 */
    private double temperature = 0.20;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
