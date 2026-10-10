package com.devknow.ingestion.git;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.FileUtils;
import com.devknow.common.util.HashUtils;
import com.devknow.common.util.JsonUtils;
import com.devknow.common.util.PathUtils;
import com.devknow.domain.entity.Document;
import com.devknow.domain.entity.IngestionJob;
import com.devknow.domain.entity.KnowledgeSource;
import com.devknow.domain.enums.DocType;
import com.devknow.domain.mapper.DocumentMapper;
import com.devknow.domain.mapper.KnowledgeSourceMapper;
import com.devknow.ingestion.chunker.Chunk;
import com.devknow.ingestion.chunker.HeadingChunker;
import com.devknow.ingestion.config.DevknowProperties;
import com.devknow.ingestion.index.IndexWriter;
import com.devknow.ingestion.job.IngestionJobService;
import com.devknow.ingestion.parser.DocParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 git 浅克隆同步仓库文档。
 *
 * <p>完整链路：clone → 按 glob 匹配文件 → 选解析器 → 生成 chunk → 落库。
 *
 * <p>两个刻意的设计：
 * <ol>
 *   <li><b>支持按扩展名分派解析器</b> —— 新增格式（如 .md 换 .rst）只需实现 {@link DocParser}，
 *       本类不用改。这是"对扩展开放"的落点</li>
 *   <li><b>解析完成后按配置清理仓库</b> —— 仓库只是原料中转站，长期数据在 PostgreSQL。
 *       保留与否由 {@code devknow.data.keep-repo} 控制（第 2 周做增量时改 true）</li>
 * </ol>
 */
@Slf4j
@Component
public class GitRepoDocSyncer {

    private final RepoFetcher repoFetcher;
    private final DocumentMapper documentMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final IndexWriter indexWriter;
    private final HeadingChunker chunker;
    private final DevknowProperties properties;
    private final IngestionJobService jobService;
    private final List<DocParser> parsers;

    public GitRepoDocSyncer(RepoFetcher repoFetcher,
                            DocumentMapper documentMapper,
                            KnowledgeSourceMapper sourceMapper,
                            IndexWriter indexWriter,
                            HeadingChunker chunker,
                            DevknowProperties properties,
                            IngestionJobService jobService,
                            List<DocParser> parsers) {
        this.repoFetcher = repoFetcher;
        this.documentMapper = documentMapper;
        this.sourceMapper = sourceMapper;
        this.indexWriter = indexWriter;
        this.chunker = chunker;
        this.properties = properties;
        this.jobService = jobService;
        this.parsers = parsers;
    }

    /**
     * 同步一个知识源的文档。
     *
     * @return 写入的文档数
     */
    public int sync(KnowledgeSource source, IngestionJob job) {
        requireRepoInfo(source);

        Path repoPath = properties.getData().repoDir(
                PathUtils.sanitizeSegment(source.getRepoOwner()),
                PathUtils.sanitizeSegment(source.getRepoName()));

        try {
            // 克隆也放进 try 内：如果克隆成功但导入阶段抛异常，
            // 清理逻辑必须照样执行，否则残留目录会让下次重试直接失败
            // （JGit: Destination path already exists）。
            String headCommit = repoFetcher.fetch(
                    source.getRepoOwner(),
                    source.getRepoName(),
                    source.getRepoUrl(),
                    source.getBranch() == null ? "main" : source.getBranch(),
                    repoPath);

            int docCount = importDocuments(source, job, repoPath);
            recordSyncedCommit(source, headCommit);
            return docCount;
        } finally {
            if (!properties.getData().isKeepRepo()) {
                FileUtils.deleteQuietly(repoPath);
                log.debug("已清理临时仓库目录: {}", repoPath);
            }
        }
    }

    private int importDocuments(KnowledgeSource source, IngestionJob job, Path repoPath) {
        String pattern = source.getDocPathPattern();
        List<Path> candidates = new ArrayList<>();
        try (var stream = FileUtils.listFiles(repoPath, null)) {
            stream.filter(p -> !p.toString().contains("/.git/"))
                    .filter(p -> !p.toString().contains("\\.git\\"))
                    .filter(p -> PathUtils.matches(pattern, relativize(repoPath, p)))
                    .forEach(candidates::add);
        }

        log.info("文档匹配完成: source={}, pattern={}, 命中 {} 个文件",
                source.getName(), pattern, candidates.size());
        if (candidates.isEmpty()) {
            log.warn("未匹配到任何文档文件，请检查 docPathPattern 是否正确: {}", pattern);
        }

        int written = 0;
        int chunkTotal = 0;
        int failed = 0;
        for (Path file : candidates) {
            String relativePath = relativize(repoPath, file);
            DocParser parser = resolveParser(relativePath);
            if (parser == null) {
                continue;
            }
            try {
                RepoDoc parsed = parser.parse(relativePath, FileUtils.readString(file));
                if (parsed == null || parsed.content().isBlank()) {
                    continue;
                }
                int chunks = saveDocument(source, parsed);
                written++;
                chunkTotal += chunks;
            } catch (Exception e) {
                // 单个文件失败不应中断整个同步，否则一个坏文件会卡住 500 个文件。
                // 但必须带堆栈 —— 只打 e.getMessage() 会把"chunk 一个都没写进去"
                // 这种问题变成无迹可寻（实测踩过：216 个文档入库、chunk 却为 0）。
                log.warn("文档处理失败，跳过: {} —— {}: {}",
                        relativePath, e.getClass().getSimpleName(), e.getMessage(), e);
                failed++;
            }
        }
        jobService.updateProgress(job.getId(), null, written, candidates.size());
        log.info("文档同步完成: source={}, 写入 {} 个文档, 失败 {} 个, 共 {} 个 chunk",
                source.getName(), written, failed, chunkTotal);
        return written;
    }

    /** 落库文档本体，并顺带完成切分与 chunk 写入。 */
    private int saveDocument(KnowledgeSource source, RepoDoc parsed) {
        String contentHash = HashUtils.sha256Hex(parsed.content());

        // 内容未变则跳过：连切分都省掉，这是增量同步省钱的关键
        if (documentMapper.existsUnchanged(source.getId(), DocType.DOC.getValue(),
                parsed.relativePath(), contentHash) > 0) {
            return 0;
        }

        Document document = new Document();
        document.setSourceId(source.getId());
        document.setDocType(DocType.DOC.getValue());
        document.setExternalId(parsed.relativePath());
        document.setPath(parsed.relativePath());
        document.setTitle(parsed.title());
        document.setUrl(buildBlobUrl(source, parsed.relativePath()));
        document.setRawContent(parsed.content());
        document.setContentHash(contentHash);
        document.setVersion(source.getBranch());
        document.setLang("en");
        document.setPublishedAt(null);
        document.setStatus("ACTIVE");
        document.setMetadata(JsonUtils.toJson(parsed.metadata()));
        documentMapper.upsert(document);

        Long documentId = documentMapper.selectIdByNaturalKey(
                source.getId(), DocType.DOC.getValue(), parsed.relativePath());
        if (documentId == null) {
            log.warn("文档写入后未取到 id，跳过切分: {}", parsed.relativePath());
            return 0;
        }

        List<Chunk> chunks = chunker.chunk(parsed.content(), parsed.title(), parsed.breadcrumb());
        int saved = indexWriter.saveChunks(documentId, chunks);
        log.debug("文档已切分: docId={}, 路径={}, chunk 数={}", documentId, parsed.relativePath(), saved);
        return saved;
    }

    private DocParser resolveParser(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        String extension = dot > 0 ? relativePath.substring(dot) : "";
        for (DocParser parser : parsers) {
            if (parser.supports(extension)) {
                return parser;
            }
        }
        return null;
    }

    private void recordSyncedCommit(KnowledgeSource source, String headCommit) {
        KnowledgeSource update = new KnowledgeSource();
        update.setId(source.getId());
        update.setLastSyncedCommit(headCommit);
        update.setLastSyncedAt(Instant.now());
        sourceMapper.updateById(update);
    }

    private static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String buildBlobUrl(KnowledgeSource source, String relativePath) {
        if (source.getRepoUrl() == null) {
            return null;
        }
        String base = source.getRepoUrl().replaceAll("\\.git$", "");
        String branch = source.getBranch() == null ? "main" : source.getBranch();
        return base + "/blob/" + branch + "/" + relativePath;
    }

    private static void requireRepoInfo(KnowledgeSource source) {
        if (source.getRepoUrl() == null || source.getRepoOwner() == null || source.getRepoName() == null) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "知识源缺少仓库信息: " + source.getName());
        }
    }
}
