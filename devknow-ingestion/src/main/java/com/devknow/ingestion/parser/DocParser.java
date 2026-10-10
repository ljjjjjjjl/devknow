package com.devknow.ingestion.parser;

import com.devknow.ingestion.git.RepoDoc;

/**
 * 文档解析器：把仓库中的原始文件转成结构化文本。
 *
 * <p>第 1 周的实现刻意保持"最小可用" —— 只做格式归一化（AsciiDoc → Markdown）
 * 与标题提取，不做语义切分。切分是 {@code Chunker} 的职责。
 *
 * <p>这样分层的原因：把"解析对不对"与"切分对不对"两个变量分开验证，
 * 出错时才分得清是哪一层的锅。
 */
public interface DocParser {

    /** 是否支持该扩展名（如 {@code .adoc}）。 */
    boolean supports(String extension);

    /**
     * 解析文件内容。
     *
     * @param relativePath 仓库内相对路径，用于生成标题与 breadcrumb
     * @param rawContent   文件原始内容
     * @return 解析结果；内容为空时返回 {@code null} 由调用方跳过
     */
    RepoDoc parse(String relativePath, String rawContent);
}
