package com.devknow.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 配置。
 *
 * <pre>
 * devknow:
 *   github:
 *     token: ${GITHUB_TOKEN}      # 绝不硬编码，走环境变量
 *     base-url: https://api.github.com
 *     page-size: 100              # GitHub 上限就是 100，别传更大
 * </pre>
 *
 * <p>无 token 时限流是 60 次/小时，本项目至少需要拉几十页，**必须配 token**。
 * 用 classic token 并勾选 {@code repo} scope（最小够用范围）。
 *
 * <p><b>为什么不用 fine-grained token</b>：实测无法为第三方公开仓库授权 ——
 * 其 {@code Only select repositories} 白名单只能选自己有写权限的仓库，
 * 而选 {@code Public repositories} 时 GitHub 不渲染 Repository permissions 区块，
 * 导致无法授予 Issues 权限。采集他人仓库的 issue 只有 classic token 走得通。
 */
@Data
@ConfigurationProperties(prefix = "devknow.github")
public class GitHubProperties {

    private String token;

    private String baseUrl = "https://api.github.com";

    /** 每页条数，GitHub 硬上限 100 */
    private int pageSize = 100;

    /** 请求超时（秒） */
    private int timeoutSeconds = 30;

    /** 剩余配额低于此值就等到窗口重置，避免"跑到一半被限流" */
    private int remainingThreshold = 50;

    /**
     * 首次采集的时间范围（天）。
     *
     * <p>仅对"首次采集"生效（{@code knowledge_source.last_synced_at} 为空时）；
     * 之后的增量同步一律以上次同步时间为准。
     *
     * <p>设为 {@code 0} 或负数表示不限制，会尝试拉取仓库全部历史
     * —— 对成熟仓库意味着数万条数据、翻几百页、耗时一两小时，一般不需要。
     */
    private int sinceDays = 365;

    private String userAgent = "devknow-ingestion";

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
