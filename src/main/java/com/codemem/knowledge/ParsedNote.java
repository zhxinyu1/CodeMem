package com.codemem.knowledge;

import java.time.Instant;
import java.util.List;

public record ParsedNote(
        String path,
        String title,
        String subject,
        String noteStatus,
        List<String> tags,
        List<String> links,
        String contentHash,
        Instant modifiedAt,
        List<NoteChunk> chunks
) {
}