package com.codemem.extraction;

import java.time.LocalDateTime;

/**
 * 记忆检查器视图：保留 memoryJson / statePatchJson 原文，由前端解析展示记忆候选与补丁。
 */
public record TurnExtractionView(
        Long id,
        String turnId,
        String learnerId,
        String projectId,
        String summary,
        String memoryJson,
        String statePatchJson,
        String status,
        LocalDateTime createdAt
) {
    public static TurnExtractionView from(TurnExtraction extraction) {
        return new TurnExtractionView(
                extraction.getId(),
                extraction.getTurnId(),
                extraction.getLearnerId(),
                extraction.getProjectId(),
                extraction.getSummary(),
                extraction.getMemoryJson(),
                extraction.getStatePatchJson(),
                extraction.getStatus(),
                extraction.getCreatedAt()
        );
    }
}