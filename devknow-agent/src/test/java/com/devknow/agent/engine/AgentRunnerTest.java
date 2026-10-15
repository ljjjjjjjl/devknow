package com.devknow.agent.engine;

import com.devknow.agent.client.AssistantTurn;
import com.devknow.agent.client.ChatClient;
import com.devknow.agent.client.ToolCall;
import com.devknow.agent.config.AgentChatProperties;
import com.devknow.agent.stream.AgentStreamListener;
import com.devknow.agent.tool.AgentTool;
import com.devknow.agent.tool.ToolResult;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.dto.AgentChatResponse;
import com.devknow.domain.dto.Citation;
import com.devknow.domain.entity.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环的单元测试：mock 掉 {@link ChatClient}，全程不打 API。
 *
 * <p><b>为什么值得先写测试</b>：流式改造把唯一的循环拆出了"两种模型调用"的分支，
 * 分支的正确性靠手点页面是看不全的 —— 熔断、工具抛异常、空回合这三条路径
 * 用真实模型很难稳定复现。
 */
class AgentRunnerTest {

    private static final String TOOL_NAME = "search_knowledge";

    private final ChatClient chatClient = mock(ChatClient.class);
    private final AgentChatProperties properties = new AgentChatProperties();

    // ------------------------------------------------------------------
    // 流式路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单步直接回答：增量按顺序推出，拼起来等于最终答案")
    void streamsAnswerWithoutTool() {
        when(chatClient.streamChat(anyString(), anyDouble(), anyList(), anyList(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = invocation.getArgument(4);
                    onDelta.accept("你好");
                    onDelta.accept("，世界");
                    return new AssistantTurn("你好，世界", List.of());
                });

        RecordingListener listener = new RecordingListener();
        AgentChatResponse response = runner(List.of()).stream(config(3), "打个招呼", listener);

        assertThat(response.answer()).isEqualTo("你好，世界");
        assertThat(response.steps()).isEqualTo(1);
        assertThat(listener.deltas).containsExactly("你好", "，世界");
        assertThat(listener.steps).containsExactly(1);
        assertThat(listener.tools).isEmpty();
    }

    @Test
    @DisplayName("调工具后回答：两步；工具与引用都推给了前端，引用按 documentId 去重")
    void streamsToolCallThenAnswer() {
        Citation duplicated = new Citation(7L, "DataSource 配置", "https://example.com/7");
        StubTool tool = new StubTool(TOOL_NAME,
                new ToolResult("检索结果", List.of(duplicated, duplicated,
                        new Citation(9L, "Logback 升级", "https://example.com/9"))));

        when(chatClient.streamChat(anyString(), anyDouble(), anyList(), anyList(), any()))
                .thenReturn(
                        new AssistantTurn(null, List.of(new ToolCall("call_1", TOOL_NAME, "{\"topK\":5}"))),
                        new AssistantTurn("根据文档……", List.of()));

        RecordingListener listener = new RecordingListener();
        AgentChatResponse response = runner(List.of(tool)).stream(config(4), "怎么配置数据源", listener);

        assertThat(response.steps()).isEqualTo(2);
        assertThat(response.answer()).isEqualTo("根据文档……");
        assertThat(tool.calls).isEqualTo(1);
        assertThat(listener.steps).containsExactly(1, 2);
        assertThat(listener.tools).containsExactly(TOOL_NAME);
        // 推给前端的引用顺序 == 最终响应里的引用顺序
        assertThat(listener.citationIds).containsExactly(7L, 9L);
        assertThat(response.citations()).extracting(Citation::documentId).containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("工具抛异常：转成错误结果喂回模型，循环继续而不是中断")
    void toolFailureIsFedBackToModel() {
        StubTool broken = new StubTool(TOOL_NAME, null) {
            @Override
            public ToolResult execute(JsonNode arguments) {
                throw new IllegalStateException("向量库连不上");
            }
        };

        when(chatClient.streamChat(anyString(), anyDouble(), anyList(), anyList(), any()))
                .thenReturn(
                        new AssistantTurn(null, List.of(new ToolCall("call_1", TOOL_NAME, "{}"))),
                        new AssistantTurn("检索失败，我无法回答", List.of()));

        AgentChatResponse response = runner(List.of(broken)).stream(config(3), "问题", new RecordingListener());

        assertThat(response.steps()).isEqualTo(2);
        assertThat(response.answer()).isEqualTo("检索失败，我无法回答");
    }

    @Test
    @DisplayName("max_steps 熔断：模型一直要调工具时抛 AGENT_MAX_STEPS_EXCEEDED")
    void abortsWhenMaxStepsExceeded() {
        when(chatClient.streamChat(anyString(), anyDouble(), anyList(), anyList(), any()))
                .thenReturn(new AssistantTurn(null,
                        List.of(new ToolCall("call_1", TOOL_NAME, "{}"))));

        StubTool tool = new StubTool(TOOL_NAME, new ToolResult("结果", List.of()));
        RecordingListener listener = new RecordingListener();

        assertThatThrownBy(() -> runner(List.of(tool)).stream(config(2), "问题", listener))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_MAX_STEPS_EXCEEDED);

        assertThat(listener.steps).containsExactly(1, 2);
        assertThat(tool.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("空回合：模型既不给文本也不调工具，直接失败而不是空转")
    void emptyTurnFails() {
        when(chatClient.streamChat(anyString(), anyDouble(), anyList(), anyList(), any()))
                .thenReturn(new AssistantTurn(null, List.of()));

        assertThatThrownBy(() -> runner(List.of()).stream(config(3), "问题", new RecordingListener()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_LLM_ERROR);
    }

    // ------------------------------------------------------------------
    // 非流式路径：确认改造没有把它切到流式上
    // ------------------------------------------------------------------

    @Test
    @DisplayName("run() 仍走非流式的 chat()，不会顺带走 streamChat()")
    void runKeepsUsingNonStreamingChat() {
        when(chatClient.chat(anyString(), anyDouble(), anyList(), anyList()))
                .thenReturn(new AssistantTurn("完整回答", List.of()));

        AgentChatResponse response = runner(List.of()).run(config(3), "问题");

        assertThat(response.answer()).isEqualTo("完整回答");
        verify(chatClient).chat(anyString(), anyDouble(), anyList(), anyList());
        verify(chatClient, never()).streamChat(anyString(), anyDouble(), anyList(), anyList(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("query 为空：不进循环，直接抛 BAD_REQUEST")
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> runner(List.of()).run(config(3), "  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    // ------------------------------------------------------------------

    private AgentRunner runner(List<AgentTool> tools) {
        return new AgentRunner(chatClient, properties, tools);
    }

    private static AgentConfig config(int maxSteps) {
        AgentConfig config = new AgentConfig();
        config.setId(1L);
        config.setName("devknow-default");
        config.setEnabled(true);
        config.setSystemPrompt("你是研发知识库助手。");
        config.setMaxSteps(maxSteps);
        // temperature 留空，走 AgentChatProperties 的默认值
        return config;
    }

    /** 记录回调顺序的监听器：断言"推出去的"和"返回的"一致。 */
    private static final class RecordingListener implements AgentStreamListener {
        private final List<String> deltas = new ArrayList<>();
        private final List<Integer> steps = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private final List<Long> citationIds = new ArrayList<>();

        @Override
        public void onStep(int step) {
            steps.add(step);
        }

        @Override
        public void onDelta(String text) {
            deltas.add(text);
        }

        @Override
        public void onTool(String name, String arguments) {
            tools.add(name);
        }

        @Override
        public void onCitation(Citation citation) {
            citationIds.add(citation.documentId());
        }
    }

    /** 假工具：默认返回固定结果，子类可覆写成抛异常。 */
    private static class StubTool implements AgentTool {
        private final String name;
        private final ToolResult result;
        private int calls;

        StubTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试用检索工具";
        }

        @Override
        public JsonNode parameters() {
            return JsonUtils.mapper().createObjectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(JsonNode arguments) {
            calls++;
            return result;
        }
    }
}
