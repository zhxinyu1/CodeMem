package com.codemem.workflow;

import java.util.List;

public record ReviewResult(boolean pass, List<String> issues, List<String> evidence) {

    public static ReviewResult passed() {
        return new ReviewResult(true, List.of(), List.of());
    }

    public static ReviewResult failed(List<String> issues, List<String> evidence) {
        return new ReviewResult(false, List.copyOf(issues), List.copyOf(evidence));
    }

    public String critique() {
        if (issues.isEmpty()) {
            return "（审查未指出具体问题）";
        }
        return String.join("；", issues);
    }
}