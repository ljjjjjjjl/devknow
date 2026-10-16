package com.devknow.ingestion.chunker;

import com.devknow.common.util.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 按标题层级切分 Markdown 文本。
 *
 * <p><b>为什么不定长切分</b>：修车手册、技术文档、法规都以"节"为语义单位。
 * 定长 512 token 会把一个配置示例从中截断，检索命中后半段时
 * LLM 拿到的是没有前提的碎片 —— 这是 RAG 效果最隐蔽的杀手。
 *
 * <p><b>标题路径（heading_path）为什么重要</b>：
 * <ul>
 *   <li>检索时做关键词加权：问 "Redis 配置"，路径含 Redis 的块提权</li>
 *   <li>生成时给 LLM 上下文定位："这段出自 Data > Redis > 配置"</li>
 *   <li>引用展示可读性大幅提升</li>
 * </ul>
 *
 * <p><b>超长小节的处理分三层</b>：
 * <ol>
 *   <li>行级扫描把文本切成"单元" —— 围栏代码块整体是一个不可拆的单元，
 *       其余按空行分段</li>
 *   <li>贪心聚合单元到 {@link #TARGET_SIZE}</li>
 *   <li>兜底硬切仍超 {@link #HARD_MAX_SIZE} 的单元，保证输出长度必有上界</li>
 * </ol>
 *
 * <p><b>三个尺寸常量的分工</b>（改之前务必分清）：
 * <ul>
 *   <li>{@code TARGET_SIZE} —— 聚合目标，类内部使用</li>
 *   <li>{@code MAX_SIZE} —— <b>调用方（{@code flush}）判断要不要切分的触发阈值，
 *       类内部不使用</b></li>
 *   <li>{@code HARD_MAX_SIZE} / {@code CODE_HARD_MAX_SIZE} —— 切分方法保证的输出上界</li>
 * </ul>
 */
@Component
public class HeadingChunker {

    /**
     * 目标块大小（字符），约等于 400~600 token。
     *
     * <p>这是切分的<b>甜点区</b>：单个向量表征的文本越长，语义越被稀释，
     * 检索命中率越低。聚合阶段会尽量把块控制在这个尺寸附近。
     */
    private static final int TARGET_SIZE = 1_600;

    /**
     * 二次切分的<b>触发阈值</b>。
     *
     * <p><b>注意：这个常量不是本类内部使用的</b>，而是给调用方
     * {@link #flush} 判断"这一段要不要送进 {@link #splitLongText}"用的。
     * {@code splitLongText} 保证的输出上界是 {@link #HARD_MAX_SIZE}，不是本值 ——
     * 一个介于 MAX_SIZE 和 HARD_MAX_SIZE 之间的独立段落会被原样保留为一个块
     * （刻意为之：保住段落完整性，不被无意义地劈开）。
     */
    private static final int MAX_SIZE = 2_400;

    /** 低于此大小的小节与相邻内容合并，避免产生大量碎片 */
    private static final int MIN_SIZE = 200;

    /**
     * 普通文本的绝对上界（字符）。任何非代码块的输出都不得超过它。
     *
     * <p><b>为什么必须有它</b>：{@link #MAX_SIZE} 只决定"要不要调用切分"，
     * 约束不了切分结果。当整篇文档是一个没有空行的长列表时
     * （实测 spring-boot 的 {@code redirect.adoc}：280 KB、零空行），
     * 按段落切只会得到 1 个段落，产出 7 万 token 的巨块。
     */
    static final int HARD_MAX_SIZE = 4_000;

    /**
     * 代码块的绝对上界（字符），比普通文本放宽。
     *
     * <p>权衡：代码块整体保留优先（截断的代码比没有代码更糟），但也不能无界。
     *
     * <p><b>为什么是 6000 而不是 8000</b>：embedding 模型上限是 8192 token，
     * 而 {@link #estimateTokens} 的最坏情况是"整块中文 = 每字符 1 token"。
     * 8000 字符的全中文块 = 8000 token，距上限只剩 2.3% 余量；
     * 6000 则留出 27%。见 {@code HeadingChunkerTest} 中的上界断言。
     */
    static final int CODE_HARD_MAX_SIZE = 6_000;

    /**
     * embedding 模型的输入 token 上限。
     *
     * <p>取值依据：SiliconFlow 官方文档 —— BAAI/bge-m3 与 Pro/BAAI/bge-m3 为 8192，
     * 同系列 bge-large-zh/en-v1.5 只有 512，Qwen3-Embedding 系列为 32768。
     * 选 m3 是因为它是三者里中英文都强、且 1024 维的折中选择。
     *
     * <p>这里只用于<b>断言上界是否安全</b>，不参与切分逻辑。
     */
    static final int EMBEDDING_MAX_TOKENS = 8_192;

    /** 围栏标记 */
    private static final String FENCE = "```";

    /** 聚合时的块间分隔符 */
    private static final String BLOCK_SEPARATOR = "\n\n";

    /**
     * 硬切片段的分隔标记。<b>默认空串 = 关闭</b>。
     *
     * <p>为什么默认关闭：
     * <ol>
     *   <li>"还原原文顺序"靠 {@code document_chunk} 的
     *       {@code UNIQUE (document_id, chunk_index)} 就够了 —— 按 chunk_index
     *       排序即原文顺序，不需要带内标记</li>
     *   <li>往 {@code content} 里插字符会直接进入 embedding 向量、也会出现在
     *       引用展示里，是纯噪声</li>
     * </ol>
     * "这块是硬切出来的片段"这个信息更适合放进 chunk 的 metadata。
     * 如果确实要可见标记，改成如 {@code "\n…"} 即可。
     */
    private static final String HARD_SPLIT_MARKER = "";

    /**
     * 切分入口。
     *
     * @param content     Markdown 正文
     * @param docTitle    文档标题，作为标题路径的根
     * @param breadcrumb  来自文件路径的 breadcrumb（可为 null）
     */
    public List<Chunk> chunk(String content, String docTitle, String breadcrumb) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        String root = (breadcrumb != null && !breadcrumb.isBlank()) ? breadcrumb
                : (docTitle != null ? docTitle : "");

        String[] lines = content.split("\n", -1);
        Deque<String> headings = new ArrayDeque<>();
        StringBuilder buffer = new StringBuilder();
        int currentLevel = 0;
        int index = 0;
        String openingFence = null;   // 非 null 表示在代码块内

        for (String line : lines) {
            String trimmed = line.strip();

            // 围栏开闭与 toBlocks 保持一致：开启认 startsWith(FENCE)，
            // 关闭认 isClosingFence（整行纯反引号且不短于开启围栏）。
            // 两者必须同步修改，否则上游会把代码块内的 # 误判成标题。
            if (openingFence != null) {
                if (isClosingFence(trimmed, openingFence)) {
                    openingFence = null;
                }
                buffer.append(line).append('\n');
                continue;
            }

            if (trimmed.startsWith(FENCE)) {
                openingFence = fenceTokenOf(trimmed);
                buffer.append(line).append('\n');
                continue;
            }

            int level = headingLevel(trimmed);
            if (level > 0) {
                // 遇到新标题：先把之前累积的内容落成一个块
                index = flush(chunks, buffer, headings, root, currentLevel, index);
                buffer.setLength(0);

                updateHeadingStack(headings, level, headingText(trimmed, level));
                currentLevel = level;
                // 标题本身也放进正文，让 embedding 能感知该段的主题
                buffer.append(trimmed).append('\n');
                continue;
            }
            buffer.append(line).append('\n');
        }
        flush(chunks, buffer, headings, root, currentLevel, index);

        return chunks;
    }

    /** 把缓冲区内容落成 1~n 个块，返回下一个可用的 chunkIndex。 */
    private int flush(List<Chunk> chunks, StringBuilder buffer, Deque<String> headings,
                      String root, int level, int index) {
        String text = buffer.toString().strip();
        if (text.isEmpty()) {
            return index;
        }
        String headingPath = buildHeadingPath(root, headings);
        // MAX_SIZE 是"要不要切分"的触发阈值；真正的输出上界由 splitLongText 保证
        List<String> parts = text.length() <= MAX_SIZE
                ? List.of(text)
                : splitLongText(text);

        for (String part : parts) {
            String trimmedPart = part.strip();
            if (trimmedPart.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(
                    index++,
                    detectType(trimmedPart),
                    headingPath,
                    (short) Math.max(level, 1),
                    trimmedPart,
                    estimateTokens(trimmedPart)));
        }
        return index;
    }

    /**
     * 把超长文本切成多个块。
     *
     * <p>相比旧实现的三处修正：
     * <ol>
     *   <li><b>围栏状态按行维护</b>，不再按段落 {@code startsWith/endsWith} 判断 ——
     *       旧写法在"围栏关闭后同段还有正文"时会把 {@code inCode} 永久卡在 true，
     *       导致后续内容再也不被切开</li>
     *   <li><b>空段落直接丢弃</b>，不再往缓冲区塞多余换行、污染长度计算</li>
     *   <li><b>补上长度上界</b>：单个超大段落先被识别出来单独硬切
     *       （旧实现完全没有上界）</li>
     * </ol>
     *
     * <p>代码块不再需要 {@code !inCode} 守卫：在第 1 步它已经是<b>原子单元</b>，
     * 聚合阶段不可能把它切开。
     */
    List<String> splitLongText(String text) {
        List<String> blocks = toBlocks(text);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            int limit = hardLimitOf(block);

            // 单元自身就超上界 —— 先给前面收口，再单独硬切这个单元
            if (block.length() > limit) {
                flushBuffer(result, current);          // 收一个结果块
                result.addAll(hardSplit(block, limit));
                continue;
            }

            // 塞不下、且当前块已够大 —— 收口开新块。
            // 长度计算把分隔符一并算进去，避免实际块略超 TARGET_SIZE
            boolean overflow = current.length() + block.length()
                    + BLOCK_SEPARATOR.length() > TARGET_SIZE;
            if (overflow && current.length() >= MIN_SIZE) {
                flushBuffer(result, current);          // 收一个结果块
            }
            current.append(block).append(BLOCK_SEPARATOR);
        }
        flushBuffer(result, current);                  // 收最后一个结果块

        return result;
    }

    // ------------------------------------------------------------------
    // 第 1 层：行级扫描，切成不可分割的单元
    // ------------------------------------------------------------------

    /**
     * 把文本切成"不可分割的单元"：围栏代码块整体算一个单元，其余按空行分段。
     *
     * <p><b>关键</b>：代码围栏的开闭必须<b>逐行</b>判断。代码块内部的空行
     * 不是段落边界，所以不能先 {@code split("\n\n")} 再猜。
     *
     * <p>围栏识别做了严格化：只有<b>整行纯反引号</b>且不短于开启围栏的行
     * 才算关闭。这样代码块内部的 {@code ```java} 这类"带 info string 的围栏样式文本"
     * 不会被误判成关闭，从而把代码块从中间截断。
     *
     * <p>用 {@code strip()} 后再比较，因此缩进的围栏也能识别。
     */
    private List<String> toBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        String openingFence = null;   // 非 null 表示当前位于代码块内部

        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();

            if (openingFence != null) {
                if (isClosingFence(stripped, openingFence)) {
                    block.append(line).append('\n');
                    flushBuffer(blocks, block);        // 收一个单元：完整代码块
                    openingFence = null;
                } else {
                    // 代码块内容原样保留 —— 含空行，也含 "```java" 这种带 info string 的行
                    block.append(line).append('\n');
                }
                continue;
            }

            if (stripped.startsWith(FENCE)) {
                flushBuffer(blocks, block);            // 收一个单元：围栏前的正文
                block.append(line).append('\n');
                openingFence = fenceTokenOf(stripped);
                continue;
            }

            if (stripped.isEmpty()) {
                flushBuffer(blocks, block);            // 收一个单元：一个段落
                continue;
            }

            block.append(line).append('\n');
        }

        // 未闭合的围栏：剩余内容整体作为一个单元
        // （AsciiDocParser 已做闭合兜底，这里是防御性处理）
        flushBuffer(blocks, block);
        return blocks;
    }

    /** 取行首连续反引号作为围栏标记，如 {@code "```java"} → {@code "```"}。 */
    private static String fenceTokenOf(String stripped) {
        int n = 0;
        while (n < stripped.length() && stripped.charAt(n) == '`') {
            n++;
        }
        return stripped.substring(0, n);
    }

    /**
     * 是否为关闭围栏。
     *
     * <p>严格化处理：<b>整行只有反引号</b>，且长度不短于开启围栏
     * （CommonMark 允许用更长的围栏关闭）。带 info string 的行不算关闭 ——
     * 这正是"代码块内的 {@code ```java} 被误判为关闭"的修复点。
     */
    private static boolean isClosingFence(String stripped, String openingFence) {
        if (stripped.length() < openingFence.length()) {
            return false;
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) != '`') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 第 2 层：兜底硬切，保证长度有上界
    // ------------------------------------------------------------------

    /**
     * 硬切一个超限单元。
     *
     * <p>三层兜底，每层的输出都不再超限：
     * <ol>
     *   <li>按换行切 —— 保住行的完整性（列表项、代码行）</li>
     *   <li>单行仍超限 → 按句子边界切</li>
     *   <li>单句仍超限 → 按字符数切（并对代理对做边界保护）</li>
     * </ol>
     */
    private List<String> hardSplit(String block, int limit) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : block.split("\n", -1)) {
            if (line.length() > limit) {
                // 单行就超限：先收口，再对这一行做句子级切分
                flushBuffer(pieces, current);
                pieces.addAll(splitLongLine(line, limit));
                continue;
            }
            if (current.length() + line.length() + 1 > limit) {
                flushBuffer(pieces, current);
            }
            current.append(line).append('\n');
        }
        flushBuffer(pieces, current);
        return markHardSplits(pieces);
    }

    /** 超长单行：先按中英文句末标点切，仍超限的再按字符数硬切。 */
    private List<String> splitLongLine(String line, int limit) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : line.split("(?<=[.!?。！？])")) {
            // split 会产出空串和纯空白项（如 "Hello. " 的尾随空格），必须丢掉，
            // 否则会往缓冲区塞无意义内容并污染长度计算
            if (sentence.isBlank()) {
                continue;
            }
            if (sentence.length() > limit) {
                flushBuffer(result, current);
                appendByChars(result, sentence, limit);
                continue;
            }
            if (current.length() + sentence.length() > limit) {
                flushBuffer(result, current);
            }
            current.append(sentence);
        }
        flushBuffer(result, current);
        return result;
    }

    /**
     * 按字符数硬切一句话。
     *
     * <p><b>代理对保护</b>：emoji（如 🚀）在 UTF-16 里占两个 char，
     * 如果切点正好落在高代理位之后，会产出半个字符 —— 编码成 UTF-8 时
     * 变成 {@code ?} 或直接抛异常。这里在边界处回退一位。
     */
    private static void appendByChars(List<String> target, String sentence, int limit) {
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + limit, sentence.length());
            if (end < sentence.length() && Character.isHighSurrogate(sentence.charAt(end - 1))) {
                end--;      // 不要让切点落在代理对中间
            }
            String piece = sentence.substring(start, end).strip();
            if (!piece.isEmpty()) {
                target.add(piece);
            }
            start = end;
        }
    }

    /**
     * 给"被硬切开的片段"打标记。
     *
     * <p>{@link #HARD_SPLIT_MARKER} 默认为空串（关闭），原因见该常量的注释。
     * 开启后只在<b>非末尾</b>的片段上加标记，表示"后面还有"。
     */
    private static List<String> markHardSplits(List<String> pieces) {
        if (HARD_SPLIT_MARKER.isEmpty() || pieces.size() <= 1) {
            return pieces;
        }
        List<String> marked = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            marked.add(i < pieces.size() - 1 ? pieces.get(i) + HARD_SPLIT_MARKER : pieces.get(i));
        }
        return marked;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 代码块用更宽松的上界。 */
    private static int hardLimitOf(String block) {
        return block.stripLeading().startsWith(FENCE) ? CODE_HARD_MAX_SIZE : HARD_MAX_SIZE;
    }

    /**
     * 把缓冲区收成一个元素并清空缓冲区；纯空白直接丢弃。
     *
     * <p>单元（{@link #toBlocks}）和结果块（{@link #splitLongText} /
     * {@link #hardSplit}）都用它 —— 两处逻辑完全一致，只是"收的是什么"语义不同，
     * 所以只保留一个实现，避免复制成两份后改一处忘一处。
     * 每个调用点都有注释标明收的是哪一种。
     *
     * <p>空缓冲区是正常情况（连续空行、未闭合围栏），直接返回不产生空元素。
     */
    private static void flushBuffer(List<String> target, StringBuilder buffer) {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) {
            target.add(text);
        }
    }

    /** 标题栈：遇到 level=2 时弹出所有 >= 2 的层级，再压入。 */
    private void updateHeadingStack(Deque<String> stack, int level, String text) {
        // 栈内元素形如 "2:Data"，用层级前缀保证弹出逻辑正确
        while (!stack.isEmpty() && levelOf(stack.peekLast()) >= level) {
            stack.pollLast();
        }
        stack.addLast(level + ":" + text);
    }

    private String buildHeadingPath(String root, Deque<String> stack) {
        StringBuilder sb = new StringBuilder();
        if (root != null && !root.isBlank()) {
            sb.append(root);
        }
        for (String entry : stack) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(entry.substring(entry.indexOf(':') + 1));
        }
        return sb.toString();
    }

    private static int levelOf(String entry) {
        int idx = entry.indexOf(':');
        return idx > 0 ? Integer.parseInt(entry.substring(0, idx)) : 0;
    }

    private static int headingLevel(String line) {
        if (!line.startsWith("#")) {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level > 6 || level >= line.length() || line.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }

    private static String headingText(String line, int level) {
        return line.substring(level).strip();
    }

    /** 粗略判断块类型：整块被代码围栏包裹即为 CODE。 */
    private static String detectType(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return "CODE";
        }
        if (trimmed.startsWith("|") || trimmed.contains("\n|")) {
            return "TABLE";
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("1. ")) {
            return "LIST";
        }
        return "TEXT";
    }

    /**
     * 估算 token 数，委托给 {@link TokenEstimator}。
     *
     * <p>保留这个包级方法只是为了不动既有调用点与测试；实现已经上收到
     * {@code devknow-common}，与向量化批大小、会话上下文预算共用同一口径。
     * 原先这里和 {@code SiliconFlowEmbeddingService} 各有一份副本，注释里写着
     * "两处必须同步修改" —— 会话持久化会让它变三份，所以统一收口。
     */
    static int estimateTokens(String text) {
        return TokenEstimator.estimate(text);
    }
}
