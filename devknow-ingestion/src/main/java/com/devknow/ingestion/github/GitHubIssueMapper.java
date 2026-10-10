package com.devknow.ingestion.github;

import com.devknow.common.util.HashUtils;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.entity.Document;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public GitHubIssueMapper(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /**
     * 写入一条 issue 或 PR。
     *
     * @return true 表示内容有变化（新增或更新），false 表示内容未变已跳过
     */
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
