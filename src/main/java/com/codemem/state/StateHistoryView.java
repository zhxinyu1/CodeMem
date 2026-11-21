package com.codemem.state;

import java.time.LocalDateTime;

public record StateHistoryView(
        Integer version,
        String summary,
        LocalDateTime createdAt
) {
}