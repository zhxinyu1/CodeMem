package com.codemem.conversation;

/**
 * Result of deleting a conversation: how many rows were removed per table.
 * The front end only needs the id, but the counts make the cascade verifiable.
 */
public record ConversationDeletedView(
        Long conversationId,
        int messages,
        int turns,
        int extractions,
        int agentRuns,
        int agentSteps,
        int outboxEvents
) {
}