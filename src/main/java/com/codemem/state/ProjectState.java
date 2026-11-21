package com.codemem.state;

import java.time.Instant;
import java.util.List;

public record ProjectState(
        String learnerId,
        String projectId,
        Goal goal,
        String stage,
        List<Mastery> mastered,
        List<WeakPoint> weakPoints,
        Blocker currentBlocker,
        List<Decision> decisions,
        List<NextAction> nextActions,
        List<OpenQuestion> openQuestions,
        Preferences preferences,
        int version,
        Instant updatedAt,
        List<String> evidenceRefs
) {
    public ProjectState {
        if (mastered == null) {
            mastered = List.of();
        }
        if (weakPoints == null) {
            weakPoints = List.of();
        }
        if (decisions == null) {
            decisions = List.of();
        }
        if (nextActions == null) {
            nextActions = List.of();
        }
        if (openQuestions == null) {
            openQuestions = List.of();
        }
        if (evidenceRefs == null) {
            evidenceRefs = List.of();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    public record Goal(String shortTerm, String longTerm, String targetRole) {
    }

    public record Mastery(String topic, double confidence, List<String> evidence) {
        public Mastery {
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record WeakPoint(String topic,
                            String symptom,
                            double confidence,
                            String lastSeen,
                            List<String> evidence) {
        public WeakPoint {
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record Blocker(String topic,
                          String symptom,
                          List<String> attempted,
                          String since,
                          List<String> evidence) {
        public Blocker {
            if (attempted == null) {
                attempted = List.of();
            }
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record Decision(String topic, String choice, String reason, List<String> evidence) {
        public Decision {
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record NextAction(String action, String priority, String due, List<String> evidence) {
        public NextAction {
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record OpenQuestion(String question, String status, List<String> evidence) {
        public OpenQuestion {
            if (evidence == null) {
                evidence = List.of();
            }
        }
    }

    public record Preferences(String explanationStyle, String codeStyle, String language) {
    }
}