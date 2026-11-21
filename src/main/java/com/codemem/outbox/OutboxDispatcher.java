package com.codemem.outbox;

import com.codemem.extraction.TurnExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxService outboxService;
    private final TurnExtractionService extractionService;

    public OutboxDispatcher(OutboxService outboxService,
                            TurnExtractionService extractionService) {
        this.outboxService = outboxService;
        this.extractionService = extractionService;
    }

    @Scheduled(fixedDelayString = "${codemem.outbox.poll-interval-ms:2000}")
    public void poll() {
        outboxService.recoverStaleProcessing();

        List<OutboxEvent> pending = outboxService.findPending(5);
        for (OutboxEvent event : pending) {
            if (!outboxService.claim(event.getId())) {
                continue;
            }
            try {
                extractionService.process(event);
                outboxService.markDone(event.getId());
            } catch (Exception ex) {
                log.warn("Outbox event {} failed: {}", event.getId(), ex.getMessage());
                outboxService.markFailure(event.getId(), ex);
            }
        }
    }
}