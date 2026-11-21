package com.codemem.conversation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话分组管理：列表（含组内会话数）、新建、重命名、删除。
 * 删除分组不会删除会话，组内会话回到未分组。
 */
@RestController
@RequestMapping("/api/v1/conversation-groups")
public class ConversationGroupController {

    private final ConversationGroupService groupService;

    public ConversationGroupController(ConversationGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public List<ConversationGroupView> list(@RequestParam(defaultValue = "default") String learnerId) {
        return groupService.list(learnerId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestParam(defaultValue = "default") String learnerId,
                                                      @RequestBody(required = false) GroupNameRequest request) {
        try {
            ConversationGroupView created = groupService.create(learnerId, request == null ? null : request.name());
            return ResponseEntity.ok(Map.of("created", true, "group", created));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "reason", ex.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "default") String learnerId,
                                                      @RequestBody(required = false) GroupNameRequest request) {
        try {
            ConversationGroupView renamed = groupService.rename(learnerId, id, request == null ? null : request.name());
            return ResponseEntity.ok(Map.of("renamed", true, "group", renamed));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("renamed", false, "reason", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "default") String learnerId) {
        try {
            groupService.delete(learnerId, id);
            return ResponseEntity.ok(Map.of("deleted", true, "id", id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("deleted", false, "reason", ex.getMessage()));
        }
    }

    public record GroupNameRequest(String name) {
    }
}