package com.codemem.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        Long conversationId,
        String learnerId,
        @Size(max = 64, message = "projectId 不能超过 64 字符")
        String projectId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 不能超过 4000 字符")
        String message
) {
}