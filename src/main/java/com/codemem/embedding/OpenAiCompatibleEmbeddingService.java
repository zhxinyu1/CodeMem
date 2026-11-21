package com.codemem.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "codemem.embedding.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final EmbeddingSettingsStore settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final int dimensions;

    public OpenAiCompatibleEmbeddingService(EmbeddingSettingsStore settings,
                                            ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.dimensions = settings.dimensions();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = embedAll(List.of(text == null ? "" : text));
        return vectors.isEmpty() ? new float[dimensions] : vectors.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!settings.isConfigured()) {
            throw new IllegalStateException(
                    "embedding API key is not configured; open /settings.html or set CODEMEM_EMBEDDING_API_KEY");
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", settings.model());
            requestBody.put("dimensions", dimensions);
            ArrayNode input = requestBody.putArray("input");
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl(settings.baseUrl())))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(describeFailure(response.statusCode(), response.body()));
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("embedding API returned an unexpected data array");
            }

            List<float[]> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) {
                    throw new IllegalStateException("embedding API returned an empty vector");
                }
                float[] raw = new float[embedding.size()];
                for (int index = 0; index < embedding.size(); index++) {
                    raw[index] = (float) embedding.get(index).asDouble();
                }
                vectors.add(fitToDimensions(raw));
            }
            return vectors;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("embedding API call failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String signature() {
        return settings.signature();
    }

    /**
     * 把服务商返回的错误提炼出来：401 时能直接看到 invalid_api_key、insufficient_quota 等具体原因，
     * 而不是只有一句 HTTP 401。响应体截断到 200 字符，避免刷屏。
     */
    private String describeFailure(int statusCode, String body) {
        String base = "embedding API returned HTTP " + statusCode;
        if (body == null || body.isBlank()) {
            return base;
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String code = error.path("code").asText("");
            String message = error.path("message").asText("");
            if (message.isBlank()) {
                message = body.length() > 200 ? body.substring(0, 200) : body;
            } else if (message.length() > 200) {
                message = message.substring(0, 200);
            }
            return base
                    + (code.isBlank() ? "" : " (" + code + ")")
                    + (message.isBlank() ? "" : ": " + message);
        } catch (Exception ignored) {
            return base;
        }
    }

    private String embeddingsUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        return base.endsWith("/v1") ? base + "/embeddings" : base + "/v1/embeddings";
    }

    private float[] fitToDimensions(float[] raw) {
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