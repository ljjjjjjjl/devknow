package com.devknow.domain;

import com.devknow.domain.enums.ConversationEnums.ConversationStatus;
import com.devknow.domain.enums.ConversationEnums.MessageRole;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.enums.JobEnums.JobStatus;
import com.devknow.domain.enums.JobEnums.JobType;
import com.devknow.domain.enums.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移脚本与 Java 枚举的一致性测试。
 *
 * <p>为什么需要这个测试：迁移脚本里的 {@code CHECK} 约束列举了合法取值，
 * 而 Java 枚举是另一处独立定义。两者一旦不一致，就会在运行时才暴露
 * ——采集跑了半小时、写库时报"违反 CHECK 约束"，代价很高。
 *
 * <p>这个测试把"数据库能接受的取值"与"代码会产生的取值"绑在一起，
 * 在编译期（测试阶段）就发现问题。类似地，如果后续新增枚举值却忘了写迁移，这里会立刻失败。
 *
 * <p><b>新增带 CHECK 的表时必须在这里加一个用例</b>：测试是按约束名逐个断言的，
 * 不会自动发现新表 —— 第 6 周的 {@code conversation} / {@code conversation_message}
 * 就是手工补的两个用例，脚本路径也要一起传进来。
 */
class SchemaEnumConsistencyTest {

    /**
     * 迁移脚本相对于<b>项目根目录</b>的位置。
     *
     * <p>注意不要写死绝对路径：测试运行时的当前目录取决于从哪个模块启动
     * （{@code mvn -pl devknow-domain} 时是模块目录，完整构建时是根目录），
     * 所以下面会向上逐级查找项目根。
     */
    private static final String MIGRATION_DIR = "devknow-web/src/main/resources/db/migration/";
    private static final String V1 = MIGRATION_DIR + "V1__init.sql";
    private static final String V6 = MIGRATION_DIR + "V6__add_conversation.sql";

    private String loadMigration(String relativePath) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; depth <= 3 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("未找到迁移脚本 " + relativePath
                + "，已从 " + Path.of("").toAbsolutePath() + " 向上查找 3 层");
    }

    @Test
    @DisplayName("document.doc_type 的 CHECK 约束覆盖 DocType 全部枚举值")
    void docTypeConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V1), "ck_document_type", valuesOfName(DocType.values()));
    }

    @Test
    @DisplayName("knowledge_source.type 的 CHECK 约束覆盖 SourceType 全部枚举值")
    void sourceTypeConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V1), "ck_source_type", valuesOfName(SourceType.values()));
    }

    @Test
    @DisplayName("ingestion_job.job_type 的 CHECK 约束覆盖 JobType 全部枚举值")
    void jobTypeConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V1), "ck_job_type", valuesOfName(JobType.values()));
    }

    @Test
    @DisplayName("ingestion_job.status 的 CHECK 约束覆盖 JobStatus 全部枚举值")
    void jobStatusConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V1), "ck_job_status", valuesOfName(JobStatus.values()));
    }

    // ---- 第 6 周：会话 ----

    @Test
    @DisplayName("conversation.status 的 CHECK 约束覆盖 ConversationStatus 全部枚举值")
    void conversationStatusConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V6), "ck_conversation_status",
                valuesOfValue(ConversationStatus.values()));
    }

    @Test
    @DisplayName("conversation_message.role 的 CHECK 约束覆盖 MessageRole 全部枚举值")
    void messageRoleConstraintCoversEnum() throws IOException {
        assertConstraintCovers(loadMigration(V6), "ck_message_role",
                valuesOfValue(MessageRole.values()));
    }

    // ------------------------------------------------------------------

    /**
     * 用枚举常量名比对。
     *
     * <p>适用于"枚举名 == 库里的值"的那批（{@code DocType} / {@code JobStatus} 等）。
     */
    private static List<String> valuesOfName(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).collect(Collectors.toList());
    }

    /**
     * 用枚举的 {@code value} 字段比对。
     *
     * <p>会话的两个枚举刻意让"名字大写、值小写"（{@code USER} → {@code "user"}）：
     * 库里存的是 {@code user} / {@code assistant}，与 OpenAI 消息协议一致，
     * 重建上下文时不用再转一次大小写。所以不能拿 {@code name} 去比。
     */
    private static List<String> valuesOfValue(ConversationStatus[] constants) {
        return Arrays.stream(constants).map(ConversationStatus::getValue).collect(Collectors.toList());
    }

    private static List<String> valuesOfValue(MessageRole[] constants) {
        return Arrays.stream(constants).map(MessageRole::getValue).collect(Collectors.toList());
    }

    /**
     * 断言 CHECK 约束里出现了所有枚举值。
     *
     * <p>只校验"代码里的值数据库都接受"这一个方向 —— 反向（数据库允许但代码不用）
     * 并不算错误，允许保留兼容性。
     */
    private void assertConstraintCovers(String sql, String constraintName, List<String> enumValues) {
        int start = sql.indexOf(constraintName);
        assertTrue(start >= 0, "迁移脚本中找不到约束: " + constraintName);

        int open = sql.indexOf('(', start);
        int close = sql.indexOf(')', open);
        assertTrue(open > 0 && close > open, "无法解析约束定义: " + constraintName);
        String definition = sql.substring(open, close);

        for (String value : enumValues) {
            assertTrue(definition.contains("'" + value + "'"),
                    "约束 " + constraintName + " 缺少枚举值 '" + value + "'，"
                            + "请在新迁移中补充（不要修改已执行过的迁移文件）");
        }
    }
}
