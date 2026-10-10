package com.devknow.ingestion.parser;

import com.devknow.common.util.PathUtils;
import com.devknow.ingestion.git.RepoDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AsciiDoc 解析器 —— Spring 官方文档使用的格式。
 *
 * <p>做的是<b>归一化</b>而不是完整解析：把 AsciiDoc 的核心结构转成 Markdown，
 * 让下游 {@code Chunker} 只需要处理一种语法。
 *
 * <p>转换规则：
 * <pre>
 *   = 文档标题      →  # 文档标题
 *   == 一级节       →  ## 一级节
 *   [source,java]   →  ```java
 *   ----            →  ```
 *   include::x[]    →  移除（第 2 周做文档组装）
 * </pre>
 *
 * <p><b>刻意不做的事</b>：AsciiDoc 的表格、脚注、交叉引用（{@code <<x>>}）、
 * 属性替换（{@code {version}}）暂不处理。第 1 周的目标是"文档能进来、标题层级对"，
 * 这些细节等到真正影响检索效果时再补 —— 否则会在解析器上耗掉一周。
 */
@Slf4j
@Component
public class AsciiDocParser implements DocParser {

    /** {@code [source,java]} 或 {@code [source,java,options="..."]} */
    private static final Pattern SOURCE_ATTR =
            Pattern.compile("^\\[source(?:,\\s*([A-Za-z0-9+#-]+))?.*]$");

    /** {@code include::path[]} */
    private static final Pattern INCLUDE = Pattern.compile("^include::[^\\[]+\\[.*]$");

    /** 其他块属性，如 {@code [NOTE]}、{@code [TIP]}、{@code [cols="1,1"]} */
    private static final Pattern BLOCK_ATTR = Pattern.compile("^\\[[A-Za-z].*]$");

    @Override
    public boolean supports(String extension) {
        return ".adoc".equalsIgnoreCase(extension) || ".asciidoc".equalsIgnoreCase(extension);
    }

    @Override
    public RepoDoc parse(String relativePath, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        // 去掉 AsciiDoc 的版权头注释块，它对检索毫无价值只会干扰 embedding
        String cleaned = stripLeadingCommentBlock(rawContent);

        StringBuilder out = new StringBuilder(cleaned.length());
        boolean inCodeBlock = false;
        String codeLanguage = "";
        String title = null;
        int removedIncludes = 0;

        for (String line : cleaned.lines().toList()) {
            String trimmed = line.strip();

            // ---- 代码块开闭 ----
            if (!inCodeBlock) {
                Matcher sourceMatcher = SOURCE_ATTR.matcher(trimmed);
                if (sourceMatcher.matches()) {
                    String lang = sourceMatcher.group(1);
                    codeLanguage = lang == null ? "" : lang;
                    continue;   // 属性行本身不输出，等遇到 ---- 再开代码块
                }
            }
            if (trimmed.equals("----")) {
                if (inCodeBlock) {
                    out.append("```\n");
                    inCodeBlock = false;
                } else {
                    out.append("```").append(codeLanguage).append('\n');
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                out.append(line).append('\n');
                continue;
            }

            // ---- include 指令：第 2 周做组装，现在直接移除 ----
            if (INCLUDE.matcher(trimmed).matches()) {
                removedIncludes++;
                continue;
            }

            // ---- 标题 ----
            if (trimmed.startsWith("=")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '=') {
                    level++;
                }
                if (level <= 5 && trimmed.length() > level && trimmed.charAt(level) == ' ') {
                    String headingText = trimmed.substring(level + 1).strip();
                    if (level == 1 && title == null) {
                        title = headingText;
                    }
                    out.append("#".repeat(level + 1)).append(' ').append(headingText).append('\n');
                    continue;
                }
            }

            // ---- 普通块属性（NOTE/TIP/cols 等）直接丢弃，不参与检索 ----
            if (BLOCK_ATTR.matcher(trimmed).matches()) {
                continue;
            }

            out.append(line).append('\n');
        }

        if (inCodeBlock) {
            out.append("```\n");   // 文件末尾未闭合的代码块兜底
        }

        String content = out.toString().strip();
        String resolvedTitle = title != null ? title : MarkdownParser.extractTitle(content, relativePath);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_path", relativePath);
        metadata.put("format", "asciidoc");
        metadata.put("line_count", content.lines().count());
        metadata.put("removed_includes", removedIncludes);

        return new RepoDoc(relativePath, content, resolvedTitle, PathUtils.toBreadcrumb(relativePath), metadata);
    }

    /**
     * 去掉文件开头的注释块（Spring 文档每页都有 Apache 许可证头，约 15 行）。
     * 这些内容对检索是纯噪声，且会让 embedding 把不同页面的向量拉近。
     */
    static String stripLeadingCommentBlock(String content) {
        String[] lines = content.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i < lines.length && lines[i].stripLeading().startsWith("////")) {
            i++;
            while (i < lines.length && !lines[i].strip().equals("////")) {
                i++;
            }
            i++;   // 跳过结束行
        }
        StringBuilder sb = new StringBuilder();
        for (; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
