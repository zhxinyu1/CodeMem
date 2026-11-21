package com.codemem.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运行时对话模式开关。
 * 初始值取 {@code codemem.ai.mock}；设置页可以随时切换，切换后下一轮对话立即生效，不需要重启。
 */
@Component
public class ChatModeStore {

    private static final Logger log = LoggerFactory.getLogger(ChatModeStore.class);

    private volatile boolean mock;

    public ChatModeStore(@Value("${codemem.ai.mock:true}") boolean mock) {
        this.mock = mock;
    }

    public boolean isMock() {
        return mock;
    }

    public void setMock(boolean mock) {
        this.mock = mock;
        log.info("对话模式切换为 {}", mock ? "MOCK（模板回答）" : "真实模型");
    }
}