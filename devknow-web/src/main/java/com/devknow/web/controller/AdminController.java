package com.devknow.web.controller;

import com.devknow.common.api.ApiResponse;
import com.devknow.domain.dto.JobVO;
import com.devknow.domain.dto.SearchRequest;
import com.devknow.domain.dto.SearchResult;
import com.devknow.domain.dto.SourceCreateRequest;
import com.devknow.domain.dto.SourceVO;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.ingestion.github.GitHubClient;
import com.devknow.ingestion.job.IngestionJobService;
import com.devknow.retrieval.RetrievalService;
import com.devknow.web.service.SourceService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台管理接口：知识源、采集任务、统计、检索。
 *
 * <p>Day 6 的验收标准就是这组接口能跑通完整闭环：
 * <b>建源 → 触发同步 → 查任务状态 → 看统计</b>。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SourceService sourceService;
    private final IngestionJobService jobService;
    private final RetrievalService retrievalService;

    public AdminController(SourceService sourceService,
                           IngestionJobService jobService,
                           RetrievalService retrievalService) {
        this.sourceService = sourceService;
        this.jobService = jobService;
        this.retrievalService = retrievalService;
    }

    // ---------------- 知识源 ----------------

    @GetMapping("/sources")
    public ApiResponse<List<SourceVO>> listSources() {
        List<SourceVO> list = sourceService.listSources().stream().map(SourceVO::from).toList();
        return ApiResponse.ok(list);
    }

    @PostMapping("/sources")
    public ApiResponse<SourceVO> createSource(@Valid @RequestBody SourceCreateRequest request) {
        KnowledgeSource source = sourceService.createSource(request);
        return ApiResponse.ok(SourceVO.from(source));
    }

    @DeleteMapping("/sources/{id}")
    public ApiResponse<Void> deleteSource(@PathVariable Long id) {
        sourceService.deleteSource(id);
        return ApiResponse.ok();
    }

    /**
     * 触发同步。
     *
     * @param scope issues / docs / chunk / all，默认 all
     */
    @PostMapping("/sources/{id}/sync")
    public ApiResponse<List<JobVO>> triggerSync(@PathVariable Long id,
                                                @RequestParam(defaultValue = "all") String scope) {
        List<IngestionJob> jobs = sourceService.triggerSync(id, scope);
        log.info("触发同步: sourceId={}, scope={}, 创建 {} 个任务", id, scope, jobs.size());
        return ApiResponse.ok(jobs.stream().map(JobVO::from).toList());
    }

    // ---------------- 任务 ----------------

    @GetMapping("/jobs")
    public ApiResponse<List<JobVO>> listJobs(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "50") int limit) {
        List<JobVO> list = jobService.listJobs(status, Math.min(limit, 200)).stream()
                .map(JobVO::from).toList();
        return ApiResponse.ok(list);
    }

    // ---------------- 统计与自检 ----------------

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(sourceService.stats());
    }

    /**
     * 查询 GitHub API 配额。
     *
     * <p>Day 3 的验收标准：能看到 remaining 是一个接近 5000 的数字，
     * 说明 token 生效；如果返回 60 或报错，说明 token 没配上。
     */
    @GetMapping("/github/status")
    public ApiResponse<Map<String, Object>> githubStatus() {
        GitHubClient.RateLimitInfo info = sourceService.githubStatus();
        return ApiResponse.ok(Map.of(
                "authenticated", info.isAuthenticated(),
                "limit", info.getLimit() == null ? 0 : info.getLimit(),
                "remaining", info.getRemaining() == null ? 0 : info.getRemaining(),
                "resetAt", info.resetAt() == null ? "" : info.resetAt().toString()
        ));
    }

    // ---------------- 检索 ----------------

    /**
     * 混合检索：全文 + 向量双路，RRF 融合。
     *
     * <p>{@code query} 为空由 {@link RetrievalService} 抛业务异常，
     * 不在这里判 —— 校验规则属于检索语义，放在服务层只有一份。
     *
     * <p>返回体里的 {@code rrfScore} 只在同一次 query 内可比（RRF 分数带很窄，
     * 实测跨度仅 6%），判断"哪一路贡献了什么"要看 {@code vectorRank} / {@code keywordRank}。
     */
    @PostMapping("/search")
    public ApiResponse<List<SearchResult>> search(@RequestBody SearchRequest request) {
        List<SearchResult> results = retrievalService.search(
                request.query(), request.topKOrDefault());
        log.info("检索: query={}, topK={}, 命中 {} 条",
                request.query(), request.topKOrDefault(), results.size());
        return ApiResponse.ok(results);
    }
}
