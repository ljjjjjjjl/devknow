package com.devknow.common.embedding;

import java.util.List;

/**
 * 文本向量化能力。
 *
 * <p>放在 common 模块的原因：入库流水线（ingestion）与在线检索（retrieval）都必须用
 * <b>同一个模型</b> 把文本映射到同一向量空间。若分别定义接口，极易出现"文档用 A 模型、
 * 查询用 B 模型"的隐蔽 bug —— 两边向量不在同一空间，相似度计算完全失去意义。
 *
 * <p>第 2 周提供实现，候选：
 * <ul>
 *   <li>SiliconFlow API（bge-m3，1024 维）—— 最快跑通</li>
 *   <li>DJL + ONNX Runtime 加载 bge-m3 —— 全 JVM 内推理，无外部依赖</li>
 * </ul>
 *
 * <p><b>换模型必须全量重算</b>：向量维度与语义空间都变了，
 * 因此 {@code document_chunk.embedding_model} 字段要记录生成时的模型名。
 */
public interface EmbeddingService {

    /** 批量向量化。返回顺序与入参顺序严格一致。 */
    List<float[]> embed(List<String> texts);

    /** 单条向量化，用于在线查询。 */
    default float[] embedOne(String text) {
        List<float[]> result = embed(List.of(text));
        if (result.isEmpty()) {
            throw new IllegalStateException("向量化返回空结果");
        }
        return result.get(0);
    }

    /** 向量维度。写入 pgvector 列定义时需与此一致。 */
    int dimension();

    /** 模型标识，写入 {@code document_chunk.embedding_model}，用于判断是否需要重算。 */
    String modelName();
}
