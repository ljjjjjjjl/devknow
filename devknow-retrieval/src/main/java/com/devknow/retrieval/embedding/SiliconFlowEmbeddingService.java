package com.devknow.retrieval.embedding;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.embedding.EmbeddingService;
import com.devknow.common.exception.BizException;
import com.devknow.retrieval.config.EmbeddingProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * 通过 SiliconFlow 的 OpenAI 兼容 embeddings 端点调用 bge-m3。
 *
 * <p>只负责"把文本变成向量"这一件事：
 * <ul>
 *   <li>切批在内部完成（按条数 + 累计 token 双上限），调用方一次把全部文本交给它</li>
 *   <li><b>不写重试逻辑</b> —— 重试由 {@code ingestion_job} 的 {@code max_retry} 负责。
 *       在这里 sleep 重试会占住 Worker 线程，且与任务级重试重复</li>
 *   <li>任何"结果不完整"的情况一律抛异常，绝不返回部分结果</li>
 * </ul>
 */
@Slf4j
@Component
public class SiliconFlowEmbeddingService implements EmbeddingService {

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final EmbeddingProperties properties;
    private final HttpClient httpClient;

    /** 专用 ObjectMapper：只关心 SiliconFlow 的响应格式，不受全局 Jackson 配置影响 */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SiliconFlowEmbeddingService(EmbeddingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        requireConfigured();

        List<float[]> result = new ArrayList<>(texts.size());
        for (List<String> batch : splitBatches(texts)) {
            result.addAll(callBatch(batch));
        }
        return result;
    }

    @Override
    public int dimension() {
        return properties.getDimension();
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }

    // ------------------------------------------------------------------

    /**
     * 按条数与累计 token 双上限切批。
     *
     * <p>关键规则：<b>当前批为空时无条件放入</b>。
     * 这样单条就超过 token 上限的文本会独占一批，而不是被跳过 —— 跳过会让它的
     * embedding 永远为空，进而卡在回填队列里被反复扫描。
     */
    private List<List<String>> splitBatches(List<String> texts) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int tokens = 0;

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "待向量化文本为空 —— 空字符串会让整批请求返回 400");
            }
            int t = estimateTokens(text);
            boolean overflow = !current.isEmpty()
                    && (current.size() + 1 > properties.getBatchMaxChunks()
                        || tokens + t > properties.getBatchMaxTokens());
            if (overflow) {
                batches.add(current);
                current = new ArrayList<>();
                tokens = 0;
            }
            current.add(text);
            tokens += t;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private List<float[]> callBatch(List<String> batch) {
        String url = properties.getBaseUrl() + EMBEDDINGS_PATH;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", batch);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            log.error("向量化请求体序列化失败: batchSize={}", batch.size(), e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化请求体序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("向量化请求失败: url={}, batchSize={}", url, batch.size(), e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("向量化请求被中断: url={}, batchSize={}", url, batch.size(), e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化请求被中断", e);
        }

        // 429 单独成一类：它是"稍后重试会成功"，与"请求本身有问题"不同。
        // 由上层（job 的 max_retry / 调度熔断）决定退避，这里不 sleep。
        if (response.statusCode() == 429) {
            log.warn("向量化触发限流: url={}, batchSize={}, body={}",
                    url, batch.size(), abbreviate(response.body()));
            throw new BizException(ErrorCode.EMBEDDING_RATE_LIMITED, "向量化触发限流，等待任务重试");
        }
        if (response.statusCode() >= 400) {
            log.error("向量化返回 HTTP {}: url={}, batchSize={}, body={}",
                    response.statusCode(), url, batch.size(), abbreviate(response.body()));
            throw new BizException(ErrorCode.EMBEDDING_FAILED,
                    "向量化返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        return parse(response.body(), batch.size());
    }

    /**
     * 解析响应并<b>按 {@code index} 重排</b>。
     *
     * <p>不能假设响应顺序与请求顺序一致 —— 一旦错位，写进库的是"正确维度的错误向量"，
     * 数量对、维度对、只有语义错乱，是最难发现的一类数据污染。
     */
    private List<float[]> parse(String body, int expected) {
        EmbeddingResponse parsed;
        try {
            parsed = mapper.readValue(body, EmbeddingResponse.class);
        } catch (IOException e) {
            log.error("向量化响应解析失败: expected={}, body={}", expected, abbreviate(body), e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化响应解析失败", e);
        }

        List<EmbeddingItem> items = parsed.getData();
        if (items == null || items.isEmpty()) {
            log.error("向量化响应为空: expected={}, body={}", expected, abbreviate(body));
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化响应为空，拒绝写入无效向量");
        }
        if (items.size() != expected) {
            log.error("向量化响应条数不符: expected={}, actual={}, body={}",
                    expected, items.size(), abbreviate(body));
            throw new BizException(ErrorCode.EMBEDDING_FAILED,
                    "向量化响应条数不符: 期望 " + expected + " 条，实际 " + items.size() + " 条");
        }

        float[][] ordered = new float[expected][];
        for (int i = 0; i < items.size(); i++) {
            EmbeddingItem item = items.get(i);
            int idx = item.getIndex() == null ? i : item.getIndex();
            if (idx < 0 || idx >= expected || ordered[idx] != null) {
                log.error("向量化响应 index 非法或重复: index={}, expected={}", idx, expected);
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "向量化响应 index 非法或重复: " + idx);
            }
            ordered[idx] = toVector(item.getEmbedding(), idx);
        }

        List<float[]> result = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            if (ordered[i] == null) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "向量化响应缺少 index=" + i + " 的结果");
            }
            result.add(ordered[i]);
        }
        return result;
    }

    private float[] toVector(List<Double> values, int index) {
        if (values == null || values.isEmpty()) {
            log.error("向量化响应 embedding 为空: index={}", index);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化响应 embedding 为空: index=" + index);
        }
        if (values.size() != properties.getDimension()) {
            log.error("向量维度不符: index={}, expected={}, actual={}",
                    index, properties.getDimension(), values.size());
            throw new BizException(ErrorCode.EMBEDDING_FAILED,
                    "向量维度不符: 期望 " + properties.getDimension() + "，实际 " + values.size());
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            // 必须显式 floatValue()：List<Double> 取出的是 Double，
            // 而 Java 的赋值转换不允许"拆箱 + 窄化"组合（JLS 5.2）
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new BizException(ErrorCode.EMBEDDING_NOT_CONFIGURED,
                    "向量化未启用（devknow.embedding.enabled=false）");
        }
        if (!properties.hasApiKey()) {
            throw new BizException(ErrorCode.EMBEDDING_NOT_CONFIGURED,
                    "未配置 SILICONFLOW_API_KEY，无法向量化");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /**
     * 与 {@code HeadingChunker.estimateTokens} 相同的启发式：中文 1 token、其他 4 字符 1 token。
     *
     * <p><b>这份是本地副本，两处必须同步修改。</b>
     * {@code HeadingChunker} 里的同名方法是包级可见的，跨模块调不到。
     * 若两处口径漂移，"切分批大小"与"chunk 大小上界"会用两套标准衡量同一件事。
     */
    static int estimateTokens(String text) {
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }

    // ------------------------------------------------------------------
    // 响应结构（只声明用到的字段）
    // ------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingResponse {
        private String model;
        private List<EmbeddingItem> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingItem {
        private Integer index;
        private List<Double> embedding;
    }
}
