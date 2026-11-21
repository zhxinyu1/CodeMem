package com.codemem.knowledge;

import com.codemem.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private final JdbcTemplate postgresJdbc;
    private final ObsidianScanner scanner;
    private final ChineseTokenizer tokenizer;
    private final EmbeddingService embeddingService;
    private final KnowledgeProperties properties;
    private final PostgresSchemaInitializer schemaInitializer;
    private final KnowledgeSourceStore sourceStore;

    public KnowledgeIndexService(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                                 ObsidianScanner scanner,
                                 ChineseTokenizer tokenizer,
                                 EmbeddingService embeddingService,
                                 KnowledgeProperties properties,
                                 PostgresSchemaInitializer schemaInitializer,
                                 KnowledgeSourceStore sourceStore) {
        this.postgresJdbc = postgresJdbc;
        this.scanner = scanner;
        this.tokenizer = tokenizer;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
        this.sourceStore = sourceStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        if (!schemaInitializer.isAvailable()) {
            return;
        }
        try {
            KnowledgeSource source = sourceStore.activeOrNull();
            if (source == null) {
                log.info("No knowledge source registered yet, skipping startup indexing");
                return;
            }
            // 旧版本的文档没有 source_id，启动时归到当前生效源：
            // 否则检索按 source 过滤时会把它们全部挡掉，用户会看到“资料不足”。
            adoptLegacyDocuments(UUID.fromString(source.getId()));
            // 只在“这个知识源从没索引过”时自动建索引。
            // 不能用文档数 0 判断：用户主动清空索引（让知识库保持为空）后，
            // 一次重启就把笔记全灌回来，等于白清。
            if (source.getLastIndexedAt() == null) {
                log.info("Knowledge source '{}' has never been indexed, indexing on startup", source.getName());
                reindexActive();
            }
        } catch (Exception ex) {
            log.warn("Startup knowledge indexing skipped: {}", ex.getMessage());
        }
    }

    /**
     * 顶部「重建索引」：重建下面所有已登记知识源的索引。
     * 每个源各自维护自己的文档，不互相清除；检索只使用生效源，所以多源共存不影响答案质量。
     */
    public synchronized ReindexSummary reindexAllSources() {
        ensureAvailable();
        long startedAt = System.nanoTime();
        List<KnowledgeSource> all = sourceStore.list();
        List<SourceIndexResult> details = new ArrayList<>(all.size());
        int scanned = 0;
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        int deleted = 0;
        int chunks = 0;
        int failedSources = 0;
        List<String> reasons = new ArrayList<>();

        for (KnowledgeSource source : all) {
            IndexResult result = reindexSourceInternal(source);
            boolean ok = result.failedNotes() == 0 && result.scannedNotes() > 0;
            details.add(new SourceIndexResult(source.getId(), source.getName(), ok, result));
            scanned += result.scannedNotes();
            indexed += result.indexedNotes();
            skipped += result.skippedNotes();
            failed += result.failedNotes();
            deleted += result.deletedNotes();
            chunks += result.chunks();
            if (!ok) {
                failedSources++;
                String reason = result.failureReason() == null ? "未扫描到可索引的笔记" : result.failureReason();
                reasons.add(source.getName() + "：" + reason);
            }
        }

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        ReindexSummary summary = new ReindexSummary(
                all.size(),
                failedSources,
                scanned,
                indexed,
                skipped,
                failed,
                deleted,
                chunks,
                durationMs,
                Instant.now(),
                reasons.isEmpty() ? null : String.join("；", reasons),
                details
        );
        log.info("Knowledge reindex-all finished: sources={}, failedSources={}, scanned={}, indexed={}, skipped={}, failed={}, deleted={}, chunks={}, durationMs={}",
                all.size(), failedSources, scanned, indexed, skipped, failed, deleted, chunks, durationMs);
        return summary;
    }

    /** 重建当前生效知识源的索引（新增/切换知识源、启动自动索引都走这里）。 */
    public synchronized IndexResult reindexActive() {
        ensureAvailable();
        return reindexSourceInternal(sourceStore.requireActive());
    }

    /** 知识源卡片里的「重建索引」：只重建指定知识源，不碰其他源。 */
    public synchronized IndexResult reindexSource(String sourceId) {
        ensureAvailable();
        return reindexSourceInternal(sourceStore.require(sourceId));
    }

    private IndexResult reindexSourceInternal(KnowledgeSource source) {
        long startedAt = System.nanoTime();
        UUID sourceId = UUID.fromString(source.getId());
        // 目录不可用时立刻返回，不做任何清理：否则会先把旧索引清掉、却建不出新索引
        Path rootPath = Path.of(source.getRootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            String reason = "知识源目录不存在或不可读：" + rootPath + "，请在设置页重新选择目录后再重建";
            log.warn("Knowledge reindex skipped for source '{}': {}", source.getName(), reason);
            return new IndexResult(0, 0, 0, 0, 0, 0, durationMs, Instant.now(), reason);
        }
        List<ParsedNote> notes = scanner.scan(source.getRootPath(), source.typeEnum());
        String embeddingSignature = embeddingService.signature();

        // 先做一次最小连接探测：地址/Key 不可用时立刻返回，
        // 避免每篇笔记各等一次连接超时，让页面转圈几分钟。
        if (!notes.isEmpty()) {
            try {
                embeddingService.embed("码忆连接探测");
            } catch (Exception ex) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                String reason = ex.getMessage() == null ? "embedding 服务不可用" : ex.getMessage();
                log.warn("Knowledge reindex aborted after connectivity probe for source '{}': {}",
                        source.getName(), reason);
                return new IndexResult(notes.size(), 0, 0, notes.size(), 0, 0,
                        durationMs, Instant.now(), reason);
            }
        }

        Set<String> scannedPaths = new HashSet<>();
        for (ParsedNote note : notes) {
            scannedPaths.add(note.path());
        }
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        int chunks = 0;
        String failureReason = null;

        // 笔记之间互相独立，按笔记并发向量化，避免串行等待把总耗时拖到分钟级。
        if (!notes.isEmpty()) {
            int threads = Math.min(4, notes.size());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<NoteIndexOutcome>> futures = new ArrayList<>(notes.size());
            try {
                for (ParsedNote note : notes) {
                    futures.add(pool.submit(() -> indexOne(note, embeddingSignature, sourceId)));
                }
                for (Future<NoteIndexOutcome> future : futures) {
                    NoteIndexOutcome outcome;
                    try {
                        outcome = future.get();
                    } catch (Exception ex) {
                        failed++;
                        if (failureReason == null) {
                            failureReason = ex.getMessage();
                        }
                        continue;
                    }
                    indexed += outcome.indexed();
                    skipped += outcome.skipped();
                    chunks += outcome.chunks();
                    if (outcome.failure() != null) {
                        failed++;
                        if (failureReason == null) {
                            failureReason = outcome.failure();
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
        if (notes.isEmpty()) {
            failureReason = "知识源目录下没有扫描到可索引的笔记（.md / .txt）";
        }

        int deleted = deleteStaleDocuments(scannedPaths, sourceId);
        // 记录源规模要取该源在索引里的真实总量，不能取本轮新建数：
        // 内容没变化时所有笔记都会被 skip，写回 0 会让面板显示“0 篇 / 0 块”。
        sourceStore.markIndexed(source.getId(), countDocuments(sourceId), countChunks(sourceId));
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        IndexResult result = new IndexResult(
                notes.size(),
                indexed,
                skipped,
                failed,
                deleted,
                chunks,
                durationMs,
                Instant.now(),
                failureReason
        );
        log.info("Knowledge reindex finished for source '{}': notes={}, indexed={}, skipped={}, failed={}, deleted={}, chunks={}, durationMs={}",
                source.getName(), notes.size(), indexed, skipped, failed, deleted, chunks, durationMs);
        return result;
    }

    /**
     * 清空知识库：删掉索引里的全部文档（chunk 外键级联删除），并移除已登记的知识源，
     * 回到“还没配置知识库”的空状态；磁盘上的笔记不动，重新添加目录即可恢复。
     */
    public synchronized ClearIndexResult clearAll() {
        ensureAvailable();
        int sources = sourceStore.list().size();
        int deleted = postgresJdbc.update("DELETE FROM knowledge_doc");
        // 知识源也一起移除：否则清空后列表里还留着“0 篇 / 0 块”的库，知识库算不上真的为空
        sourceStore.deleteAll();
        log.info("Knowledge base cleared: deletedDocuments={}, removedSources={}", deleted, sources);
        return new ClearIndexResult(deleted, sources, Instant.now());
    }

    /**
     * 单篇笔记的索引流程：先算完向量再替换旧块，避免失败时留下半成品。
     */
    private NoteIndexOutcome indexOne(ParsedNote note, String embeddingSignature, UUID sourceId) {
        try {
            DocRow existing = findDoc(note.path());
            // 只有同属这个源、内容与向量签名都没变的文档才跳过；
            // 同一路径被另一个源收录时重新向量化并把归属改成新源。
            if (existing != null
                    && sourceId.equals(existing.sourceId())
                    && existing.contentHash().equals(note.contentHash())
                    && embeddingSignature.equals(existing.embeddingSignature())
                    && existing.chunkCount() > 0) {
                return new NoteIndexOutcome(0, 1, 0, null);
            }

            List<float[]> embeddings = embedChunks(note);

            UUID docId;
            if (existing != null) {
                docId = existing.id();
                deleteChunks(docId);
                updateDoc(docId, note, embeddingSignature, sourceId);
            } else {
                docId = insertDoc(note, embeddingSignature, sourceId);
            }
            int chunks = insertChunks(docId, note, embeddings);
            return new NoteIndexOutcome(1, 0, chunks, null);
        } catch (Exception ex) {
            log.warn("Unable to index note {} with embedding '{}': {}",
                    note.path(), embeddingSignature, ex.getMessage());
            return new NoteIndexOutcome(0, 0, 0, ex.getMessage());
        }
    }

    private record NoteIndexOutcome(int indexed, int skipped, int chunks, String failure) {
    }

    public IndexStatus status() {
        if (!schemaInitializer.isAvailable()) {
            return new IndexStatus(false, 0, 0, null, null, false);
        }
        try {
            // 同一时间只有一个知识源生效：状态只统计生效源的文档，多源共存时不会把其他库算进来
            KnowledgeSource active = sourceStore.activeOrNull();
            if (active == null) {
                return new IndexStatus(true, 0, 0, null, embeddingService.signature(), false);
            }
            UUID sourceId = UUID.fromString(active.getId());
            long documents = countDocuments(sourceId);
            long chunks = countChunks(sourceId);
            Instant lastIndexedAt = postgresJdbc.query(
                    "SELECT max(indexed_at) AS last_indexed_at FROM knowledge_doc WHERE index_status = 'ACTIVE' AND source_id = ?",
                    resultSet -> {
                        if (resultSet.next()) {
                            Timestamp timestamp = resultSet.getTimestamp("last_indexed_at");
                            return timestamp == null ? null : timestamp.toInstant();
                        }
                        return null;
                    }, sourceId);
            Long staleDocuments = postgresJdbc.queryForObject("""
                    SELECT count(*)
                    FROM knowledge_doc d
                    WHERE d.index_status = 'ACTIVE'
                      AND d.source_id = ?
                      AND (d.embedding_signature IS DISTINCT FROM ?
                           OR NOT EXISTS (SELECT 1 FROM knowledge_chunk c WHERE c.doc_id = d.id))
                    """, Long.class, sourceId, embeddingService.signature());
            return new IndexStatus(
                    true,
                    documents,
                    chunks,
                    lastIndexedAt,
                    embeddingService.signature(),
                    staleDocuments != null && staleDocuments > 0
            );
        } catch (Exception ex) {
            log.warn("Unable to read knowledge index status: {}", ex.getMessage());
            return new IndexStatus(false, 0, 0, null, null, false);
        }
    }

    private void ensureAvailable() {
        if (!schemaInitializer.isAvailable()) {
            throw new IllegalStateException("PostgreSQL knowledge schema is not available");
        }
    }

    private int countDocuments(UUID sourceId) {
        Long count = postgresJdbc.queryForObject(
                "SELECT count(*) FROM knowledge_doc WHERE index_status = 'ACTIVE' AND source_id = ?",
                Long.class, sourceId);
        return count == null ? 0 : count.intValue();
    }

    private int countChunks(UUID sourceId) {
        Long count = postgresJdbc.queryForObject("""
                SELECT count(*) FROM knowledge_chunk c
                JOIN knowledge_doc d ON d.id = c.doc_id
                WHERE d.index_status = 'ACTIVE' AND d.source_id = ?
                """, Long.class, sourceId);
        return count == null ? 0 : count.intValue();
    }

    /** 旧版本索引没有 source_id，启动时把它们归到当前生效源，保持检索可用。 */
    private void adoptLegacyDocuments(UUID sourceId) {
        int adopted = postgresJdbc.update(
                "UPDATE knowledge_doc SET source_id = ? WHERE source_id IS NULL", sourceId);
        if (adopted > 0) {
            log.info("Adopted {} legacy knowledge documents into active source {}", adopted, sourceId);
        }
    }

    private DocRow findDoc(String path) {
        List<DocRow> rows = postgresJdbc.query(
                """
                SELECT id, content_hash, embedding_signature, source_id,
                       (SELECT count(*) FROM knowledge_chunk c WHERE c.doc_id = knowledge_doc.id) AS chunk_count
                FROM knowledge_doc
                WHERE path = ?
                """,
                (resultSet, rowNum) -> new DocRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("content_hash"),
                        resultSet.getString("embedding_signature"),
                        resultSet.getObject("source_id", UUID.class),
                        resultSet.getLong("chunk_count")
                ),
                path
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID insertDoc(ParsedNote note, String embeddingSignature, UUID sourceId) {
        UUID docId = UUID.randomUUID();
        postgresJdbc.update("""
                        INSERT INTO knowledge_doc
                            (id, path, title, subject, note_status, tags, links, content_hash, mtime, indexed_at,
                             index_status, embedding_signature, source_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'ACTIVE', ?, ?)
                        """,
                docId,
                note.path(),
                note.title(),
                note.subject(),
                note.noteStatus(),
                String.join(",", note.tags()),
                String.join(",", note.links()),
                note.contentHash(),
                Timestamp.from(note.modifiedAt()),
                embeddingSignature,
                sourceId
        );
        return docId;
    }

    private void updateDoc(UUID docId, ParsedNote note, String embeddingSignature, UUID sourceId) {
        postgresJdbc.update("""
                        UPDATE knowledge_doc
                        SET title = ?, subject = ?, note_status = ?, tags = ?, links = ?,
                            content_hash = ?, mtime = ?, indexed_at = now(), index_status = 'ACTIVE',
                            embedding_signature = ?, source_id = ?
                        WHERE id = ?
                        """,
                note.title(),
                note.subject(),
                note.noteStatus(),
                String.join(",", note.tags()),
                String.join(",", note.links()),
                note.contentHash(),
                Timestamp.from(note.modifiedAt()),
                embeddingSignature,
                sourceId,
                docId
        );
    }

    private List<float[]> embedChunks(ParsedNote note) {
        List<String> inputs = note.chunks().stream()
                .map(chunk -> chunk.headingPath() + "\n" + chunk.content())
                .toList();
        return embeddingService.embedAll(inputs);
    }

    private int insertChunks(UUID docId, ParsedNote note, List<float[]> embeddings) {
        if (embeddings.size() != note.chunks().size()) {
            throw new IllegalStateException("embedding count does not match chunk count for " + note.path());
        }

        int inserted = 0;
        for (int index = 0; index < note.chunks().size(); index++) {
            NoteChunk chunk = note.chunks().get(index);
            postgresJdbc.update("""
                            INSERT INTO knowledge_chunk
                                (id, doc_id, heading_path, content, token_count, content_hash, search_text, links, embedding, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, now())
                            """,
                    UUID.randomUUID(),
                    docId,
                    chunk.headingPath(),
                    chunk.content(),
                    chunk.tokenCount(),
                    chunk.contentHash(),
                    tokenizer.tokenize(chunk.headingPath() + " " + chunk.content()),
                    String.join(",", chunk.links()),
                    toVectorLiteral(embeddings.get(index))
            );
            inserted++;
        }
        return inserted;
    }

    private void deleteChunks(UUID docId) {
        postgresJdbc.update("DELETE FROM knowledge_chunk WHERE doc_id = ?", docId);
    }

    private int deleteStaleDocuments(Set<String> scannedPaths, UUID sourceId) {
        List<DocRowWithPath> existing = postgresJdbc.query(
                "SELECT id, path FROM knowledge_doc WHERE source_id = ?",
                (resultSet, rowNum) -> new DocRowWithPath(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("path")
                ),
                sourceId
        );
        int deleted = 0;
        for (DocRowWithPath doc : existing) {
            if (!scannedPaths.contains(doc.path())) {
                postgresJdbc.update("DELETE FROM knowledge_doc WHERE id = ?", doc.id());
                deleted++;
            }
        }
        return deleted;
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

    private record DocRow(UUID id, String contentHash, String embeddingSignature, UUID sourceId, long chunkCount) {
    }

    private record DocRowWithPath(UUID id, String path) {
    }
}