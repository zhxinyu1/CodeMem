package com.codemem.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final int maxRetries;
    private final long retryBackoffSeconds;

    public OutboxService(OutboxRepository outboxRepository,
                         ObjectMapper objectMapper,
                         @Value("${codemem.outbox.max-retries:3}") int maxRetries,
                         @Value("${codemem.outbox.retry-backoff-seconds:30}") long retryBackoffSeconds) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoffSeconds = Math.max(1, retryBackoffSeconds);
    }

    public void enqueueTurnCompleted(TurnCompletedPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            LocalDateTime now = LocalDateTime.now();
            OutboxEvent event = new OutboxEvent(
                    UUID.randomUUID().toString(),
                    "TURN",
                    payload.turnId(),
                    "TURN_COMPLETED",
                    json,
                    "PENDING",
                    0,
                    null,
                    null,
                    null,
                    now
            );
            outboxRepository.save(event);
        } catch (Exception ex) {
            // Outbox is an enhancement; a persistence failure must not break the chat response.
            log.warn("Unable to enqueue outbox event for turn {}: {}", payload.turnId(), ex.getMessage());
        }
    }

    public List<OutboxEvent> findPending(int limit) {
        return outboxRepository.findPending(LocalDateTime.now(), PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional
    public boolean claim(String id) {
        return outboxRepository.findById(id)
                .map(event -> {
                    if (!"PENDING".equals(event.getStatus())) {
                        return false;
                    }
                    event.setClaimedAt(LocalDateTime.now());
                    event.setStatus("PROCESSING");
                    outboxRepository.save(event);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void markDone(String id) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.setStatus("DONE");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextRetryAt(null);
            event.setLastError(null);
            event.setClaimedAt(null);
            outboxRepository.save(event);
        });
    }

    @Transactional
    public void markFailure(String id, Throwable error) {
        outboxRepository.findById(id).ifPresent(event -> {
            int nextRetry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            event.setRetryCount(nextRetry);
            event.setLastError(truncate(error == null ? "unknown error" : error.getMessage(), 500));
            event.setClaimedAt(null);
            if (nextRetry >= maxRetries) {
                event.setStatus("DEAD");
                event.setNextRetryAt(null);
            } else {
                event.setStatus("PENDING");
                event.setNextRetryAt(LocalDateTime.now().plusSeconds(retryBackoffSeconds * nextRetry));
            }
            outboxRepository.save(event);
        });
    }

    @Transactional
    public void recoverStaleProcessing() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<OutboxEvent> stale = outboxRepository.findStaleProcessing(cutoff);
        for (OutboxEvent event : stale) {
            event.setStatus("PENDING");
            event.setNextRetryAt(null);
            event.setClaimedAt(null);
            outboxRepository.save(event);
        }
        if (!stale.isEmpty()) {
            log.warn("Recovered {} stale PROCESSING outbox events", stale.size());
        }
    }

    @Transactional
    public boolean retry(String id) {
        return outboxRepository.findById(id)
                .map(event -> {
                    event.setStatus("PENDING");
                    event.setRetryCount(0);
                    event.setNextRetryAt(null);
                    event.setLastError(null);
                    event.setProcessedAt(null);
                    outboxRepository.save(event);
                    return true;
                })
                .orElse(false);
    }

    public OutboxStatusView status() {
        long pending = outboxRepository.countByStatus("PENDING");
        long processing = outboxRepository.countByStatus("PROCESSING");
        long done = outboxRepository.countByStatus("DONE");
        long dead = outboxRepository.countByStatus("DEAD");
        return new OutboxStatusView(pending, processing, done, dead, outboxRepository.count());
    }

    public List<OutboxEventView> recent() {
        return outboxRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(OutboxEventView::from)
                .toList();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}