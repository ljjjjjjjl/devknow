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
 * 采集任务状态机，同时充当轻量任务队列。
 *
 * <p><b>消费方式</b>：{@code SELECT ... FOR UPDATE SKIP LOCKED}，
 * 配合部分索引 {@code idx_job_pick} 让取任务 O(1)，多实例天然安全。
 *
 * <p><b>为什么不上 Kafka</b>：任务粒度粗（一次同步一个仓库）、小时级频率，
 * 数据库表完全够用，少一个运维组件，且原理更值得讲。
 */
@Data
@TableName("ingestion_job")
public class IngestionJob implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    /** SYNC_ISSUES | SYNC_DOCS | PARSE | CHUNK | EMBED */
    private String jobType;

    /** PENDING | RUNNING | SUCCESS | FAILED | DEAD */
    private String status;

    /** 分页游标，进程重启后从游标继续，实现断点续跑 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cursor;

    private Integer progressDone;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer progressTotal;

    private Integer retryCount;

    private Integer maxRetry;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    /** Worker 实例标识，便于排查"谁卡住了这个任务" */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lockedBy;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant lockedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant startedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant finishedAt;

    private Instant createdAt;
}
