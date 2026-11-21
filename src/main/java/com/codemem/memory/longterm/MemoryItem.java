package com.codemem.memory.longterm;

import java.time.Instant;

/**
 * 长期记忆条目：与知识库切片严格区分，来源是用户自述或抽取候选。
 */
public record MemoryItem(
        String id,
        String learnerId,
        String projectId,
        String type,
        String scope,
        String content,
        String summary,
        String sourceType,
        String sourceId,
        String topic,
        double importance,
        double confidence,
        String status,
        String contentHash,
        Instant validFrom,
        Instant validTo,
        Instant lastAccessedAt,
        int accessCount,
        double score
) {
}