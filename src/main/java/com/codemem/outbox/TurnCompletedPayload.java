package com.codemem.outbox;

public record TurnCompletedPayload(
        String turnId,
        Long conversationId,
        String learnerId,
        String projectId,
        String userMessage,
        String assistantMessage
) {
}