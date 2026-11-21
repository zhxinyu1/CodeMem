package com.codemem.embedding;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 运行时更新 embedding 配置。所有字段均可为空：
 * 只改 Key 时不动地址与模型，只改地址/模型时沿用已保存的 Key。
 */
public record UpdateEmbeddingSettingsRequest(
        @Size(max = 500, message = "apiKey 过长")
        String apiKey,
        @Size(max = 300, message = "baseUrl 过长")
        @Pattern(regexp = "^https?://\\S+$", message = "baseUrl 必须以 http:// 或 https:// 开头且不能包含空格")
        String baseUrl,
        @Size(max = 120, message = "model 过长")
        @Pattern(regexp = "^\\S+$", message = "model 不能包含空格")
        String model
) {
}