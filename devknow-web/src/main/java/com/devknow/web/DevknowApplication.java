package com.devknow.web;

import com.devknow.ingestion.config.DevknowProperties;
import com.devknow.ingestion.config.GitHubProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 研发知识智能体平台启动类。
 *
 * <p>启动前请确认：
 * <ol>
 *   <li>{@code docker compose up -d} 已启动 PostgreSQL 与 Redis</li>
 *   <li>环境变量 {@code GITHUB_TOKEN} 已设置（不设也能启动，但采集会被限流）</li>
 * </ol>
 *
 * <p>访问 {@code /api/health} 验证服务状态，{@code /api/admin/stats} 查看入库统计。
 */
@SpringBootApplication(scanBasePackages = "com.devknow")
@MapperScan("com.devknow.domain.mapper")
@EnableScheduling
@EnableConfigurationProperties({DevknowProperties.class, GitHubProperties.class})
public class DevknowApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevknowApplication.class, args);
    }
}
