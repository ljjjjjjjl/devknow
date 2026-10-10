package com.devknow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 智能体配置：提示词 + 模型 + 工具集 + 知识范围。
 *
 * <p>第 1 周只建表不实现，目的是让第 5 周写 Agent 时不必再改库、做 migration。
 */
@Data
@TableName("agent_config")
public class AgentConfig implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String systemPrompt;

    /** deepseek-chat / qwen-max / ... */
    private String model;

    private BigDecimal temperature;

    /** ReAct 循环最大步数，熔断用，防止无限工具调用 */
    private Integer maxSteps;

    /** 可用工具名单 JSON 数组，如 ["search_issues","search_docs"] */
    private String toolNames;

    /** 知识范围过滤 JSON，如 {"source_ids":[1,3],"versions":["3.2.x"]} */
    private String kbScope;

    private Boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;
}
