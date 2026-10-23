package com.devknow.agent.conversation;

import com.devknow.common.util.TokenEstimator;
import com.devknow.domain.entity.ConversationMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要压缩的纯逻辑：选段、分批、渲染、清洗、校验、截断。
 *
 * <p><b>为什么全部做成静态纯函数</b>：真正容易写错的就是这几段（水位不重叠、
 * 超长单条跳过、清洗时不毁标识符、截断不切半个字符），而它们出错的表现都很隐蔽 ——
 * 摘要少了半句话，没人看得出来。抽出来就能脱库、脱模型直接单测。
 *
 * <p>这里<b>不</b>负责调用模型，也不碰数据库。
 */
final class SummaryText {

    /** 模版里没有出现 {@code %}，但仍用占位符替换而不是 String.format —— 提示词以后加个百分号就炸 */
    private static final String PROMPT_TEMPLATE = """
            你是对话摘要器。输入是一段更早的对话记录，以及上一次的摘要（可能为空）。
            任务：把两者合并去重，压缩成一段不超过 300 中文字的纯文本记忆，供后续轮次理解上下文与指代。

            输出格式（严格输出三行纯文本，禁止任何 Markdown 格式如加粗/列表，依次以下列词开头）：
            目标：用户在做什么、想达成什么
            已定：已经确认的结论、参数、约束、选型
            待续：尚未解决的问题、被搁置的分支、未完成项

            规则（必须严格遵守）：
            1. 只保留对话里明确出现过的信息，禁止推测；老摘要与新对话冲突时，以新对话为准。
            2. 不要写建议、不要回答问题、不要复述代码或日志原文；只抽象描述"做了 X"。
            3. 被推翻或被修正的中间结论不要写，只写最终结论。
            4. 具体标识（类名、配置项、文件名、报错关键字）必须原样保留，它们是后续指代的锚点。
            5. 某一节没有内容时该行仍然保留，冒号后写「无」。
            6. 若这次记录里没有可沉淀的信息（寒暄、重复提问），只输出「无可提炼内容」这一行，不输出三行结构。
            7. 不要任何开场白或结尾说明，第一行必须直接以「目标：」开头。

            上一次摘要：
            {old_summary}

            待压缩的对话记录：
            {new_turns}

            现在输出合并后的摘要正文：
            """;

    /** 模型表示"这段没价值"的固定说法 */
    static final String NO_CONTENT = "无可提炼内容";

    /** 三行结构的前缀，用于校验与解析 */
    static final String PREFIX_GOAL = "目标：";
    static final String PREFIX_DONE = "已定：";
    static final String PREFIX_TODO = "待续：";

    private SummaryText() {
    }

    // ------------------------------------------------------------------
    // 选段
    // ------------------------------------------------------------------

    /**
     * 挑出"可以压"的那一段：已被窗口丢掉、且尚未进入摘要。
     *
     * <p>两个条件缺一不可：
     * <ul>
     *   <li>{@code seq < windowMinSeq} —— 必须已被挤出原文窗口</li>
     *   <li>{@code seq > uptoSeq} —— 没被压过（水位线）</li>
     * </ul>
     * 少了第一个，摘要会和原文窗口重叠，模型对同一句话看到两遍；
     * 少了第二个，同一段会被反复压缩。
     *
     * @param history     该会话的完整历史（已过滤失败占位），按 seq 升序
     * @param windowMinSeq 原文窗口里第一条消息的 seq；窗口为空时传 Integer.MAX_VALUE
     * @param uptoSeq     水位线：已压到第几条
     * @param minMessages 少于这个条数就不值得压
     */
    static List<ConversationMessage> selectToCompress(List<ConversationMessage> history,
                                                      int windowMinSeq, int uptoSeq, int minMessages) {
        List<ConversationMessage> picked = new ArrayList<>();
        for (ConversationMessage m : history) {
            Integer seq = m.getSeq();
            if (seq != null && seq < windowMinSeq && seq > uptoSeq) {
                picked.add(m);
            }
        }
        return picked.size() < minMessages ? List.of() : picked;
    }

    /**
     * 按 token 预算与条数上限切批。
     *
     * <p><b>单条自身就超预算的直接跳过</b>：那是代码块或日志，压出来几乎必然丢掉关键细节，
     * 与其产出一段"似是而非"的摘要（模型会把它当事实），不如让这段彻底失忆。
     * 跳过的消息会被后续批次的水位线一起越过。
     */
    static List<List<ConversationMessage>> batch(List<ConversationMessage> messages,
                                                 int tokenLimit, int maxCount) {
        List<List<ConversationMessage>> batches = new ArrayList<>();
        List<ConversationMessage> current = new ArrayList<>();
        int used = 0;

        for (ConversationMessage m : messages) {
            int cost = estimate(m);
            if (cost > tokenLimit) {
                continue;                      // 单条超限：跳过，不切半条
            }
            boolean full = current.size() >= maxCount || used + cost > tokenLimit;
            if (full && !current.isEmpty()) {
                batches.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(m);
            used += cost;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static int estimate(ConversationMessage m) {
        String content = m.getContent();
        return content == null ? 0 : TokenEstimator.estimate(content);
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    static String buildPrompt(String oldSummary, List<ConversationMessage> batch) {
        String previous = (oldSummary == null || oldSummary.isBlank()) ? "（无）" : oldSummary.strip();
        return PROMPT_TEMPLATE
                .replace("{old_summary}", previous)
                .replace("{new_turns}", renderTranscript(batch));
    }

    static String renderTranscript(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage m : messages) {
            sb.append("user".equals(m.getRole()) ? "用户：" : "助手：")
                    .append(m.getContent() == null ? "（空）" : m.getContent().strip())
                    .append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 后处理
    // ------------------------------------------------------------------

    /**
     * 只清洗<b>结构性</b> Markdown，绝不删可能属于标识符的字符。
     *
     * <p>反面教材：{@code replaceAll("[*#`-]", "")} —— 它会把
     * {@code min-relevance-score} 变成 {@code minrelevancescore}、把 {@code #49659}
     * 变成 {@code 49659}，而提示词里第四条明确要求"标识保留原样"。这类清洗不可逆，
     * 摘要的锚点价值直接被毁掉。
     *
     * <p>反引号保留：{@code `@ConfigurationProperties`} 比裸文本更好认，也不占多少 token。
     */
    static String cleanMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        text = text.replaceAll("(?m)^\\s*#{1,6}\\s*", "");                 // 行首标题
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");                  // 行首列表符号
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");                 // 成对加粗
        text = text.replaceAll("(?m)^\\s*[-=]{3,}\\s*$", "");              // 分隔线
        return text.strip();
    }

    /** 三行结构是否齐全 —— 缺一行说明模型没照格式来，这种摘要不能入库 */
    static boolean isWellFormed(String text) {
        return text != null
                && text.startsWith(PREFIX_GOAL)
                && text.contains(PREFIX_DONE)
                && text.contains(PREFIX_TODO);
    }

    /** 模型表示这段没有可沉淀的信息 */
    static boolean isNoContent(String text) {
        return text != null && text.contains(NO_CONTENT);
    }

    /**
     * 按字符截断，末尾补省略号。
     *
     * <p>代理对保护：切点落在 UTF-16 高代理位之后会切出半个字符，写进 UTF-8 库变成 {@code ?}。
     */
    static String clamp(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int cut = Math.max(1, max);
        if (cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut).stripTrailing() + "…";
    }

    /**
     * 按行截断：每行各限一份配额。
     *
     * <p>不用 {@code clamp(overall)} 一刀切 —— 那样最常见的后果是把「待续：」整节切掉，
     * 而未完成项恰恰是后续指代最需要的部分。
     */
    static String clampByLines(String text, int perLine) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(clamp(line, perLine));
        }
        return sb.toString();
    }
}
