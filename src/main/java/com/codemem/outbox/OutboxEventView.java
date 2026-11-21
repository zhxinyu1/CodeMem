package com.codemem.outbox;

import java.time.LocalDateTime;

public record OutboxEventView(
        String id,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {
    public static OutboxEventView from(OutboxEvent event) {
        return new OutboxEventView(
                event.getId(),
                event.getEventType(),
                event.getStatus(),
                event.getRetryCount(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getProcessedAt()
        );
    }
}