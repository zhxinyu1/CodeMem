package com.codemem.workflow;

import com.codemem.knowledge.CitationView;

import java.util.List;

public record WorkflowResult(
        String answer,
        List<CitationView> citations,
        WorkflowMode mode,
        String traceId,
        List<AgentStepView> steps,
        boolean reviewPassed,
        int revisionCount,
        String status
) {

    public boolean dual() {
        return mode == WorkflowMode.DUAL;
    }
}