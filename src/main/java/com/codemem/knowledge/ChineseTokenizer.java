package com.codemem.knowledge;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ChineseTokenizer {

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public String tokenize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH);
        StringBuilder builder = new StringBuilder();
        for (SegToken token : tokens) {
            String word = token.word == null ? "" : token.word.trim();
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}