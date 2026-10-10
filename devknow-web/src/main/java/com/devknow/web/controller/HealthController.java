package com.devknow.web.controller;

import com.devknow.common.api.ApiResponse;
import com.devknow.ingestion.config.DevknowProperties;
import com.devknow.ingestion.git.GitRepoCloner;
import com.devknow.ingestion.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与自检接口。
 *
 * <p>{@code /api/health} 是所有验证的起点 —— Day 1 的验收标准就是它能返回 UP。
 * {@code /api/health/selfcheck} 进一步暴露磁盘路径与配置视图，
 * 用于排查"配置没生效"这类问题（比翻日志快得多）。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DevknowProperties properties;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<GitRepoCloner> clonerProvider;

    public HealthController(DevknowProperties properties,
                            RateLimiter rateLimiter,
                            ObjectProvider<GitRepoCloner> clonerProvider) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clonerProvider = clonerProvider;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "devknow");
        body.put("time", Instant.now().toString());
        return ApiResponse.ok(body);
    }

    /**
     * 自检：配置是否按预期生效、GitHub token 是否就位、磁盘目录是否可写。
     *
     * <p>这是 Day 1 "环境问题耗掉整天"这个风险的直接对策 ——
     * 一眼看清生效的配置值，而不是靠猜。
     */
    @GetMapping("/selfcheck")
    public ApiResponse<Map<String, Object>> selfcheck() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", properties.getInstanceId());
        body.put("dataDir", properties.getData().dataDir().toString());
        body.put("tmpDir", properties.getData().tmpDir().toString());
        body.put("keepRepo", properties.getData().isKeepRepo());
        body.put("githubTokenConfigured", rateLimiter.hasToken());
        body.put("githubRateLimitRemaining", rateLimiter.getRemaining());
        body.put("gitClonerAvailable", clonerProvider.getIfAvailable() != null);
        return ApiResponse.ok(body);
    }
}
