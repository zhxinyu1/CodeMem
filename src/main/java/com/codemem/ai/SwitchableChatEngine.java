package com.codemem.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 在 Mock 与真实模型之间运行时切换。
 * 两个引擎都常驻，ChatService 只依赖 {@link ChatEngine}，因此切换不需要重启进程。
 */
@Component
@Primary
public class SwitchableChatEngine implements ChatEngine {

    private static final Logger log = LoggerFactory.getLogger(SwitchableChatEngine.class);

    private final MockChatEngine mockEngine;
    private final SpringAiChatEngine realEngine;
    private final ChatModeStore modeStore;

    public SwitchableChatEngine(MockChatEngine mockEngine,
                                SpringAiChatEngine realEngine,
                                ChatModeStore modeStore) {
        this.mockEngine = mockEngine;
        this.realEngine = realEngine;
        this.modeStore = modeStore;
        log.info("ChatEngine 初始模式 = {}", modeStore.isMock() ? "MOCK（模板回答）" : "真实模型");
    }

    private ChatEngine delegate() {
        return modeStore.isMock() ? mockEngine : realEngine;
    }

    @Override
    public Flux<String> stream(ChatModelRequest request) {
        return delegate().stream(request);
    }

    @Override
    public String complete(ChatModelRequest request) {
        return delegate().complete(request);
    }
}