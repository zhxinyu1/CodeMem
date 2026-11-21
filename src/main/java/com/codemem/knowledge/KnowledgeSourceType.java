package com.codemem.knowledge;

/**
 * Supported note library kinds. Adding a new kind means adding a scanner branch
 * (see {@link ObsidianScanner}) plus a value here; the rest of the pipeline is shared.
 */
public enum KnowledgeSourceType {

    /** Obsidian vault: markdown with frontmatter, [[wikilinks]] and #tags. */
    OBSIDIAN("obsidian"),

    /** Plain markdown/text folder: any directory of .md/.markdown/.txt files. */
    MARKDOWN("markdown");

    private final String wireName;

    KnowledgeSourceType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static KnowledgeSourceType parse(String value) {
        if (value == null || value.isBlank()) {
            return OBSIDIAN;
        }
        String normalized = value.trim().toLowerCase();
        for (KnowledgeSourceType type : values()) {
            if (type.wireName.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported knowledge source type: " + value
                + " (expected obsidian or markdown)");
    }
}