package com.codemem.workflow;

import java.util.List;

public record AgentRunDetailView(
        AgentRunView run,
        List<AgentStepView> steps,
        List<AgentRunView> relatedRuns
) {
}