package com.devknow.ingestion.github;

import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.ingestion.config.GitHubProperties;
import com.devknow.ingestion.job.IngestionJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 通过 GitHub API 同步 issue / PR。
 *
 * <p>翻页严格依赖 {@code Link} 响应头。任务每页更新一次进度，
 * 并把页码写入 {@code cursor}，进程崩溃后从游标续跑而不是从头开始。
 */
@Slf4j
@Component
public class GitHubIssueSyncer {

    /** 进度落库间隔：每 N 页写一次，避免高频 UPDATE */
    private static final int PROGRESS_FLUSH_PAGES = 5;

    private final GitHubClient client;
    private final GitHubIssueMapper issueMapper;
    private final IngestionJobService jobService;
    private final GitHubProperties properties;

    public GitHubIssueSyncer(GitHubClient client,
                             GitHubIssueMapper issueMapper,
                             IngestionJobService jobService,
                             GitHubProperties properties) {
        this.client = client;
        this.issueMapper = issueMapper;
        this.jobService = jobService;
        this.properties = properties;
    }

    /**
     * 计算采集起点。
     *
     * <p>两种情形：
     * <ul>
     *   <li><b>增量同步</b>（{@code lastSyncedAt} 非空）—— 用上次同步时间，只拉更新的</li>
     *   <li><b>首次采集</b>（{@code lastSyncedAt} 为空）—— 用 {@code since-days} 限制范围</li>
     * </ul>
     *
     * <p><b>为什么首次采集也要限制</b>：像 spring-boot 这种成熟仓库有数万个 issue，
     * 无限制地全量拉取需要翻几百页、耗时一两小时，且绝大部分是多年前的陈旧内容，
     * 对检索质量没有帮助。限制在近 N 年既能拿到足够的真实数据，又能把耗时压到分钟级。
     */
    private Instant resolveSince(KnowledgeSource source) {
        if (source.getLastSyncedAt() != null) {
            return source.getLastSyncedAt();
        }
        int sinceDays = properties.getSinceDays();
        if (sinceDays <= 0) {
            return null;   // 显式配置为 0 或负数表示不限制
        }
        return Instant.now().minus(Duration.ofDays(sinceDays));
    }

    /**
     * 全量 / 增量同步。
     *
     * @param source 知识源
     * @param job    对应任务，用于记录进度与游标
     * @return 本次实际写入（新增或更新）的条数
     */
    public int sync(KnowledgeSource source, IngestionJob job) {
        Instant since = resolveSince(source);
        log.info("本次采集时间范围: {} 之后（{}）",
                since, source.getLastSyncedAt() != null ? "增量：上次同步时间" : "首次采集：受 since-days 限制");

        int page = 1;
        int fetched = 0;
        int written = 0;
        int skipped = 0;

        // 首页自己拼；后续页一律跟随 Link 头里的 next URL（游标分页）。
        // 不能自己递增 page 参数 —— 对 issue 多的仓库 GitHub 会返回 422。
        String nextUrl = null;

        while (true) {
            PageResponse<GitHubClient.GitHubIssue> response = (nextUrl == null)
                    ? client.fetchIssues(source.getRepoOwner(), source.getRepoName(),
                            "all", since, "created", "asc")
                    : client.fetchIssuesByUrl(nextUrl);

            List<GitHubClient.GitHubIssue> items = response.getItems();
            if (items.isEmpty()) {
                break;
            }

            for (GitHubClient.GitHubIssue issue : items) {
                fetched++;
                try {
                    if (issueMapper.save(source, issue)) {
                        written++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // 单条失败不应中断整个采集：一条脏数据不值得重跑 1000 条
                    log.warn("issue #{} 写入失败，跳过: {}", issue.getNumber(), e.getMessage());
                }
            }

            if (page % PROGRESS_FLUSH_PAGES == 0) {
                jobService.updateProgress(job.getId(), response.getNextUrl(), fetched, null);
                log.info("同步进度: source={}, page={}, fetched={}, written={}, skipped={}",
                        source.getName(), page, fetched, written, skipped);
            }

            log.debug("第 {} 页: 返回 {} 条, Link={}", page, items.size(), response.getLinkHeader());

            if (response.isLast()) {
                log.info("采集结束: 第 {} 页返回 {} 条（不足一页或没有 next 链接）", page, items.size());
                break;
            }
            nextUrl = response.getNextUrl();
            jobService.updateProgress(job.getId(), nextUrl, fetched, null);
            page++;
        }

        log.info("同步完成: source={}, 共 {} 页, 拉取 {} 条, 写入 {} 条, 内容未变跳过 {} 条",
                source.getName(), page, fetched, written, skipped);
        return written;
    }
}
