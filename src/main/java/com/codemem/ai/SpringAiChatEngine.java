package com.codemem.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class SpringAiChatEngine implements ChatEngine {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatEngine.class);

    private final ChatClient chatClient;
    private final java.util.Map<String, Object> extraBody;

    public SpringAiChatEngine(ChatClient.Builder chatClientBuilder,
                              @org.springframework.beans.factory.annotation.Value("${codemem.ai.thinking:}")
                              String thinkingMode) {
        this.chatClient = chatClientBuilder.build();
this.extraBody = "disabled".equalsIgnoreCase(thinkingMode)
                ? java.util.Map.of("thinking", java.util.Map.of("type", "disabled"))
                : java.util.Map.of();
        log.debug("SpringAiChatEngine 已注册，thinking={}",
                extraBody.isEmpty() ? "默认(未关闭)" : "disabled");
    }

    /**
     * 逐次带上额外参数：DeepSeek 需要 {"thinking":{"type":"disabled"}} 才会关掉思考模式，
     * 只靠环境变量绑定不可靠。
     */
    private ChatClient.ChatClientRequestSpec prompt(ChatModelRequest request) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(request.systemPrompt())
                .user(request.userPrompt());
        if (!extraBody.isEmpty()) {
            spec = spec.options(OpenAiChatOptions.builder().extraBody(extraBody).build());
        }
        return spec;
    }

    /**
     * 真实模型下用「完整生成 + 分片回放」代替逐 token 流式：
     * DeepSeek 经 Spring AI 的流式映射会偶发返回空内容，导致兜底二次调用、成本翻倍。
     */
    @Override
    public Flux<String> stream(ChatModelRequest request) {
        return Flux.defer(() -> {
            String answer = complete(request);
            if (answer.isEmpty()) {
                return Flux.empty();
            }
            java.util.List<String> chunks = new java.util.ArrayList<>();
            int chunkSize = 12;
            for (int index = 0; index < answer.length(); index += chunkSize) {
                chunks.add(answer.substring(index, Math.min(answer.length(), index + chunkSize)));
            }
            return Flux.fromIterable(chunks).delayElements(java.time.Duration.ofMillis(12));
        });
    }

    @Override
    public String complete(ChatModelRequest request) {
        ChatResponse response = prompt(request).call().chatResponse();
        logUsage(response);
        String content = response == null ? null : response.getResult().getOutput().getText();
        return content == null ? "" : content;
    }

    private void logUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        log.info("LLM usage role={} prompt={} completion={} total={}",
                "chat", usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }
}