package com.devknow.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文摘要压缩配置，前缀 {@code devknow.agent.summary}。
 *
 * <p>模型默认复用 {@code devknow.agent.chat.model}：摘要和回答用同一个模型，
 * 不额外引入 provider，也不需要在两处维护模型名。
 */
@Data
@ConfigurationProperties(prefix = "devknow.agent.summary")
public class SummaryProperties {

    /** 总开关。关掉就退回"超出预算直接丢"的老行为 */
    private boolean enabled = true;

    /** 摘要专用模型；留空则用 chat 的默认模型 */
    private String model;

    /** 摘要不该有发挥空间，温度压到 0.1 */
    private double temperature = 0.1;

    /**
     * 输出 token 上限（保险丝，不是长度约束）。
     *
     * <p>长度靠提示词里的"300 中文字"约束；这里留到 400 是因为摘要里会混进
     * {@code @ConfigurationProperties} 这类标识符，token 数比纯中文高，
     * 卡在 300 有可能把最后一行截成半句。
     */
    private int maxTokens = 400;

    /**
     * 触发压缩的最少消息条数。
     *
     * <p>只丢 1~2 条时省下的 token 还不够付一次摘要调用，不值得。
     * 一轮问答是 2 条，所以 4 条 ≈ 2 轮。
     */
    private int minMessages = 4;

    /** 单批送进模型的 token 上限（超出就分批串行压） */
    private int batchTokenLimit = 2000;

    /** 单批最多几条消息 */
    private int batchMaxMessages = 20;

    /** 注入 prompt 时每一行（目标/已定/待续）允许的最大字符数 */
    private int lineChars = 110;
}
