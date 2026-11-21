package com.codemem.profile;

import java.time.LocalDateTime;

public record ProfileView(
        String learnerId,
        String displayName,
        String goal,
        String stage,
        String weeklyHours,
        String preferences,
        LocalDateTime updatedAt
) {
    public static ProfileView from(LearnerProfile profile) {
        return new ProfileView(
                profile.getLearnerId(),
                profile.getDisplayName(),
                profile.getGoal(),
                profile.getStage(),
                profile.getWeeklyHours(),
                profile.getPreferences(),
                profile.getUpdatedAt()
        );
    }
}