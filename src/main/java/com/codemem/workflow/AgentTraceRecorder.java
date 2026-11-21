package com.codemem.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 追踪落库：写 agent_run / agent_step。追踪失败只降级记录，不影响对话主流程。
 */
@Service
public class AgentTraceRecorder implements TraceSink {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceRecorder.class);

    private final AgentRunRepository runs;
    private final AgentStepRepository steps;

    public AgentTraceRecorder(AgentRunRepository runs, AgentStepRepository steps) {
        this.runs = runs;
        this.steps = steps;
    }

    @Override
    public void recordRun(String runId,
                          String traceId,
                          String turnId,
                          WorkflowMode mode,
                          String status,
                          long latencyMs,
                          int promptTokens,
                          int completionTokens,
                          int revisionCount) {
        try {
            String id = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
            runs.save(new AgentRun(
                    id,
                    traceId,
                    turnId,
                    mode == null ? WorkflowMode.SINGLE.name() : mode.name(),
                    status,
                    (int) latencyMs,
                    promptTokens,
                    completionTokens,
                    revisionCount,
                    LocalDateTime.now()
            ));
        } catch (RuntimeException ex) {
            log.warn("Unable to persist agent_run for traceId={}", traceId, ex);
        }
    }

    @Override
    public void recordStep(String runId, AgentStepView step) {
        if (step == null || runId == null) {
            return;
        }
        try {
            steps.save(new AgentStep(
                    runId,
                    step.agentName(),
                    step.stepNo(),
                    step.inputDigest(),
                    step.outputDigest(),
                    step.status(),
                    (int) step.latencyMs(),
                    LocalDateTime.now()
            ));
        } catch (RuntimeException ex) {
            log.warn("Unable to persist agent_step for runId={}", runId, ex);
        }
    }

    @Override
    public List<AgentStepView> steps(String runId) {
        return steps.findByRunIdOrderByStepNoAsc(runId).stream()
                .map(this::toView)
                .toList();
    }

    public List<AgentRunView> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<AgentRun> found = runs.findTop20ByOrderByCreatedAtDesc();
        return found.stream().limit(safeLimit).map(AgentRunView::from).toList();
    }

    public AgentRunDetailView detail(String traceId) {
        List<AgentRun> matched = runs.findByTraceIdOrderByCreatedAtAsc(traceId);
        if (matched.isEmpty()) {
            return null;
        }
        AgentRun latest = matched.get(matched.size() - 1);
        List<AgentStepView> stepViews = steps(latest.getId());
        List<AgentRunView> related = matched.stream().map(AgentRunView::from).toList();
        return new AgentRunDetailView(AgentRunView.from(latest), stepViews, related);
    }

    private AgentStepView toView(AgentStep step) {
        return new AgentStepView(
                step.getAgentName(),
                step.getStepNo() == null ? 0 : step.getStepNo(),
                step.getStatus(),
                step.getInputDigest(),
                step.getOutputDigest(),
                step.getLatencyMs() == null ? 0L : step.getLatencyMs()
        );
    }
}