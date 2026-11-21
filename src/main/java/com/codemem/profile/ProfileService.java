package com.codemem.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ProfileService {

    private final LearnerProfileRepository profiles;

    public ProfileService(LearnerProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public LearnerProfile getDefault() {
        return profiles.findByLearnerId("default").orElseGet(this::fallbackProfile);
    }

    public String summary(LearnerProfile profile) {
        return "自述目标（仅参考，学习目标以笔记中的规划为准）：" + profile.getGoal()
                + "；当前阶段：" + profile.getStage()
                + "；投入：" + profile.getWeeklyHours()
                + "；偏好：" + profile.getPreferences();
    }

    private LearnerProfile fallbackProfile() {
        return new LearnerProfile(
                "default",
                "学习者",
                "目标以笔记中的学习规划为准（画像里暂未单独声明）",
                "数据结构基础",
                "每周 10-20 小时",
                "先讲结构再讲题；代码要完整可运行；发现知识盲区时直接指出。",
                LocalDateTime.now()
        );
    }
}