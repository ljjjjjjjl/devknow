package com.devknow.ingestion.chunker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeadingChunker} 的切分边界测试。
 *
 * <p>重点覆盖旧实现踩过的坑：围栏状态、长度上界、编码边界。
 */
class HeadingChunkerTest {

    private final HeadingChunker chunker = new HeadingChunker();

    // ------------------------------------------------------------------
    // 一、围栏状态机
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未闭合围栏：不抛异常，剩余内容整体保留")
    void unclosedFence() {
        String text = "```java\n"
                + "int a = 1;\n"
                + "\n"
                + "int b = 2;\n";          // 全程没有闭合围栏

        List<String> parts = chunker.splitLongText(text);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0))
                .contains("int a = 1;")
                .contains("int b = 2;");
    }

    @Test
    @DisplayName("代码块内含空行：不被当成段落边界拆开")
    void blankLineInsideCodeBlock() {
        String text = "```java\n"
                + "public void foo() {\n"
                + "\n"                        // 代码块内的空行
                + "    int a = 1;\n"
                + "\n"                        // 又一个
                + "}\n"
                + "```\n";

        List<String> parts = chunker.splitLongText(text);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0))
                .contains("public void foo()")
                .contains("int a = 1;");
    }

    @Test
    @DisplayName("四反引号围栏内：三反引号代码块不被误判为关闭")
    void nestedFenceWithInfoString() {
        // 两处填充都是必需的：splitLongText 会把"小于 MIN_SIZE(200)"的块与相邻内容合并，
        // 代码块太短时它不会单独成块，就观察不到围栏在哪里闭合。
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            code.append("int v").append(i).append(" = ").append(i).append(";\n");
        }
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            tail.append("后面还有正文，第 ").append(i)
                .append(" 段，用来把总量推到 TARGET_SIZE 之上。\n");
        }

        String text = "````markdown\n"
                + "示例：\n"
                + "```java\n"                 // ← 内容，不是关闭围栏
                + code
                + "```\n"                     // ← 内容（长度 3 < 4）
                + "````\n"                    // ← 真正的关闭围栏
                + "\n"
                + tail;

        List<String> parts = chunker.splitLongText(text);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0))
                .contains("```java")
                .contains("int v0 = 0;")
                // 关键断言：外层 4 反引号围栏在同一个块内闭合。
                // 若内部的 ``` 被误判为关闭，这个块会以 3 个反引号收尾，断言失败。
                .endsWith("````");
        assertThat(parts.get(1)).contains("后面还有正文");
    }

    @Test
    @DisplayName("围栏关闭后同段紧跟正文：状态不会卡死（旧实现的致命失效）")
    void fenceFollowedByTextWithoutBlankLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("```java\nint a = 1;\n```\n紧跟的正文。\n");   // 围栏与正文间无空行
        for (int i = 0; i < 60; i++) {
            sb.append("\n第 ").append(i).append(" 段正文，用来把总长度推到 MAX_SIZE 之上。\n");
        }

        List<String> parts = chunker.splitLongText(sb.toString());

        // 旧实现里 inCode 会永久停在 true，整个方法只返回 1 个块
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.get(0))
                .contains("int a = 1;")
                .contains("紧跟的正文");
    }

    // ------------------------------------------------------------------
    // 二、长度上界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("零空行长文档（redirect.adoc 形态）：切成多块且每块不超上界")
    void longListWithoutBlankLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6_000; i++) {
            sb.append("* xref:ROOT:page-").append(i)
              .append(".adoc[#anchor-").append(i).append("]\n");
        }

        List<String> parts = chunker.splitLongText(sb.toString());

        assertThat(parts).hasSizeGreaterThan(50);
        assertThat(parts).allSatisfy(p ->
                assertThat(p.length()).isLessThanOrEqualTo(HeadingChunker.HARD_MAX_SIZE));
    }

    @Test
    @DisplayName("单行超 HARD_MAX_SIZE：按句子边界切，不劈开句子")
    void singleLineOverHardMax() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }

        List<String> parts = chunker.splitLongText(sb.toString().strip());

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts).allSatisfy(p ->
                assertThat(p.length()).isLessThanOrEqualTo(HeadingChunker.HARD_MAX_SIZE));
        // 句子边界切分：每块应以句末标点收尾，而不是从词中间断开
        assertThat(parts.get(0)).endsWith(".");
    }

    @Test
    @DisplayName("硬上界换算成 token 后仍远低于 embedding 模型上限")
    void hardLimitsFitWithinEmbeddingBudget() {
        // 最坏情况：整块都是中文，estimateTokens ≈ 字符数（1 字符 = 1 token）
        String worstCaseText = "中".repeat(HeadingChunker.HARD_MAX_SIZE);
        assertThat(HeadingChunker.estimateTokens(worstCaseText))
                .isLessThan(HeadingChunker.EMBEDDING_MAX_TOKENS);

        String worstCaseCode = "中".repeat(HeadingChunker.CODE_HARD_MAX_SIZE);
        assertThat(HeadingChunker.estimateTokens(worstCaseCode))
                .isLessThan(HeadingChunker.EMBEDDING_MAX_TOKENS);
    }

    // ------------------------------------------------------------------
    // 三、编码边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("中英 emoji 混合：长度受控，且 emoji 不被劈成半个字符")
    void mixedEncoding() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            sb.append("第").append(i)
              .append("段：Spring Boot 的 AOT 处理 🚀 会把构建期决策固化。\n\n");
        }

        List<String> parts = chunker.splitLongText(sb.toString());

        assertThat(parts).isNotEmpty();
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.length()).isLessThanOrEqualTo(HeadingChunker.HARD_MAX_SIZE);
            assertThat(HeadingChunker.estimateTokens(p))
                    .isLessThan(HeadingChunker.EMBEDDING_MAX_TOKENS);
            assertThat(hasLoneSurrogate(p))
                    .as("emoji 被从中间劈开，产生了孤立代理位: %s", p)
                    .isFalse();
        });
    }

    /** 检测孤立代理位 —— 出现即说明某个增补平面字符被切成了两半。 */
    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 四、边界与回归
    // ------------------------------------------------------------------

    @Test
    @DisplayName("连续空行：产生空段落但不留多余空行")
    void consecutiveBlankLines() {
        String text = "第一段。\n\n\n\n\n第二段。\n\n   \n\n第三段。";

        List<String> parts = chunker.splitLongText(text);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0))
                .contains("第一段")
                .contains("第二段")
                .contains("第三段")
                .doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("空白输入：返回空列表而不是含空元素的列表")
    void blankInput() {
        assertThat(chunker.splitLongText("")).isEmpty();
        assertThat(chunker.splitLongText("   \n\n  \n")).isEmpty();
    }

    @Test
    @DisplayName("含标题的代码块小节当前判为 TEXT —— 记录已知缺陷")
    void codeBlockSectionIsTypedAsTextKnownIssue() {
        // detectType 要求"整块首尾都是围栏"，但切分时标题行也被放进正文
        // （见 chunk() 里 buffer.append(trimmed)），所以实际永远判不成 CODE。
        // 这是库里 chunk_type='CODE' 为 0 的原因。
        //
        // 修复 detectType 时请同步更新本测试。
        String content = "## 示例\n\n```java\nint a = 1;\n```\n";

        List<Chunk> chunks = chunker.chunk(content, "示例文档", "root");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkType()).isEqualTo("TEXT");
    }
}
