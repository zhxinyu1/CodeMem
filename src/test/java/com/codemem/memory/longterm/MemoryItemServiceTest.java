package com.codemem.memory.longterm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryItemServiceTest {

    @Test
    void classifiesCandidateTypes() {
        assertEquals("WEAKNESS", MemoryItemService.classify("卡点：红黑树左旋，指针总写错"));
        assertEquals("PLAN", MemoryItemService.classify("计划：两周内练完红黑树和堆"));
        assertEquals("MASTERY", MemoryItemService.classify("掌握：左旋四步可以默写"));
        assertEquals("FACT", MemoryItemService.classify("我更习惯先看结构再刷题"));
    }

    @Test
    void extractsTopicBeforeFirstComma() {
        assertEquals("红黑树左旋", MemoryItemService.topicOf("卡点：红黑树左旋，指针总写错"));
        assertEquals("两周内练完红黑树和堆", MemoryItemService.topicOf("计划：两周内练完红黑树和堆"));
        assertEquals("短期目标", MemoryItemService.topicOf("阶段：短期目标"));
        assertEquals("红黑树左旋", MemoryItemService.topicOf("卡点：红黑树左旋：现在能默写四步了"));
    }

    @Test
    void normalizesWhitespace() {
        assertEquals("卡点：左旋 指针", MemoryItemService.normalize("  卡点：左旋\n\t指针  "));
    }

    @Test
    void topicIsCappedAtSixtyChars() {
        String longTopic = "卡点：" + "红".repeat(200);
        assertEquals(60, MemoryItemService.topicOf(longTopic).length());
    }

    @Test
    void decayIsOneForFreshMemory() {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        assertEquals(1.0, MemoryItemService.decayFactor(now, now, now), 0.0001);
    }

    @Test
    void decayHalvesEveryThirtyDays() {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        assertEquals(0.5, MemoryItemService.decayFactor(now.minus(Duration.ofDays(30)), null, now), 0.01);
        assertEquals(0.25, MemoryItemService.decayFactor(now.minus(Duration.ofDays(60)), null, now), 0.01);
        assertTrue(MemoryItemService.decayFactor(now.minus(Duration.ofDays(7)), null, now) > 0.8);
    }

    @Test
    void lastAccessWinsOverCreationTime() {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        Instant old = now.minus(Duration.ofDays(120));
        Instant recent = now.minus(Duration.ofDays(1));
        double withAccess = MemoryItemService.decayFactor(recent, old, now);
        double withoutAccess = MemoryItemService.decayFactor(null, old, now);
        assertTrue(withAccess > withoutAccess);
    }

    @Test
    void decayNeverGoesNegativeForFutureTimestamp() {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        assertEquals(1.0, MemoryItemService.decayFactor(now.plus(Duration.ofDays(3)), null, now), 0.0001);
    }

    @Test
    void recallRaisesImportanceAndConfidence() {
        assertEquals(0.65, MemoryItemService.boostedImportance(0.6), 0.0001);
        assertEquals(0.72, MemoryItemService.boostedConfidence(0.7), 0.0001);
    }

    @Test
    void reinforcementIsCappedAtOne() {
        assertEquals(1.0, MemoryItemService.boostedImportance(0.98), 0.0001);
        assertEquals(1.0, MemoryItemService.boostedConfidence(0.99), 0.0001);
        double importance = 0.6;
        for (int index = 0; index < 20; index++) {
            importance = MemoryItemService.boostedImportance(importance);
        }
        assertEquals(1.0, importance, 0.0001);
    }
}