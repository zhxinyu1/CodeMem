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
@Table(name = "state_event")
public class StateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, length = 64)
    private String learnerId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "from_version", nullable = false)
    private Integer fromVersion;

    @Column(name = "to_version", nullable = false)
    private Integer toVersion;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "patch_json", nullable = false, columnDefinition = "JSON")
    private String patchJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "JSON")
    private String evidenceJson;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StateEvent() {
    }

    public StateEvent(String learnerId,
                      String projectId,
                      Integer fromVersion,
                      Integer toVersion,
                      String turnId,
                      String patchJson,
                      String evidenceJson,
                      String status,
                      LocalDateTime createdAt) {
        this.learnerId = learnerId;
        this.projectId = projectId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.turnId = turnId;
        this.patchJson = patchJson;
        this.evidenceJson = evidenceJson;
        this.status = status;
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

    public Integer getFromVersion() {
        return fromVersion;
    }

    public Integer getToVersion() {
        return toVersion;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getPatchJson() {
        return patchJson;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}