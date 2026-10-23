package com.devknow.agent.conversation;

import com.devknow.domain.entity.ConversationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要压缩的纯逻辑测试：选段、分批、清洗、校验、截断。
 *
 * <p>这几段都不碰模型也不碰数据库，但出错的表现极隐蔽 ——
 * 摘要少了半句、标识被清净、同一段被压两遍，界面上都看不出来，
 * 只有模型开始"失忆"或"重复确认"时才暴露。所以逐条测。
 *
 * <p>内容一律用中文：{@code TokenEstimator} 对中文是 1 字符 1 token，
 * 断言里能直接数出"几条几条"。
 */
class SummaryTextTest {

    // ------------------------------------------------------------------
    // 选段：只压"已被窗口丢掉、且没压过"的那一段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("选段：只取「已出窗口」且「水位之后」的消息，窗口内的不碰")
    void selectsOnlyDroppedAndUnsummarized() {
        List<ConversationMessage> history = List.of(
                user(1, "q1"), assistant(2, "a1"),
                user(3, "q2"), assistant(4, "a2"),
                user(5, "q3"), assistant(6, "a3"));

        // 窗口从 seq=5 开始（5、6 还在窗口里）；水位在 1（1 已压过）
        List<ConversationMessage> picked = SummaryText.selectToCompress(history, 5, 1, 1);

        assertThat(picked).extracting(ConversationMessage::getSeq).containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("选段：条数不够 minMessages 时整段放弃，不值得付一次调用")
    void skipsWhenBelowMinMessages() {
        List<ConversationMessage> history = List.of(
                user(1, "q1"), assistant(2, "a1"),
                user(3, "q2"), assistant(4, "a2"),
                user(5, "q3"), assistant(6, "a3"));

        assertThat(SummaryText.selectToCompress(history, 5, 1, 4)).isEmpty();
    }

    @Test
    @DisplayName("选段：窗口为空时整段都可压")
    void selectsEverythingWhenWindowEmpty() {
        List<ConversationMessage> history = List.of(
                user(1, "q1"), assistant(2, "a1"));

        assertThat(SummaryText.selectToCompress(history, Integer.MAX_VALUE, 0, 1))
                .extracting(ConversationMessage::getSeq).containsExactly(1, 2);
    }

    // ------------------------------------------------------------------
    // 分批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分批：单条自身就超预算的整条跳过，不切半条")
    void batchSkipsOversizedSingleMessage() {
        List<ConversationMessage> messages = List.of(
                user(1, chinese(5)),
                assistant(2, chinese(50)),   // 单条 50 token > 批上限 20
                user(3, chinese(5)));

        List<List<ConversationMessage>> batches = SummaryText.batch(messages, 20, 20);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).extracting(ConversationMessage::getSeq).containsExactly(1, 3);
    }

    @Test
    @DisplayName("分批：条数上限与 token 上限都会触发换批")
    void batchRespectsCountAndTokenLimits() {
        List<ConversationMessage> messages = List.of(
                user(1, chinese(6)), assistant(2, chinese(6)),
                user(3, chinese(6)), assistant(4, chinese(6)));

        // 条数上限 2 → 两批
        assertThat(SummaryText.batch(messages, 1000, 2))
                .extracting(List::size).containsExactly(2, 2);

        // token 上限 15 → 每批两条（6+6=12，再加就 18 超了）
        assertThat(SummaryText.batch(messages, 15, 20))
                .extracting(List::size).containsExactly(2, 2);
    }

    // ------------------------------------------------------------------
    // 清洗：只删结构符号，不碰标识符
    // ------------------------------------------------------------------

    @Test
    @DisplayName("清洗：去掉标题/列表/加粗/分隔线，但标识符里的 - # * . 原样保留")
    void cleanMarkdownKeepsIdentifiers() {
        String raw = """
                **目标：** 配置多数据源
                - 已定：阈值 min-relevance-score=0.4，见 issue #49659
                * 待续：确认 *.java 通配符的写法
                ---
                """;

        String text = SummaryText.cleanMarkdown(raw);

        assertThat(text).contains("min-relevance-score=0.4");
        assertThat(text).contains("#49659");
        assertThat(text).contains("*.java");
        assertThat(text).doesNotContain("**");
        assertThat(text).doesNotContain("---");
        assertThat(text).startsWith("目标：");
    }

    @Test
    @DisplayName("校验：三行齐全才算合格；缺「待续」或没有前缀一律不合格")
    void wellFormedRequiresAllThreeSections() {
        assertThat(SummaryText.isWellFormed("目标：A\n已定：B\n待续：C")).isTrue();
        assertThat(SummaryText.isWellFormed("目标：A\n已定：B")).isFalse();
        assertThat(SummaryText.isWellFormed("A\nB\nC")).isFalse();
        assertThat(SummaryText.isWellFormed(null)).isFalse();
    }

    @Test
    @DisplayName("无可提炼内容：识别为合法结果，不是失败")
    void detectsNoContent() {
        assertThat(SummaryText.isNoContent("无可提炼内容")).isTrue();
        assertThat(SummaryText.isNoContent("目标：A\n已定：B\n待续：C")).isFalse();
    }

    // ------------------------------------------------------------------
    // 截断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("截断：超长补省略号；切点落在代理对中间时回退一位，不切出半个字符")
    void clampIsSurrogateSafe() {
        assertThat(SummaryText.clamp("短文本", 10)).isEqualTo("短文本");

        // 「中中中🚀」：切在第 4 位正好是高代理位，必须回退到 3
        String withEmoji = "中中中🚀中中中";
        String clamped = SummaryText.clamp(withEmoji, 4);

        assertThat(clamped).isEqualTo("中中中…");
        assertThat(Character.isHighSurrogate(clamped.charAt(clamped.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("按行截断：每一行独立受限，绝不会整节丢掉「待续」")
    void clampByLinesKeepsAllSections() {
        String text = "目标：" + chinese(50) + "\n已定：" + chinese(50) + "\n待续：" + chinese(50);

        String clamped = SummaryText.clampByLines(text, 20);

        assertThat(clamped.split("\n")).hasSize(3);
        assertThat(clamped).contains("目标：").contains("已定：").contains("待续：");
        assertThat(clamped.lines()).allSatisfy(line -> assertThat(line).hasSizeLessThanOrEqualTo(21));
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提示词：旧摘要为空写「（无）」，非空则原样带进模版")
    void promptCarriesOldSummaryAndTranscript() {
        List<ConversationMessage> batch = List.of(user(1, "怎么配置多数据源"), assistant(2, "用 @Primary"));

        String empty = SummaryText.buildPrompt(null, batch);
        assertThat(empty).contains("（无）");
        assertThat(empty).contains("用户：怎么配置多数据源");
        assertThat(empty).contains("助手：用 @Primary");
        assertThat(empty).contains("无可提炼内容");
        assertThat(empty).doesNotContain("{old_summary}").doesNotContain("{new_turns}");

        String withOld = SummaryText.buildPrompt("目标：配置多数据源", batch);
        assertThat(withOld).contains("目标：配置多数据源");
    }

    // ------------------------------------------------------------------

    private static String chinese(int n) {
        return "中".repeat(n);
    }

    private static ConversationMessage user(int seq, String content) {
        return message(seq, "user", content);
    }

    private static ConversationMessage assistant(int seq, String content) {
        return message(seq, "assistant", content);
    }

    private static ConversationMessage message(int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
