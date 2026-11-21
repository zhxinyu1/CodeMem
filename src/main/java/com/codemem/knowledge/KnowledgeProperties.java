package com.codemem.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemem.knowledge")
public record KnowledgeProperties(
        String root,
        int chunkMaxChars,
        int retrievalTopK,
        int embeddingDimensions
) {
    public KnowledgeProperties {
        if (root == null || root.isBlank()) {
            root = "E:/Note/知识库";
        }
        if (chunkMaxChars <= 0) {
            chunkMaxChars = 1200;
        }
        if (retrievalTopK <= 0) {
            retrievalTopK = 4;
        }
        if (embeddingDimensions <= 0) {
            embeddingDimensions = 1024;
        }
    }
}