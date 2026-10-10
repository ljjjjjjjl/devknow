package com.devknow.ingestion.git;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.FileUtils;
import com.devknow.ingestion.config.DevknowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 仓库获取的统一入口：<b>优先 git 浅克隆，失败自动回退 tarball 下载</b>。
 *
 * <p><b>为什么要两种方式</b>：国内网络下 JGit 的 git 协议连接经常失败
 * （{@code connection failed}），而 GitHub 的 tarball 域名
 * {@code codeload.github.com} 通常可达。本项目只需要"某版本的文档文件"，
 * 不需要 git 历史 —— 所以 tarball 是功能等价、体积更小（实测 6 MB vs 35 MB）
 * 的可靠替代。
 *
 * <p>保留 git 优先的原因是：网络正常时 git 能顺带拿到精确的 commit hash
 * 用于增量同步，且支持切换任意 tag/分支。回退方案则牺牲一点精确性换可达性。
 *
 * <p>回退是<b>自动</b>的：调用方不需要知道用的哪种方式，也不需要处理"该用哪个"的判断。
 */
@Slf4j
@Component
public class RepoFetcher {

    private final GitRepoCloner gitCloner;
    private final TarballDownloader tarballDownloader;
    private final DevknowProperties properties;

    public RepoFetcher(GitRepoCloner gitCloner,
                       TarballDownloader tarballDownloader,
                       DevknowProperties properties) {
        this.gitCloner = gitCloner;
        this.tarballDownloader = tarballDownloader;
        this.properties = properties;
    }

    /**
     * 拉取仓库工作区到 {@code target}。
     *
     * @return 版本锚点（commit hash，回退时为分支名）
     */
    public String fetch(String owner, String repo, String repoUrl, String branch, Path target) {
        String resolvedBranch = (branch == null || branch.isBlank()) ? "main" : branch;

        try {
            return gitCloner.shallowClone(repoUrl, resolvedBranch, target,
                    properties.getData().getCloneTimeoutSeconds());
        } catch (BizException e) {
            log.warn("git 克隆失败，回退到 tarball 下载。原因: {}", e.getMessage());
            // 克隆失败可能留下半成品目录，回退前必须清干净，否则解压会与残留文件混杂
            FileUtils.deleteQuietly(target);
        }

        try {
            return tarballDownloader.downloadAndExtract(owner, repo, resolvedBranch, target);
        } catch (BizException e) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "git 克隆与 tarball 下载均失败，请检查网络能否访问 "
                            + "github.com 或 codeload.github.com。最后错误: " + e.getMessage(), e);
        }
    }
}
