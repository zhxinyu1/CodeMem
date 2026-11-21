package com.codemem.embedding;

import java.util.List;

public interface EmbeddingService {

    float[] embed(String text);

    int dimensions();

    String signature();

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}