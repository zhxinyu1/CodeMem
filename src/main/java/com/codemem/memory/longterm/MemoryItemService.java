package com.codemem.memory.longterm;

import com.codemem.embedding.EmbeddingService;
import com.codemem.knowledge.ChineseTokenizer;
import com.codemem.knowledge.HashUtils;
import com.codemem.knowledge.PostgresSchemaInitializer;
import com.codemem.knowledge.TsQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期语义记忆：写入去重、矛盾失效、向量 + 关键词混合召回。
 */
@Service
public class MemoryItemService {

    private static final Logger log = LoggerFactory.getLogger(MemoryItemService.class);

    /** 指令不再做排序，统一写入该默认值，仅保留列兼容。 */
    private static final int DEFAULT_PRIORITY = 100;
    private static final int CANDIDATE_LIMIT = 20;
    private static final int RRF_K = 60;
    private static final int MAX_PER_TOPIC = 2;
private static final double DENSE_MIN_SCORE = 0.55;
    private static final double HALF_LIFE_DAYS = 30.0;
    private static final double IMPORTANCE_BOOST = 0.05;
    private static final double CONFIDENCE_BOOST = 0.02;

    private final JdbcTemplate postgresJdbc;
    private final EmbeddingService embeddingService;
    private final ChineseTokenizer tokenizer;
    private final PostgresSchemaInitializer schemaInitializer;

    public MemoryItemService(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                             EmbeddingService embeddingService,
                             ChineseTokenizer tokenizer,
                             PostgresSchemaInitializer schemaInitializer) {
        this.postgresJdbc = postgresJdbc;
        this.embeddingService = embeddingService;
        this.tokenizer = tokenizer;
        this.schemaInitializer = schemaInitializer;
    }

    /**
     * 把抽取候选写入长期记忆：同内容去重，同主题的旧记忆失效（保留历史，不删除）。
     */
    public int recordCandidates(String learnerId, String projectId,
                                List<String> candidates, String sourceType, String sourceId) {
        // 记忆改为纯手写（全局指令）：系统不再自动抽取。
        // 保留签名是为了不牵动 Outbox 抽取链路，返回值恒为 0 表示“不写入”。
        return 0;
    }

    /**
     * 手动纠错：旧主题记忆立即失效，新记忆可被检索。
     */
    public boolean recordCorrection(String learnerId, String projectId, String topic, String content, String sourceId) {
        // 同上：纠错也不再写记忆，避免系统侧产生用户没写过的内容。
        return false;
    }

    /**
     * 手动写入一条长期记忆：用户自己录入，来源标 MANUAL。
     * 同主题的历史生效记忆会被置为 SUPERSEDED（保留历史，不删除）；完全相同内容只做加权确认。
     */
    public boolean addManual(String learnerId, String projectId, String content,
                             String type, String topic, Double importance) {
        if (!schemaInitializer.isAvailable()) {
            return false;
        }
        try {
            return recordOne(learnerId, projectId, content, "MANUAL", "manual:web", type, topic, importance);
        } catch (RuntimeException ex) {
            log.warn("Unable to add manual memory for learner {}: {}", learnerId, ex.getMessage());
            return false;
        }
    }

    /**
     * 恢复归档：重新置为 ACTIVE、清掉失效时间，重新参与召回。
     */
    public boolean restore(String learnerId, String projectId, String id) {
        if (!schemaInitializer.isAvailable() || id == null || id.isBlank()) {
            return false;
        }
        try {
            int updated = postgresJdbc.update("""
                    UPDATE memory_item
                    SET status = 'ACTIVE', valid_to = NULL, last_accessed_at = now()
                    WHERE id = ?::uuid AND learner_id = ? AND project_id = ? AND status = 'ARCHIVED'
                    """, id, learnerId, projectId);
            return updated > 0;
        } catch (RuntimeException ex) {
            log.warn("Unable to restore memory {}: {}", id, ex.getMessage());
            return false;
        }
    }

    private static String normalizeType(String raw) {
        String type = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case "FACT", "WEAKNESS", "PLAN", "MASTERY" -> type;
            default -> null;
        };
    }

    private boolean recordOne(String learnerId, String projectId,
                              String raw, String sourceType, String sourceId) {
        return recordOne(learnerId, projectId, raw, sourceType, sourceId, null, null, null);
    }

    private boolean recordOne(String learnerId, String projectId,
                              String raw, String sourceType, String sourceId,
                              String typeOverride, String topicOverride, Double importanceOverride) {
        String content = normalize(raw);
        if (content.length() < 3) {
            return false;
        }
        String hash = HashUtils.sha256(learnerId + "|" + content);
        String type = classify(content);
        if (typeOverride != null && !typeOverride.isBlank()) {
            String normalizedType = normalizeType(typeOverride);
            if (normalizedType != null) {
                type = normalizedType;
            }
        }
        String topic = topicOverride == null || topicOverride.isBlank() ? topicOf(content) : topicOverride.trim();

        Integer existing = postgresJdbc.queryForObject(
                "SELECT count(*) FROM memory_item WHERE learner_id = ? AND content_hash = ? AND status = 'ACTIVE'",
                Integer.class, learnerId, hash);
        if (existing != null && existing > 0) {
            postgresJdbc.update("""
                    UPDATE memory_item
                    SET confidence = LEAST(1.0, confidence + %s),
                        importance = LEAST(1.0, importance + %s),
                        last_accessed_at = now()
                    WHERE learner_id = ? AND content_hash = ? AND status = 'ACTIVE'
                    """.formatted(CONFIDENCE_BOOST, IMPORTANCE_BOOST), learnerId, hash);
            return false;
        }

        if (!topic.isBlank()) {
            postgresJdbc.update("""
                    UPDATE memory_item
                    SET status = 'SUPERSEDED', valid_to = now()
                    WHERE learner_id = ? AND project_id = ? AND topic = ? AND status = 'ACTIVE' AND content_hash <> ?
                    """, learnerId, projectId, topic, hash);
        }

        String vector = null;
        try {
            vector = toVectorLiteral(embeddingService.embed(content));
        } catch (RuntimeException ex) {
            log.warn("Embedding unavailable for memory content, fallback to keyword only: {}", ex.getMessage());
        }
        String searchText = tokenizer.tokenize(content);
        String summary = content.length() <= 200 ? content : content.substring(0, 200);

        int inserted = postgresJdbc.update("""
                        INSERT INTO memory_item (
                            id, learner_id, project_id, type, scope, content, summary, source_type, source_id,
                            topic, importance, confidence, status, content_hash, valid_from, embedding, search_text
                        ) VALUES (?::uuid, ?, ?, ?, 'PROJECT', ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, now(), ?::vector, ?)
                        ON CONFLICT (learner_id, content_hash) WHERE status = 'ACTIVE' DO NOTHING
                        """,
                UUID.randomUUID().toString(),
                learnerId,
                projectId,
                type,
                content,
                summary,
                sourceType,
                sourceId,
                topic,
importanceOverride != null
                        ? Math.max(0.0, Math.min(1.0, importanceOverride))
                        : (sourceType.equals("MANUAL") ? 1.0 : 0.6),
                sourceType.equals("MANUAL") ? 1.0 : 0.7,
                hash,
                vector,
                searchText
        );
        if (inserted == 0) {
            // 并发下另一个线程已写入同一事实：转为确认加权，不算新增
            postgresJdbc.update("""
                    UPDATE memory_item
                    SET confidence = LEAST(1.0, confidence + %s),
                        importance = LEAST(1.0, importance + %s),
                        last_accessed_at = now()
                    WHERE learner_id = ? AND content_hash = ? AND status = 'ACTIVE'
                    """.formatted(CONFIDENCE_BOOST, IMPORTANCE_BOOST), learnerId, hash);
            return false;
        }
        return true;
    }

    public List<MemoryItemView> search(String learnerId, String projectId, String query, int requestedTopK) {
        if (!schemaInitializer.isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        int topK = Math.max(1, Math.min(8, requestedTopK));
        try {
            List<ScoredMemory> dense = List.of();
            try {
                String vector = toVectorLiteral(embeddingService.embed(query));
                dense = postgresJdbc.query("""
                                SELECT id::text, content, topic, type, source_type, status, valid_to,
                                       kind, priority,
                                       importance, confidence,
                                       created_at, last_accessed_at,
                                       1 - (embedding <=> ?::vector) AS score
                                FROM memory_item
                                WHERE learner_id = ? AND project_id = ? AND status = 'ACTIVE' AND embedding IS NOT NULL
                                ORDER BY embedding <=> ?::vector
                                LIMIT ?
                                """,
                        (resultSet, rowNum) -> mapRow(resultSet),
                        vector, learnerId, projectId, vector, CANDIDATE_LIMIT);
            } catch (RuntimeException ex) {
                log.warn("Memory dense search failed: {}", ex.getMessage());
            }

            String keywordQuery = TsQueryBuilder.build(tokenizer.tokenize(query));
            List<ScoredMemory> keyword = keywordQuery.isBlank()
                    ? List.of()
                    : postgresJdbc.query("""
                            SELECT id::text, content, topic, type, source_type, status, valid_to,
                                   kind, priority,
                                   importance, confidence,
                                   created_at, last_accessed_at,
                                   ts_rank_cd(to_tsvector('simple', search_text), to_tsquery('simple', ?)) AS score
                            FROM memory_item
                            WHERE learner_id = ? AND project_id = ? AND status = 'ACTIVE'
                              AND search_text IS NOT NULL
                              AND to_tsvector('simple', search_text) @@ to_tsquery('simple', ?)
                            ORDER BY score DESC
                            LIMIT ?
                            """,
                    (resultSet, rowNum) -> mapRow(resultSet),
                    keywordQuery, learnerId, projectId, keywordQuery, CANDIDATE_LIMIT);

            List<MemoryItemView> fused = fuse(dense, keyword, topK);
            return touch(fused);
        } catch (RuntimeException ex) {
            log.warn("Memory search failed for query '{}': {}", query, ex.getMessage());
            return List.of();
        }
    }

    public int countActive(String learnerId, String projectId) {
        if (!schemaInitializer.isAvailable()) {
            return 0;
        }
        Integer count = postgresJdbc.queryForObject(
                "SELECT count(*) FROM memory_item WHERE learner_id = ? AND project_id = ? AND status = 'ACTIVE'",
                Integer.class, learnerId, projectId);
        return count == null ? 0 : count;
    }

    /**
     * 指数衰减：以最近访问时间（没有则创建时间）为锚点，30 天半衰期。
     * 冷记忆排序自然下沉，但不删除，仍可被精确关键词召回后重新升温。
     */
    /**
     * 使用频率加权：每次召回 +0.05，重复抽取再次确认 +0.05；单一来源不能无限抬高，封顶 1.0。
     */
    public static double boostedImportance(double current) {
        return Math.min(1.0, current + IMPORTANCE_BOOST);
    }

    public static double boostedConfidence(double current) {
        return Math.min(1.0, current + CONFIDENCE_BOOST);
    }

    public static double decayFactor(Instant lastAccessedAt, Instant createdAt, Instant now) {
        Instant anchor = lastAccessedAt != null ? lastAccessedAt : createdAt;
        if (anchor == null || now == null) {
            return 1.0;
        }
        double days = Math.max(0.0, (now.toEpochMilli() - anchor.toEpochMilli()) / 86_400_000.0);
        return Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    /**
     * 手动归档：置为 ARCHIVED 并写 valid_to，之后不再参与召回，但保留历史。
     */
    public boolean archive(String learnerId, String projectId, String id) {
        if (!schemaInitializer.isAvailable() || id == null || id.isBlank()) {
            return false;
        }
        try {
            int updated = postgresJdbc.update("""
                    UPDATE memory_item
                    SET status = 'ARCHIVED', valid_to = now()
                    WHERE id = ?::uuid AND learner_id = ? AND project_id = ? AND status = 'ACTIVE'
                    """, id, learnerId, projectId);
            return updated > 0;
        } catch (RuntimeException ex) {
            log.warn("Unable to archive memory {}: {}", id, ex.getMessage());
            return false;
        }
    }

/**
     * 记忆管理列表：按最近活跃时间排序，分数用「重要度 × 衰减」便于前端展示冷热。
     *
     * @param status "ALL" 返回全部状态，其余按具体状态（ACTIVE / ARCHIVED / SUPERSEDED）过滤
     */
// ================= 全局指令（每轮回答前注入并强制遵守） =================

    /**
     * 当前生效的全局指令，按 priority 升序（越小越先注入）。
     */
    public List<MemoryItemView> listInstructions(String learnerId, String projectId) {
        if (!schemaInitializer.isAvailable()) {
            return List.of();
        }
        try {
            return postgresJdbc.query("""
                            SELECT id::text, content, topic, type, source_type, status, valid_to,
                                   importance, confidence, kind, priority
                            FROM memory_item
                            WHERE learner_id = ? AND project_id = ?
                              AND kind = 'INSTRUCTION' AND status = 'ACTIVE'
                            ORDER BY created_at ASC, id ASC
                            """,
                    (resultSet, rowNum) -> new MemoryItemView(
                            resultSet.getString("id"),
                            resultSet.getString("content"),
                            resultSet.getString("topic"),
                            resultSet.getString("type"),
                            resultSet.getString("source_type"),
                            1.0,
                            resultSet.getDouble("importance"),
                            resultSet.getDouble("confidence"),
                            resultSet.getString("status"),
                            resultSet.getTimestamp("valid_to") == null
                                    ? null
                                    : resultSet.getTimestamp("valid_to").toInstant(),
                            resultSet.getString("kind"),
                            resultSet.getInt("priority")
                    ),
                    learnerId, projectId);
        } catch (RuntimeException ex) {
            log.warn("Unable to list instructions: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 手工新增一条全局指令。 */
    public MemoryItemView createInstruction(String learnerId, String projectId, String content, Integer priority) {
        if (!schemaInitializer.isAvailable()) {
            throw new IllegalStateException("记忆存储不可用（PostgreSQL 未就绪）");
        }
        String text = normalizeInstruction(content);
        int prio = DEFAULT_PRIORITY;
        String hash = HashUtils.sha256(learnerId + "|" + text);
        String id = UUID.randomUUID().toString();
        int inserted = postgresJdbc.update("""
                        INSERT INTO memory_item (
                            id, learner_id, project_id, type, scope, content, summary, source_type, source_id,
                            topic, importance, confidence, status, content_hash, valid_from, kind, priority
                        ) VALUES (?::uuid, ?, ?, 'INSTRUCTION', 'PROJECT', ?, ?, 'MANUAL', NULL,
                                  ?, 1.0, 1.0, 'ACTIVE', ?, now(), 'INSTRUCTION', ?)
                        ON CONFLICT (learner_id, content_hash) WHERE status = 'ACTIVE' DO NOTHING
                        """,
                id, learnerId, projectId, text, text.length() <= 200 ? text : text.substring(0, 200),
                "全局指令", hash, prio);
        if (inserted == 0) {
            throw new IllegalArgumentException("同样的指令已经存在");
        }
        return instructionsById(id);
    }

    /** 修改指令内容 / 优先级 / 启停。返回是否有字段真正被更新。 */
    public boolean updateInstruction(String learnerId, String projectId, String id,
                                     String content, Integer priority, Boolean active) {
        if (!schemaInitializer.isAvailable() || id == null || id.isBlank()) {
            return false;
        }
        int changed = 0;
        try {
            if (content != null && !content.isBlank()) {
                String text = normalizeInstruction(content);
                String hash = HashUtils.sha256(learnerId + "|" + text);
                changed += postgresJdbc.update("""
                        UPDATE memory_item
                        SET content = ?, summary = ?, content_hash = ?
                        WHERE id = ?::uuid AND learner_id = ? AND project_id = ? AND kind = 'INSTRUCTION'
                        """,
                        text, text.length() <= 200 ? text : text.substring(0, 200), hash,
                        id, learnerId, projectId);
            }
            if (active != null) {
                changed += postgresJdbc.update("""
                        UPDATE memory_item
                        SET status = ?, valid_to = CASE WHEN ? THEN NULL ELSE now() END
                        WHERE id = ?::uuid AND learner_id = ? AND project_id = ? AND kind = 'INSTRUCTION'
                        """, active ? "ACTIVE" : "ARCHIVED", active, id, learnerId, projectId);
            }
        } catch (RuntimeException ex) {
            log.warn("Unable to update instruction {}: {}", id, ex.getMessage());
            return false;
        }
        return changed > 0;
    }

    /** 把一条自动抽取的记忆升级为全局指令（内容不变，改为强制遵守）。 */
    public boolean promoteToInstruction(String learnerId, String projectId, String id, Integer priority) {
        if (!schemaInitializer.isAvailable() || id == null || id.isBlank()) {
            return false;
        }
        try {
            int changed = postgresJdbc.update("""
                    UPDATE memory_item
                    SET kind = 'INSTRUCTION', type = 'INSTRUCTION', status = 'ACTIVE',
                        priority = ?, importance = 1.0, confidence = 1.0, valid_to = NULL
                    WHERE id = ?::uuid AND learner_id = ? AND project_id = ?
                    """, DEFAULT_PRIORITY, id, learnerId, projectId);
            return changed > 0;
        } catch (RuntimeException ex) {
            log.warn("Unable to promote memory {} to instruction: {}", id, ex.getMessage());
            return false;
        }
    }

    private MemoryItemView instructionsById(String id) {
        return postgresJdbc.queryForObject("""
                        SELECT id::text, content, topic, type, source_type, status, valid_to,
                               importance, confidence, kind, priority
                        FROM memory_item WHERE id = ?::uuid
                        """,
                (resultSet, rowNum) -> new MemoryItemView(
                        resultSet.getString("id"),
                        resultSet.getString("content"),
                        resultSet.getString("topic"),
                        resultSet.getString("type"),
                        resultSet.getString("source_type"),
                        1.0,
                        resultSet.getDouble("importance"),
                        resultSet.getDouble("confidence"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("valid_to") == null
                                ? null
                                : resultSet.getTimestamp("valid_to").toInstant(),
                        resultSet.getString("kind"),
                        resultSet.getInt("priority")
                ), id);
    }

    /** 把指令列表渲染成注入 Prompt 的文本块（预览接口与 Prompt 共用同一份实现）。 */
    public static String instructionsBlock(List<MemoryItemView> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return "（用户还没有设置全局指令）";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (MemoryItemView instruction : instructions) {
            builder.append(index++).append(". ").append(instruction.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private static String normalizeInstruction(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("指令内容不能为空");
        }
        String text = content.trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static int normalizePriority(Integer priority) {
        if (priority == null) {
            return 100;
        }
        return Math.max(0, Math.min(999, priority));
    }

    public List<MemoryItemView> list(String learnerId, String projectId, String status, int limit) {
        if (!schemaInitializer.isAvailable()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(200, limit));
        String filter = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null
                : status.trim().toUpperCase(java.util.Locale.ROOT);
        Instant now = Instant.now();
        try {
            return postgresJdbc.query("""
                            SELECT id::text, content, topic, type, source_type, status, valid_to,
                                   kind, priority,
                                   created_at, last_accessed_at, importance, confidence
FROM memory_item
                            WHERE learner_id = ? AND project_id = ?
                              AND COALESCE(kind, '') <> 'INSTRUCTION'
                              AND (CAST(? AS text) IS NULL OR status = CAST(? AS text))
                            ORDER BY COALESCE(last_accessed_at, created_at) DESC
                            LIMIT ?
                            """,
                    (resultSet, rowNum) -> {
                        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
                        java.sql.Timestamp accessed = resultSet.getTimestamp("last_accessed_at");
                        Instant accessedAt = accessed == null ? null : accessed.toInstant();
                        double score = resultSet.getDouble("importance")
                                * decayFactor(accessedAt, createdAt, now);
                        return new MemoryItemView(
                                resultSet.getString("id"),
                                resultSet.getString("content"),
                                resultSet.getString("topic"),
                                resultSet.getString("type"),
                                resultSet.getString("source_type"),
                                score,
                                resultSet.getDouble("importance"),
                                resultSet.getDouble("confidence"),
                                resultSet.getString("status"),
                                resultSet.getTimestamp("valid_to") == null
                                        ? null
                                        : resultSet.getTimestamp("valid_to").toInstant(),
                                resultSet.getString("kind"),
                                resultSet.getInt("priority")
                        );
},
                    learnerId, projectId, filter, filter, safeLimit);
        } catch (RuntimeException ex) {
            log.warn("Unable to list memories: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 只看生效中的记忆（对话召回与检查器面板用）。 */
    public List<MemoryItemView> listActive(String learnerId, String projectId, int limit) {
        return list(learnerId, projectId, "ACTIVE", limit);
    }

    /** 物理删除一条记忆；只删当前 learnerId + projectId 名下的，避免跨分区误删。 */
    public boolean delete(String learnerId, String projectId, String id) {
        if (!schemaInitializer.isAvailable() || id == null || id.isBlank()) {
            return false;
        }
        try {
            int deleted = postgresJdbc.update(
                    "DELETE FROM memory_item WHERE id = ?::uuid AND learner_id = ? AND project_id = ?",
                    id, learnerId, projectId);
            return deleted > 0;
        } catch (RuntimeException ex) {
            log.warn("Unable to delete memory {}: {}", id, ex.getMessage());
            return false;
        }
    }

    private List<MemoryItemView> fuse(List<ScoredMemory> dense, List<ScoredMemory> keyword, int topK) {
        Map<String, Fusion> fused = new LinkedHashMap<>();
        List<ScoredMemory> usefulDense = keyword.isEmpty()
                ? dense
                : dense.stream().filter(memory -> memory.score >= DENSE_MIN_SCORE).toList();
        addRanked(fused, usefulDense, 0.9);
        addRanked(fused, keyword, 1.1);

        List<Fusion> ranked = new ArrayList<>(fused.values());
        ranked.sort((left, right) -> Double.compare(right.score, left.score));

        List<MemoryItemView> views = new ArrayList<>();
        Map<String, Integer> perTopic = new LinkedHashMap<>();
        for (Fusion fusion : ranked) {
            if (views.size() >= topK) {
                break;
            }
            String topic = fusion.memory.topic == null ? "" : fusion.memory.topic;
            int count = perTopic.getOrDefault(topic, 0);
            if (!topic.isBlank() && count >= MAX_PER_TOPIC) {
                continue;
            }
            double decayed = fusion.score * decayFactor(
                    fusion.memory.lastAccessedAt, fusion.memory.createdAt, Instant.now());
            ScoredMemory memory = fusion.memory;
            views.add(new MemoryItemView(
                    memory.id(),
                    memory.content(),
                    memory.topic(),
                    memory.type(),
                    memory.sourceType(),
                    decayed,
                    memory.importance(),
                    memory.confidence(),
                    memory.status(),
                    memory.validTo(),
                    memory.kind(),
                    memory.priority()
            ));
            perTopic.put(topic, count + 1);
        }
        return views;
    }

    private void addRanked(Map<String, Fusion> fused, List<ScoredMemory> memories, double weight) {
        for (int index = 0; index < memories.size(); index++) {
            ScoredMemory memory = memories.get(index);
            Fusion fusion = fused.computeIfAbsent(memory.id, key -> new Fusion(memory));
            fusion.score += weight / (RRF_K + index + 1);
        }
    }

    /**
     * 命中即升温：落库 + 让本次响应立刻体现加权后的重要度与置信度，避免前端看到旧值。
     */
    private List<MemoryItemView> touch(List<MemoryItemView> views) {
        List<MemoryItemView> boosted = new ArrayList<>();
        for (MemoryItemView view : views) {
            try {
                postgresJdbc.update(
                        ("UPDATE memory_item "
                                + "SET last_accessed_at = now(), access_count = access_count + 1, "
                                + "importance = LEAST(1.0, importance + %s), "
                                + "confidence = LEAST(1.0, confidence + %s) "
                                + "WHERE id = ?::uuid").formatted(IMPORTANCE_BOOST, CONFIDENCE_BOOST),
                        view.id());
            } catch (RuntimeException ignored) {
                // 访问计数失败不影响检索结果
            }
            boosted.add(new MemoryItemView(
                    view.id(),
                    view.content(),
                    view.topic(),
                    view.type(),
                    view.sourceType(),
                    view.score(),
                    boostedImportance(view.importance()),
                    boostedConfidence(view.confidence()),
                    view.status(),
                    view.validTo(),
                    view.kind(),
                    view.priority()
            ));
        }
        return boosted;
    }

    private ScoredMemory mapRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ScoredMemory(
                resultSet.getString("id"),
                resultSet.getString("content"),
                resultSet.getString("topic"),
                resultSet.getString("type"),
                resultSet.getString("source_type"),
                resultSet.getString("status"),
                resultSet.getTimestamp("valid_to") == null ? null : resultSet.getTimestamp("valid_to").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("last_accessed_at") == null
                        ? null
                        : resultSet.getTimestamp("last_accessed_at").toInstant(),
                resultSet.getDouble("importance"),
                resultSet.getDouble("confidence"),
                resultSet.getDouble("score"),
                resultSet.getString("kind"),
                resultSet.getInt("priority")
        );
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        builder.append(']');
        return builder.toString();
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    static String classify(String content) {
        if (content.startsWith("卡点") || content.startsWith("卡在") || content.contains("卡点")) {
            return "WEAKNESS";
        }
        if (content.startsWith("计划") || content.startsWith("下一步") || content.contains("下一步")) {
            return "PLAN";
        }
        if (content.startsWith("掌握") || content.startsWith("已掌握") || content.contains("学会")) {
            return "MASTERY";
        }
        return "FACT";
    }

    public static String topicOf(String content) {
        String text = content;
        int separator = -1;
        for (String marker : List.of("：", ":")) {
            int index = text.indexOf(marker);
            if (index >= 0 && (separator < 0 || index < separator)) {
                separator = index;
            }
        }
        String topic = separator >= 0 ? text.substring(separator + 1) : text;
        int cut = -1;
        for (String marker : List.of("：", "，", ",")) {
            int index = topic.indexOf(marker);
            if (index >= 0 && (cut < 0 || index < cut)) {
                cut = index;
            }
        }
        if (cut > 0) {
            topic = topic.substring(0, cut);
        }
        topic = topic.trim();
        return topic.length() <= 60 ? topic : topic.substring(0, 60);
    }

    private record ScoredMemory(
            String id,
            String content,
            String topic,
            String type,
            String sourceType,
            String status,
            Instant validTo,
            Instant createdAt,
            Instant lastAccessedAt,
            double importance,
            double confidence,
            double score,
            String kind,
            int priority
    ) {

        MemoryItem toItem() {
            return new MemoryItem(id, null, null, type, "PROJECT", content, content, sourceType,
                    null, topic, importance, confidence, status, null, null, validTo, null, 0, score);
        }
    }

    private static final class Fusion {

        private final ScoredMemory memory;
        private double score;

        private Fusion(ScoredMemory memory) {
            this.memory = memory;
        }
    }
}