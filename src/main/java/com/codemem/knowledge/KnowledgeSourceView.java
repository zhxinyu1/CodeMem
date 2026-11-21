package com.codemem.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * A knowledge source plus whether its directory is currently reachable, so the
 * settings page can warn before the user switches to a moved/deleted library.
 */
public record KnowledgeSourceView(
        String id,
        String name,
        String type,
        String rootPath,
        boolean active,
        boolean directoryExists,
        int docCount,
        int chunkCount,
        LocalDateTime lastIndexedAt,
        LocalDateTime createdAt
) {
    public static KnowledgeSourceView from(KnowledgeSource source) {
        boolean exists;
        try {
            exists = Files.isDirectory(Path.of(source.getRootPath()));
        } catch (Exception ex) {
            exists = false;
        }
        return new KnowledgeSourceView(
                source.getId(),
                source.getName(),
                source.getType(),
                source.getRootPath(),
                source.isActive(),
                exists,
                source.getDocCount(),
                source.getChunkCount(),
                source.getLastIndexedAt(),
                source.getCreatedAt()
        );
    }
}