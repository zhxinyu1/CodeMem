package com.codemem.embedding;

public record EmbeddingSettingsView(
        String baseUrl,
        String model,
        int dimensions,
        boolean keyConfigured
) {
    public static EmbeddingSettingsView from(EmbeddingSettingsStore settings) {
        return new EmbeddingSettingsView(
                settings.baseUrl(),
                settings.model(),
                settings.dimensions(),
                settings.isConfigured()
        );
    }
}