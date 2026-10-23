package com.devknow.agent.conversation;

import com.devknow.agent.client.ChatMessage;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.devknow.common.util.TokenEstimator;
import com.devknow.domain.dto.Citation;
import com.devknow.domain.dto.ConversationMessageView;
import com.devknow.domain.entity.Conversation;
import com.devknow.domain.entity.ConversationMessage;
import com.devknow.domain.enums.ConversationEnums.ConversationStatus;
import com.devknow.domain.enums.ConversationEnums.MessageRole;
import com.devknow.domain.mapper.ConversationMapper;
import com.devknow.domain.mapper.ConversationMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 会话读写与上下文裁剪。
 *
 * <p>放在 {@code devknow-agent} 而不是 {@code devknow-domain}：它要做
 * "把历史消息拼成 {@link ChatMessage}"这件事，而 {@code ChatMessage} 是 Agent
 * 的模型层类型。放 domain 会让领域层反向依赖 Agent。
 *
 * <p><b>调用顺序有坑</b>：写本轮 user 消息必须排在
 * {@link #loadContext(Long)} <b>之后</b>。反过来的话 loadContext 会把本轮问题
 * 当成历史返回，而 {@code AgentRunner} 内部还会再追加一次本轮 query ——
 * 结果是同一条问题在 messages 里出现两次。调用方见 {@code AgentController}。
 */
@Slf4j
@Service
public class ConversationService {

    /** 会话列表返回上限。当前数据量小、不做分页，但接口不能没有上限。 */
    private static final int LIST_LIMIT = 100;

    /** 标题截断长度。列宽 200，留足余量。 */
    private static final int TITLE_MAX_CHARS = 60;

    /**
     * 注入 prompt 时摘要每一行（目标/已定/待续）的最大字符数。
     *
     * <p>三行各 110 ≈ 330 字，加上原来的 4000 token 窗口，总预算因此可预测：
     * 摘要部分最多 330 token，不会出现"摘要一长就把原文窗口挤没"的连锁反应。
     */
    private static final int SUMMARY_LINE_CHARS = 110;

    /** 失败原因截断长度，与 {@code conversation_message.error} 的列宽一致。 */
    private static final int ERROR_MAX_CHARS = 500;

    /**
     * 上下文 token 预算。
     *
     * <p><b>只约束 user + assistant 历史</b>，不含 system prompt、本轮 query，
     * 也不含本轮 ReAct 里 tool 返回的检索结果（那部分靠 {@code max_steps} 兜底）。
     * 4000 对 DeepSeek-V3 的上下文窗口来说很保守，这里卡的是成本与延迟，不是容量。
     */
    static final int DEFAULT_TOKEN_BUDGET = 4000;

    /**
     * 条数硬上限。
     *
     * <p>为什么 token 预算之外还要这一道：{@link TokenEstimator} 是启发式，
     * 中英混合与代码块会<b>低估</b>。多一道条数闸门，估算失真时也不会失控。
     */
    static final int MAX_CONTEXT_MESSAGES = 10;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationSummarizer summarizer;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper,
                               ConversationSummarizer summarizer) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.summarizer = summarizer;
    }

    // ------------------------------------------------------------------
    // 编排入口
    // ------------------------------------------------------------------

    /**
     * 解析本轮要用的会话 id：传了就用（并校验存在、未删除、归属一致），没传就新建。
     *
     * <p>集中在这里做校验，是为了让 {@link #appendUser} / {@link #appendAssistant}
     * 不必各自再查一次会话 —— 一轮对话会写两次消息，重复校验是纯浪费。
     */
    public Long resolve(Long agentConfigId, Long conversationId, String firstQuery) {
        if (conversationId == null) {
            return create(agentConfigId, firstQuery);
        }
        Conversation c = requireActive(conversationId);
        // 会话绑定在某个 agent_config 上。换了配置还拿旧 id 续聊，等于用 B 的人设、
        // B 的工具去跑 A 的历史，结果不可解释。宁可明确报错。
        if (!c.getAgentConfigId().equals(agentConfigId)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "会话 " + conversationId + " 属于配置 " + c.getAgentConfigId()
                            + "，与本次请求的 " + agentConfigId + " 不一致");
        }
        return conversationId;
    }

    /** 新建会话，标题取首条 query 截断。 */
    public Long create(Long agentConfigId, String firstQuery) {
        Instant now = Instant.now();
        Conversation c = new Conversation();
        c.setAgentConfigId(agentConfigId);
        c.setTitle(buildTitle(firstQuery));
        c.setStatus(ConversationStatus.ACTIVE.getValue());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversationMapper.insert(c);
        log.info("新建会话: id={}, configId={}, title={}", c.getId(), agentConfigId, c.getTitle());
        return c.getId();
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * 拉取历史并裁剪成能塞进模型的上下文。
     *
     * <p>失败占位消息（{@code error} 非空）在 SQL 层就被过滤掉了，不会进到这里。
     */
    public List<ChatMessage> loadContext(Long conversationId) {
        return loadContext(conversationId, DEFAULT_TOKEN_BUDGET, MAX_CONTEXT_MESSAGES);
    }

    List<ChatMessage> loadContext(Long conversationId, int tokenBudget, int maxMessages) {
        List<ConversationMessage> history = messageMapper.listForContext(conversationId);
        List<ChatMessage> context = trim(history, tokenBudget, maxMessages);

        // 摘要只在"有内容被挤出窗口"时才有意义；它失败必须退回原样，所以整段兜住异常
        String summary = null;
        try {
            summary = summarizer.maybeSummarize(conversationId, history, context.size());
        } catch (RuntimeException e) {
            log.warn("摘要压缩异常，按原样返回上下文: conversationId={}, {}", conversationId, e.toString());
        }

        if (summary != null && !summary.isBlank()) {
            // 放在历史之前、system prompt 之后：模型先看到"这是背景，不是事实来源"
            List<ChatMessage> withSummary = new ArrayList<>(context.size() + 1);
            withSummary.add(ChatMessage.system(
                    ConversationSummarizer.SUMMARY_PREFIX + SummaryText.clampByLines(summary, SUMMARY_LINE_CHARS)));
            withSummary.addAll(context);
            context = withSummary;
        }

        log.debug("会话上下文: conversationId={}, 历史 {} 条 → 取 {} 条（预算 {} tokens / {} 条，摘要 {} 字）",
                conversationId, history.size(), context.size(), tokenBudget, maxMessages,
                summary == null ? 0 : summary.length());
        return context;
    }

    /**
     * 裁剪：从最近往前累加，直到超出 token 预算或条数上限。
     *
     * <p>做成静态包级可见的<b>纯函数</b>，是为了能脱离数据库直接单测边界 ——
     * 裁剪逻辑是这一段里唯一容易写错的部分，值得有回归覆盖。
     *
     * <p>取的是<b>连续</b>的最近窗口（一旦某条超预算就停），不是"挑出所有塞得下的"。
     * 跳跃式挑选会把上下文打成碎片，比少给几轮更糟。
     */
    static List<ChatMessage> trim(List<ConversationMessage> history, int tokenBudget, int maxMessages) {
        LinkedList<ChatMessage> picked = new LinkedList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0 && picked.size() < maxMessages; i--) {
            ConversationMessage m = history.get(i);
            int cost = TokenEstimator.estimate(m.getContent());
            if (used + cost > tokenBudget) {
                break;
            }
            picked.addFirst(toChatMessage(m));
            used += cost;
        }
        // 窗口必须以 user 开头。截断点可能正好落在一条 assistant 上，
        // 那样模型会看到"没有提问的回答"，指代关系也断了。
        while (!picked.isEmpty() && !MessageRole.USER.getValue().equals(picked.getFirst().role())) {
            picked.removeFirst();
        }
        return picked;
    }

    private static ChatMessage toChatMessage(ConversationMessage m) {
        return MessageRole.USER.getValue().equals(m.getRole())
                ? ChatMessage.user(m.getContent())
                : ChatMessage.assistant(m.getContent());
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    public void appendUser(Long conversationId, String content) {
        append(conversationId, MessageRole.USER.getValue(), content, null, null);
    }

    public void appendAssistant(Long conversationId, String content, List<Citation> citations) {
        String json = (citations == null || citations.isEmpty()) ? null : JsonUtils.toJson(citations);
        append(conversationId, MessageRole.ASSISTANT.getValue(), content, json, null);
    }

    /**
     * 追加一条失败占位。
     *
     * <p>为什么失败也要落库：不写的话回看会看到"有问无答"，
     * 用户分不清是自己没发出去还是服务挂了。
     *
     * <p>这条消息的 {@code role} 也是 assistant，但 {@code error} 非空 ——
     * {@code loadContext} 用的 SQL 会把它过滤掉，不会污染下一轮的上下文。
     */
    public void appendFailure(Long conversationId, String reason) {
        append(conversationId, MessageRole.ASSISTANT.getValue(), null, null,
                truncate(reason, ERROR_MAX_CHARS));
    }

    private void append(Long conversationId, String role, String content,
                        String citationsJson, String error) {
        ConversationMessage m = new ConversationMessage();
        m.setConversationId(conversationId);
        m.setSeq(messageMapper.nextSeq(conversationId));
        m.setRole(role);
        m.setContent(content);
        m.setCitations(citationsJson);
        m.setError(error);
        m.setCreatedAt(Instant.now());
        messageMapper.insertMessage(m);
        // updated_at 没有触发器，不显式刷新的话会话列表会一直按创建时间排
        conversationMapper.touch(conversationId);
    }

    // ------------------------------------------------------------------
    // 管理接口
    // ------------------------------------------------------------------

    public List<Conversation> list(Long agentConfigId) {
        return conversationMapper.listActive(agentConfigId, LIST_LIMIT);
    }

    public List<ConversationMessageView> messages(Long conversationId) {
        requireActive(conversationId);
        return messageMapper.listAll(conversationId).stream()
                .map(ConversationService::toView)
                .toList();
    }

    public void delete(Long conversationId) {
        if (conversationMapper.softDelete(conversationId) == 0) {
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND,
                    "会话不存在或已删除: " + conversationId);
        }
        log.info("软删除会话: id={}", conversationId);
    }

    // ------------------------------------------------------------------

    private Conversation requireActive(Long conversationId) {
        Conversation c = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (c == null || !ConversationStatus.ACTIVE.getValue().equals(c.getStatus())) {
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND,
                    "会话不存在或已删除: " + conversationId);
        }
        return c;
    }

    private static ConversationMessageView toView(ConversationMessage m) {
        return new ConversationMessageView(m.getId(), m.getSeq(), m.getRole(), m.getContent(),
                parseCitations(m.getCitations()), m.getError(), m.getCreatedAt());
    }

    /**
     * 把 jsonb 文本解析回引用列表。
     *
     * <p>用 {@code Citation[].class} 而不是 {@code TypeReference<List<Citation>>}：
     * 泛型在运行时被擦除，项目在采集模块已经踩过一次 {@code ClassCastException}。
     * 数组类型是具体类型，不依赖擦除后的推断。
     *
     * <p>解析失败按"无引用"处理并打 WARN，不让一条坏数据把整个会话列表接口打挂。
     */
    private static List<Citation> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Citation[] arr = JsonUtils.fromJson(json, Citation[].class);
            return (arr == null || arr.length == 0) ? null : List.of(arr);
        } catch (Exception e) {
            log.warn("会话引用解析失败，按无引用处理: {}", e.getMessage());
            return null;
        }
    }

    static String buildTitle(String query) {
        String t = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return "新会话";
        }
        return truncate(t, TITLE_MAX_CHARS);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
