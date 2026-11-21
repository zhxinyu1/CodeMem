package com.codemem.knowledge;

import java.util.List;

public record NoteChunk(
        String headingPath,
        String content,
        int tokenCount,
        String contentHash,
        List<String> links
) {
}