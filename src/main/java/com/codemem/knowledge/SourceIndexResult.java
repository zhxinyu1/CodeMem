package com.codemem.knowledge;

/** 全量重建时单个知识源的结果，ok 表示该源扫描到了笔记且没有失败。 */
public record SourceIndexResult(
        String id,
        String name,
        boolean ok,
        IndexResult result
) {
}