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
 * 文档：抓取到的原始内容，切分前的完整单元（一条 issue / 一个文档文件）。
 *
 * <p><b>幂等键</b>：{@code (source_id, doc_type, external_id, version)} 唯一。
 * 采集重跑不会产生重复行，这是从"脚本"到"系统"的分界线。
 *
 * <p><b>增量依据</b>：{@code contentHash} 未变则跳过切分与向量化，可省 90% 算力。
 */
@Data
@TableName("document")
public class Document implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    /** DOC | ISSUE | RELEASE_NOTE | SOURCE_CODE */
    private String docType;

    /** issue number / 文件相对路径 / tag 名 */
    private String externalId;

    private String url;

    private String title;

    /** 版本标识，如 v3.2.1 / 3.1.x。技术文档答案是强版本相关的 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String version;

    /** 版本排序用整数。禁止字符串比较：'3.10' &lt; '3.9' 是 true */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer versionOrder;

    /** 文档在仓库内的路径，用于生成 breadcrumb */
    private String path;

    private String rawContent;

    /** sha256，去重 + 增量判断 */
    private String contentHash;

    private String lang;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant publishedAt;

    /** ACTIVE | DELETED | OUTDATED */
    private String status;

    /**
     * 数据源特有字段（labels / state / state_reason / comment_count 等）。
     *
     * <p>用 String 承载 JSONB 文本而非 Map：避免依赖 MyBatis-Plus 的 JacksonTypeHandler
     * 在自定义 SQL（ON CONFLICT upsert）中失效，读写都走 {@code JsonUtils} 显式转换，
     * 行为完全可预期。
     */
    private String metadata;

    private Instant createdAt;

    private Instant updatedAt;
}
