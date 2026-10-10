package com.devknow.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建知识源请求。
 */
@Data
public class SourceCreateRequest {

    @NotBlank(message = "知识源名称不能为空")
    private String name;

    /** GITHUB_REPO | GIT_REPO | WEBSITE */
    @NotBlank(message = "知识源类型不能为空")
    private String type;

    /**
     * 仓库地址，如 https://github.com/spring-projects/spring-boot
     * 传入后会解析出 repoOwner / repoName，无需调用方分别填。
     */
    @NotBlank(message = "仓库地址不能为空")
    private String repoUrl;

    private String branch = "main";

    /** 文档文件匹配模式（glob），如 src/main/antora/modules/ROOT/pages/**&#47;*.adoc */
    private String docPathPattern;

    private String license;
}
