package com.codemem.extraction;

import com.codemem.ai.ChatEngine;
import com.codemem.ai.ChatModelRequest;
import com.codemem.state.StateOp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 结构化输出抽取器：一次生成 + 一次修复，强校验后才产出补丁。
 *
 * <p>失败策略：模型调用异常（例如没有 Key）自动回落到规则版；内容不合格则丢弃补丁，
 * 只保留摘要，由 TurnExtractionService 记为 SKIPPED，避免污染状态。</p>
 */
@Component
public class LlmTurnExtractor implements TurnExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmTurnExtractor.class);

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "goal", "stage", "mastered", "weakPoints", "currentBlocker",
            "decisions", "nextActions", "openQuestions", "preferences"
    );
    private static final Set<String> LIST_PATHS = Set.of(
            "mastered", "weakPoints", "decisions", "nextActions", "openQuestions"
    );
    private static final Set<String> OPS = Set.of("SET", "ADD", "REMOVE", "RESOLVE");

    private static final String SYSTEM_PROMPT = """
            你是“码忆”的记忆抽取器。输入是一轮对话，输出严格的 JSON，不要输出任何解释或代码块标记。

            JSON schema：
            {
              "summary": "不超过 120 字的中文摘要，概括用户这轮说了什么",
              "memoryCandidates": ["可长期复用的记忆候选，0-3 条，每条不超过 60 字"],
              "ops": [
                {
                  "op": "SET | ADD | REMOVE | RESOLVE",
                  "path": "goal | stage | mastered | weakPoints | currentBlocker | decisions | nextActions | openQuestions | preferences",
                  "value": { ... },
                  "confidence": 0.0 - 1.0,
                  "evidence": ["turn:<turnId>"]
                }
              ]
            }

            规则：
            1. 只抽取用户明确表达的事实，不要推断用户水平。
            2. 用户表达卡点/不会/没懂时，用 SET currentBlocker（value 含 topic、symptom、since）并 ADD weakPoints（含 topic、symptom）。
            3. 用户表达下一步/计划时，用 ADD nextActions（value 含 action、priority）。
            4. 用户表达学会了某个点时，用 ADD mastered（value 含 topic）。
            5. ADD/REMOVE 只能用于列表字段；SET 只能用于 goal/stage/currentBlocker/preferences。
            6. 每条 op 的 evidence 必须是 ["turn:<本轮 turnId>"]，不得为空。
            7. 没有值得写入状态的内容时，ops 返回空数组。
            """;

    private final ChatEngine chatEngine;
    private final ObjectMapper objectMapper;
    private final RuleBasedTurnExtractor fallback;

    public LlmTurnExtractor(ChatEngine chatEngine, ObjectMapper objectMapper, RuleBasedTurnExtractor fallback) {
        this.chatEngine = chatEngine;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public TurnExtractionResult extract(com.codemem.outbox.TurnCompletedPayload payload) {
        String prompt = buildPrompt(payload);
        String raw;
        try {
            raw = callModel(prompt);
        } catch (RuntimeException ex) {
            log.warn("LLM 抽取不可用，回落到规则版：{}", ex.getMessage());
            return fallback.extract(payload);
        }

        ParseOutcome outcome = parse(raw, payload);
        if (!outcome.ok()) {
            String repairPrompt = prompt + "\n\n上一次输出不合格：" + outcome.error() + "\n请只输出修正后的 JSON。";
            try {
                raw = callModel(repairPrompt);
            } catch (RuntimeException ex) {
                log.warn("LLM 修复调用失败，回落到规则版：{}", ex.getMessage());
                return fallback.extract(payload);
            }
            outcome = parse(raw, payload);
        }

        if (!outcome.ok()) {
            log.warn("LLM 抽取结果校验失败，丢弃补丁：{}", outcome.error());
            return new TurnExtractionResult(
                    truncate(payload.userMessage(), 240),
                    List.of(),
                    List.of(),
                    "LLM 抽取失败：" + outcome.error()
            );
        }
        return outcome.result();
    }

    private String callModel(String prompt) {
        ChatModelRequest request = new ChatModelRequest(
                SYSTEM_PROMPT,
                prompt,
                "",
                "",
                0,
                0,
                "extractor"
        );
        String answer = chatEngine.complete(request);
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("抽取模型返回空内容");
        }
        return answer.trim();
    }

    private String buildPrompt(com.codemem.outbox.TurnCompletedPayload payload) {
        return """
                【turnId】%s
                【用户消息】
                %s

                【助手回复】
                %s

                请只输出 JSON。
                """.formatted(payload.turnId(), payload.userMessage(), truncate(payload.assistantMessage(), 1200));
    }

    private ParseOutcome parse(String raw, com.codemem.outbox.TurnCompletedPayload payload) {
        String json = stripCodeFence(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception ex) {
            return ParseOutcome.failed("JSON 无法解析：" + ex.getMessage());
        }
        if (!root.isObject()) {
            return ParseOutcome.failed("顶层必须是 JSON 对象");
        }

        String summary = root.path("summary").asText("").trim();
        if (summary.isEmpty()) {
            return ParseOutcome.failed("summary 不能为空");
        }

        List<String> memories = new ArrayList<>();
        JsonNode memoryNode = root.path("memoryCandidates");
        if (!memoryNode.isMissingNode() && !memoryNode.isNull()) {
            if (!memoryNode.isArray()) {
                return ParseOutcome.failed("memoryCandidates 必须是数组");
            }
            for (JsonNode item : memoryNode) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    memories.add(item.asText().trim());
                }
            }
        }

        List<StateOp> ops = new ArrayList<>();
        JsonNode opsNode = root.path("ops");
        if (!opsNode.isMissingNode() && !opsNode.isNull()) {
            if (!opsNode.isArray()) {
                return ParseOutcome.failed("ops 必须是数组");
            }
            int index = 0;
            for (JsonNode opNode : opsNode) {
                index++;
                StateOp op = toStateOp(opNode);
                if (op == null) {
                    return ParseOutcome.failed("第 " + index + " 条 op 结构不合法");
                }
                String error = validate(op, payload.turnId());
                if (error != null) {
                    return ParseOutcome.failed("第 " + index + " 条 op 校验失败：" + error);
                }
                ops.add(op);
            }
        }

        return ParseOutcome.ok(new TurnExtractionResult(summary, memories, ops, "LLM 结构化抽取"));
    }

    private StateOp toStateOp(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String op = node.path("op").asText("");
        String path = node.path("path").asText("");
        JsonNode value = node.has("value") ? node.get("value") : null;
        Double confidence = node.has("confidence") && node.get("confidence").isNumber()
                ? node.get("confidence").asDouble()
                : null;
        // 证据保持原样（含空列表），由 validate 显式拒绝，不做默认值填充
        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNode = node.path("evidence");
        if (evidenceNode.isArray()) {
            for (JsonNode item : evidenceNode) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    evidence.add(item.asText().trim());
                }
            }
        }
        return new StateOp(op.trim().toUpperCase(), path.trim(), value, confidence, evidence);
    }

    private String validate(StateOp op, String turnId) {
        if (!OPS.contains(op.op())) {
            return "不支持的 op：" + op.op();
        }
        if (!ALLOWED_PATHS.contains(op.path())) {
            return "路径不在白名单：" + op.path();
        }
        if (op.evidence() == null || op.evidence().isEmpty()) {
            return "evidence 不能为空";
        }
        boolean evidenceOk = op.evidence().stream()
                .anyMatch(item -> item != null && item.contains(turnId));
        if (!evidenceOk) {
            return "evidence 必须包含本轮 turnId";
        }
        if (op.confidence() != null && (op.confidence() < 0.0 || op.confidence() > 1.0)) {
            return "confidence 必须在 0~1";
        }
        if (("ADD".equals(op.op()) || "REMOVE".equals(op.op())) && !LIST_PATHS.contains(op.path())) {
            return op.op() + " 只支持列表字段";
        }
        if (("SET".equals(op.op()) || "ADD".equals(op.op())) && op.value() == null) {
            return op.op() + " 需要 value";
        }
        if (op.value() != null && op.value().isObject()) {
            String required = requiredField(op.path());
            if (required != null && op.value().path(required).asText("").isBlank()) {
                return op.path() + " 的 value 缺少 " + required;
            }
        }
        return null;
    }

    private String requiredField(String path) {
        return switch (path) {
            case "weakPoints", "mastered" -> "topic";
            case "currentBlocker" -> "topic";
            case "nextActions" -> "action";
            case "openQuestions" -> "question";
            case "decisions" -> "decision";
            default -> null;
        };
    }

    private String stripCodeFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        return text.trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private record ParseOutcome(boolean ok, TurnExtractionResult result, String error) {

        static ParseOutcome ok(TurnExtractionResult result) {
            return new ParseOutcome(true, result, null);
        }

        static ParseOutcome failed(String error) {
            return new ParseOutcome(false, null, error);
        }
    }
}