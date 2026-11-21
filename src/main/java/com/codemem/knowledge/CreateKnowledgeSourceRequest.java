package com.codemem.knowledge;

/**
 * Create/switch payload for a knowledge source. {@code activate} switches the
 * index to the new source, {@code reindex} rebuilds right away (only meaningful
 * together with activate or on an already active source).
 */
public record CreateKnowledgeSourceRequest(
        String name,
        String type,
        String rootPath,
        Boolean activate,
        Boolean reindex
) {
    public boolean activateOrDefault() {
        return activate == null || activate;
    }

    public boolean reindexOrDefault() {
        return reindex == null || reindex;
    }
}