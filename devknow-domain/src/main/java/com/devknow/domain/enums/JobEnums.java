package com.devknow.domain.enums;

import lombok.Getter;

/**
 * 采集任务类型与状态。
 */
public final class JobEnums {

    private JobEnums() {
    }

    @Getter
    public enum JobType {
        /** 通过 GitHub API 同步 issues / PR */
        SYNC_ISSUES("SYNC_ISSUES"),
        /** 通过 git clone 同步仓库文档 */
        SYNC_DOCS("SYNC_DOCS"),
        /** 文档结构化解析（第2周） */
        PARSE("PARSE"),
        /** 按标题层级切分（第2周） */
        CHUNK("CHUNK"),
        /** 向量化写入 pgvector（第2周） */
        EMBED("EMBED");

        private final String value;

        JobType(String value) {
            this.value = value;
        }
    }

    /**
     * 任务状态机：
     * <pre>
     *   PENDING ──→ RUNNING ──→ SUCCESS
     *                  │
     *                  ├──→ FAILED ──(retryCount &lt; maxRetry)──→ PENDING
     *                  │
     *                  └──→ DEAD  （超过 maxRetry，需人工介入）
     * </pre>
     * {@code DEAD} 即死信状态 —— 必须存在，否则失败任务会无限重试刷爆日志。
     */
    @Getter
    public enum JobStatus {
        PENDING("PENDING"),
        RUNNING("RUNNING"),
        SUCCESS("SUCCESS"),
        FAILED("FAILED"),
        DEAD("DEAD");

        private final String value;

        JobStatus(String value) {
            this.value = value;
        }
    }
}
