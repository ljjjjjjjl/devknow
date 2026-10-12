package com.devknow.ingestion.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.enums.JobEnums.JobStatus;
import com.devknow.domain.enums.JobEnums.JobType;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.domain.mapper.IngestionJobMapper;
import com.devknow.domain.mapper.KnowledgeSourceMapper;
import com.devknow.ingestion.git.GitRepoDocSyncer;
import com.devknow.ingestion.github.GitHubIssueSyncer;
import com.devknow.ingestion.index.ChunkBackfillService;
import com.devknow.ingestion.index.EmbeddingBackfillService;
import com.devknow.retrieval.config.EmbeddingProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务消费者。{@code @Scheduled} 轮询待执行任务，提交到<b>虚拟线程</b>执行器
 * （IO 密集型场景下几乎零成本提升并发度）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "devknow.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    /**
     * 单轮切分回填最多处理多少篇文档。
     *
     * <p>不复用 {@code devknow.embedding.scan-limit-per-round} —— 那是嵌入的批次上限，
     * 与切分无关。切分是纯 CPU 操作（无网络、无 API 配额），上限可以取得更大。
     */
    private static final int CHUNK_SCAN_LIMIT = 1000;

    private final IngestionJobService jobService;
    private final KnowledgeSourceMapper sourceMapper;
    private final IngestionJobMapper jobMapper;
    private final DocumentChunkMapper chunkMapper;
    private final GitHubIssueSyncer issueSyncer;
    private final GitRepoDocSyncer docSyncer;
    private final EmbeddingBackfillService backfillService;
    private final ChunkBackfillService chunkBackfillService;
    private final EmbeddingProperties embeddingProperties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public JobWorker(IngestionJobService jobService,
                     KnowledgeSourceMapper sourceMapper,
                     IngestionJobMapper jobMapper,
                     DocumentChunkMapper chunkMapper,
                     GitHubIssueSyncer issueSyncer,
                     GitRepoDocSyncer docSyncer,
                     EmbeddingBackfillService backfillService,
                     ChunkBackfillService chunkBackfillService,
                     EmbeddingProperties embeddingProperties) {
        this.jobService = jobService;
        this.sourceMapper = sourceMapper;
        this.jobMapper = jobMapper;
        this.chunkMapper = chunkMapper;
        this.issueSyncer = issueSyncer;
        this.docSyncer = docSyncer;
        this.backfillService = backfillService;
        this.chunkBackfillService = chunkBackfillService;
        this.embeddingProperties = embeddingProperties;
    }

    /** 用 {@code fixedDelay} 而非 {@code fixedRate}，避免任务堆积拖垮轮询线程自身。 */
    @Scheduled(fixedDelayString = "${devknow.worker.poll-interval-ms:5000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
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
            boolean syncJob = false;
            if (JobType.SYNC_ISSUES.getValue().equals(job.getJobType())) {
                count = issueSyncer.sync(source, job);
                syncJob = true;
            } else if (JobType.SYNC_DOCS.getValue().equals(job.getJobType())) {
                count = docSyncer.sync(source, job);
                syncJob = true;
            } else if (JobType.CHUNK.getValue().equals(job.getJobType())) {
                count = chunkBackfillService.backfill(source.getId(), CHUNK_SCAN_LIMIT);
            } else if (JobType.EMBED.getValue().equals(job.getJobType())) {
                count = backfillService.backfill(source.getId(),
                        embeddingProperties.getScanLimitPerRound());
            } else {
                throw new IllegalStateException("暂不支持的任务类型: " + job.getJobType());
            }

            jobService.markSuccess(job.getId(), count, null);

            // 采集成功后 enqueue EMBED，把"新内容可检索"的延迟从最长一个调度周期压到秒级。
            // 独立 try：enqueue 失败绝不能让已成功的同步任务变成失败。
            if (syncJob) {
                try {
                    enqueueEmbedJobIfNeeded(source);
                } catch (Exception e) {
                    log.warn("enqueue EMBED 任务失败，交给定时调度兜底: sourceId={}",
                            source.getId(), e);
                }
            }
        } catch (Exception e) {
            // markFailed 内部会判断是否超过重试上限，决定回退 PENDING 还是置 DEAD
            jobService.markFailed(job.getId(), e);
        }
    }

    /**
     * 采集成功后补一个 EMBED 任务。
     *
     * <p><b>必须传 {@code skipUnfinishedCheck=true}</b>：{@code createJob} 内部的
     * {@code countUnfinished(sourceId)} 是按<b>整个源</b>统计所有未完成任务，<b>不区分 jobType</b>。
     * {@code scope=all} 时两个 SYNC 任务并发跑，先完成的那个调本方法时另一个 SYNC 仍是 RUNNING，
     * 会被误判成"重复采集"而抛 {@code JOB_ALREADY_RUNNING}，异常被上面的 catch 吞掉。
     * EMBED 与 SYNC 是两类互不阻塞的任务，不该被这条检查拦住。
     *
     * <p>幂等改由 {@link #countActiveEmbedJobs()} 的前置检查保证 ——
     * 它只统计 <b>EMBED 类型</b>的 PENDING/RUNNING，正是这里需要的语义。
     * 两者之间的竞态窗口只有几毫秒：{@code @Scheduled} 是单线程的，
     * 而本方法只在 SYNC 完成时触发。即便偶尔创建出两个 EMBED 任务也无害 ——
     * {@code updateEmbedding} 带 {@code AND embedding IS NULL}，不会重复写库，
     * 代价仅是少量重复的 API 调用。
     */
    private void enqueueEmbedJobIfNeeded(KnowledgeSource source) {
        if (!embeddingProperties.isEnabled() || countActiveEmbedJobs() > 0) {
            return;
        }
        jobService.createJob(source.getId(), JobType.EMBED.getValue(), true);
        log.info("已 enqueue EMBED 任务: sourceId={}", source.getId());
    }

    /**
     * 定时扫描并创建 EMBED 任务 —— 回填的兜底通道，也是首次回填的入口。
     *
     * <p>熔断这条守卫不能省：任务失败进 DEAD 后就不再属于 PENDING/RUNNING，
     * 只看"有没有待跑任务"会让调度器不停创建新任务，死信无限增长。
     *
     * <p>{@code createJob} 同样传 {@code skipUnfinishedCheck=true}：否则同源有 SYNC 任务
     * 在跑时会因 {@code countUnfinished} 不区分 jobType 而被误判为重复。
     * 幂等在这里由 {@link #countActiveEmbedJobs()} 保证。
     */
    @Scheduled(fixedDelayString = "${devknow.embedding.scan-interval-ms:600000}")
    public void scanAndEnqueueEmbedJobs() {
        try {
            if (!embeddingProperties.isEnabled()) {
                return;
            }
            int dead = jobMapper.countDeadEmbedJobsSinceLastSuccess();
            if (dead >= embeddingProperties.getMaxConsecutiveDead()) {
                log.error("EMBED 熔断已打开: 自上次成功累计 {} 个死信（阈值 {}），停止创建新任务；"
                        + "修好配置后删除死信行即可复位", dead, embeddingProperties.getMaxConsecutiveDead());
                return;
            }
            if (countActiveEmbedJobs() > 0) {
                return;
            }
            // 单源项目：只挂唯一的知识源。多源时遍历所有 enabled 的源，各建一个 job
            // （ingestion_job.source_id 是 NOT NULL，必须挂在某个源上）。
            KnowledgeSource source = sourceMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeSource>()
                                    .eq(KnowledgeSource::getEnabled, true))
                    .stream().findFirst().orElse(null);
            // 存在性检查取 1 条即可，走 idx_chunk_unembedded 部分索引
            if (source == null || chunkMapper.selectUnembeddedBySource(source.getId(), 1).isEmpty()) {
                return;
            }
            jobService.createJob(source.getId(), JobType.EMBED.getValue(), true);
            log.info("定时调度创建 EMBED 任务: sourceId={}", source.getId());
        } catch (Exception e) {
            log.error("扫描并创建 EMBED 任务失败", e);
        }
    }

    private long countActiveEmbedJobs() {
        return jobMapper.selectCount(new LambdaQueryWrapper<IngestionJob>()
                .eq(IngestionJob::getJobType, JobType.EMBED.getValue())
                .in(IngestionJob::getStatus,
                        JobStatus.PENDING.getValue(), JobStatus.RUNNING.getValue()));
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

    public long pendingCount() {
        return jobService.countByStatus(JobStatus.PENDING);
    }

    /** 优雅关闭：等 30 秒让在途任务结束；被硬杀的残留任务由僵死回收兜底。 */
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
