package com.codemem.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_run")
public class AgentRun {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Column(nullable = false, length = 32)
    private String workflow;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "revision_count", nullable = false)
    private Integer revisionCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentRun() {
    }

    public AgentRun(String id,
                    String traceId,
                    String turnId,
                    String workflow,
                    String status,
                    Integer latencyMs,
                    Integer promptTokens,
                    Integer completionTokens,
                    Integer revisionCount,
                    LocalDateTime createdAt) {
        this.id = id;
        this.traceId = traceId;
        this.turnId = turnId;
        this.workflow = workflow;
        this.status = status;
        this.latencyMs = latencyMs;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.revisionCount = revisionCount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getWorkflow() {
        return workflow;
    }

    public String getStatus() {
        return status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getRevisionCount() {
        return revisionCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}