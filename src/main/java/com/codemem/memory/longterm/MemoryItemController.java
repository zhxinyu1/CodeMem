package com.codemem.memory.longterm;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆管理：列表、手动写入、归档、恢复与物理删除。
 * 归档与失效只改状态、保留历史；删除是物理删除，前端需二次确认。
 */
@RestController
@RequestMapping("/api/v1/memory/items")
public class MemoryItemController {

    private final MemoryItemService memoryItemService;

    public MemoryItemController(MemoryItemService memoryItemService) {
        this.memoryItemService = memoryItemService;
    }

/**
     * 记忆列表。{@code status} 默认 ACTIVE；记忆管理页传 ALL 或具体状态查看历史。
     */
    @GetMapping
    public List<MemoryItemView> list(@RequestParam(defaultValue = "default") String learnerId,
                                     @RequestParam(defaultValue = "default") String projectId,
                                     @RequestParam(defaultValue = "ACTIVE") String status,
                                     @RequestParam(defaultValue = "20") int limit) {
        return memoryItemService.list(learnerId, projectId, status, limit);
    }

    /**
     * 手动写入一条长期记忆。内容必填；类型、主题、重要度留空时按内容自动推断。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) MemoryCreateRequest request,
                                                      @RequestParam(defaultValue = "default") String learnerId,
                                                      @RequestParam(defaultValue = "default") String projectId) {
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.length() < 3) {
            return ResponseEntity.badRequest().body(Map.of(
                    "created", false,
                    "reason", "记忆内容至少 3 个字符"
            ));
        }
        boolean created = memoryItemService.addManual(
                learnerId, projectId, content, request.type(), request.topic(), request.importance());
        if (!created) {
            return ResponseEntity.status(409).body(Map.of(
                    "created", false,
                    "reason", "内容过短，或与已有生效记忆完全相同（重复只会加重确认，不会新增）"
            ));
        }
        return ResponseEntity.ok(Map.of("created", true, "content", content));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id,
                                                      @RequestParam(defaultValue = "default") String learnerId,
                                                      @RequestParam(defaultValue = "default") String projectId) {
        try {
            java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("deleted", false, "reason", "id 不是合法 UUID"));
        }
        boolean deleted = memoryItemService.delete(learnerId, projectId, id);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("deleted", false, "reason", "记忆不存在"));
        }
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable String id,
                                                       @RequestParam(defaultValue = "default") String learnerId,
                                                       @RequestParam(defaultValue = "default") String projectId) {
        try {
            java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("archived", false, "reason", "id 不是合法 UUID"));
        }
        boolean archived = memoryItemService.archive(learnerId, projectId, id);
        if (!archived) {
            return ResponseEntity.status(404).body(Map.of(
                    "archived", false,
                    "reason", "记忆不存在或已归档"
            ));
        }
        return ResponseEntity.ok(Map.of("archived", true, "id", id));
    }

    /**
     * 恢复归档：只接受 ARCHIVED 状态的记忆，恢复后重新参与召回。
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String id,
                                                       @RequestParam(defaultValue = "default") String learnerId,
                                                       @RequestParam(defaultValue = "default") String projectId) {
        try {
            java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("restored", false, "reason", "id 不是合法 UUID"));
        }
        boolean restored = memoryItemService.restore(learnerId, projectId, id);
        if (!restored) {
            return ResponseEntity.status(404).body(Map.of(
                    "restored", false,
                    "reason", "记忆不存在或不在归档状态"
            ));
        }
        return ResponseEntity.ok(Map.of("restored", true, "id", id));
    }

    // ================= 全局指令（每轮回答前注入并强制遵守） =================

    /** 当前生效的指令，按 priority 升序，就是每轮注入的那批。 */
    @GetMapping("/instructions")
    public List<MemoryItemView> instructions(@RequestParam(defaultValue = "default") String learnerId,
                                             @RequestParam(defaultValue = "default") String projectId) {
        return memoryItemService.listInstructions(learnerId, projectId);
    }

    /** 预览本轮会注入 Prompt 的完整指令块，便于确认“到底遵守了什么”。 */
    @GetMapping("/instructions/preview")
    public Map<String, Object> preview(@RequestParam(defaultValue = "default") String learnerId,
                                       @RequestParam(defaultValue = "default") String projectId) {
        List<MemoryItemView> instructions = memoryItemService.listInstructions(learnerId, projectId);
        return Map.of(
                "count", instructions.size(),
                "block", MemoryItemService.instructionsBlock(instructions)
        );
    }

    @PostMapping("/instructions")
    public ResponseEntity<Map<String, Object>> createInstruction(@RequestBody InstructionRequest body,
                                                                 @RequestParam(defaultValue = "default") String learnerId,
                                                                 @RequestParam(defaultValue = "default") String projectId) {
        try {
            MemoryItemView created = memoryItemService.createInstruction(
                    learnerId, projectId, body.content(), body.priority());
            return ResponseEntity.ok(Map.of("created", true, "instruction", created));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "reason", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(503).body(Map.of("created", false, "reason", ex.getMessage()));
        }
    }

    @PutMapping("/instructions/{id}")
    public ResponseEntity<Map<String, Object>> updateInstruction(@PathVariable String id,
                                                                 @RequestBody InstructionRequest body,
                                                                 @RequestParam(defaultValue = "default") String learnerId,
                                                                 @RequestParam(defaultValue = "default") String projectId) {
        try {
            java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("updated", false, "reason", "id 不是合法 UUID"));
        }
        boolean updated = memoryItemService.updateInstruction(
                learnerId, projectId, id, body.content(), body.priority(), body.active());
        if (!updated) {
            return ResponseEntity.status(404).body(Map.of("updated", false, "reason", "指令不存在或未变更"));
        }
        return ResponseEntity.ok(Map.of("updated", true, "id", id));
    }

    /** 把一条自动抽取的记忆升级为全局指令。 */
    @PostMapping("/{id}/promote")
    public ResponseEntity<Map<String, Object>> promote(@PathVariable String id,
                                                       @RequestParam(defaultValue = "default") String learnerId,
                                                       @RequestParam(defaultValue = "default") String projectId,
                                                       @RequestParam(required = false) Integer priority) {
        try {
            java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("promoted", false, "reason", "id 不是合法 UUID"));
        }
        boolean promoted = memoryItemService.promoteToInstruction(learnerId, projectId, id, priority);
        if (!promoted) {
            return ResponseEntity.status(404).body(Map.of("promoted", false, "reason", "记忆不存在"));
        }
        return ResponseEntity.ok(Map.of("promoted", true, "id", id));
    }

    public record InstructionRequest(String content, Integer priority, Boolean active) {
    }

    public record MemoryCreateRequest(String content, String type, String topic, Double importance) {
    }
}