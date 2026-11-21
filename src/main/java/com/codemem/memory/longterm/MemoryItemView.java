package com.codemem.memory.longterm;

import java.time.Instant;

/**
 * 对外视图：对话响应、SSE 事件与记忆/指令管理页共用。
 *
 * <p>{@code kind} 区分两类数据：
 * <ul>
 *   <li>{@code INSTRUCTION} —— 用户手写的全局指令，每轮回答前必须遵守；</li>
 *   <li>{@code AUTO} —— 对话后自动抽取的记忆，只作背景参考，不强制。</li>
 * </ul>
 * {@code priority} 越小越先注入（0–999）。
 */
public record MemoryItemView(
        String id,
        String content,
        String topic,
        String type,
        String sourceType,
        double score,
        double importance,
        double confidence,
        String status,
        Instant validTo,
        String kind,
        int priority
) {

    public static final String KIND_INSTRUCTION = "INSTRUCTION";
    public static final String KIND_AUTO = "AUTO";

    public boolean isInstruction() {
        return KIND_INSTRUCTION.equals(kind);
    }

    public static MemoryItemView from(MemoryItem item, double score) {
        return new MemoryItemView(
                item.id(),
                item.content(),
                item.topic(),
                item.type(),
                item.sourceType(),
                score,
                item.importance(),
                item.confidence(),
                item.status(),
                item.validTo(),
                KIND_AUTO,
                100
        );
    }
}