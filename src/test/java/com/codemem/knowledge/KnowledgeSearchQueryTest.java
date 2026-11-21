package com.codemem.knowledge;

import org.junit.jupiter.api.Test;

class KnowledgeSearchQueryTest {

    @Test
    void buildsChineseTsQuery() {
        ChineseTokenizer tokenizer = new ChineseTokenizer();
KnowledgeSearchService service = new KnowledgeSearchService(
                null, null, tokenizer, new KnowledgeProperties("E:/Note/知识库", 1200, 6, 1024), null,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null);
        String tokens = tokenizer.tokenize("红黑树 左旋");
        String tsQuery = service.toTsQuery(tokens);
        System.out.print("TOKENS_HEX=");
        tokens.codePoints().forEach(cp -> System.out.printf("U+%04X ", cp));
        System.out.println();
        System.out.print("TSQUERY_HEX=");
        tsQuery.codePoints().forEach(cp -> System.out.printf("U+%04X ", cp));
        System.out.println();
    }
}
