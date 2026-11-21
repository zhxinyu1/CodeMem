package com.codemem.memory.working;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class WorkingMemoryService {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int windowSize;
    private final Duration ttl;

    public WorkingMemoryService(StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                @Value("${codemem.memory.window-size:12}") int windowSize,
                                @Value("${codemem.memory.ttl:7d}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.windowSize = Math.max(2, windowSize);
        this.ttl = ttl;
    }

    public void append(Long conversationId, String role, String content) {
        String key = messagesKey(conversationId);
        try {
            String json = objectMapper.writeValueAsString(new WorkingMessage(
                    role,
                    abbreviate(content, 8000),
                    Instant.now().toString()
            ));
            redis.opsForList().rightPush(key, json);
            redis.opsForList().trim(key, -windowSize, -1);
            redis.expire(key, ttl);
            redis.expire(summaryKey(conversationId), ttl);
        } catch (Exception ex) {
            // Redis is a performance layer. A conversation must still work without it.
            log.warn("Unable to append working memory for conversation {}: {}", conversationId, ex.getMessage());
        }
    }

    public List<WorkingMessage> recent(Long conversationId) {
        try {
            List<String> raw = redis.opsForList().range(messagesKey(conversationId), 0, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<WorkingMessage> result = new ArrayList<>(raw.size());
            for (String item : raw) {
                try {
                    result.add(objectMapper.readValue(item, WorkingMessage.class));
                } catch (Exception ignored) {
                    log.debug("Skipping malformed working memory item: {}", item);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Unable to read working memory for conversation {}: {}", conversationId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    public String summary(Long conversationId) {
        try {
            String summary = redis.opsForValue().get(summaryKey(conversationId));
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        } catch (Exception ex) {
            log.warn("Unable to read working memory summary for conversation {}: {}", conversationId, ex.getMessage());
        }
        return buildSummary(recent(conversationId));
    }

    public String refreshSummary(Long conversationId) {
        String summary = buildSummary(recent(conversationId));
        try {
            redis.opsForValue().set(summaryKey(conversationId), summary, ttl);
        } catch (Exception ex) {
            log.warn("Unable to refresh working memory summary for conversation {}: {}", conversationId, ex.getMessage());
        }
        return summary;
    }

    public void clear(Long conversationId) {
        try {
            redis.delete(List.of(messagesKey(conversationId), summaryKey(conversationId)));
        } catch (Exception ex) {
            log.warn("Unable to clear working memory for conversation {}: {}", conversationId, ex.getMessage());
        }
    }

    private String buildSummary(List<WorkingMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (WorkingMessage message : messages) {
            builder.append(message.role())
                    .append(": ")
                    .append(abbreviate(message.content(), 160))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private String messagesKey(Long conversationId) {
        return "wm:" + conversationId + ":messages";
    }

    private String summaryKey(Long conversationId) {
        return "wm:" + conversationId + ":summary";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}