package com.codemem.extraction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "turn_extraction")
public class TurnExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false, length = 64)
    private String turnId;

    @Column(name = "learner_id", nullable = false, length = 64)
    private String learnerId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(length = 1000)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory_json", nullable = false, columnDefinition = "JSON")
    private String memoryJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_patch_json", columnDefinition = "JSON")
    private String statePatchJson;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TurnExtraction() {
    }

    public TurnExtraction(String turnId,
                          String learnerId,
                          String projectId,
                          String summary,
                          String memoryJson,
                          String statePatchJson,
                          String status,
                          LocalDateTime createdAt) {
        this.turnId = turnId;
        this.learnerId = learnerId;
        this.projectId = projectId;
        this.summary = summary;
        this.memoryJson = memoryJson;
        this.statePatchJson = statePatchJson;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getLearnerId() {
        return learnerId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getSummary() {
        return summary;
    }

    public String getMemoryJson() {
        return memoryJson;
    }

    public String getStatePatchJson() {
        return statePatchJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}