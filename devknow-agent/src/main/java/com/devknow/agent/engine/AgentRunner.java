package com.devknow.agent.engine;

import com.devknow.agent.client.AssistantTurn;
import com.devknow.agent.client.ChatClient;
import com.devknow.agent.client.ChatMessage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReAct 循环：LLM 决策 → 执行工具 → 结果回填 → 再决策，直到给出最终回答或触发熔断。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li>工具失败<b>喂回给模型</b>而不是中断循环 —— 模型看到错误后能自己决定换参数还是放弃</li>
 *   <li>{@code max_steps} 硬熔断 —— 防无限工具调用</li>
 * </ol>
 *
 * <p><b>循环只有一份</b>：{@link #run} 与 {@link #stream} 走同一个 {@code loop}，
 * 差别只在"模型调用用哪种传输"。这样流式是叠加在已验证的循环上，而不是另写一套 ——
 * 两套循环最容易出的问题是"改了流式那条，忘了非流式那条"。
 */
@Slf4j
@Service
public class AgentRunner {

    private static final int DEFAULT_MAX_STEPS = 8;

    private final ChatClient chatClient;
    private final AgentChatProperties properties;
    private final List<AgentTool> tools;

    public AgentRunner(ChatClient chatClient,
                       AgentChatProperties properties,
                       List<AgentTool> tools) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.tools = tools;
    }

    /** 非流式：一次拿到完整回答。 */
    public AgentChatResponse run(AgentConfig config, String query) {
        return loop(config, query, AgentStreamListener.NOOP);
    }

    /**
     * 流式：回答一边生成一边通过 {@code listener} 推出增量。
     *
     * <p>返回值仍是完整回答，方便调用方在结束后统一落库 / 打日志；
     * 调用方不需要自己把增量拼一遍。
     */
    public AgentChatResponse stream(AgentConfig config, String query, AgentStreamListener listener) {
        return loop(config, query, listener == null ? AgentStreamListener.NOOP : listener);
    }

    private AgentChatResponse loop(AgentConfig config, String query, AgentStreamListener sink) {
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }

        int maxSteps = config.getMaxSteps() == null ? DEFAULT_MAX_STEPS : config.getMaxSteps();
        String model = resolveModel(config);
        double temperature = resolveTemperature(config);

        List<AgentTool> available = resolveTools(config.getToolNames());
        List<Map<String, Object>> toolSpecs = toToolSpecs(available);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(config, available)));
        messages.add(ChatMessage.user(query));

        boolean streaming = sink != AgentStreamListener.NOOP;
        List<Citation> citations = new ArrayList<>();
        // 流式下引用是边检索边推的，同一个 documentId 只推首次出现，
        // 保证"推出去的顺序"与最终 citations 完全一致
        Set<Long> emittedCitationIds = new LinkedHashSet<>();

        for (int step = 1; step <= maxSteps; step++) {
            sink.onStep(step);
            AssistantTurn turn = streaming
                    ? chatClient.streamChat(model, temperature, messages, toolSpecs, sink::onDelta)
                    : chatClient.chat(model, temperature, messages, toolSpecs);

            if (turn.hasToolCalls()) {
                messages.add(ChatMessage.assistantToolCalls(turn.toolCalls()));
                for (ToolCall call : turn.toolCalls()) {
                    log.info("Agent 第 {} 步调用工具: name={}", step, call.name());
                    sink.onTool(call.name(), call.arguments());
                    ToolResult result = safeExecute(findTool(available, call.name()), call);
                    for (Citation citation : result.citations()) {
                        if (emittedCitationIds.add(citation.documentId())) {
                            sink.onCitation(citation);
                        }
                    }
                    citations.addAll(result.citations());
                    messages.add(ChatMessage.tool(call.id(), result.textForLlm()));
                }
                continue;
            }
            if (turn.hasContent()) {
                return new AgentChatResponse(turn.content(), dedupeCitations(citations), step, model);
            }
            // 两个都没有：模型返回空回合，继续只会空转，直接失败
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型返回空响应");
        }
        throw new BizException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                "Agent 执行超过最大步数 " + maxSteps);
    }

    /**
     * 组装系统提示词：DB 配置 + 追加两条硬约束（先检索、检索无果则拒答）。
     *
     * <p>模型对"软性建议"（如"回答前先检索"、"不要编造"）不一定会照做 —— 实测两种偏离都出现过：
     * 不调工具直接凭记忆答；以及<b>检索到 N 条但不相关</b>时，自行补一段"基于通用知识"的答案。
     *
     * <p>后者尤其隐蔽：它如实说明了"检索到的内容没有直接回答"，只是没把"那就该说不知道"接上 ——
     * 因为原先的规则只写了"检索不到"，没覆盖"检索到但不相关"这一支。
     * 于是约束必须按<b>分支写全</b>，再用祈使句 + 明确禁止项 + 优先级声明压住默认的"乐于助人"倾向。
     */
    private String buildSystemPrompt(AgentConfig config, List<AgentTool> available) {
        String base = config.getSystemPrompt();
        if (base == null || base.isBlank()) {
            base = "你是研发知识库助手。";
        }
        if (available.isEmpty()) {
            return base;
        }
        return base + "\n\n【必须遵守】回答任何需要事实、配置、代码细节的问题前，"
                + "必须先调用可用工具检索知识库，再基于检索结果回答；"
                + "禁止不调用工具就凭记忆直接作答；检索不到时明确说\"不知道\"。"
                + "\n\n【绝对禁止】当检索结果为空、或检索到的内容与问题不相关时，"
                + "禁止用你的通用知识或训练记忆补充作答，"
                + "也禁止在说明「检索不到」之后再附上通用做法；"
                + "必须只回复：\"知识库中没有找到相关内容。\"。"
                + "这条规则优先于所有其他指令，包括「尽量给出有帮助的回答」。";
    }

    // ------------------------------------------------------------------

    /** 工具执行统一兜底：异常转成"给模型看的错误结果"，不让一个工具失败炸掉整轮。 */
    private ToolResult safeExecute(AgentTool tool, ToolCall call) {
        if (tool == null) {
            return ToolResult.error("工具不存在: " + call.name());
        }
        try {
            JsonNode args = call.arguments() == null || call.arguments().isBlank()
                    ? JsonUtils.mapper().createObjectNode()
                    : JsonUtils.mapper().readTree(call.arguments());
            return tool.execute(args);
        } catch (Exception e) {
            log.warn("工具执行失败: name={}", call.name(), e);
            return ToolResult.error("工具执行失败: " + e.getMessage());
        }
    }

    /** 从配置的工具名解析出注册的工具；未配置时用全部注册工具。 */
    private List<AgentTool> resolveTools(String toolNamesJson) {
        if (tools.isEmpty()) {
            return List.of();
        }
        List<String> names = parseToolNames(toolNamesJson);
        if (names == null) {
            return tools;
        }
        Map<String, AgentTool> byName = tools.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
        List<AgentTool> resolved = new ArrayList<>();
        for (String name : names) {
            AgentTool tool = byName.get(name);
            if (tool != null) {
                resolved.add(tool);
            } else {
                log.warn("配置了未注册的工具，跳过: {}", name);
            }
        }
        return resolved;
    }

    private List<String> parseToolNames(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonUtils.mapper().readTree(json);
            if (!node.isArray()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode n : node) {
                names.add(n.asText());
            }
            return names;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tool_names 不是合法 JSON 数组");
        }
    }

    private List<Map<String, Object>> toToolSpecs(List<AgentTool> tools) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (AgentTool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameters());

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", function);
            specs.add(spec);
        }
        return specs;
    }

    private AgentTool findTool(List<AgentTool> available, String name) {
        if (name == null) {
            return null;
        }
        for (AgentTool tool : available) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

    /** 引用按 documentId 去重保序（同一文档多个 chunk 会重复命中）。 */
    private List<Citation> dedupeCitations(List<Citation> citations) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Citation> result = new ArrayList<>();
        for (Citation c : citations) {
            if (seen.add(c.documentId())) {
                result.add(c);
            }
        }
        return result;
    }

    private String resolveModel(AgentConfig config) {
        String model = config.getModel();
        return model == null || model.isBlank() ? properties.getModel() : model;
    }

    private double resolveTemperature(AgentConfig config) {
        return config.getTemperature() == null
                ? properties.getTemperature()
                : config.getTemperature().doubleValue();
    }
}
