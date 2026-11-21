package com.codemem.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话模式开关：Mock（模板回答，不发模型请求）与真实模型之间切换，即时生效。
 */
@RestController
@RequestMapping("/api/v1/settings/chat-mode")
public class ChatModeController {

    private final ChatModeStore store;

    public ChatModeController(ChatModeStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> get() {
        boolean mock = store.isMock();
        return Map.of("mock", mock, "mode", mock ? "mock" : "real");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) ChatModeRequest request) {
        if (request == null || request.mock() == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "缺少 mock 参数"));
        }
        store.setMock(request.mock());
        boolean mock = store.isMock();
        return ResponseEntity.ok(Map.of("ok", true, "mock", mock, "mode", mock ? "mock" : "real"));
    }

    public record ChatModeRequest(Boolean mock) {
    }
}