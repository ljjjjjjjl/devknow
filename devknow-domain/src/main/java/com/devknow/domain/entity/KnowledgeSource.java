package com.devknow.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 知识源：一个可同步的仓库或文档站。
 *
 * <p>表 DDL 见 {@code devknow-web/src/main/resources/db/migration/V1__init.sql}
 */
@Data
@TableName("knowledge_source")
public class KnowledgeSource implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** GIT_REPO | GITHUB_REPO | WEBSITE */
    private String type;

    private String repoUrl;

    private String repoOwner;

    private String repoName;

    private String branch;

    /** 文档文件匹配模式，如 src/main/antora/modules/ROOT/pages/**&#47;*.adoc */
    private String docPathPattern;

    /** 开源许可证，合规留痕 */
    private String license;

    /** 上次同步到的 commit，增量同步锚点 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastSyncedCommit;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant lastSyncedAt;

    private Boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;
}
