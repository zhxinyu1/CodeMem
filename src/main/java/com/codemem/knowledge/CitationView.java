package com.codemem.knowledge;

public record CitationView(
        String sourceType,
        String path,
        String heading,
        String snippet,
        double score
) {
    public static CitationView from(RetrievedChunk chunk) {
        String snippet = chunk.content() == null
                ? ""
                : chunk.content().replaceAll("\\s+", " ").trim();
        if (snippet.length() > 180) {
            snippet = snippet.substring(0, 180) + "...";
        }
        return new CitationView(
                "NOTE",
                chunk.path(),
                chunk.headingPath() == null ? "" : chunk.headingPath(),
                snippet,
                chunk.score()
        );
    }
}