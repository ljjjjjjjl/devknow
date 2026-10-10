package com.devknow.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.domain.dto.SourceCreateRequest;
import com.devknow.domain.entity.Document;
import com.devknow.domain.entity.DocumentChunk;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.enums.JobEnums.JobType;
import com.devknow.domain.enums.SourceType;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.domain.mapper.DocumentMapper;
import com.devknow.domain.mapper.KnowledgeSourceMapper;
import com.devknow.ingestion.github.GitHubClient;
import com.devknow.ingestion.job.IngestionJobService;
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
    private final IngestionJobService jobService;
    private final GitHubClient gitHubClient;

    public SourceService(KnowledgeSourceMapper sourceMapper,
                         DocumentMapper documentMapper,
                         DocumentChunkMapper chunkMapper,
                         IngestionJobService jobService,
                         GitHubClient gitHubClient) {
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.jobService = jobService;
        this.gitHubClient = gitHubClient;
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

        long unembedded = countUnembeddedChunks();
        stats.put("chunkWithoutEmbedding", unembedded);
        stats.put("pendingJobs", jobService.countByStatus(
                com.devknow.domain.enums.JobEnums.JobStatus.PENDING));
        stats.put("deadJobs", jobService.countByStatus(
                com.devknow.domain.enums.JobEnums.JobStatus.DEAD));
        stats.put("generatedAt", Instant.now().toString());
        return stats;
    }

    /**
     * 尚未向量化的 chunk 数。
     *
     * <p>第 1 周没有 embedding 列，这个数字等于 chunk 总数 —— 它正是"第 2 周要处理多少"
     * 的进度指标，所以现在就把它暴露出来。
     */
    private long countUnembeddedChunks() {
        Long total = chunkMapper.selectCount(null);
        return total == null ? 0L : total;
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
