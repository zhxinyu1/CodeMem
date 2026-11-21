package com.codemem.conversation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationGroupService groupService;

    public ConversationController(ConversationService conversationService,
                                  ConversationGroupService groupService) {
        this.conversationService = conversationService;
        this.groupService = groupService;
    }

    @GetMapping
    public List<ConversationView> list(@RequestParam(defaultValue = "default") String learnerId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return conversationService.listConversations(learnerId, page, size).stream()
                .map(ConversationView::from)
                .toList();
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable Long conversationId,
                                      @RequestParam(defaultValue = "default") String learnerId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "200") int size) {
return conversationService.listMessages(conversationId, learnerId, page, size).stream()
                .map(MessageView::from)
                .toList();
    }

    @DeleteMapping("/{conversationId}")
    public ConversationDeletedView delete(@PathVariable Long conversationId,
                                         @RequestParam(defaultValue = "default") String learnerId) {
        return conversationService.deleteConversation(conversationId, learnerId);
    }

    /**
     * 把会话归入分组；{@code groupId} 传 null（或 body 为空）表示移出分组。
     */
    @PatchMapping("/{conversationId}/group")
    public ResponseEntity<Map<String, Object>> assignGroup(@PathVariable Long conversationId,
                                                           @RequestParam(defaultValue = "default") String learnerId,
                                                           @RequestBody(required = false) AssignGroupRequest request) {
        Long groupId = request == null ? null : request.groupId();
        try {
            groupService.assignConversation(learnerId, conversationId, groupId);
            return ResponseEntity.ok(Map.of("assigned", true, "conversationId", conversationId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("assigned", false, "reason", ex.getMessage()));
        }
    }

    public record AssignGroupRequest(Long groupId) {
    }
}