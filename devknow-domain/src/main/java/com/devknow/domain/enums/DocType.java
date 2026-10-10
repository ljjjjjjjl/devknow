package com.devknow.domain.enums;

import lombok.Getter;

/**
 * 文档类型。
 */
@Getter
public enum DocType {

    /** 仓库中的文档文件（.adoc / .md） */
    DOC("DOC"),

    /** GitHub issue（不含 PR） */
    ISSUE("ISSUE"),

    /** Pull Request */
    PR("PR"),

    /** Release notes / 变更日志 */
    RELEASE_NOTE("RELEASE_NOTE"),

    /** 源码文件（第5周接入，用于源码级问答） */
    SOURCE_CODE("SOURCE_CODE");

    private final String value;

    DocType(String value) {
        this.value = value;
    }

    public static DocType of(String value) {
        for (DocType t : values()) {
            if (t.value.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知文档类型: " + value);
    }
}
