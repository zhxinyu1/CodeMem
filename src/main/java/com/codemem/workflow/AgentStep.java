package com.codemem.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_step")
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    @Column(name = "input_digest", length = 500)
    private String inputDigest;

    @Column(name = "output_digest", length = 500)
    private String outputDigest;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentStep() {
    }

    public AgentStep(String runId,
                     String agentName,
                     Integer stepNo,
                     String inputDigest,
                     String outputDigest,
                     String status,
                     Integer latencyMs,
                     LocalDateTime createdAt) {
        this.runId = runId;
        this.agentName = agentName;
        this.stepNo = stepNo;
        this.inputDigest = inputDigest;
        this.outputDigest = outputDigest;
        this.status = status;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getAgentName() {
        return agentName;
    }

    public Integer getStepNo() {
        return stepNo;
    }

    public String getInputDigest() {
        return inputDigest;
    }

    public String getOutputDigest() {
        return outputDigest;
    }

    public String getStatus() {
        return status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}