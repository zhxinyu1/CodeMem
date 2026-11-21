package com.codemem.conversation;

import java.time.LocalDateTime;

public record TurnContext(
        Conversation conversation,
        ChatTurn turn,
        ChatMessage userMessage,
        LocalDateTime startedAt
) {
}