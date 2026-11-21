package com.codemem.knowledge;

public record RetrievedChunk(
        String chunkId,
        String path,
        String title,
        String headingPath,
        String content,
        double score
) {
}