package com.devknow.common.api;

import lombok.Getter;

/**
 * 统一错误码。
 *
 * <p>约定：错误码按模块分段，便于日志检索与前端分支处理。
 * <ul>
 *   <li>0        成功</li>
 *   <li>1xxxx    通用/参数</li>
 *   <li>2xxxx    知识源与采集</li>
 *   <li>3xxxx    检索</li>
 *   <li>4xxxx    Agent 运行时</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 通用 ----
    BAD_REQUEST(10000, "请求参数不合法"),
    NOT_FOUND(10001, "资源不存在"),
    CONFLICT(10002, "资源冲突"),
    INTERNAL_ERROR(10003, "服务内部错误"),

    // ---- 知识源与采集 ----
    SOURCE_NOT_FOUND(20000, "知识源不存在"),
    SOURCE_ALREADY_EXISTS(20001, "知识源已存在"),
    JOB_NOT_FOUND(20002, "采集任务不存在"),
    JOB_ALREADY_RUNNING(20003, "该知识源已有任务正在执行"),
    GITHUB_API_ERROR(20004, "GitHub API 调用失败"),
    GITHUB_RATE_LIMITED(20005, "GitHub API 触发限流"),
    REPO_CLONE_FAILED(20006, "仓库克隆失败"),
    DOC_PARSE_FAILED(20007, "文档解析失败"),

    // ---- 检索 ----
    EMBEDDING_FAILED(30000, "向量化失败"),
    EMBEDDING_NOT_CONFIGURED(30001, "向量化服务未配置（第2周接入）"),
    RETRIEVAL_FAILED(30002, "检索失败"),
    EMBEDDING_RATE_LIMITED(30003, "向量化触发限流"),
    RERANK_FAILED(30004, "重排序失败"),

    // ---- Agent ----
    AGENT_NOT_FOUND(40000, "智能体配置不存在"),
    AGENT_MAX_STEPS_EXCEEDED(40001, "Agent 执行步数超限"),
    TOOL_EXECUTION_FAILED(40002, "工具执行失败"),
    AGENT_LLM_ERROR(40003, "Agent 调用模型失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
