package com.codemem.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "turn")
public class ChatTurn {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ChatTurn() {
    }

    public ChatTurn(String id,
                    Long conversationId,
                    String traceId,
                    String status,
                    LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.traceId = traceId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}