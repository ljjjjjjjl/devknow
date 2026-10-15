package com.devknow.agent.engine;

import com.devknow.agent.client.AssistantTurn;
import com.devknow.agent.client.ChatClient;
import com.devknow.agent.client.ChatMessage;
import com.devknow.agent.client.ToolCall;
import com.devknow.agent.config.AgentChatProperties;
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
 * <p>第 5 周非流式最小闭环。两条硬规则：
 * <ol>
 *   <li>工具失败<b>喂回给模型</b>而不是中断循环 —— 模型看到错误后能自己决定换参数还是放弃</li>
 *   <li>{@code max_steps} 硬熔断 —— 防无限工具调用</li>
 * </ol>
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

    public AgentChatResponse run(AgentConfig config, String query) {
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

        List<Citation> citations = new ArrayList<>();
        for (int step = 1; step <= maxSteps; step++) {
            AssistantTurn turn = chatClient.chat(model, temperature, messages, toolSpecs);

            if (turn.hasToolCalls()) {
                messages.add(ChatMessage.assistantToolCalls(turn.toolCalls()));
                for (ToolCall call : turn.toolCalls()) {
                    log.info("Agent 第 {} 步调用工具: name={}", step, call.name());
                    ToolResult result = safeExecute(findTool(available, call.name()), call);
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
     * 组装系统提示词：DB 配置 + 追加一条"必须先用工具检索"的硬约束。
     *
     * <p>模型对"软性建议"（如"回答前先检索"）不一定会照做 —— 实测会直接凭记忆作答。
     * 追加的硬约束用祈使句 + 明确禁止项，显著提高工具调用率。
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
                + "禁止不调用工具就凭记忆直接作答；检索不到时明确说\"不知道\"。";
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
