package com.codemem.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "learner_profile")
public class LearnerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, length = 64)
    private String learnerId;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(nullable = false, length = 500)
    private String goal;

    @Column(nullable = false, length = 200)
    private String stage;

    @Column(name = "weekly_hours", nullable = false, length = 32)
    private String weeklyHours;

    @Column(nullable = false, length = 1000)
    private String preferences;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LearnerProfile() {
    }

    public LearnerProfile(String learnerId,
                          String displayName,
                          String goal,
                          String stage,
                          String weeklyHours,
                          String preferences,
                          LocalDateTime updatedAt) {
        this.learnerId = learnerId;
        this.displayName = displayName;
        this.goal = goal;
        this.stage = stage;
        this.weeklyHours = weeklyHours;
        this.preferences = preferences;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getLearnerId() {
        return learnerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getGoal() {
        return goal;
    }

    public String getStage() {
        return stage;
    }

    public String getWeeklyHours() {
        return weeklyHours;
    }

    public String getPreferences() {
        return preferences;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}