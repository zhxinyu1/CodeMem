package com.codemem.workflow;

import com.codemem.ai.ChatEngine;
import com.codemem.ai.ChatModelRequest;
import org.springframework.stereotype.Component;

/**
 * 学习规划师：负责起草与修订，只写黑板上的 mentorDraft。
 */
@Component
public class MentorAgent {

    private static final String ROLE_DRAFT = "mentor";
    private static final String ROLE_REVISE = "mentor-revise";

    private final ChatEngine chatEngine;

    public MentorAgent(ChatEngine chatEngine) {
        this.chatEngine = chatEngine;
    }

    public String draft(Blackboard board) {
        BudgetGuard budget = board.budget();
        String systemPrompt = AgentPrompts.MENTOR_SYSTEM;
        String userPrompt = budget.truncate(AgentPrompts.mentorUserPrompt(board), budget.mentorLimit());

        ChatModelRequest request = new ChatModelRequest(
                systemPrompt,
                userPrompt,
                board.userQuery(),
                board.profileSummary(),
                board.retrievedMemories().size(),
                board.retrievedNotes().size(),
                ROLE_DRAFT
        );

        String answer = chatEngine.complete(request);
        String draft = answer == null ? "" : answer.trim();
        budget.addTokens(AgentTokens.estimate(systemPrompt) + AgentTokens.estimate(userPrompt) + AgentTokens.estimate(draft));
        board.writeMentorDraft(draft);
        return draft;
    }

    public String revise(Blackboard board, String critique) {
        BudgetGuard budget = board.budget();
        String systemPrompt = AgentPrompts.MENTOR_SYSTEM;
        String userPrompt = budget.truncate(AgentPrompts.mentorRevisionPrompt(board, critique), budget.mentorLimit());

        ChatModelRequest request = new ChatModelRequest(
                systemPrompt,
                userPrompt,
                board.userQuery(),
                board.profileSummary(),
                board.retrievedMemories().size(),
                board.retrievedNotes().size(),
                ROLE_REVISE
        );

        String answer = chatEngine.complete(request);
        String revised = answer == null ? "" : answer.trim();
        budget.addTokens(AgentTokens.estimate(systemPrompt) + AgentTokens.estimate(userPrompt) + AgentTokens.estimate(revised));
        board.writeMentorDraft(revised);
        return revised;
    }
}