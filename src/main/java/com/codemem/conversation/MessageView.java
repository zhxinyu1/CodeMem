package com.codemem.conversation;

import java.time.LocalDateTime;

public record MessageView(
        Long id,
        String role,
        String content,
        String turnId,
        LocalDateTime createdAt
) {
    public static MessageView from(ChatMessage message) {
        return new MessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getTurnId(),
                message.getCreatedAt()
        );
    }
}