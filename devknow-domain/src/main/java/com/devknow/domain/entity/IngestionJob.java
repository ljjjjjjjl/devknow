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
 *
 * <p><b>关于 {@code FieldStrategy.ALWAYS} 的取舍（踩过坑）</b>：
 * {@code ALWAYS} 的语义是"即使值为 null 也写进 SET 子句"，用来支持<b>显式清空</b>字段。
 * 但它和 {@code new IngestionJob()} + {@code updateById()} 这种"部分更新"组合时，
 * 会<b>静默清空未显式赋值的字段</b> —— 实测 {@code started_at} 就是这样被写成 NULL 的
 * （导致任务耗时无法计算），{@code updateProgress} 更是每 5 页清空一次
 * {@code locked_by}/{@code locked_at}，使僵死任务回收逻辑失效。
 *
 * <p>因此这里只对<b>确实需要写 NULL</b> 的字段保留 {@code ALWAYS}：
 * <ul>
 *   <li>{@code errorMessage} —— 重跑时要清掉上次的错误</li>
 *   <li>{@code lockedBy} / {@code lockedAt} —— 成功、失败、回收时都要解锁</li>
 * </ul>
 * 其余字段（{@code cursor}/{@code progressTotal}/{@code startedAt}/{@code finishedAt}）
 * <b>从不需要写 NULL</b>，改为默认策略（null 不进 SET），
 * 这样将来即使有人误用部分更新，也不会再清掉它们。
 *
 * <p><b>但仅靠这一点不够</b>：只要还有字段带 {@code ALWAYS}，
 * "部分更新"就依然危险。所以 {@code IngestionJobService} 里所有"只改几列"的更新
 * 一律改用 {@code LambdaUpdateWrapper} 显式列出列名 —— 让"没写到的列不动"
 * 成为结构性保证，而不是靠开发者记得。
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

    /**
     * 分页游标，进程重启后从游标继续，实现断点续跑。
     *
     * <p>不加 {@code ALWAYS}：这个字段只需要"被写入"，从不需要"被清空"。
     */
    private String cursor;

    private Integer progressDone;

    /** 不加 {@code ALWAYS}：见类注释。unknown 时保持旧值，不清空。 */
    private Integer progressTotal;

    private Integer retryCount;

    private Integer maxRetry;

    /** 需要能写 NULL：重跑时清掉上次的错误信息 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    /** Worker 实例标识，便于排查"谁卡住了这个任务"。需要能写 NULL：解锁时清空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lockedBy;

    /**
     * 锁定时间 / <b>心跳时间</b>。
     *
     * <p>两个语义，不要混淆：
     * <ul>
     *   <li><b>领取时</b> —— 记录任务被哪个 Worker 在什么时候领走（配合 {@code lockedBy}）。
     *       由 {@code pickNextJob()} 写入，是第一次心跳。</li>
     *   <li><b>运行中</b> —— 每次 {@code updateProgress()} 都会刷新，作为心跳。
     *       超过 {@code STALE_LOCK_TIMEOUT}（30 分钟）<b>没有任何刷新</b>，
     *       {@code reclaimStaleJobs()} 才判定为僵死。</li>
     * </ul>
     *
     * <p>注意判据是"距最后一次心跳的时长"，不是"任务已运行时长"。
     * 任务的开始时间看 {@code startedAt}。
     *
     * <p>需要能写 NULL：解锁时（成功 / 失败 / 回收）清空。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant lockedAt;

    /**
     * 任务开始时间（{@code pickNextJob} 写入）。
     *
     * <p><b>绝不能加 {@code ALWAYS}</b>：没有任何场景需要把它清成 NULL，
     * 而一旦允许写 NULL，{@code markSuccess} 的部分更新就会把它抹掉，
     * 导致任务耗时不可计算。这是实际踩过的坑。
     */
    private Instant startedAt;

    /** 不加 {@code ALWAYS}：见类注释。 */
    private Instant finishedAt;

    private Instant createdAt;
}
