package com.codemem.embedding;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings/embedding")
public class EmbeddingSettingsController {

    private final EmbeddingSettingsStore settings;
    private final EmbeddingService embeddingService;

    public EmbeddingSettingsController(EmbeddingSettingsStore settings,
                                       EmbeddingService embeddingService) {
        this.settings = settings;
        this.embeddingService = embeddingService;
    }

    @GetMapping
    public EmbeddingSettingsView get() {
        return EmbeddingSettingsView.from(settings);
    }

    @PostMapping
    public EmbeddingSettingsView update(@Valid @RequestBody UpdateEmbeddingSettingsRequest request) {
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            settings.setApiKey(request.apiKey());
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            URI uri;
            try {
                uri = URI.create(request.baseUrl().trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Base URL 格式不正确，示例：https://api.openai.com/v1");
            }
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL 必须是带主机名的 http:// 或 https:// 地址");
            }
            settings.setBaseUrl(request.baseUrl());
        }
        if (request.model() != null && !request.model().isBlank()) {
            settings.setModel(request.model());
        }
        return EmbeddingSettingsView.from(settings);
    }

    /**
     * 用当前配置发一次最小请求，让用户在重建索引前就能知道地址/Key 是否可用。
     */
    @PostMapping("/test")
    public Map<String, Object> test() {
        long startedAt = System.nanoTime();
        try {
            embeddingService.embed("码忆连接测试");
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            return Map.of(
                    "ok", true,
                    "latencyMs", latencyMs,
                    "message", "连接成功，向量服务可用",
                    "signature", embeddingService.signature()
            );
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            return Map.of(
                    "ok", false,
                    "latencyMs", latencyMs,
                    "message", ex.getMessage() == null ? "连接失败" : ex.getMessage(),
                    "signature", embeddingService.signature()
            );
        }
    }
}