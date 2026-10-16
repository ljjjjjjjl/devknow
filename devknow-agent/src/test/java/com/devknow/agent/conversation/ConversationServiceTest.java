package com.devknow.agent.conversation;

import com.devknow.agent.client.ChatMessage;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.TokenEstimator;
import com.devknow.domain.entity.Conversation;
import com.devknow.domain.entity.ConversationMessage;
import com.devknow.domain.enums.ConversationEnums.ConversationStatus;
import com.devknow.domain.mapper.ConversationMapper;
import com.devknow.domain.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上下文裁剪的边界测试。
 *
 * <p><b>为什么单测这一段</b>：{@code trim} 是整个会话持久化里唯一容易写错的纯逻辑，
 * 而它出错的表现是"模型莫名其妙丢了上下文"，靠手点页面几乎发现不了。
 * 做成静态纯函数就是为了能这样直接测，不用起 Spring 上下文、不用连库。
 *
 * <p>测试里的内容一律用中文：{@link TokenEstimator} 对中文是 1 字符 1 token，
 * 数字好算，断言里能直接看出"几条几条被丢掉"。
 */
class ConversationServiceTest {

    @Test
    @DisplayName("预算够：历史全部保留，顺序不变")
    void keepsAllWhenWithinBudget() {
        List<ConversationMessage> history = List.of(
                user(1, "问题一"),
                assistant(2, "回答一"),
                user(3, "问题二"),
                assistant(4, "回答二"));

        List<ChatMessage> context = ConversationService.trim(history, 4000, 10);

        assertThat(context).extracting(ChatMessage::content)
                .containsExactly("问题一", "回答一", "问题二", "回答二");
        assertThat(context).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "user", "assistant");
    }

    @Test
    @DisplayName("超预算：从最近往前保留，丢掉更早的整轮")
    void dropsOldestWhenOverBudget() {
        // 每条 20 个中文字 = 20 tokens，四条共 80
        List<ConversationMessage> history = List.of(
                user(1, chinese(20)), assistant(2, chinese(20)),
                user(3, chinese(20)), assistant(4, chinese(20)));

        // 预算 50：最新的 a2(20) + u2(20) = 40 放得下，再加 a1 会到 60，停
        List<ChatMessage> context = ConversationService.trim(history, 50, 10);

        assertThat(context).hasSize(2);
        assertThat(context).extracting(ChatMessage::role).containsExactly("user", "assistant");
    }

    @Test
    @DisplayName("条数上限：token 还够也按条数截断")
    void respectsMessageCountLimit() {
        List<ConversationMessage> history = List.of(
                user(1, "问题一"), assistant(2, "回答一"),
                user(3, "问题二"), assistant(4, "回答二"));

        List<ChatMessage> context = ConversationService.trim(history, 4000, 2);

        assertThat(context).extracting(ChatMessage::content)
                .containsExactly("问题二", "回答二");
    }

    @Test
    @DisplayName("截断点落在 assistant 上：把它丢掉，保证窗口以 user 开头")
    void windowAlwaysStartsWithUser() {
        List<ConversationMessage> history = List.of(
                user(1, chinese(20)), assistant(2, chinese(20)),
                user(3, chinese(20)), assistant(4, chinese(20)));

        // 预算 25 只放得下最后一条 a2 —— 那是"没有提问的回答"，必须丢掉
        List<ChatMessage> context = ConversationService.trim(history, 25, 10);

        assertThat(context).isEmpty();
    }

    @Test
    @DisplayName("空历史：得到空上下文，不是 null")
    void emptyHistoryGivesEmptyContext() {
        assertThat(ConversationService.trim(List.of(), 4000, 10)).isEmpty();
    }

    @Test
    @DisplayName("单条就超预算：整段历史都不给，而不是硬塞进去撑爆上下文")
    void singleOversizedMessageIsDropped() {
        List<ConversationMessage> history = List.of(
                user(1, chinese(100)), assistant(2, chinese(100)));

        assertThat(ConversationService.trim(history, 50, 10)).isEmpty();
    }

    @Test
    @DisplayName("标题：压平空白；空 query 兜底；超长截断到 60 字符加省略号")
    void titleIsNormalisedAndTruncated() {
        assertThat(ConversationService.buildTitle("  怎么   配置\n数据源  ")).isEqualTo("怎么 配置 数据源");
        assertThat(ConversationService.buildTitle("   ")).isEqualTo("新会话");
        assertThat(ConversationService.buildTitle(null)).isEqualTo("新会话");

        String title = ConversationService.buildTitle(chinese(100));
        assertThat(title).hasSize(61);
        assertThat(title).endsWith("…");
    }

    // ------------------------------------------------------------------
    // 会话归属校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("没传 conversationId：新建会话并返回新 id")
    void resolveCreatesWhenAbsent() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        when(conversations.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            c.setId(42L);
            return 1;
        });
        ConversationService service =
                new ConversationService(conversations, mock(ConversationMessageMapper.class));

        assertThat(service.resolve(1L, null, "怎么配置数据源")).isEqualTo(42L);
    }

    @Test
    @DisplayName("传了且归属一致：复用同一个 id")
    void resolveReusesWhenOwnershipMatches() {
        assertThat(serviceWith(active(7L, 1L)).resolve(1L, 7L, "问题")).isEqualTo(7L);
    }

    @Test
    @DisplayName("传了但属于别的配置：报 BAD_REQUEST，不能拿 B 的人设跑 A 的历史")
    void resolveRejectsForeignConversation() {
        ConversationService service = serviceWith(active(7L, 1L));

        assertThatThrownBy(() -> service.resolve(2L, 7L, "问题"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("传了但会话已软删：报 CONVERSATION_NOT_FOUND")
    void resolveRejectsDeletedConversation() {
        Conversation deleted = active(7L, 1L);
        deleted.setStatus(ConversationStatus.DELETED.getValue());
        ConversationService service = serviceWith(deleted);

        assertThatThrownBy(() -> service.resolve(1L, 7L, "问题"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private static ConversationService serviceWith(Conversation c) {
        ConversationMapper conversations = mock(ConversationMapper.class);
        when(conversations.selectById(c.getId())).thenReturn(c);
        return new ConversationService(conversations, mock(ConversationMessageMapper.class));
    }

    private static Conversation active(long id, long agentConfigId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setAgentConfigId(agentConfigId);
        c.setStatus(ConversationStatus.ACTIVE.getValue());
        return c;
    }

    // ------------------------------------------------------------------

    private static String chinese(int n) {
        return "中".repeat(n);
    }

    private static ConversationMessage user(int seq, String content) {
        return message(seq, "user", content);
    }

    private static ConversationMessage assistant(int seq, String content) {
        return message(seq, "assistant", content);
    }

    private static ConversationMessage message(int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
