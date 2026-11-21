package com.codemem.embedding;

import com.codemem.knowledge.ChineseTokenizer;
import com.codemem.knowledge.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConditionalOnProperty(name = "codemem.embedding.provider", havingValue = "hash")
public class HashEmbeddingService implements EmbeddingService {

    private final ChineseTokenizer tokenizer;
    private final int dimensions;

    public HashEmbeddingService(ChineseTokenizer tokenizer, KnowledgeProperties properties) {
        this.tokenizer = tokenizer;
        this.dimensions = properties.embeddingDimensions();
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String tokens = tokenizer.tokenize(text);
        for (String token : tokens.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            add(vector, token, 1.0f);
            addBigrams(vector, token);
        }
        normalize(vector);
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String signature() {
        return "hash:v1:" + dimensions;
    }

    private void addBigrams(float[] vector, String token) {
        if (token.length() < 2) {
            return;
        }
        for (int index = 0; index < token.length() - 1; index++) {
            add(vector, token.substring(index, index + 2), 0.45f);
        }
    }

    private void add(float[] vector, String token, float weight) {
        int index = Math.floorMod(token.toLowerCase(Locale.ROOT).hashCode(), dimensions);
        vector[index] += weight;
    }

    private void normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) {
            return;
        }
        double length = Math.sqrt(norm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / length);
        }
    }
}