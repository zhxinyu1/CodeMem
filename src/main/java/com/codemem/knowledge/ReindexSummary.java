package com.codemem.knowledge;

import java.time.Instant;
import java.util.List;

/**
 * 顶部「重建索引」的汇总结果：一次重建下面所有知识源，并保留每个源的明细。
 */
public record ReindexSummary(
        int sources,
        int failedSources,
        int scannedNotes,
        int indexedNotes,
        int skippedNotes,
        int failedNotes,
        int deletedNotes,
        int chunks,
        long durationMs,
        Instant finishedAt,
        String failureReason,
        List<SourceIndexResult> details
) {
}