package com.codemem.chat;

import com.codemem.knowledge.CitationView;
import com.codemem.memory.longterm.MemoryItemView;

import java.util.List;

public record ChatResponse(
        Long conversationId,
        String turnId,
        String traceId,
        Long userMessageId,
        Long assistantMessageId,
        String answer,
        List<CitationView> citations,
        List<MemoryItemView> memories,
        int memoryCount,
        long latencyMs,
        boolean mock
) {
}