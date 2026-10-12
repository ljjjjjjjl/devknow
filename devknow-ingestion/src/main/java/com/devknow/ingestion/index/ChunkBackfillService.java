package com.devknow.ingestion.index;

import com.devknow.domain.entity.Document;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.mapper.DocumentMapper;
import com.devknow.ingestion.chunker.Chunk;
import com.devknow.ingestion.chunker.HeadingChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 切分回填：把已入库但尚未切分的 issue/PR 补上 chunk。
 *
 * <p><b>为什么需要它</b>：{@code GitHubIssueMapper} 原本只写 {@code document} 行、不切分，
 * 所以现有 5128 篇 issue/PR 一条 chunk 都没有。而重新触发同步会被 {@code contentHash}
 * 短路（内容没变）—— 所以必须有一个独立的回填入口。
 *
 * <p>回填完成后，新采集的 issue/PR 由 {@code GitHubIssueMapper} 在写入时<b>顺带切分</b>，
 * 这条回填路径只在需要批量补历史数据时使用。
 *
 * <p><b>状态就是一条查询</b>：{@code NOT EXISTS (chunk)} 即待办列表，
 * 所以不需要进度表、游标或标记列。重复触发是幂等的。
 */
@Slf4j
@Service
public class ChunkBackfillService {

    private final DocumentMapper documentMapper;
    private final HeadingChunker chunker;
    private final IndexWriter indexWriter;

    public ChunkBackfillService(DocumentMapper documentMapper,
                                HeadingChunker chunker,
                                IndexWriter indexWriter) {
        this.documentMapper = documentMapper;
        this.chunker = chunker;
        this.indexWriter = indexWriter;
    }

    /**
     * 处理一轮切分回填。
     *
     * @param sourceId      知识源 id
     * @param limitPerRound 本轮最多处理多少篇；切分是纯 CPU 操作，比嵌入快得多
     * @return 本轮<b>写入的 chunk 条数</b>（不是文档篇数）
     */
    public int backfill(Long sourceId, int limitPerRound) {
        List<Document> pending = documentMapper.selectUnchunkedIssues(sourceId, limitPerRound);
        if (pending.isEmpty()) {
            return 0;
        }

        int chunks = 0;
        int done = 0;
        int failed = 0;
        for (Document doc : pending) {
            try {
                chunks += chunkDocument(doc);
                done++;
            } catch (Exception e) {
                // 单篇失败不中断整轮；但必须打完整堆栈 ——
                // 第 1 周的教训：容忍型 catch 只打 message 会让"chunk 全为 0"这种问题无迹可寻。
                failed++;
                log.error("切分失败，跳过: documentId={}, docType={}, externalId={}",
                        doc.getId(), doc.getDocType(), doc.getExternalId(), e);
            }
        }

        log.info("切分回填完成: sourceId={}, 处理 {} 篇（失败 {}）, 写入 {} 个 chunk",
                sourceId, done, failed, chunks);
        return chunks;
    }

    /**
     * 切分单篇文档并落库。
     *
     * <p>实时采集路径（{@code GitHubIssueMapper}）也走这里 ——
     * <b>让 breadcrumb 的构造逻辑只有一处实现</b>，避免两条链路各写一份而慢慢漂移。
     *
     * <p>调用方需保证 {@code doc.getId()} 非空（即已完成 upsert 并回查过 id）。
     *
     * @return 写入的 chunk 条数；正文为空或全是空白时返回 0
     */
    public int chunkDocument(Document doc) {
        String content = doc.getRawContent();
        if (content == null || content.isBlank()) {
            return 0;
        }
        List<Chunk> chunks = chunker.chunk(content, doc.getTitle(), issueBreadcrumb(doc));
        return indexWriter.saveChunks(doc.getId(), chunks);
    }

    /**
     * issue / PR 的 {@code heading_path} 根。
     *
     * <p><b>不带 source 名。</b>两个理由：
     * <ol>
     *   <li>DOC 链路的 breadcrumb 是<b>纯路径派生</b>的（实测形如
     *       {@code data-initialization > Database Initialization > ...}），本来就不含源名 ——
     *       带上源名反而与 DOC 链路不一致</li>
     *   <li>{@code source.name} 是可变的（本项目已经改过一次源名）。它是持久化字段，
     *       一旦写进几千条 heading_path，改名就要批量刷这些行。
     *       检索层要展示源名时 JOIN {@code document → knowledge_source} 即可</li>
     * </ol>
     *
     * <p><b>带标题。</b>实测 issue 正文几乎不含 markdown 标题
     * （4318 篇 ISSUE 里只有 106 篇含 {@code "## "}，PR 是 113/810），
     * 所以标题栈通常是空的 —— 不带标题的话 {@code heading_path} 会退化成一个纯 ID
     * （{@code issue #4318}），对关键词加权毫无用处。issue 没有"位置"这个概念，
     * <b>标题就是它唯一的结构信息</b>。
     */
    private static String issueBreadcrumb(Document doc) {
        String label = DocType.PR.getValue().equals(doc.getDocType()) ? "PR" : "issue";
        String number = doc.getExternalId() == null ? "?" : doc.getExternalId();
        String title = doc.getTitle() == null ? "" : doc.getTitle().strip();
        return title.isEmpty()
                ? label + " #" + number
                : label + " #" + number + " > " + title;
    }
}
