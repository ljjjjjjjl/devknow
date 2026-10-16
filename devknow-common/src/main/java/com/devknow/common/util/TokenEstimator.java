package com.devknow.common.util;

/**
 * token 数的量级估算。
 *
 * <p>用"中文字符 1 token、其他 4 字符 1 token"的启发式，不引入 tiktoken 之类的依赖：
 * 这里的用途是<b>控制上下文预算</b>，只需要量级正确，不需要精确。
 *
 * <p>为什么放在 {@code devknow-common}：切分（ingestion）、向量化批大小（retrieval）、
 * 会话上下文预算（agent）三处都要用同一个口径。原先 {@code HeadingChunker} 与
 * {@code SiliconFlowEmbeddingService} 各持一份包级可见的副本，注释里写着
 * "两处必须同步修改" —— 会话持久化会让它变成第三份，且 third copy 连复制都做不到
 * （agent 模块看不到 ingestion 的包级方法）。口径一旦漂移，
 * "切分批大小"与"chunk 大小上界"就在用两套标准衡量同一件事。
 *
 * <p>注意这是<b>上界偏乐观</b>的估算：中文按 1 字符 1 token 算，
 * 中英混合与代码块会低估。所以调用方不应只依赖它，还要加一道条数硬上限。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 估算 token 数。null 与空串返回 0。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }
}
