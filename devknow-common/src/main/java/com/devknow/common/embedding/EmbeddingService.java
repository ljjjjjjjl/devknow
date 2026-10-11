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

    /**
     * 批量向量化，返回顺序与入参顺序严格一致。
     *
     * <p><b>必须提供批量能力，不能靠"循环调单条"代替。</b>
     * 回填阶段有 10000+ 个 chunk，HTTP 调用的成本几乎全在往返延迟上：
     * <ul>
     *   <li>逐条调用：10000 次请求 × 约 250ms 往返 ≈ 40 分钟以上，且请求体里
     *       绝大部分是固定开销（模型名、协议头、鉴权）</li>
     *   <li>按 100 条一批：约 100 次请求 ≈ 1 分钟以内</li>
     * </ul>
     * 差距接近两个数量级，而且这个差距随语料增长线性放大。
     * 因此实现类应当把"切批"做在自己内部（按条数与累计 token 双上限），
     * 调用方只需要把待处理文本一次性交给它。
     *
     * <p><b>失败必须抛异常，不允许"少返回几条"或"返回空列表"。</b>
     * 返回的元素个数与入参不一致时，调用方无法判断是"哪几条失败了"，
     * 会把错位的向量写进数据库 —— 这是最难排查的一类错误：
     * 数量对得上、维度也对得上，只有语义静默错乱。
     * 宁可让整批失败并由任务重试，也不要产生任何"部分成功"的模糊状态。
     *
     * @param texts 待向量化文本，不可为 null，元素不可为 null 或空白
     * @return 与 {@code texts} 等长、顺序一致的向量列表
     * @throws com.devknow.common.exception.BizException 调用失败、限流、或响应不完整时
     */
    List<float[]> embed(List<String> texts);

    /**
     * 单条向量化，用于在线查询。
     *
     * <p>默认实现走 {@link #embed(List)} —— 查询路径只有一条文本，
     * 不需要为它单独实现一条 HTTP 路径。
     */
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
