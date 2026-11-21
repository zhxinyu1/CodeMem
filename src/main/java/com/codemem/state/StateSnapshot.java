package com.codemem.state;

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
@Table(name = "state_snapshot")
public class StateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, length = 64)
    private String learnerId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_json", nullable = false, columnDefinition = "JSON")
    private String stateJson;

    @Column(length = 1000)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StateSnapshot() {
    }

    public StateSnapshot(String learnerId,
                         String projectId,
                         Integer version,
                         String stateJson,
                         String summary,
                         LocalDateTime createdAt) {
        this.learnerId = learnerId;
        this.projectId = projectId;
        this.version = version;
        this.stateJson = stateJson;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getLearnerId() {
        return learnerId;
    }

    public String getProjectId() {
        return projectId;
    }

    public Integer getVersion() {
        return version;
    }

    public String getStateJson() {
        return stateJson;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}