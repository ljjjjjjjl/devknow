package com.devknow.domain.dto;

import com.devknow.domain.entity.IngestionJob;

import java.time.Instant;

/**
 * 采集任务视图对象。
 */
public record JobVO(
        Long id,
        Long sourceId,
        String jobType,
        String status,
        String cursor,
        Integer progressDone,
        Integer progressTotal,
        Integer retryCount,
        Integer maxRetry,
        String errorMessage,
        String lockedBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
    public static JobVO from(IngestionJob j) {
        return new JobVO(
                j.getId(), j.getSourceId(), j.getJobType(), j.getStatus(), j.getCursor(),
                j.getProgressDone(), j.getProgressTotal(), j.getRetryCount(), j.getMaxRetry(),
                j.getErrorMessage(), j.getLockedBy(), j.getStartedAt(), j.getFinishedAt(),
                j.getCreatedAt());
    }
}
