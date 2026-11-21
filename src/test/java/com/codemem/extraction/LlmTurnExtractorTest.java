package com.codemem.extraction;

import com.codemem.ai.ChatEngine;
import com.codemem.ai.ChatModelRequest;
import com.codemem.outbox.TurnCompletedPayload;
import com.codemem.state.StateOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTurnExtractorTest {

    private static final String TURN_ID = "turn-abc-123";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsValidStructuredOutput() {
        FakeEngine engine = new FakeEngine(validJson());
        TurnExtractionResult result = extractor(engine).extract(payload("我卡在红黑树左旋"));

        assertEquals("用户在描述左旋卡点", result.summary());
        assertEquals(List.of("红黑树左旋是薄弱点"), result.memoryCandidates());
        assertEquals(1, result.ops().size());
        StateOp op = result.ops().get(0);
        assertEquals("SET", op.op());
        assertEquals("currentBlocker", op.path());
        assertTrue(op.evidence().get(0).contains(TURN_ID));
        assertEquals(1, engine.calls);
    }

    @Test
    void repairsOnceThenAccepts() {
        FakeEngine engine = new FakeEngine("这不是 JSON", validJson());
        TurnExtractionResult result = extractor(engine).extract(payload("我卡在红黑树左旋"));

        assertEquals(2, engine.calls);
        assertEquals(1, result.ops().size());
        assertTrue(result.reason().contains("LLM"));
    }

    @Test
    void dropsPatchWhenRepairStillInvalid() {
        FakeEngine engine = new FakeEngine("仍然不是 JSON", "还不是 JSON");
        TurnExtractionResult result = extractor(engine).extract(payload("我卡在红黑树左旋"));

        assertEquals(2, engine.calls);
        assertTrue(result.ops().isEmpty());
        assertFalse(result.summary().isBlank());
        assertTrue(result.reason().contains("LLM 抽取失败"));
    }

    @Test
    void rejectsPathOutsideWhitelist() {
        String json = """
                {"summary":"试图写非法路径","memoryCandidates":[],"ops":[
                  {"op":"SET","path":"password","value":"x","confidence":0.9,"evidence":["turn:turn-abc-123"]}]}
                """;
        TurnExtractionResult result = extractor(new FakeEngine(json, json)).extract(payload("随便"));

        assertTrue(result.ops().isEmpty());
        assertTrue(result.reason().contains("白名单"));
    }

    @Test
    void rejectsOpWithoutEvidence() {
        String json = """
                {"summary":"缺证据","memoryCandidates":[],"ops":[
                  {"op":"ADD","path":"weakPoints","value":{"topic":"左旋"},"confidence":0.9,"evidence":[]}]}
                """;
        TurnExtractionResult result = extractor(new FakeEngine(json, json)).extract(payload("我卡在左旋"));

        assertTrue(result.ops().isEmpty());
        assertTrue(result.reason().contains("evidence"));
    }

    @Test
    void rejectsOutOfRangeConfidence() {
        String json = """
                {"summary":"置信度越界","memoryCandidates":[],"ops":[
                  {"op":"ADD","path":"weakPoints","value":{"topic":"左旋"},"confidence":1.5,
                   "evidence":["turn:turn-abc-123"]}]}
                """;
        TurnExtractionResult result = extractor(new FakeEngine(json, json)).extract(payload("我卡在左旋"));

        assertTrue(result.ops().isEmpty());
        assertTrue(result.reason().contains("confidence"));
    }

    @Test
    void fallsBackToRuleExtractorWhenModelThrows() {
        FakeEngine engine = new FakeEngine();
        engine.exception = new IllegalStateException("embedding api key is not configured");
        TurnExtractionResult result = extractor(engine).extract(payload("我卡在红黑树左旋，指针总写错"));

        assertFalse(result.ops().isEmpty());
        assertTrue(result.reason().contains("规则抽取"));
        assertEquals(1, engine.calls);
    }

    @Test
    void mockEngineProducesValidExtractionForBlocker() {
        com.codemem.ai.MockChatEngine mock = new com.codemem.ai.MockChatEngine();
        TurnExtractionResult result = new LlmTurnExtractor(mock, objectMapper, rule()).extract(
                payload("我卡在红黑树左旋，指针总写错"));

        assertFalse(result.ops().isEmpty());
        assertTrue(result.ops().stream().anyMatch(op -> "currentBlocker".equals(op.path())));
        assertTrue(result.ops().stream().allMatch(op -> !op.evidence().isEmpty()));
    }

    private String validJson() {
        return """
                {"summary":"用户在描述左旋卡点","memoryCandidates":["红黑树左旋是薄弱点"],"ops":[
                  {"op":"SET","path":"currentBlocker",
                   "value":{"topic":"红黑树左旋","symptom":"指针总写错"},
                   "confidence":0.8,"evidence":["turn:turn-abc-123"]}]}
                """;
    }

    private LlmTurnExtractor extractor(ChatEngine engine) {
        return new LlmTurnExtractor(engine, objectMapper, rule());
    }

    private RuleBasedTurnExtractor rule() {
        return new RuleBasedTurnExtractor(objectMapper);
    }

    private TurnCompletedPayload payload(String userMessage) {
        return new TurnCompletedPayload(TURN_ID, 1L, "learner", "project", userMessage, "助手回复");
    }

    private static final class FakeEngine implements ChatEngine {

        private final Deque<String> answers = new ArrayDeque<>();
        private RuntimeException exception;
        private int calls;

        private FakeEngine(String... outputs) {
            answers.addAll(List.of(outputs));
        }

        @Override
        public reactor.core.publisher.Flux<String> stream(ChatModelRequest request) {
            return reactor.core.publisher.Flux.just(complete(request));
        }

        @Override
        public String complete(ChatModelRequest request) {
            calls++;
            if (exception != null) {
                throw exception;
            }
            return answers.isEmpty() ? "" : answers.poll();
        }
    }

    }