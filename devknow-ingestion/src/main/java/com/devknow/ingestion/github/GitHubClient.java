package com.devknow.ingestion.github;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.ingestion.config.GitHubProperties;
import com.devknow.ingestion.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST API 客户端。
 *
 * <p>为什么用 JDK 自带的 {@link HttpClient} 而不是 RestClient/WebClient：
 * <ul>
 *   <li>需要直接读取响应头（{@code Link} / {@code X-RateLimit-*}），HttpClient 暴露最直接</li>
 *   <li>不引入额外抽象层 — 采集场景不需要声明式客户端</li>
 *   <li>阻塞式调用 + 虚拟线程即可，无需响应式栈</li>
 * </ul>
 *
 * <p>分页严格遵循 RFC 5988 的 {@code Link} 响应头，不手拼页码 —
 * 手拼页码会在数据变动时漏条或重复。
 */
@Slf4j
@Component
public class GitHubClient {

    private static final Pattern LINK_NEXT =
            Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private final GitHubProperties properties;
    private final RateLimiter rateLimiter;

    /**
     * 专用 ObjectMapper。
     *
     * <p><b>必须注册 JSR-310 模块</b>：GitHub 返回的时间字段是 ISO-8601 字符串
     * （如 {@code "created_at": "2024-05-01T10:20:30Z"}），实体里对应 {@code Instant}。
     * 裸的 {@code new ObjectMapper()} 没有日期时间模块，反序列化时会抛：
     * <pre>
     * Java 8 date/time type `java.time.Instant` not supported by default:
     * add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
     * </pre>
     *
     * <p>这里用 {@code findAndRegisterModules()} 自动发现 classpath 上的模块
     * （Spring Boot 已传递引入 jsr310），比手工 {@code registerModule(new JavaTimeModule())}
     * 更省事，也不会因为将来新增模块而漏配。
     *
     * <p>注意：不走 Spring 容器里的 ObjectMapper 是刻意的 —— 采集端只关心
     * GitHub 的响应格式，不应受 application.yml 里全局 Jackson 配置（命名策略、
     * null 处理等）的影响。
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient httpClient;

    public GitHubClient(GitHubProperties properties, RateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 快速失败：如果 JSR-310 模块不可用，采集跑到一半才会报错，代价很高。
        // 在这里提前暴露，启动日志里一眼可见。
        if (!mapper.canSerialize(Instant.class)) {
            log.error("ObjectMapper 无法处理 Instant —— 缺少 jackson-datatype-jsr310，"
                    + "采集会在解析 GitHub 响应时失败。请检查依赖是否完整。");
        }
    }

    /**
     * 拉取仓库的 issues（<b>结果包含 PR</b>，由调用方按 {@code pull_request} 字段区分）。
     *
     * @param owner     仓库属主
     * @param repo      仓库名
     * @param state     open / closed / all
     * @param since     只取此时间之后更新的（增量同步用），可为 null
     * @param page     页码，从 1 开始
     * @param sort      排序字段：created / updated / comments
     * @param direction asc / desc
     */
    /**
     * 首次拉取仓库 issues（<b>结果包含 PR</b>，由调用方按 {@code pull_request} 字段区分）。
     *
     * <p><b>不传 {@code page} 参数</b>：GitHub 对 issue 数量大的仓库禁用了页码分页，
     * 传 {@code page=2} 会直接返回 422。首页不带 {@code page}，后续页面一律跟随
     * {@code Link} 头里的 {@code next} URL（内含 {@code after} 游标）。
     *
     * @param owner     仓库属主
     * @param repo      仓库名
     * @param state     open / closed / all
     * @param since     只取此时间之后<b>更新</b>的（注意 GitHub 的 since 是按更新时间，不是创建时间）
     * @param sort      排序字段：created / updated / comments
     * @param direction asc / desc
     */
    public PageResponse<GitHubIssue> fetchIssues(String owner, String repo, String state,
                                                 Instant since, String sort, String direction) {
        StringBuilder url = new StringBuilder(properties.getBaseUrl())
                .append("/repos/").append(owner).append('/').append(repo)
                .append("/issues?state=").append(state)
                .append("&per_page=").append(properties.getPageSize())
                .append("&sort=").append(sort)
                .append("&direction=").append(direction);
        if (since != null) {
            url.append("&since=").append(since);
        }
        return getPaged(url.toString(), GitHubIssue.class, properties.getPageSize());
    }

    /** 按 {@code Link} 头给出的完整 URL 拉取后续页面（游标分页）。 */
    public PageResponse<GitHubIssue> fetchIssuesByUrl(String url) {
        return getPaged(url, GitHubIssue.class, properties.getPageSize());
    }

    /**
     * 通用分页 GET。
     *
     * <p><b>必须显式传 {@code elementType}</b>：泛型在运行时会擦除，
     * {@code new TypeReference<List<T>>() {}} 里的 {@code T} 会退化成 {@code Object}，
     * Jackson 于是反序列化成 {@code List<LinkedHashMap>}，调用方遍历时强转就抛
     * {@code ClassCastException}。传入具体的 {@code Class<T>} 才能真正反序列化。
     *
     * @param url         完整请求地址（首页自己拼，后续页直接用 Link 头里的 next）
     * @param elementType 列表元素的实际类型，如 {@code GitHubIssue.class}
     * @param pageSize    本次请求的每页条数，用于判断"不足一页 = 最后一页"
     */
    public <T> PageResponse<T> getPaged(String url, Class<T> elementType, int pageSize) {
        HttpResponse<String> response = send(url);
        PageResponse<T> page = new PageResponse<>();
        page.setPageSize(pageSize);
        try {
            List<T> items = mapper.readValue(
                    response.body(),
                    mapper.getTypeFactory().constructCollectionType(List.class, elementType));
            page.setItems(items);
        } catch (IOException e) {
            throw new BizException(ErrorCode.GITHUB_API_ERROR, "GitHub 响应解析失败: " + e.getMessage(), e);
        }
        String link = response.headers().firstValue("Link").orElse(null);
        page.setLinkHeader(link);
        page.setNextUrl(parseNextUrl(link));
        return page;
    }

    /** 取当前 token 的配额信息，用于 {@code /api/admin/github/ping}。 */
    public RateLimitInfo fetchRateLimit() {
        HttpResponse<String> response = send(properties.getBaseUrl() + "/rate_limit");
        try {
            Map<String, Object> root = mapper.readValue(response.body(), new TypeReference<>() {
            });
            Map<String, Object> core = asMap(root.get("resources"), "core");
            RateLimitInfo info = new RateLimitInfo();
            info.setLimit(intOf(core.get("limit")));
            info.setRemaining(intOf(core.get("remaining")));
            info.setResetEpochSecond(longOf(core.get("reset")));
            info.setAuthenticated(properties.hasToken());
            return info;
        } catch (Exception e) {
            throw new BizException(ErrorCode.GITHUB_API_ERROR, "配额信息解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Object inner = map.get(key);
            if (inner instanceof Map<?, ?> innerMap) {
                return (Map<String, Object>) innerMap;
            }
        }
        throw new IllegalStateException("GitHub 响应结构异常，缺少字段: " + key);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private HttpResponse<String> send(String url) {
        if (!properties.hasToken()) {
            log.warn("未配置 GITHUB_TOKEN，限流仅 60 次/小时，采集很可能中途失败");
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.GITHUB_RATE_LIMITED, "等待限流配额时被中断", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", properties.getUserAgent())
                .GET();
        if (properties.hasToken()) {
            builder.header("Authorization", "Bearer " + properties.getToken());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BizException(ErrorCode.GITHUB_API_ERROR, "GitHub 请求失败: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.GITHUB_API_ERROR, "GitHub 请求被中断", e);
        }

        // 无论成功失败都刷新配额视图，这样限流判断始终基于最新事实
        rateLimiter.updateFromHeaders(
                response.headers().firstValue("X-RateLimit-Remaining").orElse(null),
                response.headers().firstValue("X-RateLimit-Reset").orElse(null));

        if (response.statusCode() == 403 || response.statusCode() == 429) {
            throw new BizException(ErrorCode.GITHUB_RATE_LIMITED,
                    "GitHub 限流或拒绝访问，HTTP " + response.statusCode()
                            + "，剩余配额 " + rateLimiter.getRemaining());
        }
        if (response.statusCode() >= 400) {
            throw new BizException(ErrorCode.GITHUB_API_ERROR,
                    "GitHub 返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response;
    }

    /**
     * 从 {@code Link} 头解析下一页的<b>完整 URL</b>。
     *
     * <p>示例（游标分页）：{@code <https://api.github.com/...&after=Y3Vyc29yOnYyOpHO...>; rel="next"}
     * <p>示例（页码分页）：{@code <https://api.github.com/...&page=2>; rel="next"}
     *
     * <p>直接返回整个 URL 而不是解析出参数，是因为首页之后的请求可以直接用它，
     * 无需关心 GitHub 用的是 {@code page} 还是 {@code after} 游标 ——
     * 而 issue 多的仓库只支持后者（用 {@code page} 会被 422 拒绝）。
     */
    static String parseNextUrl(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher matcher = LINK_NEXT.matcher(linkHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    /**
     * GitHub issue 响应中我们关心的字段子集。
     *
     * <p>{@code pullRequest} 非空即表示该条目实际是 PR —— GitHub 的 issues 接口
     * 把两者混在一起返回，这是最容易踩的坑之一。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubIssue {

        private Long id;

        private Integer number;

        private String title;

        private String body;

        private String state;

        @JsonProperty("state_reason")
        private String stateReason;

        @JsonProperty("html_url")
        private String htmlUrl;

        @JsonProperty("comments")
        private Integer commentCount;

        @JsonProperty("created_at")
        private Instant createdAt;

        @JsonProperty("updated_at")
        private Instant updatedAt;

        @JsonProperty("closed_at")
        private Instant closedAt;

        @JsonProperty("pull_request")
        private Map<String, Object> pullRequest;

        private List<Label> labels = new ArrayList<>();

        private Milestone milestone;

        /** 是否为 PR */
        public boolean isPullRequest() {
            return pullRequest != null && !pullRequest.isEmpty();
        }

        public List<String> labelNames() {
            List<String> names = new ArrayList<>();
            if (labels != null) {
                for (Label label : labels) {
                    if (label != null && label.getName() != null) {
                        names.add(label.getName());
                    }
                }
            }
            return names;
        }

        /** 用 milestone 标题作为 version（GitHub 上习惯把 milestone 命名为 3.2.x 这类版本号） */
        public String milestoneTitle() {
            return milestone == null ? null : milestone.getTitle();
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Label {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Milestone {
        private String title;
        private String state;
    }

    /** 配额信息视图。 */
    @Data
    public static class RateLimitInfo {
        private Integer limit;
        private Integer remaining;
        private Long resetEpochSecond;
        private boolean authenticated;

        public Instant resetAt() {
            return resetEpochSecond == null ? null : Instant.ofEpochSecond(resetEpochSecond);
        }
    }
}
