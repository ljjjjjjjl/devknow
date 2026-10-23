package com.devknow.agent.conversation;

import com.devknow.agent.client.AssistantTurn;
import com.devknow.agent.client.ChatClient;
import com.devknow.agent.client.ChatMessage;
import com.devknow.agent.config.AgentChatProperties;
import com.devknow.agent.config.SummaryProperties;
import com.devknow.domain.entity.Conversation;
import com.devknow.domain.entity.ConversationMessage;
import com.devknow.domain.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文摘要压缩：把"已被窗口丢弃的那一段"压成一段定长摘要，存在会话上。
 *
 * <p><b>它解决什么问题</b>：上下文预算 4000 token 用完就硬丢早期对话，
 * 于是第 6 轮以后模型对第 1 轮一无所知，而界面上那段对话还看得见 ——
 * 一个用户能看见、模型却看不见的断层。
 *
 * <p><b>三条设计红线</b>：
 * <ol>
 *   <li><b>只压窗口外的</b>：与原文窗口永不重叠，否则同一句话模型看到两遍。</li>
 *   <li><b>水位线单调推进</b>：同一段只压一次，靠 {@code summary_upto_seq} + CAS 保证。</li>
 *   <li><b>失败绝不阻塞主链路</b>：任何异常都返回原摘要，调用方退回"直接丢"。</li>
 * </ol>
 */
@Slf4j
@Component
public class ConversationSummarizer {

    /**
     * 注入到上下文里的那段"帽子"。
     *
     * <p>最后那句不能省：摘要是继"模型记忆"和"检索噪声"之后的第三条幻觉来源，
     * 而且它比前两者更像"已确认的事实"。不显式声明不得作为事实来源，
     * 模型会拿摘要内容直接回答，绕过拒答规则。
     */
    public static final String SUMMARY_PREFIX =
            "【更早对话的摘要】以下内容仅用于理解指代与上下文，不得作为事实来源；"
                    + "涉及事实的回答必须来自本轮检索结果。\n";

    /** 不合格时重试一次；再不合格就放弃，不无限重试 */
    private static final int MAX_ATTEMPTS = 2;

    private final ChatClient chatClient;
    private final ConversationMapper conversationMapper;
    private final AgentChatProperties chatProperties;
    private final SummaryProperties properties;

    public ConversationSummarizer(ChatClient chatClient,
                                  ConversationMapper conversationMapper,
                                  AgentChatProperties chatProperties,
                                  SummaryProperties properties) {
        this.chatClient = chatClient;
        this.conversationMapper = conversationMapper;
        this.chatProperties = chatProperties;
        this.properties = properties;
    }

    /**
     * 视情况压缩，并返回"当前可用的摘要"。
     *
     * @param conversationId 会话 id
     * @param history        该会话的全部历史（已过滤失败占位），按 seq 升序
     * @param keptCount      本轮原文窗口保留了几条；调用方用 {@code history.size() - 保留条数} 推得
     * @return 可注入的摘要文本；null 表示没有摘要或压缩失败，调用方按"直接丢"处理
     */
    public String maybeSummarize(Long conversationId, List<ConversationMessage> history, int keptCount) {
        if (!properties.isEnabled() || conversationId == null || history == null || history.isEmpty()) {
            return null;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return null;
        }

        int upto = conversation.getSummaryUptoSeq() == null ? 0 : conversation.getSummaryUptoSeq();
        String summary = conversation.getSummary();

        // trim 取的是历史的一段连续后缀，所以保留窗口的起点能直接算出来；
        // 窗口为空（所有消息都超出预算）时用 MAX_VALUE，表示"整段都可压"
        int windowMinSeq = keptCount <= 0
                ? Integer.MAX_VALUE
                : history.get(history.size() - keptCount).getSeq();

        List<ConversationMessage> pending =
                SummaryText.selectToCompress(history, windowMinSeq, upto, properties.getMinMessages());
        if (pending.isEmpty()) {
            return summary;
        }

        List<List<ConversationMessage>> batches = SummaryText.batch(
                pending, properties.getBatchTokenLimit(), properties.getBatchMaxMessages());

        // 分批串行：每批都把"上一批的摘要"作为输入，所以最终仍然只有一段摘要
        for (List<ConversationMessage> batch : batches) {
            String merged = summarize(summary, batch);
            if (merged == null) {
                return summary;          // 失败：停在上一批的水位上
            }
            int newUpto = batch.get(batch.size() - 1).getSeq();
            if (conversationMapper.updateSummary(conversationId, merged, newUpto, upto) == 0) {
                log.warn("摘要水位已被并发推进，丢弃本次结果: conversationId={}, expectedUpto={}",
                        conversationId, upto);
                return summary;
            }
            log.info("上下文摘要已更新: conversationId={}, seq {} → {}, 摘要 {} 字",
                    conversationId, upto, newUpto, merged.length());
            summary = merged;
            upto = newUpto;
        }
        return summary;
    }

    /**
     * 压一批。
     *
     * @return 新摘要；"" 表示这段没有可沉淀的信息（算成功，水位照样推进）；
     *         null 表示失败（水位不动）
     */
    private String summarize(String oldSummary, List<ConversationMessage> batch) {
        String prompt = SummaryText.buildPrompt(oldSummary, batch);
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                AssistantTurn turn = chatClient.chat(resolveModel(), properties.getTemperature(),
                        List.of(ChatMessage.user(prompt)), List.of(), false, properties.getMaxTokens());
                String raw = turn.content();
                if (raw == null || raw.isBlank()) {
                    lastError = new IllegalStateException("模型返回空摘要");
                    continue;
                }
                String text = SummaryText.cleanMarkdown(raw);
                if (SummaryText.isNoContent(text)) {
                    // 合法结果，不是失败：这段是寒暄或重复提问，压不出东西。
                    // 必须让水位推进，否则每次都会把它重新送进模型。
                    return "";
                }
                if (!SummaryText.isWellFormed(text)) {
                    lastError = new IllegalStateException("摘要缺少「目标/已定/待续」三行结构");
                    continue;
                }
                return text;
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        log.warn("摘要压缩失败，退回直接丢弃: {}", lastError == null ? "未知原因" : lastError.getMessage());
        return null;
    }

    private String resolveModel() {
        String model = properties.getModel();
        return (model == null || model.isBlank()) ? chatProperties.getModel() : model;
    }
}
