package com.codemem.conversation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话自定义分组：新建、重命名、删除，以及把会话归入/移出分组。
 * 分组只做归类展示；删除分组时组内会话回到“未分组”，会话与消息不受影响。
 */
@Service
public class ConversationGroupService {

    private static final int NAME_MAX_LENGTH = 64;

    private final ConversationGroupRepository groups;
    private final JdbcTemplate jdbc;

    public ConversationGroupService(ConversationGroupRepository groups,
                                    @Qualifier("mysqlJdbc") JdbcTemplate jdbc) {
        this.groups = groups;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ConversationGroupView> list(String learnerId) {
        String normalized = normalizeLearnerId(learnerId);
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT group_id, COUNT(*) AS total
                FROM conversation
                WHERE learner_id = ? AND group_id IS NOT NULL
                GROUP BY group_id
                """, normalized)) {
            counts.put(((Number) row.get("group_id")).longValue(), ((Number) row.get("total")).longValue());
        }
        return groups.findByLearnerIdOrderBySortOrderAscIdAsc(normalized).stream()
                .map(group -> new ConversationGroupView(group.getId(), group.getName(),
                        counts.getOrDefault(group.getId(), 0L)))
                .toList();
    }

    @Transactional
    public ConversationGroupView create(String learnerId, String name) {
        String normalized = normalizeLearnerId(learnerId);
        String clean = cleanName(name);
        if (groups.existsByLearnerIdAndName(normalized, clean)) {
            throw new IllegalArgumentException("分组已存在：" + clean);
        }
        int nextOrder = groups.findByLearnerIdOrderBySortOrderAscIdAsc(normalized).stream()
                .mapToInt(ConversationGroup::getSortOrder)
                .max()
                .orElse(0) + 1;
        ConversationGroup saved = groups.save(new ConversationGroup(normalized, clean, nextOrder, LocalDateTime.now()));
        return new ConversationGroupView(saved.getId(), saved.getName(), 0);
    }

    @Transactional
    public ConversationGroupView rename(String learnerId, Long id, String name) {
        String normalized = normalizeLearnerId(learnerId);
        String clean = cleanName(name);
        ConversationGroup group = groups.findByIdAndLearnerId(id, normalized)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在：" + id));
        if (!group.getName().equals(clean) && groups.existsByLearnerIdAndName(normalized, clean)) {
            throw new IllegalArgumentException("分组已存在：" + clean);
        }
        group.setName(clean);
        group.setUpdatedAt(LocalDateTime.now());
        ConversationGroup saved = groups.save(group);
        return new ConversationGroupView(saved.getId(), saved.getName(),
                countConversations(normalized, saved.getId()));
    }

    @Transactional
    public void delete(String learnerId, Long id) {
        String normalized = normalizeLearnerId(learnerId);
        groups.findByIdAndLearnerId(id, normalized)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在：" + id));
        jdbc.update("UPDATE conversation SET group_id = NULL WHERE group_id = ? AND learner_id = ?", id, normalized);
        groups.deleteById(id);
    }

    @Transactional
    public void assignConversation(String learnerId, Long conversationId, Long groupId) {
        String normalized = normalizeLearnerId(learnerId);
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id = ? AND learner_id = ?",
                Integer.class, conversationId, normalized);
        if (owned == null || owned == 0) {
            throw new IllegalArgumentException("会话不存在：" + conversationId);
        }
        if (groupId != null) {
            groups.findByIdAndLearnerId(groupId, normalized)
                    .orElseThrow(() -> new IllegalArgumentException("分组不存在：" + groupId));
        }
        jdbc.update("UPDATE conversation SET group_id = ? WHERE id = ? AND learner_id = ?",
                groupId, conversationId, normalized);
    }

    private long countConversations(String learnerId, Long groupId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE learner_id = ? AND group_id = ?",
                Long.class, learnerId, groupId);
        return total == null ? 0 : total;
    }

    private String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("分组名称不能为空");
        }
        if (clean.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("分组名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return clean;
    }

    private String normalizeLearnerId(String learnerId) {
        if (learnerId == null || learnerId.isBlank()) {
            return "default";
        }
        return learnerId.trim();
    }
}