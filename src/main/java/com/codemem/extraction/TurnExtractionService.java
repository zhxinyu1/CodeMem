package com.codemem.extraction;

import com.codemem.memory.longterm.MemoryItemService;
import com.codemem.outbox.OutboxEvent;
import com.codemem.outbox.TurnCompletedPayload;
import com.codemem.state.ProjectState;
import com.codemem.state.StatePatch;
import com.codemem.state.StateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TurnExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TurnExtractionService.class);

    private final TurnExtractor extractor;
    private final TurnExtractionRepository repository;
    private final StateService stateService;
    private final MemoryItemService memoryItemService;
    private final ObjectMapper objectMapper;
    private final Counter appliedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;

    public TurnExtractionService(TurnExtractor extractor,
                                 TurnExtractionRepository repository,
                                 StateService stateService,
                                 MemoryItemService memoryItemService,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.extractor = extractor;
        this.repository = repository;
        this.stateService = stateService;
        this.memoryItemService = memoryItemService;
        this.objectMapper = objectMapper;
        this.appliedCounter = Counter.builder("codemem.extraction.applied").register(meterRegistry);
        this.skippedCounter = Counter.builder("codemem.extraction.skipped").register(meterRegistry);
        this.failedCounter = Counter.builder("codemem.extraction.failed").register(meterRegistry);
    }

    public void process(OutboxEvent event) {
        TurnCompletedPayload payload = readPayload(event);
        TurnExtractionResult result = extractor.extract(payload);
        String memoryJson = writeJson(result.memoryCandidates());
        persistLongTermMemories(payload, result);

        if (result.ops().isEmpty()) {
            skippedCounter.increment();
            saveExtraction(payload, result.summary(), memoryJson, null, "SKIPPED");
            return;
        }

        ProjectState current = stateService.getOrCreate(payload.learnerId(), payload.projectId());
        StatePatch patch = new StatePatch(current.version(), result.ops(), result.reason());
        String patchJson = writeJson(patch);
        TurnExtraction extraction = saveExtraction(payload, result.summary(), memoryJson, patchJson, "EXTRACTED");

        try {
            stateService.applyPatch(payload.learnerId(), payload.projectId(), patch, payload.turnId(), false);
            extraction.setStatus("APPLIED");
            appliedCounter.increment();
            repository.save(extraction);
        } catch (RuntimeException ex) {
            extraction.setStatus("FAILED");
            failedCounter.increment();
            repository.save(extraction);
            log.warn("Automatic state extraction failed for turn {}: {}", payload.turnId(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * 长期记忆写入失败不阻塞状态补丁与 Outbox，只降级记录。
     */
    private void persistLongTermMemories(TurnCompletedPayload payload, TurnExtractionResult result) {
        try {
            // 对话摘要属于短期上下文，不进长期记忆，避免每轮都写入一条噪声
            java.util.List<String> semantic = result.memoryCandidates().stream()
                    .filter(candidate -> candidate != null && !candidate.startsWith("对话摘要"))
                    .toList();
            memoryItemService.recordCandidates(
                    payload.learnerId(),
                    payload.projectId(),
                    semantic,
                    "EXTRACTION",
                    payload.turnId()
            );
        } catch (RuntimeException ex) {
            log.warn("Unable to persist long-term memories for turn {}: {}", payload.turnId(), ex.getMessage());
        }
    }

    private TurnCompletedPayload readPayload(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), TurnCompletedPayload.class);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to parse outbox payload " + event.getId(), ex);
        }
    }

    private TurnExtraction saveExtraction(TurnCompletedPayload payload,
                                          String summary,
                                          String memoryJson,
                                          String patchJson,
                                          String status) {
        repository.findByTurnId(payload.turnId()).ifPresent(existing -> {
            repository.delete(existing);
            repository.flush();
        });
        TurnExtraction extraction = new TurnExtraction(
                payload.turnId(),
                payload.learnerId(),
                payload.projectId(),
                summary,
                memoryJson,
                patchJson,
                status,
                java.time.LocalDateTime.now()
        );
        return repository.save(extraction);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize extraction result", ex);
        }
    }
}