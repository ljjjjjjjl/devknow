package com.devknow.common.util;

import java.util.regex.Pattern;

/**
 * 路径与 glob 匹配工具。
 *
 * <p>用于把知识源配置里的 glob 模式（如
 * {@code src/main/antora/modules/ROOT/pages/**&#47;*.adoc}）编译成正则，
 * 匹配仓库内文件的相对路径。
 *
 * <p>为什么不用 {@code FileSystems.getDefault().getPathMatcher("glob:...")}：
 * 它的行为在不同操作系统上有差异（Windows 反斜杠），而仓库内的路径永远是 {@code /} 分隔，
 * 自己实现能保证跨平台一致。
 */
public final class PathUtils {

    private PathUtils() {
    }

    /**
     * 把 glob 编译为正则。
     *
     * <p>支持三种通配符：
     * <ul>
     *   <li>{@code **} —— 匹配任意层级目录（含零层）</li>
     *   <li>{@code *}  —— 匹配单层内任意字符（不含 {@code /}）</li>
     *   <li>{@code ?}  —— 匹配单个字符（不含 {@code /}）</li>
     * </ul>
     */
    public static Pattern globToPattern(String glob) {
        if (glob == null || glob.isBlank()) {
            // 未配置模式时退化为"匹配全部"，避免静默什么都不匹配
            return Pattern.compile(".*");
        }
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i += 2;
                        // 吃掉紧跟的 '/'，使 "**/x" 也能匹配顶层的 "x"
                        if (i < n && glob.charAt(i) == '/') {
                            i++;
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    regex.append("[^/]");
                    i++;
                }
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> {
                    regex.append('\\').append(c);
                    i++;
                }
                default -> {
                    regex.append(c);
                    i++;
                }
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 判断相对路径是否匹配 glob 模式。
     *
     * <p><b>支持多模式</b>：逗号分隔，任意一个命中即算匹配。例如：
     * <pre>
     * documentation/spring-boot-docs/src/docs/antora/modules/ROOT/pages/**&#47;*.adoc,
     * build-plugin/**&#47;src/docs/**&#47;*.adoc
     * </pre>
     *
     * <p>为什么需要：一个仓库的文档往往分散在多处（主文档、插件文档、根目录说明文件），
     * 单个 glob 覆盖不全。空白片段会被忽略，所以末尾多余的逗号不会退化成"匹配全部"。
     */
    public static boolean matches(String glob, String relativePath) {
        if (relativePath == null || glob == null || glob.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        for (String part : glob.split(",")) {
            String pattern = part.strip();
            if (pattern.isEmpty()) {
                continue;
            }
            if (globToPattern(pattern).matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把文件路径转换为标题路径（breadcrumb）。
     *
     * <p>例：{@code src/main/antora/modules/ROOT/pages/data/redis.adoc}
     * → {@code data > redis}
     *
     * <p>去掉常见的前缀噪音目录（src/main/antora/...），只留对用户有意义的层级，
     * 否则 heading_path 会被无意义的工程目录占满。
     */
    public static String toBreadcrumb(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String normalized = relativePath.replace('\\', '/');
        int pagesIdx = normalized.indexOf("/pages/");
        if (pagesIdx >= 0) {
            normalized = normalized.substring(pagesIdx + "/pages/".length());
        }
        int dot = normalized.lastIndexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        return normalized.replace('/', '>').replace(">", " > ").trim();
    }

    /** 安全拼接：把 owner/repo 转成目录名，过滤掉路径穿越字符。 */
    public static String sanitizeSegment(String segment) {
        if (segment == null) {
            return "_";
        }
        String cleaned = segment.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }
}
