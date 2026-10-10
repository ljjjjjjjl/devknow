package com.devknow.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容指纹工具。
 *
 * <p>用途：
 * <ul>
 *   <li>{@code document.content_hash} —— 增量更新时判断内容是否变化，未变则跳过切分与向量化</li>
 *   <li>{@code document_chunk.content_hash} —— 跨文档去重（同一段样例代码可能被多处复制）</li>
 * </ul>
 * 用 sha256 而不是 hashCode：后者碰撞概率高，会导致"内容变了却判定未变"的静默数据错误。
 */
public final class HashUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtils() {
    }

    /** 计算 sha256 十六进制摘要（64 字符，对应 CHAR(64)）。 */
    public static String sha256Hex(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必定内置 SHA-256，走到这里说明运行环境被破坏
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 多段内容拼接后取指纹，用 \\u0000 分隔以避免 ["ab","c"] 与 ["a","bc"] 撞车。 */
    public static String sha256Hex(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('\u0000');
            }
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        return sha256Hex(sb.toString());
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
