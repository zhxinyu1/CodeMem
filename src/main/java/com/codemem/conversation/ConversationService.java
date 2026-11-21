package com.codemem.conversation;

import com.codemem.common.ForbiddenException;
import com.codemem.common.NotFoundException;
import com.codemem.memory.working.WorkingMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int TITLE_MAX_LENGTH = 50;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatTurnRepository turns;
    private final JdbcTemplate jdbc;
    private final WorkingMemoryService workingMemory;

    public ConversationService(ConversationRepository conversations,
                               ChatMessageRepository messages,
                               ChatTurnRepository turns,
                               @Qualifier("mysqlJdbc") JdbcTemplate jdbc,
                               WorkingMemoryService workingMemory) {
        this.conversations = conversations;
        this.messages = messages;
        this.turns = turns;
        this.jdbc = jdbc;
        this.workingMemory = workingMemory;
    }

    @Transactional
    public TurnContext beginTurn(String learnerId, Long conversationId, String userText) {
        String normalizedLearnerId = normalizeLearnerId(learnerId);
        LocalDateTime now = LocalDateTime.now();

        Conversation conversation;
        if (conversationId == null) {
            conversation = conversations.save(new Conversation(
                    normalizedLearnerId,
                    buildTitle(userText),
                    now
            ));
        } else {
            conversation = conversations.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));
            if (!conversation.getLearnerId().equals(normalizedLearnerId)) {
                throw new IllegalArgumentException("conversation does not belong to learner: " + normalizedLearnerId);
            }
        }

        String turnId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        ChatTurn turn = turns.save(new ChatTurn(
                turnId,
                conversation.getId(),
                traceId,
                "STREAMING",
                now
        ));

        ChatMessage userMessage = messages.save(new ChatMessage(
                conversation.getId(),
                turnId,
                "user",
                userText,
                now
        ));

        conversation.setUpdatedAt(now);
        conversations.save(conversation);

        return new TurnContext(conversation, turn, userMessage, now);
    }

    @Transactional
    public ChatMessage completeTurn(TurnContext context, String answer, long latencyMs) {
        LocalDateTime now = LocalDateTime.now();
        ChatMessage assistantMessage = messages.save(new ChatMessage(
                context.conversation().getId(),
                context.turn().getId(),
                "assistant",
                answer,
                now
        ));

        ChatTurn turn = turns.findById(context.turn().getId())
                .orElseThrow(() -> new IllegalStateException("turn disappeared: " + context.turn().getId()));
        turn.setStatus("SUCCESS");
        turn.setLatencyMs(toIntMillis(latencyMs));
        turns.save(turn);

        Conversation conversation = conversations.findById(context.conversation().getId())
                .orElseThrow(() -> new IllegalStateException("conversation disappeared: " + context.conversation().getId()));
        conversation.setUpdatedAt(now);
        conversations.save(conversation);

        return assistantMessage;
    }

    @Transactional
    public void failTurn(String turnId, long latencyMs) {
        turns.findById(turnId).ifPresent(turn -> {
            turn.setStatus("FAILED");
            turn.setLatencyMs(toIntMillis(latencyMs));
            turns.save(turn);
        });
    }

    @Transactional(readOnly = true)
    public List<Conversation> listConversations(String learnerId, int page, int size) {
        int safeSize = Math.max(1, Math.min(200, size));
        int safePage = Math.max(0, page);
        return conversations.findByLearnerIdOrderByUpdatedAtDesc(
                normalizeLearnerId(learnerId), PageRequest.of(safePage, safeSize));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(Long conversationId, String learnerId, int page, int size) {
        String normalizedLearnerId = normalizeLearnerId(learnerId);
Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("conversation not found: " + conversationId));
        if (!conversation.getLearnerId().equals(normalizedLearnerId)) {
            throw new ForbiddenException("conversation does not belong to learner: " + normalizedLearnerId);
        }
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId, PageRequest.of(safePage, safeSize));
    }

    /**
     * 删除会话及其派生数据。
     *
     * 会话主体是 MySQL 的 conversation/message/turn 三张表，但一轮对话会派生抽取结果、
     * Agent 轨迹和 outbox 事件，这些都按 turn_id 挂着。这里按会话把整条链路清掉，
     * 避免留下悬空引用。学习者的 state_event 属于跨会话的学习状态，不随会话删除回滚。
     */
    @Transactional
    public ConversationDeletedView deleteConversation(Long conversationId, String learnerId) {
        String normalizedLearnerId = normalizeLearnerId(learnerId);
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("conversation not found: " + conversationId));
        if (!conversation.getLearnerId().equals(normalizedLearnerId)) {
            throw new ForbiddenException("conversation does not belong to learner: " + normalizedLearnerId);
        }

        List<String> turnIds = jdbc.queryForList(
                "SELECT id FROM turn WHERE conversation_id = ?", String.class, conversationId);

        int extractions = 0;
        int agentRuns = 0;
        int agentSteps = 0;
        int outboxEvents = 0;
        if (!turnIds.isEmpty()) {
            Object[] ids = turnIds.toArray();
            String in = placeholders(turnIds.size());
            agentSteps = jdbc.update(
                    "DELETE FROM agent_step WHERE run_id IN (SELECT id FROM agent_run WHERE turn_id IN (" + in + "))",
                    ids);
            agentRuns = jdbc.update("DELETE FROM agent_run WHERE turn_id IN (" + in + ")", ids);
            extractions = jdbc.update("DELETE FROM turn_extraction WHERE turn_id IN (" + in + ")", ids);
            outboxEvents = jdbc.update(
                    "DELETE FROM outbox_event WHERE aggregate_type = 'TURN' AND aggregate_id IN (" + in + ")", ids);
        }

        int deletedMessages = jdbc.update("DELETE FROM message WHERE conversation_id = ?", conversationId);
        int deletedTurns = jdbc.update("DELETE FROM turn WHERE conversation_id = ?", conversationId);
        jdbc.update("DELETE FROM conversation WHERE id = ?", conversationId);

        // 工作记忆在 Redis，删除失败不影响主流程（WorkingMemoryService 内部已兜底）
        workingMemory.clear(conversationId);

        log.info("Conversation {} deleted: messages={}, turns={}, extractions={}, agentRuns={}, agentSteps={}, outboxEvents={}",
                conversationId, deletedMessages, deletedTurns, extractions, agentRuns, agentSteps, outboxEvents);

        return new ConversationDeletedView(
                conversationId,
                deletedMessages,
                deletedTurns,
                extractions,
                agentRuns,
                agentSteps,
                outboxEvents
        );
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String normalizeLearnerId(String learnerId) {
        if (learnerId == null || learnerId.isBlank()) {
            return "default";
        }
        return learnerId.trim();
    }

    private String buildTitle(String userText) {
        String singleLine = userText.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= TITLE_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, TITLE_MAX_LENGTH) + "...";
    }

    private int toIntMillis(long latencyMs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs));
    }
}