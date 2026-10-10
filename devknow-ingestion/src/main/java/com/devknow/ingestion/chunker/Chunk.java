package com.devknow.ingestion.chunker;

/**
 * 切分产物，尚未持久化。
 *
 * @param chunkIndex   文档内顺序，从 0 开始
 * @param chunkType    TEXT | CODE | TABLE | LIST
 * @param headingPath  标题路径，如 "Spring Boot > Data > Redis > 配置"
 * @param headingLevel 所属标题层级
 * @param content      正文
 * @param contentTokens 估算 token 数
 */
public record Chunk(
        int chunkIndex,
        String chunkType,
        String headingPath,
        short headingLevel,
        String content,
        int contentTokens
) {
}
