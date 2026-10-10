package com.devknow.ingestion.git;

import java.util.Map;

/**
 * 从仓库中抽取出的一个文档文件。
 *
 * @param relativePath 仓库内相对路径（用 {@code /} 分隔）
 * @param content      解析后的文本内容
 * @param title        文档标题（取自一级标题或文件名）
 * @param breadcrumb   标题路径，用于写入 chunk 的 heading_path
 * @param metadata     附加信息（文件大小、行数等）
 */
public record RepoDoc(
        String relativePath,
        String content,
        String title,
        String breadcrumb,
        Map<String, Object> metadata
) {
}
