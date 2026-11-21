package com.codemem.conversation;

import java.time.LocalDateTime;

public record ConversationView(
        Long id,
        String learnerId,
        Long groupId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ConversationView from(Conversation conversation) {
        return new ConversationView(
                conversation.getId(),
                conversation.getLearnerId(),
                conversation.getGroupId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}