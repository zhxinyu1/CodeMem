package com.codemem.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class MockChatEngine implements ChatEngine {

    private static final Logger log = LoggerFactory.getLogger(MockChatEngine.class);
    private static final int CHUNK_SIZE = 12;

    private final boolean extractorFailure;

    public MockChatEngine() {
        this(false);
    }

    @Autowired
    public MockChatEngine(@Value("${codemem.ai.mock.extractor-failure:false}") boolean extractorFailure) {
        this.extractorFailure = extractorFailure;
        // 启动期明确告知当前引擎，避免“以为切了真实模型其实还在 Mock”
        log.debug("MockChatEngine 已注册，是否启用由 SwitchableChatEngine 按当前模式决定");
    }

    @Override
    public Flux<String> stream(ChatModelRequest request) {
        List<String> chunks = split(answerFor(request));
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(18));
    }

    @Override
    public String complete(ChatModelRequest request) {
        return answerFor(request);
    }

    private String answerFor(ChatModelRequest request) {
        String role = request.agentRole();
        if (role == null || role.isBlank()) {
            return buildAnswer(request);
        }
        return switch (role) {
            case "mentor" -> buildMentorDraft(request);
            case "mentor-revise" -> buildMentorRevision(request);
            case "reviewer" -> buildReviewerVerdict(request);
            case "extractor" -> {
                // 模拟“没有真实 Key”：用于端到端验证 LLM 抽取器的规则版回落
                if (extractorFailure) {
                    throw new IllegalStateException("embedding api key is not configured (simulated)");
                }
                yield buildExtraction(request);
            }
            default -> buildAnswer(request);
        };
    }

    /**
     * Mock 草稿：在没有检索证据时故意写一句“笔记里已经确认过”，
     * 用来让 Reviewer 能稳定拦下「无证据被当成事实」这一类问题。
     */
    private String buildMentorDraft(ChatModelRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        boolean noEvidence = !hasTopicalSupport(prompt, request.userMessage());
        String evidenceLine = noEvidence
                ? "这条结论我在笔记里已经确认过，你可以直接照做。"
                : "依据：本轮检索到 " + request.knowledgeCount() + " 条笔记片段（见引用）。";
        return """
                【Mentor 草稿·Mock】

                问题：%s

                建议步骤：
                1. 先复述问题里涉及的三个关键概念，确认没有理解偏差。
                2. 用最小可运行例子验证一次，再扩展到完整场景。
                3. 把这次踩到的坑写回状态里的卡点，下一轮直接复练。

                %s
                """.formatted(request.userMessage(), evidenceLine);
    }

    private String buildMentorRevision(ChatModelRequest request) {
        return """
                【Mentor 修订版·Mock】

                已按评审意见修正：删除“笔记里已经确认过”的无证据断言。

                问题：%s

                建议步骤：
                1. 先复述问题里涉及的三个关键概念，确认没有理解偏差。
                2. 用最小可运行例子验证一次，再扩展到完整场景。
                3. 把这次踩到的坑写回状态里的卡点，下一轮直接复练。

                当前资料不足：上述步骤是通用建议，不含笔记事实引用。
                """.formatted(request.userMessage());
    }

    private String buildReviewerVerdict(ChatModelRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        String draft = section(prompt, "【待审查草稿】", "【本轮可用证据】");
        boolean noEvidence = !hasTopicalSupport(prompt, request.userMessage());
        boolean unsupportedClaim = draft.contains("在笔记里已经确认过");
        if (noEvidence && unsupportedClaim) {
            return """
                    VERDICT: REJECT
                    ISSUES:
                    - 草稿声称“在笔记里已经确认过”，但本轮没有任何检索引用，属于无证据断言
                    EVIDENCE:
                    - knowledgeCount=0，【本轮可用证据】为空
                    """;
        }
        return """
                VERDICT: PASS
                ISSUES:
                EVIDENCE:
                """;
    }

    /**
     * Mock 抽取：产出与 LlmTurnExtractor 约定的 JSON schema，供无 Key 环境端到端验证。
     */
    private String buildExtraction(ChatModelRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        String user = section(prompt, "【用户消息】", "【助手回复】").trim();
        String turnId = firstMatch(prompt, "【turnId】(.+)");
        if (turnId.isBlank()) {
            turnId = "unknown";
        }
        String evidence = "turn:" + turnId;
        String today = java.time.LocalDate.now().toString();
        String summary = abbreviate(user, 80);

        java.util.List<String> ops = new java.util.ArrayList<>();
        String blocker = firstHit(user, java.util.List.of("卡在", "卡点", "不会", "没懂", "搞不懂", "不明白"));
        if (blocker != null) {
            String topic = abbreviate(user.substring(Math.min(user.length(), user.indexOf(blocker) + blocker.length())), 24);
            ops.add("""
                    {"op":"SET","path":"currentBlocker","value":{"topic":"%s","symptom":"%s","since":"%s"},
                     "confidence":0.8,"evidence":["%s"]}"""
                    .formatted(escape(topic), escape(abbreviate(user, 120)), today, evidence));
            ops.add("""
                    {"op":"ADD","path":"weakPoints","value":{"topic":"%s","symptom":"%s","confidence":0.75,"lastSeen":"%s"},
                     "confidence":0.75,"evidence":["%s"]}"""
                    .formatted(escape(topic), escape(abbreviate(user, 120)), today, evidence));
        }
        String plan = firstHit(user, java.util.List.of("下一步", "接下来", "计划", "打算", "准备学"));
        if (plan != null) {
            ops.add("""
                    {"op":"ADD","path":"nextActions","value":{"action":"%s","priority":"MEDIUM"},
                     "confidence":0.65,"evidence":["%s"]}"""
                    .formatted(escape(abbreviate(user, 120)), evidence));
        }
        String mastery = firstHit(user, java.util.List.of("学会了", "掌握了", "搞懂了", "弄明白了"));
        if (mastery != null) {
            String topic = abbreviate(user.substring(Math.min(user.length(), user.indexOf(mastery) + mastery.length())), 24);
            ops.add("""
                    {"op":"ADD","path":"mastered","value":{"topic":"%s"},
                     "confidence":0.7,"evidence":["%s"]}"""
                    .formatted(escape(topic), evidence));
        }

        return """
                {"summary":"%s","memoryCandidates":["%s"],"ops":[%s]}
                """.formatted(escape(summary), escape(summary), String.join(",", ops));
    }

    private String firstHit(String text, java.util.List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private String firstMatch(String text, String pattern) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    /**
     * 证据是否真的支撑问题：检索到的笔记内容与问句是否有足够的词面重合。
     * 与 embedding 分数无关，hash provider 下也能稳定判断。
     */
    private boolean hasTopicalSupport(String prompt, String query) {
        String notes = String.join(" ", sourceContents(prompt));
        if (notes.isBlank()) {
            return false;
        }
        String normalizedQuery = query == null ? "" : query.replaceAll("[\\s，。！？、,.!?：:；;（）()]", "");
        if (normalizedQuery.length() < 2) {
            return false;
        }
        int total = 0;
        int hit = 0;
        for (int index = 0; index + 2 <= normalizedQuery.length(); index++) {
            String gram = normalizedQuery.substring(index, index + 2);
            total++;
            if (notes.contains(gram)) {
                hit++;
            }
        }
        return total > 0 && (double) hit / total >= 0.15;
    }

    private List<String> sourceContents(String prompt) {
        List<String> contents = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<source\\b[^>]*>(.*?)</source>", java.util.regex.Pattern.DOTALL)
                .matcher(prompt);
        while (matcher.find()) {
            contents.add(matcher.group(1));
        }
        return contents;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String section(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) {
            return text;
        }
        int contentStart = start + startMarker.length();
        int end = text.indexOf(endMarker, contentStart);
        return end < 0 ? text.substring(contentStart) : text.substring(contentStart, end);
    }

    private String buildAnswer(ChatModelRequest request) {
        String profile = request.profileSummary() == null || request.profileSummary().isBlank()
                ? "（画像尚未加载）"
                : request.profileSummary();
        return """
                【Mock 模式】对话链路已接通。

                你刚才问的是：%s

                当前画像：%s
                最近工作记忆：%d 条。
                知识库引用：%d 条。

                本轮骨架已经具备：
                1. SSE 流式输出；
                2. MySQL 会话与 Turn 落库；
                3. Redis 工作记忆窗口；
                4. 学习画像读取与 Prompt 注入；
                5. Obsidian 只读扫描与知识库引用。

                把 CODEMEM_AI_MOCK 设为 false，并配置 OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_CHAT_MODEL 后，会切换到真实模型。
                """.formatted(request.userMessage(), profile, request.memoryCount(), request.knowledgeCount());
    }

    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < text.length(); index += CHUNK_SIZE) {
            chunks.add(text.substring(index, Math.min(text.length(), index + CHUNK_SIZE)));
        }
        return chunks;
    }
}