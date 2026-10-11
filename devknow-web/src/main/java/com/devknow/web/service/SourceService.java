package com.devknow.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.domain.dto.SourceCreateRequest;
import com.devknow.domain.entity.Document;
import com.devknow.domain.entity.DocumentChunk;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.enums.JobEnums.JobStatus;
import com.devknow.domain.enums.JobEnums.JobType;
import com.devknow.domain.enums.SourceType;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.domain.mapper.DocumentMapper;
import com.devknow.domain.mapper.IngestionJobMapper;
import com.devknow.domain.mapper.KnowledgeSourceMapper;
import com.devknow.ingestion.github.GitHubClient;
import com.devknow.ingestion.job.IngestionJobService;
import com.devknow.retrieval.config.EmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识源管理。
 */
@Slf4j
@Service
public class SourceService {

    /** 从仓库地址中解析 owner/repo，兼容带 .git 后缀与末尾斜杠 */
    private static final Pattern REPO_URL =
            Pattern.compile("^(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final KnowledgeSourceMapper sourceMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final IngestionJobMapper jobMapper;
    private final IngestionJobService jobService;
    private final GitHubClient gitHubClient;
    private final EmbeddingProperties embeddingProperties;

    public SourceService(KnowledgeSourceMapper sourceMapper,
                         DocumentMapper documentMapper,
                         DocumentChunkMapper chunkMapper,
                         IngestionJobMapper jobMapper,
                         IngestionJobService jobService,
                         GitHubClient gitHubClient,
                         EmbeddingProperties embeddingProperties) {
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.jobMapper = jobMapper;
        this.jobService = jobService;
        this.gitHubClient = gitHubClient;
        this.embeddingProperties = embeddingProperties;
    }

    public List<KnowledgeSource> listSources() {
        return sourceMapper.selectList(
                new LambdaQueryWrapper<KnowledgeSource>().orderByDesc(KnowledgeSource::getCreatedAt));
    }

    public KnowledgeSource getSource(Long id) {
        KnowledgeSource source = sourceMapper.selectById(id);
        if (source == null) {
            throw new BizException(ErrorCode.SOURCE_NOT_FOUND, "知识源不存在: id=" + id);
        }
        return source;
    }

    @Transactional
    public KnowledgeSource createSource(SourceCreateRequest request) {
        SourceType type = SourceType.of(request.getType());

        Matcher matcher = REPO_URL.matcher(request.getRepoUrl().trim());
        if (!matcher.matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "无法从仓库地址解析 owner/repo: " + request.getRepoUrl());
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);

        Long duplicated = sourceMapper.selectCount(new LambdaQueryWrapper<KnowledgeSource>()
                .eq(KnowledgeSource::getRepoOwner, owner)
                .eq(KnowledgeSource::getRepoName, repo));
        if (duplicated != null && duplicated > 0) {
            throw new BizException(ErrorCode.SOURCE_ALREADY_EXISTS,
                    "该仓库已存在知识源: " + owner + "/" + repo);
        }

        KnowledgeSource source = new KnowledgeSource();
        source.setName(request.getName());
        source.setType(type.getValue());
        source.setRepoUrl(request.getRepoUrl().trim());
        source.setRepoOwner(owner);
        source.setRepoName(repo);
        source.setBranch(request.getBranch() == null || request.getBranch().isBlank()
                ? "main" : request.getBranch());
        source.setDocPathPattern(request.getDocPathPattern());
        source.setLicense(request.getLicense());
        source.setEnabled(true);
        sourceMapper.insert(source);

        log.info("创建知识源: id={}, {} ({}), type={}", source.getId(), source.getName(),
                owner + "/" + repo, type.getValue());
        return source;
    }

    /**
     * 触发同步。按类型选择拉取 issue 还是文档，或两者都拉。
     *
     * <p><b>为什么传 {@code skipUnfinishedCheck=true}</b>：{@code scope=all} 要在同一个批次里
     * 连续创建两个任务，而防重复检查会看到"本批次刚创建的第一个任务"并拒绝第二个。
     * 跨批次的防重复仍然生效（由 Controller 层的入口语义保证：一次请求 = 一个批次）。
     *
     * @return 创建的任务列表
     */
    public List<IngestionJob> triggerSync(Long sourceId, String scope) {
        KnowledgeSource source = getSource(sourceId);
        String normalized = scope == null ? "all" : scope.toLowerCase();

        List<IngestionJob> jobs = new java.util.ArrayList<>();
        if ("all".equals(normalized) || "issues".equals(normalized)) {
            jobs.add(jobService.createJob(sourceId, JobType.SYNC_ISSUES.getValue(), true));
        }
        if ("all".equals(normalized) || "docs".equals(normalized)) {
            jobs.add(jobService.createJob(sourceId, JobType.SYNC_DOCS.getValue(), true));
        }
        if (jobs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的同步范围: " + scope);
        }
        return jobs;
    }

    /**
     * 入库统计。所有数字都直接来自数据库聚合查询，
     * 而不是内存计数器 —— 重启后数字依然正确。
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sourceCount", sourceMapper.selectCount(null));
        stats.put("documentCount", documentMapper.selectCount(null));
        stats.put("chunkCount", chunkMapper.selectCount(null));

        Map<String, Long> byType = new LinkedHashMap<>();
        for (DocType docType : DocType.values()) {
            Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                    .eq(Document::getDocType, docType.getValue()));
            byType.put(docType.getValue(), count == null ? 0L : count);
        }
        stats.put("documentCountByType", byType);

        stats.put("pendingEmbedding", countUnembeddedChunks());
        stats.put("deadEmbedJobs", countDeadEmbedJobs());
        stats.put("embedCircuitOpen", isEmbedCircuitOpen());
        stats.put("pendingJobs", jobService.countByStatus(JobStatus.PENDING));
        stats.put("deadJobs", jobService.countByStatus(JobStatus.DEAD));
        stats.put("generatedAt", Instant.now().toString());
        return stats;
    }

    /**
     * 尚未向量化的 chunk 数 —— 回填的进度指标。
     *
     * <p><b>为什么用字符串列名的 {@link QueryWrapper} 而不是 {@code LambdaQueryWrapper}：
     * {@code DocumentChunk} 实体里没有 {@code embedding} 字段</b>（向量写入全部走手写 SQL，
     * 实体不需要承载 1024 维数组）。而 {@code LambdaQueryWrapper} 要求属性能被
     * MyBatis-Plus 解析成列 —— 给字段加 {@code @TableField(exist = false)} 也不行，
     * 那会让它根本不在 TableInfo 里，调用时抛
     * {@code can not find lambda cache for this property}。
     * 用字符串列名直接生成 {@code WHERE embedding IS NULL}，是最简单也最诚实的做法。
     *
     * <p>命中 V2 迁移建的 {@code idx_chunk_unembedded} 部分索引，全部嵌完后接近 O(1)；
     * 若用全表扫描则是 O(总行数)，而这个接口会被频繁调用。
     */
    private long countUnembeddedChunks() {
        Long count = chunkMapper.selectCount(new QueryWrapper<DocumentChunk>().isNull("embedding"));
        return count == null ? 0L : count;
    }

    /** EMBED 类型的死信数。它是 {@code deadJobs} 的子集，但熔断判据只看它。 */
    private long countDeadEmbedJobs() {
        Long count = jobMapper.selectCount(new LambdaQueryWrapper<IngestionJob>()
                .eq(IngestionJob::getJobType, JobType.EMBED.getValue())
                .eq(IngestionJob::getStatus, JobStatus.DEAD.getValue()));
        return count == null ? 0L : count;
    }

    /**
     * EMBED 熔断器是否已打开。
     *
     * <p>判据与 {@code JobWorker.scanAndEnqueueEmbedJobs} 保持一致 ——
     * 两处必须同步，否则会出现"调度其实已经停了，但接口显示一切正常"，让人排查错方向。
     */
    private boolean isEmbedCircuitOpen() {
        return jobMapper.countDeadEmbedJobsSinceLastSuccess()
                >= embeddingProperties.getMaxConsecutiveDead();
    }

    /** 查询 GitHub 配额，用于验证 token 是否生效。 */
    public GitHubClient.RateLimitInfo githubStatus() {
        return gitHubClient.fetchRateLimit();
    }

    /** 删除知识源（其下文档与 chunk 由外键 ON DELETE CASCADE 自动清理）。 */
    @Transactional
    public void deleteSource(Long id) {
        getSource(id);
        sourceMapper.deleteById(id);
        log.info("删除知识源: id={}", id);
    }

    public List<DocumentChunk> previewChunks(Long documentId, int limit) {
        return chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .orderByAsc(DocumentChunk::getChunkIndex)
                .last("LIMIT " + Math.min(limit, 100)));
    }
}
