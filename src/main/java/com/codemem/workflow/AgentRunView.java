package com.codemem.workflow;

import java.time.LocalDateTime;

public record AgentRunView(
        String id,
        String traceId,
        String turnId,
        String workflow,
        String status,
        Integer latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer revisionCount,
        LocalDateTime createdAt
) {

    public static AgentRunView from(AgentRun run) {
        return new AgentRunView(
                run.getId(),
                run.getTraceId(),
                run.getTurnId(),
                run.getWorkflow(),
                run.getStatus(),
                run.getLatencyMs(),
                run.getPromptTokens(),
                run.getCompletionTokens(),
                run.getRevisionCount(),
                run.getCreatedAt()
        );
    }
}