package com.codemem.workflow;

public record AgentStepView(
        String agentName,
        int stepNo,
        String status,
        String inputDigest,
        String outputDigest,
        long latencyMs
) {
}