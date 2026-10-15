package com.devknow.agent.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型返回的一个工具调用。
 *
 * @param id        本次调用的 id，回填 tool 结果时必须原样带回，模型靠它对齐
 * @param name      工具名
 * @param arguments 参数的 JSON 字符串（OpenAI 协议里 arguments 是字符串，不是对象）
 */
public record ToolCall(String id, String name, String arguments) {

    /** 转成回传 assistant 消息里的 tool_calls 元素。 */
    Map<String, Object> toMap() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", function);
        return call;
    }
}
