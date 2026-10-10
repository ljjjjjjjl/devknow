package com.devknow.ingestion.git;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Git 仓库操作封装（基于 JGit，无需本机安装 git 命令行）。
 *
 * <p>只做浅克隆：{@code --depth 1}。本项目需要的是"当前版本的文档内容"，
 * 完整历史会让克隆体积和时间翻数倍，而我们只需要一个 commit hash 作为增量锚点。
 *
 * <p>多版本的处理放在第 2 周：届时复用同一份仓库做 {@code git checkout <tag>}，
 * 而不是每个版本克隆一份（磁盘与时间都会乘以版本数）。
 */
@Slf4j
@Component
public class GitRepoCloner {

    /**
     * 浅克隆仓库到目标目录。目标目录已存在时先清理，保证结果可预期。
     *
     * @param repoUrl  仓库地址
     * @param branch   分支名
     * @param target   目标目录
     * @param timeoutSeconds 超时（秒）
     * @return 克隆后 HEAD 的 commit hash，作为下次增量同步的锚点
     */
    public String shallowClone(String repoUrl, String branch, Path target, int timeoutSeconds) {
        // 必须先确认目录不存在。JGit 对"非空目录"会直接抛
        // Destination path already exists and is not an empty directory，
        // 所以清理失败必须在这里就暴露成明确的错误，而不是伪装成克隆失败。
        try {
            FileUtils.deleteRecursively(target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "无法清理已存在的仓库目录，请手动删除后重试: " + target, e);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "无法创建仓库目录: " + target.getParent(), e);
        }

        log.info("开始浅克隆: {} (branch={}) -> {}", repoUrl, branch, target);
        long start = System.currentTimeMillis();
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(target.toFile())
                .setBranch(branch)
                .setDepth(1)
                .setCloneAllBranches(false)
                .setTimeout(timeoutSeconds)
                .call()) {

            String commit = resolveHeadCommit(git, target);
            log.info("克隆完成: {} 耗时 {} ms, HEAD={}",
                    repoUrl, System.currentTimeMillis() - start, commit);
            return commit;
        } catch (GitAPIException e) {
            // 失败时清理半成品，否则下次重试会因"目录非空"再次失败 —— 一个失败会连锁成永久失败
            FileUtils.deleteQuietly(target);
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "克隆失败: " + repoUrl + " (" + e.getMessage() + ")", e);
        }
    }

    private String resolveHeadCommit(Git git, Path target) {
        try {
            // 浅克隆下也能解析 HEAD，得到用于增量比对的 commit hash
            return git.getRepository().resolve("HEAD").getName();
        } catch (IOException e) {
            log.warn("无法解析 HEAD commit: {}", target);
            return null;
        }
    }

}
