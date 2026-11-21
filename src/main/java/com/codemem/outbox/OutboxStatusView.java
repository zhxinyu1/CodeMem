package com.codemem.outbox;

public record OutboxStatusView(
        long pending,
        long processing,
        long done,
        long dead,
        long total
) {
}