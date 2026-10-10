package com.devknow.ingestion.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 */
@Slf4j
@Service
public class IngestionJobService {

    /** RUNNING 超过此时长视为 Worker 崩溃，任务被回收重跑 */
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
     * 记录进度。定期调用而非每条都写：采集 1000 条 issue 会产生 1000 次 UPDATE，
     * 无谓地放大 WAL 与真空压力。
     */
    public void updateProgress(Long jobId, String cursor, int done, Integer total) {
        IngestionJob update = new IngestionJob();
        update.setId(jobId);
        update.setCursor(cursor);
        update.setProgressDone(done);
        update.setProgressTotal(total);
        jobMapper.updateById(update);
    }

    public void markSuccess(Long jobId, int done, String cursor) {
        IngestionJob update = new IngestionJob();
        update.setId(jobId);
        update.setStatus(JobStatus.SUCCESS.getValue());
        update.setProgressDone(done);
        update.setCursor(cursor);
        update.setErrorMessage(null);
        update.setLockedBy(null);
        update.setLockedAt(null);
        update.setFinishedAt(Instant.now());
        jobMapper.updateById(update);
        log.info("任务执行成功: id={}, 处理 {} 条", jobId, done);
    }

    /**
     * 标记失败。未超重试上限则回退为 PENDING 等待重跑，超限则置 DEAD（死信）。
     *
     * <p>{@code DEAD} 状态必须存在：否则永久失败的任务会被无限重试，刷爆日志与配额。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long jobId, Throwable error) {
        IngestionJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        int retry = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
        int maxRetry = job.getMaxRetry() == null ? 3 : job.getMaxRetry();

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
     * @return 回收的任务数
     */
    @Transactional
    public int reclaimStaleJobs() {
        Instant deadline = Instant.now().minus(STALE_LOCK_TIMEOUT);
        List<IngestionJob> stale = jobMapper.selectStaleRunning(deadline);
        for (IngestionJob job : stale) {
            job.setStatus(JobStatus.PENDING.getValue());
            job.setLockedBy(null);
            job.setLockedAt(null);
            jobMapper.updateById(job);
            log.warn("回收僵死任务: id={}, 上次锁定于 {}", job.getId(), job.getLockedAt());
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
