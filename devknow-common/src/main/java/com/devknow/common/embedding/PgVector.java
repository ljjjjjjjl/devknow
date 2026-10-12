package com.devknow.common.embedding;

/**
 * pgvector 字面量转换。
 *
 * <p>ingestion 的向量回填和 retrieval 的在线查询必须产出完全一致的格式，
 * 否则会出现"回填能写进去、查询查不出来"这类极难定位的问题。放在 common 里只有一份实现。
 *
 * <p>ingestion 的向量回填和 retrieval 的在线查询共用本类，两处只有一份实现。
 */
public final class PgVector {

    private PgVector() {}

    /**
     * 转成 pgvector 能直接解析的数组字面量：{@code [0.1,0.2,0.3]}。
     *
     * <p>统一<b>无空格</b>格式。pgvector 本身接受带空格的写法，
     * 但无空格可省掉一次字符串扫描，也让日志里的向量更紧凑。
     */
    public static String of(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}