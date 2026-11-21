package com.codemem.knowledge;

import java.time.Instant;

public record IndexStatus(
        boolean available,
        long documents,
        long chunks,
        Instant lastIndexedAt,
        String embeddingSignature,
        boolean needsReindex
) {
}