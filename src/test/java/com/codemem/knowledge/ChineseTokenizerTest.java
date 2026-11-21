package com.codemem.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChineseTokenizerTest {

    @Test
    void tokenizesChineseQuery() {
        ChineseTokenizer tokenizer = new ChineseTokenizer();
        String tokens = tokenizer.tokenize("红黑树 左旋");
        System.out.println("TOKENS=[" + tokens + "]");
        assertFalse(tokens.isBlank());
    }
}
