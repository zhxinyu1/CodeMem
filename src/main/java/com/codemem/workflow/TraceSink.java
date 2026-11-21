package com.codemem.workflow;

import java.util.List;

/**
 * 追踪落库的抽象，便于编排层单测时不依赖数据库。
 */
public interface TraceSink {

    void recordRun(String runId,
                   String traceId,
                   String turnId,
                   WorkflowMode mode,
                   String status,
                   long latencyMs,
                   int promptTokens,
                   int completionTokens,
                   int revisionCount);

    void recordStep(String runId, AgentStepView step);

    List<AgentStepView> steps(String runId);
}