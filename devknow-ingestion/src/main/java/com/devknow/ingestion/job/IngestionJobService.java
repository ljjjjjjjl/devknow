package com.devknow.ingestion.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.enums.JobEnums.JobStatus;
import com.devknow.domain.mapper.IngestionJobMapper;
import com.devknow.ingestion.config.DevknowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 采集任务状态机 —— 本项目的轻量任务队列。
 *
 * <p>核心是 {@link #pickNextJob()}：在<b>独立事务</b>里用
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 取任务并立即置为 RUNNING。
 * 事务提交时锁释放，但状态已经是 RUNNING，其他 Worker 不会再取到它。
 *
 * <p>之所以必须用独立事务：{@code FOR UPDATE SKIP LOCKED} 的锁只在事务内有效。
 * 如果把它和"执行采集"放在同一个长事务里，长事务会让锁持有数分钟，
 * 且 MVCC 快照无法回收 —— 这是典型的"把数据库当队列用"的反面教材。
 * 正确做法是：<b>取任务用短事务，执行任务在事务外</b>。
 *
 * <p><b>本类的两条硬规则：</b>
 * <ol>
 *   <li>只改几列的更新一律用 {@link LambdaUpdateWrapper}，不用
 *       {@code new IngestionJob()} + {@code updateById()} —— 后者会静默清空未赋值的列
 *       （原因见 {@link #updateProgress} 的注释）。</li>
 *   <li>长时间运行的任务必须<b>周期性调用 {@link #updateProgress}</b>，
 *       因为它承担了心跳职责：刷新 {@code locked_at}，否则任务会被
 *       {@link #reclaimStaleJobs()} 误判为僵死并重复执行。</li>
 * </ol>
 */
@Slf4j
@Service
public class IngestionJobService {

    /** RUNNING 超过此时长<b>且期间没有任何心跳</b>，才视为 Worker 崩溃，任务被回收重跑 */
    private static final Duration STALE_LOCK_TIMEOUT = Duration.ofMinutes(30);

    private final IngestionJobMapper jobMapper;
    private final DevknowProperties properties;
    private final TransactionTemplate transactionTemplate;

    public IngestionJobService(IngestionJobMapper jobMapper,
                               DevknowProperties properties,
                               TransactionTemplate transactionTemplate) {
        this.jobMapper = jobMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建待执行任务。同一知识源已有未完成任务时拒绝，避免重复采集把 API 配额打满。
     */
    @Transactional
    public IngestionJob createJob(Long sourceId, String jobType) {
        return createJob(sourceId, jobType, false);
    }

    /**
     * 创建待执行任务。
     *
     * @param skipUnfinishedCheck 同一批次内连续创建多个任务时传 {@code true}。
     *                            <b>这个参数是必需的</b>：{@code scope=all} 会先建 SYNC_ISSUES、
     *                            再建 SYNC_DOCS，而防重复检查在第二步会看到"刚建好的第一个任务"，
     *                            于是直接抛异常 —— 结果第二个任务永远建不出来，
     *                            表现为"文档一个都没采到、document_chunk 始终为 0"。
     *                            批次内的"重复"是自己造成的，应当跳过；跨批次的重复才需要拦。
     */
    @Transactional
    public IngestionJob createJob(Long sourceId, String jobType, boolean skipUnfinishedCheck) {
        if (!skipUnfinishedCheck) {
            int unfinished = jobMapper.countUnfinished(sourceId);
            if (unfinished > 0) {
                throw new BizException(ErrorCode.JOB_ALREADY_RUNNING,
                        "该知识源已有 " + unfinished + " 个未完成任务，请等待完成后再触发");
            }
        }
        IngestionJob job = new IngestionJob();
        job.setSourceId(sourceId);
        job.setJobType(jobType);
        job.setStatus(JobStatus.PENDING.getValue());
        job.setProgressDone(0);
        job.setRetryCount(0);
        job.setMaxRetry(3);
        job.setCreatedAt(Instant.now());
        jobMapper.insert(job);
        log.info("创建采集任务: id={}, sourceId={}, type={}", job.getId(), sourceId, jobType);
        return job;
    }

    /**
     * 取一个待执行任务并置为 RUNNING，全程在一个短事务内完成。
     *
     * <p>这里用 {@code updateById(完整实体)} 是<b>安全</b>的：{@code job} 是从
     * {@code selectNextPendingForUpdate()} 查出来的完整对象，所有字段都有真实值，
     * 不存在"未赋值字段被当成显式清空"的问题。
     *
     * <p>{@code lockedAt} 在这里是<b>第一次心跳</b>：它标记任务被领走的时刻，
     * 之后由 {@link #updateProgress} 持续刷新。
     *
     * @return 领取到的任务；无任务时返回 {@code null}
     */
    public IngestionJob pickNextJob() {
        return transactionTemplate.execute(status -> {
            IngestionJob job = jobMapper.selectNextPendingForUpdate();
            if (job == null) {
                return null;
            }
            Instant now = Instant.now();
            job.setStatus(JobStatus.RUNNING.getValue());
            job.setLockedBy(properties.getInstanceId());
            job.setLockedAt(now);
            job.setStartedAt(now);
            job.setErrorMessage(null);
            jobMapper.updateById(job);
            return job;
        });
    }

    /**
     * 记录进度，<b>同时刷新心跳</b>。定期调用而非每条都写：采集 1000 条 issue
     * 会产生 1000 次 UPDATE，无谓地放大 WAL 与真空压力。
     *
     * <p><b>为什么用 LambdaUpdateWrapper 而不是 updateById</b>：
     * 旧写法是 {@code new IngestionJob()} 只 set 三列再 {@code updateById}。
     * 由于实体上有若干字段带 {@code FieldStrategy.ALWAYS}（null 也会写进 SET），
     * 这个方法会把 {@code started_at}/{@code finished_at}/{@code locked_by}/
     * {@code locked_at}/{@code error_message} <b>全部清成 NULL</b>。
     *
     * <p>后果比"算不出耗时"严重得多：{@code reclaimStaleJobs} 是按
     * {@code locked_at < deadline} 找僵死任务的，而本方法每 5 页清一次
     * {@code locked_at} —— <b>僵死任务回收实际上一直处于失效状态</b>
     * （{@code NULL < deadline} 在三值逻辑里是"未知"，永远不成立）。
     *
     * <p>用 wrapper 显式列出要改的列，其余列不动，是结构性保证。
     *
     * <p><b>为什么必须在这里刷新 {@code locked_at}（心跳）</b>：
     * 只修好"不再把 locked_at 清成 NULL"是不够的 —— 那样 {@code locked_at} 会一直停在
     * {@code pickNextJob} 写入的时刻，于是<b>任何运行超过 30 分钟的正常任务都会被误回收</b>，
     * 被置回 PENDING 后由另一个 Worker（单实例下是另一个虚拟线程）再次领走，
     * 两个执行流并发跑同一个任务：GitHub 配额双倍消耗、{@code progress_done} 变成非单调。
     *
     * <p>因此在每次记录进度时顺带刷新 {@code locked_at}。
     * <b>语义变更</b>：{@code locked_at} 从"任务被领取的时间"变为
     * <b>"最后一次证明自己还活着的时间"</b>。只要任务在推进（每 N 页调用一次本方法），
     * 就不会被回收；真正僵死的任务在 {@link #STALE_LOCK_TIMEOUT}（30 分钟）内不会刷新，
     * 才会被回收。
     *
     * <p>任务的<b>开始时间</b>仍然可以从 {@code started_at} 读到
     * （该字段已不再被清空），两者语义不冲突：
     * <ul>
     *   <li>{@code started_at} —— 什么时候开始跑的</li>
     *   <li>{@code locked_at} —— 最后一次心跳是什么时候</li>
     *   <li>{@code locked_by} —— 现在是哪个 Worker 在跑</li>
     * </ul>
     *
     * <p><b>调用方的义务</b>：任何可能运行超过 {@code STALE_LOCK_TIMEOUT} 的同步流程，
     * 都必须在循环内部周期性调用本方法。目前 {@code GitHubIssueSyncer} 满足
     * （每页调用一次）；{@code GitRepoDocSyncer} 只在文件循环结束后调用一次，
     * 依赖"仓库拉取有 5 分钟超时兜底"这一前提 —— 文档量大幅增长后需要补心跳。
     */
    public void updateProgress(Long jobId, String cursor, int done, Integer total) {
        jobMapper.update(null, new LambdaUpdateWrapper<IngestionJob>()
                .eq(IngestionJob::getId, jobId)
                .set(IngestionJob::getCursor, cursor)
                .set(IngestionJob::getProgressDone, done)
                .set(IngestionJob::getProgressTotal, total)
                // 心跳：让 reclaimStaleJobs 知道这个任务还活着。
                // 少了这一行，超过 30 分钟的正常任务会被误判为僵死并重复执行。
                .set(IngestionJob::getLockedAt, Instant.now()));
    }

    /**
     * 标记任务成功。
     *
     * <p><b>为什么用 LambdaUpdateWrapper</b>：同 {@link #updateProgress}。
     * 旧写法会把它没 set 的字段（尤其是 {@code started_at}）全部写成 NULL ——
     * 实测两个 SUCCESS 任务的 {@code started_at} 都是空的，
     * 导致 {@code finished_at - started_at} 无法计算。
     *
     * <p><b>注意本方法不再写 cursor</b>：成功后游标已无业务用途，
     * 但它是"这次同步推进到哪一页"的唯一记录，对排查有用。
     * 旧代码通过 {@code ALWAYS} 把它顺手清成了 NULL —— 这是同一类"无意义的 NULL 写入"。
     *
     * @param cursor 保留参数以兼容调用方签名；本方法不写该列
     */
    public void markSuccess(Long jobId, int done, String cursor) {
        jobMapper.update(null, new LambdaUpdateWrapper<IngestionJob>()
                .eq(IngestionJob::getId, jobId)
                .set(IngestionJob::getStatus, JobStatus.SUCCESS.getValue())
                .set(IngestionJob::getProgressDone, done)
                .set(IngestionJob::getErrorMessage, null)
                .set(IngestionJob::getLockedBy, null)
                .set(IngestionJob::getLockedAt, null)
                .set(IngestionJob::getFinishedAt, Instant.now()));
        log.info("任务执行成功: id={}, 处理 {} 条", jobId, done);
    }

    /**
     * 标记失败。未超重试上限则回退为 PENDING 等待重跑，超限则置 DEAD（死信）。
     *
     * <p>{@code DEAD} 状态必须存在：否则永久失败的任务会被无限重试，刷爆日志与配额。
     *
     * <p>这里用 {@code updateById(完整实体)} 是<b>安全</b>的：{@code job} 由
     * {@code selectById()} 查出，字段完整。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long jobId, Throwable error) {
        IngestionJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        int retry = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
        int maxRetry = (job.getMaxRetry() == null ? 3 : job.getMaxRetry());

        job.setRetryCount(retry);
        job.setErrorMessage(abbreviate(error));
        job.setLockedBy(null);
        job.setLockedAt(null);

        if (retry >= maxRetry) {
            job.setStatus(JobStatus.DEAD.getValue());
            job.setFinishedAt(Instant.now());
            log.error("任务超过最大重试次数 {}，进入死信状态: id={}", maxRetry, jobId, error);
        } else {
            job.setStatus(JobStatus.PENDING.getValue());
            log.warn("任务失败，第 {}/{} 次，将重试: id={}, 原因: {}",
                    retry, maxRetry, jobId, error.getMessage());
        }
        jobMapper.updateById(job);
    }

    /**
     * 回收僵死任务：Worker 崩溃会留下永远 RUNNING 的任务。
     *
     * <p>这是"用数据库做队列"必须自己实现的一环 —— 换成 Kafka 时由 broker 的
     * session timeout 负责，这里就得手写。
     *
     * <p><b>判据是"距最后一次心跳超过 {@link #STALE_LOCK_TIMEOUT}"</b>，
     * 不是"任务运行超过该时长"。心跳由 {@link #updateProgress} 刷新；
     * 如果一个任务连续 30 分钟没有任何进度推进，才认为它的 Worker 已经死了。
     *
     * <p>这条链路对 {@code locked_at} 的依赖是双向的、缺一不可的：
     * <ul>
     *   <li>{@code locked_at} 被清成 NULL → 本方法永远查不到行 → 回收失效</li>
     *   <li>{@code locked_at} 从不刷新 → 长任务被误回收 → 重复执行</li>
     * </ul>
     * 两个坑都踩过，改任何一处都要同时检查另一处。
     *
     * @return 回收的任务数
     */
    @Transactional
    public int reclaimStaleJobs() {
        Instant deadline = Instant.now().minus(STALE_LOCK_TIMEOUT);
        List<IngestionJob> stale = jobMapper.selectStaleRunning(deadline);
        for (IngestionJob job : stale) {
            // 先把锁定时间取出来：下面会把它清空，先前的写法日志里永远是 null
            Instant lastLockedAt = job.getLockedAt();
            job.setStatus(JobStatus.PENDING.getValue());
            job.setLockedBy(null);
            job.setLockedAt(null);
            jobMapper.updateById(job);
            log.warn("回收僵死任务: id={}, 最后心跳于 {}", job.getId(), lastLockedAt);
        }
        return stale.size();
    }

    public List<IngestionJob> listJobs(String status, int limit) {
        LambdaQueryWrapper<IngestionJob> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq(IngestionJob::getStatus, status);
        }
        query.orderByDesc(IngestionJob::getCreatedAt).last("LIMIT " + limit);
        return jobMapper.selectList(query);
    }

    public long countByStatus(JobStatus status) {
        return jobMapper.selectCount(
                new LambdaQueryWrapper<IngestionJob>().eq(IngestionJob::getStatus, status.getValue()));
    }

    private static String abbreviate(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
