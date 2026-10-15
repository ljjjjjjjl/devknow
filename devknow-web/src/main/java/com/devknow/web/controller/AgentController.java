package com.devknow.web.controller;

import com.devknow.agent.config.AgentConfigService;
import com.devknow.agent.engine.AgentRunner;
import com.devknow.common.api.ApiResponse;
import com.devknow.domain.dto.AgentChatRequest;
import com.devknow.domain.dto.AgentChatResponse;
import com.devknow.domain.entity.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 对话接口（第 5 周最小闭环：非流式）。
 *
 * <p>先跑通 ReAct 循环 + 工具调用 + 引用来源，SSE 流式在后续迭代。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentConfigService configService;
    private final AgentRunner runner;

    public AgentController(AgentConfigService configService, AgentRunner runner) {
        this.configService = configService;
        this.runner = runner;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        AgentConfig config = configService.getRequired(request.agentConfigId());
        AgentChatResponse response = runner.run(config, request.query());
        log.info("Agent 对话完成: configId={}, steps={}, 引用 {} 条",
                config.getId(), response.steps(),
                response.citations() == null ? 0 : response.citations().size());
        return ApiResponse.ok(response);
    }
}
