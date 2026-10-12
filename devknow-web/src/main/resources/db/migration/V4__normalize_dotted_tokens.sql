ALTER TABLE document_chunk DROP COLUMN content_tsv;

ALTER TABLE document_chunk
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english',
                                  regexp_replace(coalesce(heading_path, ''), '\.', '. ', 'g')), 'A') ||
            setweight(to_tsvector('english',
                                  regexp_replace(coalesce(content, ''), '\.', '. ', 'g')), 'B')
            ) STORED;

COMMENT ON COLUMN document_chunk.content_tsv IS '全文检索向量（生成列）。heading_path 权重 A、content 权重 B，两列均经 coalesce 且均做点号归一化 —— PostgreSQL 会把整条带点字符串当成单个 host token，不拆开则裸类名/配置项片段搜不到；同时 tsvector 的 || 遇 NULL 会整体传播为 NULL';

CREATE INDEX idx_chunk_content_tsv
    ON document_chunk USING gin(content_tsv);

COMMENT ON INDEX idx_chunk_content_tsv IS '全文检索 GIN 索引，支撑 content_tsv @@ plainto_tsquery 的匹配';