package com.codemem.workflow;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 双角色执行轨迹，用于演示回放。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentRunController {

    private final AgentTraceRecorder recorder;

    public AgentRunController(AgentTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/runs/recent")
    public List<AgentRunView> recent(@RequestParam(defaultValue = "10") int limit) {
        return recorder.recent(limit);
    }

    @GetMapping("/runs/{traceId}")
    public ResponseEntity<AgentRunDetailView> detail(@PathVariable String traceId) {
        AgentRunDetailView detail = recorder.detail(traceId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }
}