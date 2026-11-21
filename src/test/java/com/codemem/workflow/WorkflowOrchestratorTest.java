package com.codemem.workflow;

import com.codemem.ai.MockChatEngine;
import com.codemem.knowledge.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowOrchestratorTest {

    private final MockChatEngine engine = new MockChatEngine();
    private final CollectingSink sink = new CollectingSink();
    private final WorkflowOrchestrator orchestrator =
            new WorkflowOrchestrator(new MentorAgent(engine), new ReviewerAgent(engine), sink);

    @Test
    void singleModeSkipsReviewer() {
        WorkflowResult result = orchestrator.run(request(WorkflowMode.SINGLE, "红黑树左旋是什么", List.of()));
        assertEquals(WorkflowMode.SINGLE, result.mode());
        assertEquals(1, result.steps().size());
        assertEquals("mentor", result.steps().get(0).agentName());
        assertTrue(result.steps().stream().noneMatch(step -> step.agentName().equals("reviewer")));
        assertEquals(1, sink.runs.size());
    }

    @Test
    void dualModePassesWhenDraftIsBackedByNotes() {
        RetrievedChunk note = new RetrievedChunk("c1", "学习笔记/红黑树.md", "红黑树", "红黑树 > 左旋",
                "红黑树左旋四步缺一不可。", 0.88);
        WorkflowResult result = orchestrator.run(
                request(WorkflowMode.DUAL, "帮我制定两周的学习计划，重点练红黑树左旋", List.of(note)));
        assertEquals(WorkflowMode.DUAL, result.mode());
        assertEquals(2, result.steps().size());
        assertTrue(result.reviewPassed());
        assertEquals(0, result.revisionCount());
        assertTrue(result.answer().contains("依据：本轮检索到 1 条笔记片段"));
    }

    @Test
    void dualModeInterceptsUnsupportedClaimAndRevisesOnce() {
        WorkflowResult result = orchestrator.run(request(WorkflowMode.DUAL, "帮我制定两周的学习计划", List.of()));
        assertEquals(4, result.steps().size());
        assertEquals("mentor", result.steps().get(0).agentName());
        assertEquals("reviewer", result.steps().get(1).agentName());
        assertEquals("mentor", result.steps().get(2).agentName());
        assertEquals("reviewer", result.steps().get(3).agentName());
        assertTrue(result.steps().get(1).outputDigest().startsWith("REJECT"));
        assertEquals(1, result.revisionCount());
        assertTrue(result.reviewPassed());
        assertTrue(result.answer().contains("已按审查意见修订"));
        assertTrue(result.answer().contains("当前资料不足"));
    }

    @Test
    void revisionIsCappedAtOne() {
        WorkflowResult result = orchestrator.run(request(WorkflowMode.DUAL, "帮我制定两周的学习计划", List.of()));
        assertEquals(1, result.revisionCount());
        assertTrue(result.revisionCount() <= BudgetGuard.MAX_REVISIONS);
    }

    private WorkflowRequest request(WorkflowMode mode, String query, List<RetrievedChunk> notes) {
        return new WorkflowRequest(
                "u1", "p1", 1L, "turn-1", "trace-1", query,
                new RoutingDecision(mode, "test"),
                null, notes, List.of(), List.of(), "画像摘要", List.of(), null
        );
    }

    private static final class CollectingSink implements TraceSink {

        private final List<String> runs = new ArrayList<>();

        @Override
        public void recordRun(String runId, String traceId, String turnId, WorkflowMode mode,
                              String status, long latencyMs, int promptTokens, int completionTokens, int revisionCount) {
            runs.add(mode + ":" + status);
        }

        @Override
        public void recordStep(String runId, AgentStepView step) {
        }

        @Override
        public List<AgentStepView> steps(String runId) {
            return List.of();
        }
    }
}