package com.devknow.domain.dto;

import com.devknow.domain.entity.KnowledgeSource;

import java.time.Instant;

/**
 * 知识源视图对象。
 */
public record SourceVO(
        Long id,
        String name,
        String type,
        String repoUrl,
        String repoOwner,
        String repoName,
        String branch,
        String docPathPattern,
        String license,
        String lastSyncedCommit,
        Instant lastSyncedAt,
        Boolean enabled,
        Instant createdAt
) {
    public static SourceVO from(KnowledgeSource s) {
        return new SourceVO(
                s.getId(), s.getName(), s.getType(), s.getRepoUrl(),
                s.getRepoOwner(), s.getRepoName(), s.getBranch(),
                s.getDocPathPattern(), s.getLicense(), s.getLastSyncedCommit(),
                s.getLastSyncedAt(), s.getEnabled(), s.getCreatedAt());
    }
}
