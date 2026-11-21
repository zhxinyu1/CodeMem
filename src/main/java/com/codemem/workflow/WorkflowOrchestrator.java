package com.codemem.workflow;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 编排层：只负责“生成答案”，落库、Redis、Outbox 仍由 ChatService 负责。
 *
 * <p>流程：Router 决策 -> 取黑板上下文 -> SINGLE 起草 / DUAL 起草 + 审查 + 最多一次修订 -> 记录 trace。</p>
 */
@Service
public class WorkflowOrchestrator {

    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final String STATUS_FAILED = "FAILED";

    private final MentorAgent mentorAgent;
    private final ReviewerAgent reviewerAgent;
    private final TraceSink traceSink;

    public WorkflowOrchestrator(MentorAgent mentorAgent, ReviewerAgent reviewerAgent, TraceSink traceSink) {
        this.mentorAgent = mentorAgent;
        this.reviewerAgent = reviewerAgent;
        this.traceSink = traceSink;
    }

    public WorkflowResult run(WorkflowRequest request) {
        long startedAt = System.currentTimeMillis();
        String traceId = request.traceId() == null || request.traceId().isBlank()
                ? UUID.randomUUID().toString()
                : request.traceId();
        BudgetGuard budget = new BudgetGuard(startedAt);
        String runId = UUID.randomUUID().toString();

        Blackboard board = new Blackboard(
                request.learnerId(),
                request.projectId(),
                request.conversationId(),
                traceId,
                request.userQuery(),
                request.state(),
                request.notes(),
                request.memories(),
                request.longTermMemories(),
                request.instructions(),
                request.profileSummary(),
                request.citations(),
                budget
        );

        List<AgentStepView> steps = new ArrayList<>();
        StepRecorder recorder = new StepRecorder(runId, steps, request);

        if (request.mode() == WorkflowMode.SINGLE) {
            String draft;
            try {
                draft = recorder.record("mentor", "起草", () -> {
                    String value = mentorAgent.draft(board);
                    recorder.output = AgentTokens.digest(value, 200);
                    return value;
                });
            } catch (RuntimeException ex) {
                traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.SINGLE,
                        STATUS_FAILED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
                throw ex;
            }
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.SINGLE,
                    STATUS_DONE, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            return new WorkflowResult(draft, board.verifiedCitations(), WorkflowMode.SINGLE,
                    traceId, List.copyOf(steps), false, 0, STATUS_DONE);
        }

        String draft;
        try {
            draft = recorder.record("mentor", "起草", () -> {
                String value = mentorAgent.draft(board);
                recorder.output = AgentTokens.digest(value, 200);
                return value;
            });
        } catch (RuntimeException ex) {
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_FAILED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            throw ex;
        }

        if (budget.expired()) {
            String answer = draft + "\n\n（审查未完成：超出单轮时间预算）";
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_DEGRADED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            return new WorkflowResult(answer, board.verifiedCitations(), WorkflowMode.DUAL,
                    traceId, List.copyOf(steps), false, 0, STATUS_DEGRADED);
        }

        ReviewResult review;
        try {
            review = recorder.record("reviewer", "审查", () -> {
                ReviewResult value = reviewerAgent.review(board);
                recorder.output = value.pass()
                        ? "PASS"
                        : "REJECT：" + AgentTokens.digest(String.join("；", value.issues()), 160);
                return value;
            });
        } catch (RuntimeException ex) {
            String answer = draft + "\n\n（审查未完成：评审不可用，已直接输出草稿）";
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_DEGRADED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            return new WorkflowResult(answer, board.verifiedCitations(), WorkflowMode.DUAL,
                    traceId, List.copyOf(steps), false, 0, STATUS_DEGRADED);
        }

        if (review.pass()) {
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_DONE, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            return new WorkflowResult(draft, board.verifiedCitations(), WorkflowMode.DUAL,
                    traceId, List.copyOf(steps), true, 0, STATUS_DONE);
        }

        if (!budget.canRevise()) {
            String answer = draft + "\n\n（审查未通过，且已达修订上限）\n存在未解决问题：" + review.critique();
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_DEGRADED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 0);
            return new WorkflowResult(answer, board.verifiedCitations(), WorkflowMode.DUAL,
                    traceId, List.copyOf(steps), false, 0, STATUS_DEGRADED);
        }

        budget.onRevision();
        String revised;
        try {
            revised = recorder.record("mentor", "修订", () -> {
                String value = mentorAgent.revise(board, review.critique());
                recorder.output = AgentTokens.digest(value, 200);
                return value;
            });
        } catch (RuntimeException ex) {
            String answer = draft + "\n\n（修订失败，已输出草稿）\n存在未解决问题：" + review.critique();
            traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                    STATUS_DEGRADED, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 1);
            return new WorkflowResult(answer, board.verifiedCitations(), WorkflowMode.DUAL,
                    traceId, List.copyOf(steps), false, 1, STATUS_DEGRADED);
        }

        ReviewResult recheck;
        try {
            recheck = recorder.record("reviewer", "复审", () -> {
                ReviewResult value = reviewerAgent.review(board);
                recorder.output = value.pass() ? "PASS" : "REJECT：" + AgentTokens.digest(String.join("；", value.issues()), 160);
                return value;
            });
        } catch (RuntimeException ex) {
            recheck = ReviewResult.failed(List.of("复审不可用"), List.of());
        }

        String answer;
        if (recheck.pass()) {
            answer = revised + "\n\n（已按审查意见修订，并通过复审）";
        } else {
            answer = revised + "\n\n（审查未通过）\n存在未解决问题：" + recheck.critique();
        }
        String status = recheck.pass() ? STATUS_DONE : STATUS_DEGRADED;
        traceSink.recordRun(runId, traceId, request.turnId(), WorkflowMode.DUAL,
                status, System.currentTimeMillis() - startedAt, recorder.promptTokens, recorder.completionTokens, 1);
        return new WorkflowResult(answer, board.verifiedCitations(), WorkflowMode.DUAL,
                traceId, List.copyOf(steps), recheck.pass(), 1, status);
    }

    /**
     * 记录单角色（流式）执行的 run，供回放使用。
     */
    public void recordSingleRun(String traceId,
                                String turnId,
                                long latencyMs,
                                int promptTokens,
                                int completionTokens,
                                String status) {
        traceSink.recordRun(UUID.randomUUID().toString(), traceId, turnId,
                WorkflowMode.SINGLE, status, latencyMs, promptTokens, completionTokens, 0);
    }

    private final class StepRecorder {

        private final String runId;
        private final List<AgentStepView> steps;
        private final WorkflowRequest request;
        private int stepNo;
        int promptTokens;
        int completionTokens;
        String output = "";

        private StepRecorder(String runId, List<AgentStepView> steps, WorkflowRequest request) {
            this.runId = runId;
            this.steps = steps;
            this.request = request;
        }

        private <T> T record(String agentName, String status, SupplierWithException<T> action) {
            stepNo++;
            long startedAt = System.currentTimeMillis();
            String input = AgentTokens.digest(request.userQuery(), 200);
            T value;
            try {
                value = action.get();
            } catch (RuntimeException ex) {
                AgentStepView step = new AgentStepView(agentName, stepNo, "FAILED", input, ex.getMessage(), elapsed(startedAt));
                steps.add(step);
                traceSink.recordStep(runId, step);
                request.listener().accept(step);
                throw ex;
            }
            int prompt = AgentTokens.estimate(request.userQuery());
            promptTokens += prompt;
            completionTokens += AgentTokens.estimate(output);
            AgentStepView step = new AgentStepView(agentName, stepNo, status, input, output, elapsed(startedAt));
            steps.add(step);
            traceSink.recordStep(runId, step);
            request.listener().accept(step);
            return value;
        }

        private long elapsed(long startedAt) {
            return System.currentTimeMillis() - startedAt;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get();
    }
}