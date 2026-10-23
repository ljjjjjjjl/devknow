package com.devknow.agent.conversation;

import com.devknow.agent.client.AssistantTurn;
import com.devknow.agent.client.ChatClient;
import com.devknow.agent.config.AgentChatProperties;
import com.devknow.agent.config.SummaryProperties;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.domain.entity.Conversation;
import com.devknow.domain.entity.ConversationMessage;
import com.devknow.domain.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 摘要压缩的流程测试：什么时候压、水位怎么推、失败怎么退。
 *
 * <p>模型和数据库都是 mock，所以这里验证的是<b>编排</b>而不是摘要质量：
 * 该不该调用、调用几次、CAS 参数对不对、失败时有没有动水位。
 * 这四件事写错了不会报错，只会悄悄多烧钱或让摘要被反复覆盖。
 */
class ConversationSummarizerTest {

    private static final long CONV_ID = 7L;

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final AgentChatProperties chatProperties = mock(AgentChatProperties.class);
    private final SummaryProperties properties = new SummaryProperties();
    private final ConversationSummarizer summarizer =
            new ConversationSummarizer(chatClient, conversationMapper, chatProperties, properties);

    @BeforeEach
    void setUp() {
        when(chatProperties.getModel()).thenReturn("test-model");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("内容不够 4 条：不调用模型，省一次调用的钱")
    void doesNothingWhenTooFewMessages() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation(null, 0));

        // 历史 4 条，窗口保留 2 条 → 只剩 2 条可压，少于 minMessages=4
        String result = summarizer.maybeSummarize(CONV_ID, history(2), 2);

        assertThat(result).isNull();
        verifyNoInteractions(chatClient);
        verify(conversationMapper, never()).updateSummary(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("正常压缩：写库时带上读到的旧水位作 CAS 期望值")
    void writesSummaryWithExpectedWatermark() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation(null, 0));
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any()))
                .thenReturn(new AssistantTurn("目标：配置多数据源\n已定：用 @Primary\n待续：无", null));
        when(conversationMapper.updateSummary(eq(CONV_ID), anyString(), eq(4), eq(0))).thenReturn(1);

        String result = summarizer.maybeSummarize(CONV_ID, history(3), 2);

        assertThat(result).isEqualTo("目标：配置多数据源\n已定：用 @Primary\n待续：无");
        verify(conversationMapper).updateSummary(CONV_ID, result, 4, 0);
    }

    @Test
    @DisplayName("模型调用失败：重试一次后放弃，不动水位、不写库")
    void keepsWatermarkWhenModelFails() {
        // 水位 0：前 4 条都可压，确保真的会走到模型调用
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation("旧摘要", 0));
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any()))
                .thenThrow(new BizException(ErrorCode.AGENT_LLM_ERROR, "模型炸了"));

        String result = summarizer.maybeSummarize(CONV_ID, history(3), 2);

        assertThat(result).isEqualTo("旧摘要");
        verify(chatClient, times(2)).chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any());
        verify(conversationMapper, never()).updateSummary(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("模型输出不合格式：视为失败，不写库")
    void rejectsMalformedSummary() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation(null, 0));
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any()))
                .thenReturn(new AssistantTurn("好的，这是摘要：配置多数据源", null));

        assertThat(summarizer.maybeSummarize(CONV_ID, history(3), 2)).isNull();
        verify(conversationMapper, never()).updateSummary(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("「无可提炼内容」算成功：摘要置空但水位必须推进，否则每次都会重压")
    void noContentStillAdvancesWatermark() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation(null, 0));
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any()))
                .thenReturn(new AssistantTurn("无可提炼内容", null));
        when(conversationMapper.updateSummary(eq(CONV_ID), eq(""), eq(4), eq(0))).thenReturn(1);

        assertThat(summarizer.maybeSummarize(CONV_ID, history(3), 2)).isEmpty();
        verify(conversationMapper).updateSummary(CONV_ID, "", 4, 0);
    }

    @Test
    @DisplayName("水位已覆盖：不再重复压缩同一段，只返回已有摘要")
    void doesNotRecompressSummarizedMessages() {
        // 水位 4：前 4 条已经进过摘要，窗口又保留了 5、6，于是没有可压的
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation("已有摘要", 4));

        assertThat(summarizer.maybeSummarize(CONV_ID, history(3), 2)).isEqualTo("已有摘要");
        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("CAS 失败（并发推进了水位）：丢弃本次结果，返回旧摘要")
    void discardsResultWhenWatermarkMoved() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation("旧摘要", 0));
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList(), eq(false), any()))
                .thenReturn(new AssistantTurn("目标：A\n已定：B\n待续：C", null));
        when(conversationMapper.updateSummary(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(0);

        assertThat(summarizer.maybeSummarize(CONV_ID, history(3), 2)).isEqualTo("旧摘要");
    }

    // ------------------------------------------------------------------

    /** 造 pairs 轮问答，seq 从 1 开始连续递增 */
    private static List<ConversationMessage> history(int pairs) {
        List<ConversationMessage> list = new ArrayList<>();
        int seq = 1;
        for (int i = 0; i < pairs; i++) {
            list.add(message(seq++, "user", "问题" + i + "：怎么配置多数据源"));
            list.add(message(seq++, "assistant", "回答" + i + "：用 @Primary 标记主数据源"));
        }
        return list;
    }

    private static ConversationMessage message(int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static Conversation conversation(String summary, int uptoSeq) {
        Conversation c = new Conversation();
        c.setId(CONV_ID);
        c.setSummary(summary);
        c.setSummaryUptoSeq(uptoSeq);
        return c;
    }
}
