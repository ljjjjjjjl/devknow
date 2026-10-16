package com.devknow.agent.tool;

import com.devknow.common.util.JsonUtils;
import com.devknow.domain.dto.Citation;
import com.devknow.domain.dto.SearchResult;
import com.devknow.retrieval.RetrievalService;
import com.devknow.retrieval.rerank.RerankProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索知识库工具：把 {@link RetrievalService} 的能力暴露给 Agent。
 *
 * <p>单条结果正文截断到 {@value #MAX_CONTENT_CHARS} 字符 —— 检索出的 chunk 最长 4000 字符，
 * 不截断会让一次 ReAct 的上下文迅速打爆，相关度信号又集中在标题与开头。
 *
 * <p><b>低于 {@code devknow.rerank.min-relevance-score} 的结果按 0 命中处理。</b>
 * 理由见 {@link RerankProperties#getMinRelevanceScore()}：把"相不相关"的判断从模型手里
 * 拿回确定性代码，好让模型只面对"有结果 / 没结果"两分支，而这两支提示词都写了规则。
 */
@Slf4j
@Component
public class SearchKnowledgeTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_CONTENT_CHARS = 800;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "检索查询，支持中文或英文"},
                "topK":  {"type": "integer", "description": "返回条数，默认 5"}
              },
              "required": ["query"]
            }
            """;

    private static final JsonNode SCHEMA_NODE = parseSchema();

    private final RetrievalService retrievalService;
    private final RerankProperties rerankProperties;

    public SearchKnowledgeTool(RetrievalService retrievalService, RerankProperties rerankProperties) {
        this.retrievalService = retrievalService;
        this.rerankProperties = rerankProperties;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索知识库，返回与问题相关的文档片段。当需要查找具体技术知识、配置方法、API 用法时调用。";
    }

    @Override
    public JsonNode parameters() {
        return SCHEMA_NODE;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String query = arguments.path("query").asText(null);
        if (query == null || query.isBlank()) {
            return ToolResult.error("缺少 query 参数");
        }
        int topK = arguments.path("topK").asInt(DEFAULT_TOP_K);

        List<SearchResult> results = retrievalService.search(query, topK);

        // top-1 精排分。rerank 关闭、或降级退回 RRF 时为 null —— 那时没有可信的相关度信号，
        // 不能拿 null 去比阈值，否则会把全部结果误判成不相关。
        Double topScore = results.isEmpty() ? null : results.get(0).rerankScore();
        double minScore = rerankProperties.getMinRelevanceScore();
        boolean belowFloor = topScore != null && topScore < minScore;

        if (results.isEmpty() || belowFloor) {
            // 无结果的分支也必须打日志。少了它，事后翻日志分不清"没命中"和"压根没调用工具"，
            // 而这两种情况的排查方向完全相反（改检索 vs 改提示词）。
            log.info("search_knowledge: query={}, 命中 {} 条, top1={}, 判定为不相关（阈值 {}）",
                    query, results.size(), topScore, minScore);
            return ToolResult.empty("未检索到相关内容");
        }

        StringBuilder text = new StringBuilder("检索到 ").append(results.size()).append(" 条结果：\n\n");
        List<Citation> citations = new ArrayList<>();
        Set<Long> seenDocuments = new HashSet<>();
        int index = 1;
        for (SearchResult r : results) {
            text.append('[').append(index++).append("] ").append(r.title()).append('\n');
            if (r.headingPath() != null && !r.headingPath().isBlank()) {
                text.append("章节: ").append(r.headingPath()).append('\n');
            }
            if (r.content() != null) {
                text.append(truncate(r.content(), MAX_CONTENT_CHARS)).append('\n');
            }
            text.append('\n');
            if (r.documentId() != null && seenDocuments.add(r.documentId())) {
                citations.add(new Citation(r.documentId(), r.title(), r.documentUrl()));
            }
        }
        log.info("search_knowledge: query={}, 命中 {} 条, 引用 {} 篇",
                query, results.size(), citations.size());
        return new ToolResult(text.toString(), citations);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static JsonNode parseSchema() {
        try {
            return JsonUtils.mapper().readTree(SCHEMA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("search_knowledge 的 schema 非法", e);
        }
    }
}
