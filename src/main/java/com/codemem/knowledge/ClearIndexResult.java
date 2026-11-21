package com.codemem.knowledge;

import java.time.Instant;

/** 清空索引的结果：删除了多少篇文档、重置了多少个知识源的统计。 */
public record ClearIndexResult(
        int deletedDocuments,
        int clearedSources,
        Instant clearedAt
) {
}