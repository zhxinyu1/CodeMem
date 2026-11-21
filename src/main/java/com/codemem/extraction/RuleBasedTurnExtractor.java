package com.codemem.extraction;

import com.codemem.outbox.TurnCompletedPayload;
import com.codemem.state.StateOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class RuleBasedTurnExtractor implements TurnExtractor {

    private static final List<String> BLOCKER_KEYWORDS = List.of(
            "卡在", "卡点", "不会", "没懂", "搞不懂", "不明白", "没搞懂"
    );
    private static final List<String> MASTERY_KEYWORDS = List.of(
            "学会了", "掌握了", "搞懂了", "弄明白了", "已经会", "理解了"
    );
    private static final List<String> PLAN_KEYWORDS = List.of(
            "下一步", "接下来", "计划", "准备学", "打算"
    );

    private final ObjectMapper objectMapper;

    public RuleBasedTurnExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TurnExtractionResult extract(TurnCompletedPayload payload) {
        String user = safe(payload.userMessage());
        String assistant = safe(payload.assistantMessage());
        String evidence = "turn:" + payload.turnId();

        String summary = truncate(user, 240) + " | " + truncate(assistant, 360);
        List<StateOp> ops = new ArrayList<>();
        List<String> memories = new ArrayList<>();
        List<String> detected = new ArrayList<>();

        String blockerKeyword = firstKeyword(user, BLOCKER_KEYWORDS);
        if (blockerKeyword != null) {
            String topic = topicAfter(user, blockerKeyword);
            ObjectNode blocker = objectMapper.createObjectNode();
            blocker.put("topic", topic);
            blocker.put("symptom", truncate(user, 300));
            blocker.putArray("attempted");
            blocker.put("since", LocalDate.now().toString());
            blocker.putArray("evidence").add(evidence);
            ops.add(new StateOp("SET", "currentBlocker", blocker, 0.80, List.of(evidence)));

            ObjectNode weakPoint = objectMapper.createObjectNode();
            weakPoint.put("topic", topic);
            weakPoint.put("symptom", truncate(user, 300));
            weakPoint.put("confidence", 0.75);
            weakPoint.put("lastSeen", LocalDate.now().toString());
            weakPoint.putArray("evidence").add(evidence);
            ops.add(new StateOp("ADD", "weakPoints", weakPoint, 0.75, List.of(evidence)));

            memories.add("卡点：" + topic);
            detected.add("卡点");
        }

        String masteryKeyword = firstKeyword(user, MASTERY_KEYWORDS);
        if (masteryKeyword != null) {
            String topic = topicAfter(user, masteryKeyword);
            ObjectNode mastery = objectMapper.createObjectNode();
            mastery.put("topic", topic);
            mastery.put("confidence", 0.75);
            mastery.putArray("evidence").add(evidence);
            ops.add(new StateOp("ADD", "mastered", mastery, 0.75, List.of(evidence)));

            memories.add("已掌握：" + topic);
            detected.add("掌握");
        }

        String planKeyword = firstKeyword(user, PLAN_KEYWORDS);
        if (planKeyword != null) {
            String action = truncate(user, 160);
            ObjectNode nextAction = objectMapper.createObjectNode();
            nextAction.put("action", action);
            nextAction.put("priority", "MEDIUM");
            nextAction.put("due", "");
            nextAction.putArray("evidence").add(evidence);
            ops.add(new StateOp("ADD", "nextActions", nextAction, 0.65, List.of(evidence)));

            memories.add("计划：" + action);
            detected.add("计划");
        }

        if (ops.isEmpty()) {
            memories.add("对话摘要：" + summary);
        }

        String reason = detected.isEmpty()
                ? "规则抽取：未发现明确的状态变更信号"
                : "规则抽取：" + String.join("、", detected);
        return new TurnExtractionResult(summary, List.copyOf(memories), List.copyOf(ops), reason);
    }

    private String firstKeyword(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private String topicAfter(String text, String keyword) {
        int index = text.indexOf(keyword);
        if (index < 0) {
            return truncate(text, 60);
        }
        String tail = text.substring(index + keyword.length())
                .replaceAll("^[：:，,。！？!?；;\\s]+", "")
                .trim();
        if (tail.isBlank()) {
            return truncate(text, 60);
        }
        int cut = firstPunctuation(tail);
        String topic = cut > 0 ? tail.substring(0, cut).trim() : tail;
        return truncate(topic, 60);
    }

    private int firstPunctuation(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if ("。！？!?；;，,\n".indexOf(ch) >= 0) {
                return index;
            }
        }
        return -1;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}