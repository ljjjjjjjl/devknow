package com.devknow.ingestion.job;

import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.enums.JobEnums.JobStatus;
import com.devknow.domain.enums.JobEnums.JobType;
import com.devknow.domain.mapper.KnowledgeSourceMapper;
import com.devknow.ingestion.git.GitRepoDocSyncer;
import com.devknow.ingestion.github.GitHubIssueSyncer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务消费者。
 *
 * <p>运行模型：
 * <ul>
 *   <li>{@code @Scheduled} 定时轮询待执行任务（默认 5 秒）</li>
 *   <li>任务提交到<b>虚拟线程</b>执行器，因此可以同时跑多个任务而不占用平台线程</li>
 *   <li>{@link AtomicBoolean} 保证同一实例内不会并发轮询重叠</li>
 * </ul>
 *
 * <p>为什么用虚拟线程而不是固定大小线程池：采集是 IO 密集型
 * （等 GitHub API、等磁盘、等数据库），虚拟线程在这种场景下几乎零成本地提升并发度，
 * 而平台线程池的大小难以调优 —— 调小了浪费，调大了浪费内存。
 *
 * <p>可以通过 {@code devknow.worker.enabled=false} 关闭，便于本地只跑 Web 不打采集。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "devknow.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private final IngestionJobService jobService;
    private final KnowledgeSourceMapper sourceMapper;
    private final GitHubIssueSyncer issueSyncer;
    private final GitRepoDocSyncer docSyncer;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public JobWorker(IngestionJobService jobService,
                     KnowledgeSourceMapper sourceMapper,
                     GitHubIssueSyncer issueSyncer,
                     GitRepoDocSyncer docSyncer) {
        this.jobService = jobService;
        this.sourceMapper = sourceMapper;
        this.issueSyncer = issueSyncer;
        this.docSyncer = docSyncer;
    }

    /**
     * 轮询待执行任务并提交执行。
     *
     * <p>{@code fixedDelay}（而不是 {@code fixedRate}）保证上一次轮询结束后才开始计时，
     * 避免任务堆积导致轮询线程自身成为瓶颈。
     */
    @Scheduled(fixedDelayString = "${devknow.worker.poll-interval-ms:5000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            // 一个轮询周期内把所有待执行任务都领走，而不是每次只领一个
            IngestionJob job;
            while ((job = jobService.pickNextJob()) != null) {
                IngestionJob target = job;
                executor.submit(() -> execute(target));
            }
        } catch (Exception e) {
            log.error("轮询任务时发生异常", e);
        } finally {
            polling.set(false);
        }
    }

    /** 执行单个任务，异常一律转为任务状态，绝不让异常逃逸导致调度线程退出。 */
    void execute(IngestionJob job) {
        log.info("开始执行任务: id={}, type={}, sourceId={}",
                job.getId(), job.getJobType(), job.getSourceId());
        try {
            KnowledgeSource source = sourceMapper.selectById(job.getSourceId());
            if (source == null) {
                throw new IllegalStateException("知识源不存在: " + job.getSourceId());
            }

            int count;
            if (JobType.SYNC_ISSUES.getValue().equals(job.getJobType())) {
                count = issueSyncer.sync(source, job);
            } else if (JobType.SYNC_DOCS.getValue().equals(job.getJobType())) {
                count = docSyncer.sync(source, job);
            } else {
                throw new IllegalStateException("暂不支持的任务类型: " + job.getJobType());
            }

            jobService.markSuccess(job.getId(), count, null);
        } catch (Exception e) {
            // markFailed 内部会判断是否超过重试上限，决定回退 PENDING 还是置 DEAD
            jobService.markFailed(job.getId(), e);
        }
    }

    /** 每 10 分钟回收僵死任务（Worker 崩溃留下的 RUNNING 任务）。 */
    @Scheduled(fixedDelayString = "${devknow.worker.reclaim-interval-ms:600000}")
    public void reclaimStaleJobs() {
        try {
            int reclaimed = jobService.reclaimStaleJobs();
            if (reclaimed > 0) {
                log.warn("回收了 {} 个僵死任务", reclaimed);
            }
        } catch (Exception e) {
            log.error("回收僵死任务失败", e);
        }
    }

    /** 当前待执行任务数，供监控接口使用。 */
    public long pendingCount() {
        return jobService.countByStatus(JobStatus.PENDING);
    }

    /**
     * 优雅关闭：等待在途任务结束，避免任务被硬杀后留在 RUNNING 状态。
     * 超过 30 秒未结束则强制关闭，残留任务由 {@link #reclaimStaleJobs()} 兜底。
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭任务执行器...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("任务执行器未在 30 秒内结束，强制关闭");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
