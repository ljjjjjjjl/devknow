package com.devknow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 知识单元：切分产物，检索与向量化的基本单位。
 *
 * <p>{@code headingPath} 是 RAG 效果的隐形冠军：既用于检索时的关键词加权，
 * 也用于给 LLM 提供上下文定位，还让引用展示可读。从第一天就填，事后补是灾难。
 *
 * <p>第 2 周由 {@code V2__add_pgvector.sql} 增加：
 * {@code embedding vector(1024)} / {@code embedding_model} / {@code embedded_at}。
 */
@Data
@TableName("document_chunk")
public class DocumentChunk implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    /** TEXT | CODE | TABLE | LIST */
    private String chunkType;

    /** 标题路径，如 "Spring Boot > Data > Redis > 配置" */
    private String headingPath;

    private Short headingLevel;

    private String content;

    private Integer contentTokens;

    private String contentHash;

    private String metadata;

    private Instant createdAt;
}
