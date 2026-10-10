package com.devknow.ingestion.github;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一页数据的解析结果。
 *
 * @param <T> 元素类型
 */
@Data
public class PageResponse<T> {

    private List<T> items = new ArrayList<>();

    /**
     * 下一页的完整请求 URL，来自 {@code Link} 响应头的 {@code rel="next"}；没有下一页时为 null。
     *
     * <p><b>为什么存完整 URL 而不是页码</b>：GitHub 对 issue 数量大的仓库禁用了
     * {@code page=} 参数分页，会直接返回 422：
     * <pre>
     * "Pagination with the page parameter is not supported for large datasets,
     *  please use cursor based pagination (after/before)"
     * </pre>
     * 必须改用游标分页 —— 而 {@code Link} 头里给出的 {@code next} URL 已经带好了
     * {@code after=<cursor>} 游标，直接把它当作下一次请求地址即可，无需自己拼参数。
     * 这也让代码同时兼容页码分页与游标分页两种模式。
     */
    private String nextUrl;

    /** 原始 {@code Link} 响应头，仅用于日志诊断 */
    private String linkHeader;

    /** 本页请求的每页条数，用于判断"不足一页 = 最后一页" */
    private int pageSize;

    /**
     * 是否为最后一页。
     *
     * <p>两个信号取"或"：
     * <ul>
     *   <li>没有 {@code rel="next"} 链接</li>
     *   <li>本页返回条数不足 {@code pageSize}（数据已取完）</li>
     * </ul>
     */
    public boolean isLast() {
        return nextUrl == null || items.size() < pageSize;
    }

    public int size() {
        return items.size();
    }
}
