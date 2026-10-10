package com.devknow.ingestion.git;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.FileUtils;
import com.devknow.ingestion.config.GitHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * git 克隆的网络回退方案：下载并解压 GitHub 的源码 tarball。
 *
 * <p><b>为什么需要它</b>：JGit 走 git 智能 HTTP 协议，在国内网络下经常
 * {@code connection failed}；而 {@code codeload.github.com}（tarball 域名）
 * 通常可达且稳定。实测 spring-boot 仓库：
 * <ul>
 *   <li>git 浅克隆：约 35 MB（含 {@code .git}），经常超时或断连</li>
 *   <li>tarball：约 6 MB，4 秒左右下完</li>
 * </ul>
 * tarball 不含 git 历史与 {@code .git} 目录 —— 而本项目只需要"某版本的文档文件"，
 * 不需要历史，所以这个取舍完全成立。
 *
 * <p>唯一缺少的是 commit hash（用于增量锚点），改从 GitHub API 获取；
 * API 不可用时退化为用分支名作为锚点，不影响功能。
 */
@Slf4j
@Component
public class TarballDownloader {

    /** 单次下载上限，防止异常大的仓库把磁盘塞满 */
    private static final long MAX_BYTES = 1024L * 1024 * 1024;   // 1 GB

    private final GitHubProperties properties;
    private final HttpClient httpClient;

    public TarballDownloader(GitHubProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 下载并解压仓库的默认分支源码。
     *
     * @param owner  仓库属主
     * @param repo   仓库名
     * @param branch 分支名
     * @param target 目标目录（解压后的工作区，不含 .git）
     * @return 该分支当前的 commit hash；无法获取时返回分支名
     */
    public String downloadAndExtract(String owner, String repo, String branch, Path target) {
        String url = "https://codeload.github.com/" + owner + "/" + repo
                + "/tar.gz/refs/heads/" + branch;

        FileUtils.deleteQuietly(target);
        Path archive = null;
        try {
            Files.createDirectories(target.getParent());

            log.info("使用 tarball 回退方案下载: {}", url);
            long start = System.currentTimeMillis();
            archive = download(url);
            FileUtils.createDirectories(target);
            int files = extract(archive, target);
            long cost = System.currentTimeMillis() - start;

            log.info("tarball 下载解压完成: 共 {} 个文件, 耗时 {} ms ({} MB)",
                    files, cost, Math.round(Files.size(archive) / 1024.0 / 1024.0));

            String commit = resolveCommit(owner, repo, branch);
            log.info("版本锚点: {}", commit);
            return commit;
        } catch (IOException e) {
            FileUtils.deleteQuietly(target);
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "tarball 下载失败: " + url + " (" + e.getMessage() + ")", e);
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                    // 临时压缩包删不掉不影响主流程
                }
            }
        }
    }

    private Path download(String url) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", properties.getUserAgent())
                .GET();
        if (properties.hasToken()) {
            // 私有仓库需要凭据；公开仓库带上也无害
            builder.header("Authorization", "Bearer " + properties.getToken());
        }

        Path temp = Files.createTempFile("devknow-repo-", ".tar.gz");
        HttpResponse<Path> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofFile(temp));
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉中断会让上层无法感知取消请求
            Thread.currentThread().interrupt();
            Files.deleteIfExists(temp);
            throw new IOException("下载被中断", e);
        }
        if (response.statusCode() >= 400) {
            Files.deleteIfExists(temp);
            throw new IOException("HTTP " + response.statusCode());
        }
        long size = Files.size(temp);
        if (size > MAX_BYTES) {
            Files.deleteIfExists(temp);
            throw new IOException("压缩包超过上限 " + MAX_BYTES + " 字节: " + size);
        }
        log.debug("tarball 已下载到 {} ({} 字节)", temp, size);
        return temp;
    }

    /**
     * 解压 tar.gz，剥掉 codeload 包的顶层目录。
     *
     * <p>codeload 生成的 tarball 内部路径形如
     * {@code spring-boot-main/documentation/...}，顶层多一层"仓库名-分支"。
     * 剥掉它，工作区结构与真实仓库一致，后续按相对路径匹配 glob 才不会错位。
     *
     * @return 解压出的文件数
     */
    private int extract(Path archive, Path target) throws IOException {
        int count = 0;
        try (InputStream fin = Files.newInputStream(archive);
             BufferedInputStream bin = new BufferedInputStream(fin);
             GzipCompressorInputStream gzin = new GzipCompressorInputStream(bin);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzin)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = stripTopLevel(entry.getName());
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Path resolved = target.resolve(name).normalize();

                // 防目录穿越：压缩包里的 ../ 可能把文件写到工作区之外
                if (!resolved.startsWith(target)) {
                    log.warn("跳过越界路径: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(tar, resolved, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        }
        return count;
    }

    /** 去掉 {@code repo-branch/} 这一层前缀。 */
    private static String stripTopLevel(String entryName) {
        if (entryName == null) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    /**
     * 通过 GitHub API 取分支当前的 commit hash，作为增量同步锚点。
     *
     * <p>取不到不是致命错误 —— 退化为分支名，功能照常，只是增量判断不够精确。
     */
    private String resolveCommit(String owner, String repo, String branch) {
        String url = properties.getBaseUrl() + "/repos/" + owner + "/" + repo + "/branches/" + branch;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", properties.getUserAgent())
                    .GET();
            if (properties.hasToken()) {
                builder.header("Authorization", "Bearer " + properties.getToken());
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("获取 commit hash 失败 (HTTP {})，退化为分支名", response.statusCode());
                return branch;
            }
            // 只取 "sha" 字段，避免为此引入完整 JSON 解析
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"").matcher(response.body());
            return m.find() ? m.group(1) : branch;
        } catch (Exception e) {
            log.warn("获取 commit hash 异常（{}），退化为分支名", e.getMessage());
            return branch;
        }
    }
}
