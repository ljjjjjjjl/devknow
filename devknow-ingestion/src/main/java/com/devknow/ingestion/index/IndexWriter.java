package com.devknow.ingestion.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devknow.common.embedding.EmbeddingService;
import com.devknow.common.util.HashUtils;
import com.devknow.domain.entity.DocumentChunk;
import com.devknow.domain.mapper.DocumentChunkMapper;
import com.devknow.ingestion.chunker.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 切分结果落库。向量部分留到第 2 周。
 *
 * <p>第 1 周的职责边界很明确：<b>把 chunk 的文本与全部元数据写进 PostgreSQL</b>，
 * 但不写向量。这样第 2 周只需在此类中追加一个 embedding 步骤和
 * {@code V2__add_pgvector.sql} 迁移，不必回头改动切分逻辑。
 */
@Slf4j
@Component
public class IndexWriter {

    private final DocumentChunkMapper chunkMapper;
    private final ObjectProvider<EmbeddingService> embeddingServiceProvider;

    public IndexWriter(DocumentChunkMapper chunkMapper,
                       ObjectProvider<EmbeddingService> embeddingServiceProvider) {
        this.chunkMapper = chunkMapper;
        this.embeddingServiceProvider = embeddingServiceProvider;
    }

    /**
     * 保存一个文档的全部 chunk。
     *
     * <p>写入策略：<b>先按 documentId 删旧、再逐条插入</b>。
     * 选"删后插"而不是"逐条 upsert"，是因为文档内容变化时切分出的块数量与边界
     * 都可能改变，逐条 upsert 会留下孤儿块。
     *
     * <p>逐条插入而非攒成 List 再批量：单篇文档可能切出几百块
     * （实测有一篇 280 KB 的文档），全部驻留内存没有必要。
     *
     * @return 写入的 chunk 数
     */
    @Transactional
    public int saveChunks(Long documentId, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));

        Instant now = Instant.now();
        int saved = 0;
        for (Chunk chunk : chunks) {
            DocumentChunk entity = new DocumentChunk();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(chunk.chunkIndex());
            entity.setChunkType(chunk.chunkType());
            entity.setHeadingPath(chunk.headingPath());
            entity.setHeadingLevel(chunk.headingLevel());
            entity.setContent(chunk.content());
            entity.setContentTokens(chunk.contentTokens());
            entity.setContentHash(HashUtils.sha256Hex(chunk.content()));
            entity.setMetadata("{}");
            entity.setCreatedAt(now);
            // 必须用 insertChunk 而非 MyBatis-Plus 的 insert：
            // 后者不会把 metadata 转成 jsonb，PostgreSQL 会拒绝整条语句
            saved += chunkMapper.insertChunk(entity);
        }
        return saved;
    }

    /**
     * 第 2 周的接入点：检查向量化能力是否已配置。
     *
     * <p>现在没有实现类时返回 false，应用照常启动运行 —
     * 这正是"第 1 周不接模型"这一安排的落地方式。
     */
    public boolean embedderAvailable() {
        return embeddingServiceProvider.getIfAvailable() != null;
    }
}
