package com.devknow.ingestion.parser;

import com.devknow.common.util.PathUtils;
import com.devknow.ingestion.git.RepoDoc;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markdown 解析器。
 *
 * <p>处理内容：
 * <ul>
 *   <li>剥离 YAML front-matter（{@code --- ... ---}），元数据单独保留</li>
 *   <li>提取第一个一级标题作为文档标题</li>
 *   <li>生成 breadcrumb（基于文件路径），供 chunk 的 heading_path 使用</li>
 * </ul>
 */
@Component
public class MarkdownParser implements DocParser {

    @Override
    public boolean supports(String extension) {
        return ".md".equalsIgnoreCase(extension) || ".markdown".equalsIgnoreCase(extension);
    }

    @Override
    public RepoDoc parse(String relativePath, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        String content = rawContent;

        Map<String, Object> metadata = new LinkedHashMap<>();
        String frontMatter = extractFrontMatter(content);
        if (frontMatter != null) {
            metadata.put("front_matter", frontMatter);
            content = stripFrontMatter(content);
        }

        String title = extractTitle(content, relativePath);
        String breadcrumb = PathUtils.toBreadcrumb(relativePath);

        metadata.put("source_path", relativePath);
        metadata.put("line_count", content.lines().count());
        metadata.put("format", "markdown");

        return new RepoDoc(relativePath, content.strip(), title, breadcrumb, metadata);
    }

    /** 提取 {@code ---} 包裹的 front-matter 原文。 */
    static String extractFrontMatter(String content) {
        if (!content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        return content.substring(3, end).strip();
    }

    static String stripFrontMatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return content;
        }
        return content.substring(end + 4);
    }

    /** 取第一个 ATX 一级标题；没有则用文件名。 */
    static String extractTitle(String content, String relativePath) {
        for (String line : content.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).strip();
            }
        }
        String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
