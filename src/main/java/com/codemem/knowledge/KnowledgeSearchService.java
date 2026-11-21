package com.codemem.knowledge;

import com.codemem.embedding.EmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);
    private static final int CANDIDATE_LIMIT = 20;
    private static final int RRF_K = 60;
    private static final int MAX_CHUNKS_PER_PATH = 2;
    private static final double DENSE_MIN_SCORE = 0.55;
    private static final double KEYWORD_MIN_SCORE = 0.25;

    private final JdbcTemplate postgresJdbc;
    private final EmbeddingService embeddingService;
    private final ChineseTokenizer tokenizer;
    private final KnowledgeProperties properties;
    private final PostgresSchemaInitializer schemaInitializer;
    private final KnowledgeSourceStore sourceStore;
    private final Timer searchLatency;

    public KnowledgeSearchService(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                                  EmbeddingService embeddingService,
                                  ChineseTokenizer tokenizer,
                                  KnowledgeProperties properties,
                                  PostgresSchemaInitializer schemaInitializer,
                                  MeterRegistry meterRegistry,
                                  KnowledgeSourceStore sourceStore) {
        this.postgresJdbc = postgresJdbc;
        this.embeddingService = embeddingService;
        this.tokenizer = tokenizer;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
        this.sourceStore = sourceStore;
        this.searchLatency = Timer.builder("codemem.knowledge.search.latency").register(meterRegistry);
    }

    public List<RetrievedChunk> search(String query, Integer requestedTopK) {
        if (!schemaInitializer.isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        // 同一时间只有一个知识源生效：多源索引共存时，检索只看生效源，避免把其他库的内容混进答案。
        KnowledgeSource active = sourceStore == null ? null : sourceStore.activeOrNull();
        if (active == null) {
            return List.of();
        }
        UUID sourceId = UUID.fromString(active.getId());

        int topK = requestedTopK == null ? properties.retrievalTopK() : Math.max(1, Math.min(20, requestedTopK));
        long startedAt = System.nanoTime();
        try {
            String vector = toVectorLiteral(embeddingService.embed(query));
            List<ScoredChunk> dense = searchDense(vector, sourceId);
            String keywordQuery = toTsQuery(tokenizer.tokenize(query));
            List<ScoredChunk> keyword = keywordQuery.isBlank()
                    ? List.of()
                    : searchKeyword(keywordQuery, sourceId).stream()
                            .filter(chunk -> chunk.rawScore >= KEYWORD_MIN_SCORE)
                            .toList();
            return fuse(dense, keyword, topK);
        } catch (Exception ex) {
            log.warn("Knowledge search failed for query '{}': {}", query, ex.getMessage());
            return List.of();
        } finally {
            searchLatency.record(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private List<ScoredChunk> searchDense(String vector, UUID sourceId) {
        String sql = """
                SELECT c.id::text AS chunk_id,
                       d.path,
                       d.title,
                       c.heading_path,
                       c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM knowledge_chunk c
                JOIN knowledge_doc d ON d.id = c.doc_id
                WHERE d.index_status = 'ACTIVE'
                  AND c.embedding IS NOT NULL
                  AND d.source_id = ?
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """;
        return postgresJdbc.query(
                sql,
                (resultSet, rowNum) -> mapRow(resultSet),
                vector,
                sourceId,
                vector,
                CANDIDATE_LIMIT
        );
    }

    private List<ScoredChunk> searchKeyword(String keywordQuery, UUID sourceId) {
        String sql = """
                SELECT c.id::text AS chunk_id,
                       d.path,
                       d.title,
                       c.heading_path,
                       c.content,
                       ts_rank_cd(to_tsvector('simple', c.search_text), to_tsquery('simple', ?)) AS score
                FROM knowledge_chunk c
                JOIN knowledge_doc d ON d.id = c.doc_id
                WHERE d.index_status = 'ACTIVE'
                  AND d.source_id = ?
                  AND c.search_text IS NOT NULL
                  AND to_tsvector('simple', c.search_text) @@ to_tsquery('simple', ?)
                ORDER BY score DESC
                LIMIT ?
                """;
        return postgresJdbc.query(
                sql,
                (resultSet, rowNum) -> mapRow(resultSet),
                keywordQuery,
                sourceId,
                keywordQuery,
                CANDIDATE_LIMIT
        );
    }

    private ScoredChunk mapRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ScoredChunk(
                resultSet.getString("chunk_id"),
                resultSet.getString("path"),
                resultSet.getString("title"),
                resultSet.getString("heading_path"),
                resultSet.getString("content"),
                resultSet.getDouble("score")
        );
    }

    private List<RetrievedChunk> fuse(List<ScoredChunk> dense,
                                      List<ScoredChunk> keyword,
                                      int topK) {
        Map<String, Fusion> fused = new LinkedHashMap<>();
        List<ScoredChunk> usefulDense = keyword.isEmpty()
                ? dense
                : dense.stream().filter(chunk -> chunk.rawScore >= DENSE_MIN_SCORE).toList();
        addRanked(fused, usefulDense, 0.8);
        addRanked(fused, keyword, 1.2);

        List<Fusion> ranked = new ArrayList<>(fused.values());
        ranked.sort((left, right) -> Double.compare(right.score, left.score));

        List<RetrievedChunk> results = new ArrayList<>();
        Map<String, Integer> perPath = new LinkedHashMap<>();
        for (Fusion fusion : ranked) {
            if (results.size() >= topK) {
                break;
            }
            int count = perPath.getOrDefault(fusion.chunk.path, 0);
            if (count >= MAX_CHUNKS_PER_PATH) {
                continue;
            }
            results.add(new RetrievedChunk(
                    fusion.chunk.chunkId,
                    fusion.chunk.path,
                    fusion.chunk.title,
                    fusion.chunk.headingPath,
                    fusion.chunk.content,
                    fusion.score
            ));
            perPath.put(fusion.chunk.path, count + 1);
        }
        return results;
    }

    private void addRanked(Map<String, Fusion> fused, List<ScoredChunk> chunks, double weight) {
        for (int index = 0; index < chunks.size(); index++) {
            ScoredChunk chunk = chunks.get(index);
            Fusion fusion = fused.computeIfAbsent(chunk.chunkId, key -> new Fusion(chunk));
            fusion.score += weight / (RRF_K + index + 1);
        }
    }

    String toTsQuery(String tokenized) {
        return TsQueryBuilder.build(tokenized);
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

    private record ScoredChunk(
            String chunkId,
            String path,
            String title,
            String headingPath,
            String content,
            double rawScore
    ) {
    }

    private static final class Fusion {
        private final ScoredChunk chunk;
        private double score;

        private Fusion(ScoredChunk chunk) {
            this.chunk = chunk;
        }
    }
}