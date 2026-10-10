package com.devknow.common.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件系统工具。
 *
 * <p>集中处理三件事：读文本、递归删除、递归列出。
 * 尤其是递归删除 —— 一个写错的删除逻辑会毁掉用户数据，所以统一在这里实现并复用于
 * {@code data/tmp/{jobId}} 与 {@code data/repos/...} 的清理。
 */
public final class FileUtils {

    private FileUtils() {
    }

    public static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }

    public static void writeString(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败: " + file, e);
        }
    }

    public static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建目录失败: " + dir, e);
        }
    }

    /** 递归列出目录下所有匹配后缀的文件。目录不存在时返回空流。 */
    public static Stream<Path> listFiles(Path root, String suffix) {
        if (root == null || !Files.isDirectory(root)) {
            return Stream.empty();
        }
        try {
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> suffix == null || p.getFileName().toString().endsWith(suffix));
        } catch (IOException e) {
            throw new UncheckedIOException("遍历目录失败: " + root, e);
        }
    }

    /**
     * 递归删除目录或文件。删除失败会抛异常，<b>不静默吞掉</b>。
     *
     * <p><b>为什么失败必须抛出</b>：本方法用于清理 git 仓库工作区。
     * 若删除失败却静默返回，调用方会以为目录已清理，下次克隆时 JGit 报
     * {@code Destination path already exists and is not an empty directory}
     * —— 一个失败的清理会伪装成"克隆失败"，排查方向完全跑偏（实测踩过）。
     *
     * <p>用 {@code reverseOrder()} 保证先删子项再删父目录，顺序错了会因"目录非空"而失败。
     * Windows 上文件常被短时占用（杀毒扫描、索引服务），因此带重试。
     */
    public static void deleteRecursively(Path path) throws IOException {
        deleteRecursively(path, 3);
    }

    public static void deleteRecursively(Path path, int maxAttempts) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
                return;   // 删除成功
            } catch (IOException | UncheckedIOException e) {
                last = (e instanceof UncheckedIOException uio) ? uio.getCause() : (IOException) e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("清理目录被中断: " + path, ie);
                    }
                }
            }
        }
        throw new IOException("删除目录失败（已重试 " + maxAttempts + " 次）: " + path, last);
    }

    /** 尽力删除，不抛异常。仅用于"清理失败也无所谓"的场景。 */
    public static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            // 静默：调用方明确表示不关心结果
        }
    }

    /** 目录大小（字节），用于日志与容量告警。 */
    public static long sizeOf(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
