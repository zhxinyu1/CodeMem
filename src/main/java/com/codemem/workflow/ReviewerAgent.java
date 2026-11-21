package com.codemem.workflow;

import com.codemem.ai.ChatEngine;
import com.codemem.ai.ChatModelRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技术评审：只基于黑板里的证据审查草稿，只写 reviewerCritique。
 */
@Component
public class ReviewerAgent {

    private static final String ROLE = "reviewer";
    private static final String PASS_MARKER = "VERDICT: PASS";
    private static final String REJECT_MARKER = "VERDICT: REJECT";

    private final ChatEngine chatEngine;

    public ReviewerAgent(ChatEngine chatEngine) {
        this.chatEngine = chatEngine;
    }

    public ReviewResult review(Blackboard board) {
        BudgetGuard budget = board.budget();
        String systemPrompt = AgentPrompts.REVIEWER_SYSTEM;
        String userPrompt = budget.truncate(AgentPrompts.reviewerUserPrompt(board), budget.reviewerLimit());

        ChatModelRequest request = new ChatModelRequest(
                systemPrompt,
                userPrompt,
                board.userQuery(),
                board.profileSummary(),
                board.retrievedMemories().size(),
                board.retrievedNotes().size(),
                ROLE
        );

        String raw = chatEngine.complete(request);
        budget.addTokens(AgentTokens.estimate(systemPrompt) + AgentTokens.estimate(userPrompt) + AgentTokens.estimate(raw));
        ReviewResult result = parse(raw);
        board.writeReviewerCritique(result.critique());
        return result;
    }

    /**
     * 解析评审输出。无法解析时按“未拦截”处理，避免阻塞主流程。
     */
    static ReviewResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReviewResult.passed();
        }
        String upper = raw.toUpperCase();
        if (!upper.contains(REJECT_MARKER)) {
            return ReviewResult.passed();
        }
        List<String> issues = collectSection(raw, "ISSUES", "EVIDENCE");
        List<String> evidence = collectSection(raw, "EVIDENCE", null);
        if (issues.isEmpty()) {
            issues = List.of("评审未给出具体问题描述");
        }
        return ReviewResult.failed(issues, evidence);
    }

    private static List<String> collectSection(String raw, String startMarker, String endMarker) {
        List<String> items = new ArrayList<>();
        boolean inSection = false;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase();
            if (upper.startsWith(startMarker)) {
                inSection = true;
                continue;
            }
            if (endMarker != null && upper.startsWith(endMarker)) {
                break;
            }
            if (!inSection) {
                continue;
            }
            if (trimmed.startsWith("-") || trimmed.startsWith("•")) {
                String item = trimmed.replaceFirst("^[-•]\\s*", "").trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            } else if (!trimmed.isEmpty() && !items.isEmpty()) {
                items.set(items.size() - 1, items.get(items.size() - 1) + " " + trimmed);
            }
        }
        return items;
    }
}