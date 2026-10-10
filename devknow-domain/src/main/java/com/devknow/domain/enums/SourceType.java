package com.devknow.domain.enums;

import lombok.Getter;

/**
 * 数据源类型。
 */
@Getter
public enum SourceType {

    /** 既有 Git 仓库又有 issue 的 GitHub 仓库 */
    GITHUB_REPO("GITHUB_REPO"),

    /** 只取文档文件的 Git 仓库（GitLab / Gitee 等） */
    GIT_REPO("GIT_REPO"),

    /** 网页文档站（后续扩展） */
    WEBSITE("WEBSITE");

    private final String value;

    SourceType(String value) {
        this.value = value;
    }

    public static SourceType of(String value) {
        for (SourceType t : values()) {
            if (t.value.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知知识源类型: " + value);
    }
}
