package com.codemem.knowledge;

import java.time.Instant;

public record IndexResult(
        int scannedNotes,
        int indexedNotes,
        int skippedNotes,
        int failedNotes,
        int deletedNotes,
        int chunks,
        long durationMs,
        Instant finishedAt,
        String failureReason
) {
}