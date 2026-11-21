package com.codemem.outbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxController {

    private final OutboxService outboxService;

    public OutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping("/status")
    public OutboxStatusView status() {
        return outboxService.status();
    }

    @GetMapping("/recent")
    public List<OutboxEventView> recent() {
        return outboxService.recent();
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        boolean retried = outboxService.retry(id);
        return Map.of("retried", retried, "id", id);
    }
}