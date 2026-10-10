package com.devknow.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 项目自定义配置，前缀 {@code devknow}。
 *
 * <pre>
 * devknow:
 *   data:
 *     dir: ./data
 *     repo-dir: ${devknow.data.dir}/repos
 *     tmp-dir: ${devknow.data.dir}/tmp
 *     keep-repo: false
 *   instance-id: worker-1
 * </pre>
 *
 * <p><b>所有磁盘路径必须走这里</b>，代码中不允许出现硬编码的 {@code "./data"} 或绝对路径 —
 * 否则容器化部署时必然出问题。
 */
@Data
@ConfigurationProperties(prefix = "devknow")
public class DevknowProperties {

    private DataConfig data = new DataConfig();

    /** Worker 实例标识，写入 {@code ingestion_job.locked_by}，便于排查"谁卡住了任务" */
    private String instanceId = "worker-local";

    /**
     * 数据目录配置。
     *
     * <p><b>为什么类名是 DataConfig 而不是 Data</b>：本类使用了 Lombok 的 {@code @Data}。
     * 如果内部类也叫 {@code Data}，那么在 DevknowProperties 的作用域内，
     * {@code @Data} 会优先解析到内部类而不是 {@code lombok.Data}，
     * 于是 Lombok 注解全部失效（报"找不到 getter"）。
     *
     * <p>更麻烦的是：这个错误会让 javac 处于错误状态，导致<b>同一模块内所有</b>
     * 使用 Lombok 的类都报"找不到符号"，看起来像是 Lombok 坏了，实际只错了一处。
     * 结论：内部类命名避开 Lombok 注解名（Data / Builder / Value / Getter 等）。
     */
    @Data
    public static class DataConfig {

        /** 数据根目录 */
        private String dir = "./data";

        /** 临时文件目录，按 jobId 隔离 */
        private String tmpDir;

        /** 是否在解析完成后保留仓库（第2周做增量时改 true） */
        private boolean keepRepo = false;

        /** 单个仓库体积上限（MB），防止误 clone 超大仓库把磁盘塞满 */
        private long maxRepoSizeMb = 2048;

        /** git clone 超时（秒） */
        private int cloneTimeoutSeconds = 300;

        public Path dataDir() {
            return Path.of(dir).toAbsolutePath().normalize();
        }

        public Path tmpDir() {
            return tmpDir != null && !tmpDir.isBlank()
                    ? Path.of(tmpDir).toAbsolutePath().normalize()
                    : dataDir().resolve("tmp");
        }

        public Path repoDir(String owner, String repo) {
            return dataDir().resolve("repos").resolve(owner).resolve(repo);
        }
    }
}
