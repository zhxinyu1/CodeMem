package com.codemem.chat;

import com.codemem.ai.ChatEngine;
import com.codemem.ai.ChatModelRequest;
import com.codemem.conversation.ChatMessage;
import com.codemem.conversation.ConversationService;
import com.codemem.conversation.TurnContext;
import com.codemem.knowledge.CitationView;
import com.codemem.knowledge.CitationValidator;
import com.codemem.knowledge.KnowledgeSearchService;
import com.codemem.knowledge.RetrievedChunk;
import com.codemem.memory.working.WorkingMemoryService;
import com.codemem.memory.working.WorkingMessage;
import com.codemem.memory.longterm.MemoryItemService;
import com.codemem.memory.longterm.MemoryItemView;
import com.codemem.outbox.OutboxService;
import com.codemem.outbox.TurnCompletedPayload;
import com.codemem.profile.LearnerProfile;
import com.codemem.profile.ProfileService;
import com.codemem.state.ProjectState;
import com.codemem.state.StateService;
import com.codemem.workflow.AgentStepView;
import com.codemem.workflow.AgentTokens;
import com.codemem.workflow.Router;
import com.codemem.workflow.RoutingDecision;
import com.codemem.workflow.WorkflowMode;
import com.codemem.workflow.WorkflowOrchestrator;
import com.codemem.workflow.WorkflowRequest;
import com.codemem.workflow.WorkflowResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;

    private final ConversationService conversationService;
    private final WorkingMemoryService workingMemory;
    private final ProfileService profileService;
    private final ChatPromptBuilder promptBuilder;
    private final ChatEngine chatEngine;
    private final KnowledgeSearchService knowledgeSearchService;
    private final OutboxService outboxService;
    private final Router router;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final StateService stateService;
    private final MemoryItemService memoryItemService;
    private final CitationValidator citationValidator;
    private final Timer chatLatency;
    private final Counter chatErrors;
    private final boolean mock;
    private final boolean memoryEnabled;
    private final boolean ragEnabled;
    private final boolean stateEnabled;
    private final boolean dualEnabled;

    public ChatService(ConversationService conversationService,
                       WorkingMemoryService workingMemory,
                       ProfileService profileService,
                       ChatPromptBuilder promptBuilder,
                       ChatEngine chatEngine,
                       KnowledgeSearchService knowledgeSearchService,
                       OutboxService outboxService,
                       Router router,
                       WorkflowOrchestrator workflowOrchestrator,
                       StateService stateService,
                       MemoryItemService memoryItemService,
                       CitationValidator citationValidator,
                       MeterRegistry meterRegistry,
                       @Value("${codemem.ai.mock:true}") boolean mock,
                       @Value("${codemem.features.memory:true}") boolean memoryEnabled,
                       @Value("${codemem.features.rag:true}") boolean ragEnabled,
                       @Value("${codemem.features.state:true}") boolean stateEnabled,
                       @Value("${codemem.features.dual:true}") boolean dualEnabled) {
        this.conversationService = conversationService;
        this.workingMemory = workingMemory;
        this.profileService = profileService;
        this.promptBuilder = promptBuilder;
        this.chatEngine = chatEngine;
        this.knowledgeSearchService = knowledgeSearchService;
        this.outboxService = outboxService;
        this.router = router;
        this.workflowOrchestrator = workflowOrchestrator;
        this.stateService = stateService;
        this.memoryItemService = memoryItemService;
        this.citationValidator = citationValidator;
        this.chatLatency = Timer.builder("codemem.chat.latency").register(meterRegistry);
        this.chatErrors = Counter.builder("codemem.chat.errors").register(meterRegistry);
        this.mock = mock;
        this.memoryEnabled = memoryEnabled;
        this.ragEnabled = ragEnabled;
        this.stateEnabled = stateEnabled;
        this.dualEnabled = dualEnabled;
    }

    public ChatResponse chat(ChatRequest request) {
        String message = normalizeMessage(request.message());
        TurnContext context = conversationService.beginTurn(
                request.learnerId(),
                request.conversationId(),
                message
        );
        long startedAt = System.nanoTime();

        try {
            List<WorkingMessage> recent = memoryEnabled
                    ? workingMemory.recent(context.conversation().getId())
                    : List.of();
            LearnerProfile profile = profileService.getDefault();
            List<RetrievedChunk> knowledge = ragEnabled
                    ? knowledgeSearchService.search(message, null)
                    : List.of();
            List<RetrievedChunk> planning = ragEnabled
                    ? knowledgeSearchService.search("学习规划 路线 目标 阶段 计划 安排", 3)
                    : List.of();
            List<CitationView> citations = knowledge.stream().map(CitationView::from).toList();
            List<MemoryItemView> memories = searchLongTermMemories(request.learnerId(), projectIdOf(request), message);
            RoutingDecision decision = routeDecision(message, recent);
            List<MemoryItemView> instructions = memoryItemService.listInstructions(
                    request.learnerId(), projectIdOf(request));
            ChatModelRequest modelRequest = promptBuilder.build(request, recent, profile, knowledge, memories, instructions, planning);

            String answer;
            if (decision.mode() == WorkflowMode.DUAL) {
                answer = runDualWorkflow(context, projectIdOf(request), message, decision, recent, profile,
                        knowledge, citations, memories, instructions, null);
            } else {
                answer = chatEngine.complete(modelRequest);
                if (answer == null) {
                    answer = "";
                }
                workflowOrchestrator.recordSingleRun(
                        context.turn().getTraceId(),
                        context.turn().getId(),
                        elapsedMillis(startedAt),
                        AgentTokens.estimate(modelRequest.systemPrompt()) + AgentTokens.estimate(modelRequest.userPrompt()),
                        AgentTokens.estimate(answer),
                        "DONE"
                );
            }

            answer = checkCitations(answer, context.turn().getTraceId());
            long latencyMs = elapsedMillis(startedAt);
            chatLatency.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            ChatMessage assistant = conversationService.completeTurn(context, answer, latencyMs);
            remember(context.conversation().getId(), message, answer);
            enqueueTurn(context, projectIdOf(request), message, answer);

            return new ChatResponse(
                    context.conversation().getId(),
                    context.turn().getId(),
                    context.turn().getTraceId(),
                    context.userMessage().getId(),
                    assistant.getId(),
                    answer,
                    citations,
                    memories,
                    recent.size(),
                    latencyMs,
                    mock
            );
        } catch (RuntimeException ex) {
            chatErrors.increment();
            conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
            throw ex;
        }
    }

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        String message;
        TurnContext context;
        try {
            message = normalizeMessage(request.message());
            context = conversationService.beginTurn(
                    request.learnerId(),
                    request.conversationId(),
                    message
            );
        } catch (RuntimeException ex) {
            sendError(emitter, "BAD_REQUEST", safeMessage(ex));
            emitter.complete();
            return emitter;
        }

        long startedAt = System.nanoTime();
        Long conversationId = context.conversation().getId();

        sendEvent(emitter, "meta", Map.of(
                "conversationId", conversationId,
                "turnId", context.turn().getId(),
                "traceId", context.turn().getTraceId(),
                "mock", mock
        ));

        List<WorkingMessage> recent;
        List<RetrievedChunk> knowledge;
        List<CitationView> citations;
        ChatModelRequest modelRequest;
        LearnerProfile profile;
        RoutingDecision decision;
        List<MemoryItemView> memories;
        List<MemoryItemView> instructions;
        List<RetrievedChunk> planning;
        try {
            recent = memoryEnabled ? workingMemory.recent(conversationId) : List.of();
            profile = profileService.getDefault();
            knowledge = ragEnabled ? knowledgeSearchService.search(message, null) : List.of();
            planning = ragEnabled ? knowledgeSearchService.search("学习规划 路线 目标 阶段 计划 安排", 3) : List.of();
            citations = knowledge.stream().map(CitationView::from).toList();
            memories = searchLongTermMemories(request.learnerId(), projectIdOf(request), message);
            decision = routeDecision(message, recent);
            instructions = memoryItemService.listInstructions(request.learnerId(), projectIdOf(request));
            modelRequest = promptBuilder.build(request, recent, profile, knowledge, memories, instructions, planning);
        } catch (RuntimeException ex) {
            conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
            sendError(emitter, "CONTEXT_ERROR", safeMessage(ex));
            emitter.complete();
            return emitter;
        }

        for (CitationView citation : citations) {
            sendEvent(emitter, "citation", citation);
        }
        for (MemoryItemView memory : memories) {
            sendEvent(emitter, "memory", memory);
        }

        StringBuilder answer = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        boolean dualMode = decision.mode() == WorkflowMode.DUAL;

        try {
            Flux<String> tokenStream = dualMode
                    ? dualStream(emitter, context, projectIdOf(request), message, decision, recent, profile, knowledge, citations, memories, instructions)
                    : chatEngine.stream(modelRequest);
            Disposable subscription = tokenStream.subscribe(
                    chunk -> {
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        answer.append(chunk);
                        sendEvent(emitter, "token", Map.of("delta", chunk));
                    },
                    error -> {
                        if (finished.compareAndSet(false, true)) {
                            chatErrors.increment();
                            long latencyMs = elapsedMillis(startedAt);
                            conversationService.failTurn(context.turn().getId(), latencyMs);
                            log.warn("Streaming chat failed, traceId={}", context.turn().getTraceId(), error);
                            sendError(emitter, "CHAT_ERROR", safeMessage(error));
                            emitter.complete();
                        }
                    },
                    () -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        long latencyMs = elapsedMillis(startedAt);
                        try {
                            String fullAnswer = answer.toString();
                            if (fullAnswer.isBlank()) {
                                // 思考模式可能把预算全花在推理上导致内容为空，补一次非流式兜底
                                log.warn("Streaming returned blank content, falling back to non-streaming complete, traceId={}",
                                        context.turn().getTraceId());
                                String fallback = chatEngine.complete(modelRequest);
                                if (fallback != null && !fallback.isBlank()) {
                                    fullAnswer = fallback;
                                }
                            }
                            fullAnswer = checkCitations(fullAnswer, context.turn().getTraceId());
                            chatLatency.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                            ChatMessage assistant = conversationService.completeTurn(context, fullAnswer, latencyMs);
                            remember(conversationId, message, fullAnswer);
                            enqueueTurn(context, projectIdOf(request), message, fullAnswer);
                            if (!dualMode) {
                                workflowOrchestrator.recordSingleRun(
                                        context.turn().getTraceId(),
                                        context.turn().getId(),
                                        latencyMs,
                                        AgentTokens.estimate(modelRequest.systemPrompt())
                                                + AgentTokens.estimate(modelRequest.userPrompt()),
                                        AgentTokens.estimate(fullAnswer),
                                        "DONE"
                                );
                            }
                            sendEvent(emitter, "done", Map.of(
                                    "conversationId", conversationId,
                                    "answer", fullAnswer,
                                    "turnId", context.turn().getId(),
                                    "traceId", context.turn().getTraceId(),
                                    "assistantMessageId", assistant.getId(),
                                    "latencyMs", latencyMs,
                                    "memoryCount", recent.size(),
                                    "citationCount", citations.size(),
                                    "mock", mock
                            ));
                            emitter.complete();
                        } catch (RuntimeException ex) {
                            conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
                            log.error("Unable to persist completed turn {}", context.turn().getId(), ex);
                            sendError(emitter, "PERSISTENCE_ERROR", safeMessage(ex));
                            emitter.complete();
                        }
                    }
            );
            subscriptionRef.set(subscription);
        } catch (RuntimeException ex) {
            if (finished.compareAndSet(false, true)) {
                conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
                sendError(emitter, "CHAT_ERROR", safeMessage(ex));
                emitter.complete();
            }
            return emitter;
        }

        emitter.onTimeout(() -> {
            if (finished.compareAndSet(false, true)) {
                Disposable subscription = subscriptionRef.get();
                if (subscription != null) {
                    subscription.dispose();
                }
                conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
                sendError(emitter, "TIMEOUT", "回答超时，请重试");
                emitter.complete();
            }
        });

        emitter.onCompletion(() -> {
            Disposable subscription = subscriptionRef.get();
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
            if (finished.compareAndSet(false, true)) {
                conversationService.failTurn(context.turn().getId(), elapsedMillis(startedAt));
            }
        });

        return emitter;
    }

    private RoutingDecision routeDecision(String message, List<WorkingMessage> recent) {
        if (!dualEnabled) {
            return new RoutingDecision(WorkflowMode.SINGLE, "feature.dual=false 强制单角色");
        }
        return router.route(message, !recent.isEmpty());
    }

    private void remember(Long conversationId, String userMessage, String assistantMessage) {
        if (!memoryEnabled) {
            return;
        }
        workingMemory.append(conversationId, "user", userMessage);
        workingMemory.append(conversationId, "assistant", assistantMessage);
        workingMemory.refreshSummary(conversationId);
    }

    private void enqueueTurn(TurnContext context, String projectId, String userMessage, String assistantMessage) {
        if (!stateEnabled) {
            return;
        }
        outboxService.enqueueTurnCompleted(new TurnCompletedPayload(
                context.turn().getId(),
                context.conversation().getId(),
                context.conversation().getLearnerId(),
                projectId,
                userMessage,
                assistantMessage
        ));
    }

    private List<MemoryItemView> searchLongTermMemories(String learnerId, String projectId, String message) {
        if (!memoryEnabled) {
            return List.of();
        }
        try {
            return memoryItemService.search(learnerId, projectId, message, 4);
        } catch (RuntimeException ex) {
            log.warn("Long-term memory retrieval failed, continue without it: {}", ex.getMessage());
            return List.of();
        }
    }

    private String checkCitations(String answer, String traceId) {
        CitationValidator.Result result = citationValidator.validate(answer);
        if (result.degraded() > 0 || result.dropped() > 0) {
            log.warn("Citation drift fixed, traceId={}, {}", traceId, CitationValidator.summary(result));
        }
        return result.text();
    }

    private String projectIdOf(ChatRequest request) {
        String projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            return "default";
        }
        return projectId.trim();
    }

    /**
     * 双角色生成：编排层只产出答案，落库/Redis/Outbox 仍由本类负责。
     */
    private String runDualWorkflow(TurnContext context,
                                   String projectId,
                                   String message,
                                   RoutingDecision decision,
                                   List<WorkingMessage> recent,
                                   LearnerProfile profile,
                                   List<RetrievedChunk> knowledge,
                                   List<CitationView> citations,
                                   List<MemoryItemView> memories,
                                   List<MemoryItemView> instructions,
                                   Consumer<AgentStepView> listener) {
        String learnerId = context.conversation().getLearnerId();
        ProjectState state = stateEnabled ? stateService.getOrCreate(learnerId, projectId) : null;
        WorkflowResult result = workflowOrchestrator.run(new WorkflowRequest(
                learnerId,
                projectId,
                context.conversation().getId(),
                context.turn().getId(),
                context.turn().getTraceId(),
                message,
                decision,
                state,
                knowledge,
                recent,
                memories,
                profileService.summary(profile),
                citations,
                listener,
                instructions
        ));
        return result.answer() == null ? "" : result.answer();
    }

    /**
     * 双角色走「同步生成 + 分片回放」：先发 agent 进度事件，再把最终答案按块发送，
     * 保持 meta -> citation* -> token* -> done 的既有契约不变（agent 为追加事件）。
     */
    private Flux<String> dualStream(SseEmitter emitter,
                                    TurnContext context,
                                    String projectId,
                                    String message,
                                    RoutingDecision decision,
                                    List<WorkingMessage> recent,
                                    LearnerProfile profile,
                                    List<RetrievedChunk> knowledge,
                                    List<CitationView> citations,
                                    List<MemoryItemView> memories,
                                    List<MemoryItemView> instructions) {
        try {
            String answer = runDualWorkflow(context, projectId, message, decision, recent, profile, knowledge, citations, memories, instructions,
                    step -> sendEvent(emitter, "agent", Map.of(
                            "role", step.agentName(),
                            "status", step.status(),
                            "digest", step.outputDigest() == null ? "" : step.outputDigest()
                    )));
            return Flux.fromIterable(splitForStream(answer));
        } catch (RuntimeException ex) {
            log.warn("Dual workflow failed, fallback to single role, traceId={}",
                    context.turn().getTraceId(), ex);
            ChatRequest fallback = new ChatRequest(
                    context.conversation().getId(),
                    context.conversation().getLearnerId(),
                    projectId,
                    message
            );
            return chatEngine.stream(promptBuilder.build(fallback, recent, profile, knowledge, memories,
                    memoryItemService.listInstructions(fallback.learnerId(), projectId), List.of()));
        }
    }

    private List<String> splitForStream(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int chunkSize = 12;
        List<String> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < text.length(); index += chunkSize) {
            chunks.add(text.substring(index, Math.min(text.length(), index + chunkSize)));
        }
        return chunks;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            log.debug("SSE client disconnected while sending {}: {}", eventName, ex.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        sendEvent(emitter, "error", Map.of("code", code, "message", message));
    }

    private String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return message.trim();
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }
}