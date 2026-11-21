package com.codemem.embedding;

import com.codemem.knowledge.KnowledgeProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "codemem.embedding.provider", havingValue = "spring-ai")
public class SpringAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final String signature;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel,
                                    KnowledgeProperties properties,
                                    @Value("${spring.ai.openai.embedding.options.model:unknown}") String model) {
        this.embeddingModel = embeddingModel;
        this.dimensions = properties.embeddingDimensions();
        this.signature = "openai-compatible:" + model + ":" + dimensions;
    }

    @Override
    public float[] embed(String text) {
        return fitToDimensions(embeddingModel.embed(text));
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return embeddingModel.embed(texts).stream()
                .map(this::fitToDimensions)
                .toList();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String signature() {
        return signature;
    }

    private float[] fitToDimensions(float[] raw) {
        if (raw == null || raw.length == 0) {
            return new float[dimensions];
        }
        float[] result = new float[dimensions];
        System.arraycopy(raw, 0, result, 0, Math.min(raw.length, dimensions));
        normalize(result);
        return result;
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