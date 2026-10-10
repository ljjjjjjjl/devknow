package com.devknow.ingestion.ratelimit;

import com.devknow.ingestion.config.GitHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * GitHub API 限流器（V1 单机实现）。
 *
 * <p>策略是"以服务端返回的配额为准，而非本地计数"：
 * 每次响应都读取 {@code X-RateLimit-Remaining} 与 {@code X-RateLimit-Reset}，
 * 剩余量低于阈值时直接 sleep 到窗口重置。
 *
 * <p>为什么不用本地令牌桶：本地计数与 GitHub 的实际配额必然有偏差
 * （其他进程/工具也在用同一个 token），以服务端为准才不会误判。
 *
 * <p>第 3 天可替换为 Redis 版本以支持多实例共享配额 —— 届时本类保持不变，
 * 只把 {@link #updateFromHeaders} / {@link #acquire()} 的实现换成 Redis + Lua。
 */
@Slf4j
@Component
public class RateLimiter {

    private final GitHubProperties properties;

    private volatile int remaining = Integer.MAX_VALUE;
    private volatile Instant resetAt = Instant.EPOCH;

    public RateLimiter(GitHubProperties properties) {
        this.properties = properties;
    }

    /** 每次 HTTP 响应后调用，刷新配额视图。 */
    public void updateFromHeaders(String remainingHeader, String resetHeader) {
        try {
            if (remainingHeader != null) {
                this.remaining = Integer.parseInt(remainingHeader.trim());
            }
            if (resetHeader != null) {
                this.resetAt = Instant.ofEpochSecond(Long.parseLong(resetHeader.trim()));
            }
        } catch (NumberFormatException e) {
            // 头部格式异常不应中断采集，保持上一次的视图即可
            log.debug("限流响应头解析失败: remaining={}, reset={}", remainingHeader, resetHeader);
        }
    }

    /**
     * 请求前的等待闸门。剩余配额不足时阻塞到窗口重置。
     *
     * @throws InterruptedException 线程被中断时抛出，由调用方决定是否终止任务
     */
    public void acquire() throws InterruptedException {
        if (remaining > properties.getRemainingThreshold()) {
            return;
        }
        long waitSeconds = Duration.between(Instant.now(), resetAt).toSeconds();
        if (waitSeconds <= 0) {
            // 窗口已重置，给下一次响应一个探测机会
            return;
        }
        log.warn("GitHub 配额不足 (remaining={}, threshold={})，等待 {} 秒至窗口重置",
                remaining, properties.getRemainingThreshold(), waitSeconds);
        Thread.sleep(Duration.ofSeconds(waitSeconds).toMillis());
        remaining = Integer.MAX_VALUE;
    }

    public int getRemaining() {
        return remaining;
    }

    public Instant getResetAt() {
        return resetAt;
    }

    public boolean hasToken() {
        return properties.hasToken();
    }
}
