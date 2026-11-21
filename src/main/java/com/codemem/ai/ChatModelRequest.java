package com.codemem.ai;

public record ChatModelRequest(
        String systemPrompt,
        String userPrompt,
        String userMessage,
        String profileSummary,
        int memoryCount,
        int knowledgeCount,
        String agentRole
) {
}