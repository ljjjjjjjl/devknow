package com.devknow.retrieval.rerank;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过 SiliconFlow 的 rerank 端点调用 bge-reranker-v2-m3（cross-encoder 精排）。
 *
 * <p>与 {@code SiliconFlowEmbeddingService} 同平台、同 key、同一种调用方式，
 * 不引入新的技术栈依赖。
 *
 * <p><b>不做重试</b>：与向量化一致 —— 这里 sleep 重试会占住请求线程。
 * 调用方（{@code RetrievalService}）在失败时退回 RRF 顺序，
 * 检索宁可给出"次优但可用"的结果，也不要因为一个外部依赖返回 500。
 */
@Slf4j
@Component
public class SiliconFlowRerankService {

    private static final String RERANK_PATH = "/rerank";

    private final RerankProperties properties;
    private final HttpClient httpClient;

    /** 专用 ObjectMapper：只关心 SiliconFlow 的响应格式，不受全局 Jackson 配置影响 */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SiliconFlowRerankService(RerankProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isUsable() {
        return properties.isUsable();
    }

    /**
     * 对候选文档按与 query 的相关度打分。
     *
     * @param documents 待重排文本
     * @return <b>与 {@code documents} 等长、下标一一对应</b>的相关度分数，越大越相关。
     *         下标对应关系是硬约束：错位会让"正确维度的错误分数"排出一份看起来正常、
     *         实际语义错乱的榜单，和向量化那边按 index 重排是同一个理由。
     * @throws BizException 调用失败或响应不可用时抛出，由调用方决定降级
     */
    public double[] rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return new double[0];
        }
        if (!properties.isUsable()) {
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序未启用或未配置 API Key");
        }

        List<String> truncated = new ArrayList<>(documents.size());
        for (String doc : documents) {
            truncated.add(truncate(doc));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("query", query);
        body.put("documents", truncated);
        // 显式要求返回全部候选：不设 top_n 时服务端可能只回前若干条，
        // 那样我们拿不到完整分数，只能给缺失项记 0，排序会被悄悄扭曲。
        body.put("top_n", truncated.size());
        body.put("return_documents", false);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序请求体序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + RERANK_PATH))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("重排序请求失败: documents={}, error={}", truncated.size(), e.toString());
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序请求被中断", e);
        }

        if (response.statusCode() >= 400) {
            log.warn("重排序返回 HTTP {}: documents={}, body={}",
                    response.statusCode(), truncated.size(), abbreviate(response.body()));
            throw new BizException(ErrorCode.RERANK_FAILED,
                    "重排序返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        return parse(response.body(), truncated.size());
    }

    /**
     * 解析响应并<b>按 index 还原到入参下标</b>。
     *
     * <p>若所有分数都缺失（字段名与服务端不一致时会这样），直接抛异常而不是返回全 0 ——
     * 全 0 会让候选退化成按 id 排序，结果是"看起来跑通了、排序毫无意义"，
     * 这类静默失效比直接失败难查得多。
     */
    private double[] parse(String body, int expected) {
        RerankResponse parsed;
        try {
            parsed = mapper.readValue(body, RerankResponse.class);
        } catch (IOException e) {
            log.warn("重排序响应解析失败: expected={}, body={}", expected, abbreviate(body));
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序响应解析失败", e);
        }

        List<RerankItem> items = parsed.getResults();
        if (items == null || items.isEmpty()) {
            log.warn("重排序响应为空: expected={}, body={}", expected, abbreviate(body));
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序响应为空");
        }

        double[] scores = new double[expected];
        int scored = 0;
        for (RerankItem item : items) {
            Double value = item.scoreValue();
            Integer idx = item.getIndex();
            if (value == null || idx == null || idx < 0 || idx >= expected) {
                continue;
            }
            scores[idx] = value;
            scored++;
        }
        if (scored == 0) {
            log.warn("重排序响应没有任何可用分数（字段名可能不符）: expected={}, body={}",
                    expected, abbreviate(body));
            throw new BizException(ErrorCode.RERANK_FAILED, "重排序响应缺少 relevance_score 字段");
        }
        if (scored != expected) {
            // 缺分项保持 0 分（排到最后），不因此整体降级 —— 部分可用好过完全不用
            log.warn("重排序返回分数不全: expected={}, scored={}，缺失项按 0 分处理", expected, scored);
        }
        return scores;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = properties.getMaxDocumentChars();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ------------------------------------------------------------------
    // 响应结构（只声明用到的字段）
    // ------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResponse {
        private String id;
        private List<RerankItem> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankItem {
        private Integer index;

        /** SiliconFlow / Jina / Cohere 系的字段名 */
        @JsonProperty("relevance_score")
        private Double relevanceScore;

        /** 少数实现用 score，作为兜底 */
        private Double score;

        Double scoreValue() {
            return relevanceScore != null ? relevanceScore : score;
        }
    }
}
