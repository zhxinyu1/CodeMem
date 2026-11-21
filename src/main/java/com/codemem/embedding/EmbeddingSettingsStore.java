package com.codemem.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingSettingsStore {

    private volatile String baseUrl;
    private volatile String model;
    private final int dimensions;
    private volatile String apiKey;

    public EmbeddingSettingsStore(
            @Value("${codemem.embedding.api-key:}") String initialApiKey,
            @Value("${codemem.embedding.base-url:https://api.openai.com}") String baseUrl,
            @Value("${codemem.embedding.model:text-embedding-3-small}") String model,
            @Value("${codemem.embedding.dimensions:1024}") int dimensions) {
        this.apiKey = initialApiKey == null ? "" : initialApiKey.trim();
        this.baseUrl = baseUrl;
        this.model = model;
        this.dimensions = dimensions;
    }

    public String apiKey() {
        return apiKey;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com"
                : baseUrl.trim();
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model.trim();
    }

    public int dimensions() {
        return dimensions;
    }

    /**
     * 向量签名包含服务地址与模型：任一变化都必须重建索引，否则新旧向量不可比。
     */
    public String signature() {
        return "openai-compatible:" + baseUrl + ":" + model + ":" + dimensions;
    }
}