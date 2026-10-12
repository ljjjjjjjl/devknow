package com.devknow.ingestion.github;

import com.devknow.common.util.HashUtils;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.entity.Document;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.mapper.DocumentMapper;
import com.devknow.ingestion.index.ChunkBackfillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 GitHub issue / PR 映射为 {@link Document} 并幂等落库。
 *
 * <p>映射设计的几个关键取舍：
 * <ul>
 *   <li><b>version 取 milestone 标题</b> —— GitHub 上习惯把 milestone 命名为 3.2.x，
 *       正好可以作为版本过滤的依据</li>
 *   <li><b>不拉取评论</b> —— 每个 issue 单独请求评论会消耗数千次 API 调用。
 *       {@code comments} 字段只记录数量，正文留在原站，需要时靠 url 回溯。
 *       第 2 周若确需评论，可对高价值 issue 做按需补充</li>
 *   <li><b>state_reason 存入 metadata</b> —— {@code not_planned} 的 issue 往往没有有效结论，
 *       第 2 周切分时可据此降权或过滤</li>
 * </ul>
 */
@Slf4j
@Component
public class GitHubIssueMapper {

    /** 单条正文上限，防止超大 issue 撑爆单行 INSERT */
    private static final int MAX_CONTENT_CHARS = 100_000;

    private final DocumentMapper documentMapper;
    private final ChunkBackfillService chunkBackfillService;

    public GitHubIssueMapper(DocumentMapper documentMapper,
                             ChunkBackfillService chunkBackfillService) {
        this.documentMapper = documentMapper;
        this.chunkBackfillService = chunkBackfillService;
    }

    /**
     * 写入一条 issue 或 PR，并<b>立即切分</b>。
     *
     * <p><b>为什么必须加 {@code @Transactional}</b>：upsert 与切分必须同生共死。
     * 如果它们分属两个事务，切分失败时 {@code document} 已经落库，而下一次同步会因为
     * {@code contentHash} 未变被 {@link DocumentMapper#existsUnchanged} 短路直接 return ——
     * <b>这篇文档的 chunk 永远不会被创建，成为永久孤儿</b>。
     * 加了事务之后，切分失败会连同 document 一起回滚，下次同步会重新处理它。
     * 这与第 1 周"文档入库正常、chunk 却始终为 0"是同一类 bug，只是触发面更窄。
     *
     * @return true 表示内容有变化（新增或更新），false 表示内容未变已跳过
     */
    @Transactional
    public boolean save(KnowledgeSource source, GitHubClient.GitHubIssue issue) {
        String docType = issue.isPullRequest() ? DocType.PR.getValue() : DocType.ISSUE.getValue();
        String externalId = String.valueOf(issue.getNumber());
        String content = truncate(issue.getBody(), MAX_CONTENT_CHARS);
        String contentHash = HashUtils.sha256Hex(issue.getTitle(), content);

        // 内容未变则跳过：增量同步时能省下绝大部分写操作与后续切分、向量化成本
        if (documentMapper.existsUnchanged(source.getId(), docType, externalId, contentHash) > 0) {
            return false;
        }

        Document document = new Document();
        document.setSourceId(source.getId());
        document.setDocType(docType);
        document.setExternalId(externalId);
        document.setUrl(issue.getHtmlUrl());
        document.setTitle(issue.getTitle());
        document.setVersion(issue.milestoneTitle());
        document.setVersionOrder(null);
        document.setPath(null);
        document.setRawContent(content);
        document.setContentHash(contentHash);
        document.setLang("en");
        document.setPublishedAt(issue.getCreatedAt());
        document.setStatus("ACTIVE");
        document.setMetadata(JsonUtils.toJson(buildMetadata(issue)));

        documentMapper.upsert(document);

        Long documentId = documentMapper.selectIdByNaturalKey(source.getId(), docType, externalId);
        if (documentId == null) {
            log.warn("文档写入后未取到 id，跳过切分: docType={}, externalId={}", docType, externalId);
            return false;
        }

        document.setId(documentId);
        int chunks = chunkBackfillService.chunkDocument(document);
        log.debug("issue 已切分: docType={}, externalId={}, chunk 数={}", docType, externalId, chunks);
        return true;
    }

    private Map<String, Object> buildMetadata(GitHubClient.GitHubIssue issue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("is_pr", issue.isPullRequest());
        metadata.put("number", issue.getNumber());
        metadata.put("state", issue.getState());
        metadata.put("state_reason", issue.getStateReason());
        metadata.put("labels", issue.labelNames());
        metadata.put("comment_count", issue.getCommentCount());
        metadata.put("updated_at", toIso(issue.getUpdatedAt()));
        metadata.put("closed_at", toIso(issue.getClosedAt()));
        metadata.put("milestone", issue.milestoneTitle());
        return metadata;
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供测试与调试使用的标签摘要。 */
    public static String describe(GitHubClient.GitHubIssue issue, List<String> labels) {
        return "#" + issue.getNumber() + " " + issue.getTitle() + " " + labels;
    }
}
