package com.codemem.knowledge;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaInitializer.class);

    private final JdbcTemplate postgresJdbc;
    private final KnowledgeProperties properties;
    private volatile boolean available;

    public PostgresSchemaInitializer(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                                     KnowledgeProperties properties) {
        this.postgresJdbc = postgresJdbc;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        try {
            postgresJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            postgresJdbc.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_doc (
                        id UUID PRIMARY KEY,
                        path TEXT UNIQUE NOT NULL,
                        title TEXT,
                        subject VARCHAR(128),
                        note_status VARCHAR(32),
                        tags TEXT,
                        links TEXT,
                        content_hash CHAR(64) NOT NULL,
                        mtime TIMESTAMPTZ,
                        indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        index_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                        embedding_signature VARCHAR(200)
                    )
                    """);
postgresJdbc.execute("""
                    ALTER TABLE knowledge_doc
                    ADD COLUMN IF NOT EXISTS embedding_signature VARCHAR(200)
                    """);
            // 文档归属哪个知识源（knowledge_source.id）；切换源时按它整体清理旧库
            postgresJdbc.execute("""
                    ALTER TABLE knowledge_doc
                    ADD COLUMN IF NOT EXISTS source_id UUID
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_knowledge_doc_source
                    ON knowledge_doc (source_id)
                    """);
            postgresJdbc.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_chunk (
                        id UUID PRIMARY KEY,
                        doc_id UUID NOT NULL REFERENCES knowledge_doc(id) ON DELETE CASCADE,
                        heading_path TEXT,
                        content TEXT NOT NULL,
                        token_count INT,
                        content_hash CHAR(64) NOT NULL,
                        search_text TEXT,
                        links TEXT,
                        embedding vector(%d),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.formatted(properties.embeddingDimensions()));
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding
                    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_search
                    ON knowledge_chunk USING gin (to_tsvector('simple', search_text))
                    """);
            // 长期语义记忆：来源是用户自述/抽取候选，不是笔记事实，与知识库分开检索
            postgresJdbc.execute("""
                    CREATE TABLE IF NOT EXISTS memory_item (
                        id UUID PRIMARY KEY,
                        learner_id VARCHAR(64) NOT NULL,
                        project_id VARCHAR(64) NOT NULL DEFAULT 'default',
                        type VARCHAR(24) NOT NULL,
                        scope VARCHAR(24) NOT NULL,
                        content TEXT NOT NULL,
                        summary VARCHAR(500),
                        source_type VARCHAR(24) NOT NULL,
                        source_id VARCHAR(128),
                        topic VARCHAR(128),
                        importance REAL NOT NULL DEFAULT 0.5,
                        confidence REAL NOT NULL DEFAULT 0.5,
                        status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                        content_hash CHAR(64) NOT NULL,
                        valid_from TIMESTAMPTZ,
                        valid_to TIMESTAMPTZ,
                        last_accessed_at TIMESTAMPTZ,
                        access_count INT NOT NULL DEFAULT 0,
                        embedding vector(%d),
                        search_text TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.formatted(properties.embeddingDimensions()));
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_scope
                    ON memory_item (learner_id, project_id, scope, status)
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_topic
                    ON memory_item (topic)
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_embedding
                    ON memory_item USING hnsw (embedding vector_cosine_ops)
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_search
                    ON memory_item USING gin (to_tsvector('simple', search_text))
                    """);
// 并发去重兜底：同一学习者的同一 ACTIVE 事实只能有一条
            postgresJdbc.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_active_hash
                    ON memory_item (learner_id, content_hash)
                    WHERE status = 'ACTIVE'
                    """);
            // kind 区分两类条目：INSTRUCTION = 用户手写的全局指令（每轮回答必须遵守），
            // AUTO = 对话后自动抽取的背景记忆。priority 越小越先注入。
            postgresJdbc.execute("""
                    ALTER TABLE memory_item
                    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'AUTO'
                    """);
            postgresJdbc.execute("""
                    ALTER TABLE memory_item
                    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 100
                    """);
            postgresJdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_kind
                    ON memory_item (learner_id, project_id, kind, status)
                    """);
            available = true;
            log.info("PostgreSQL knowledge schema is ready, embeddingDimensions={}", properties.embeddingDimensions());
        } catch (Exception ex) {
            available = false;
            log.error("Unable to initialize PostgreSQL knowledge schema; knowledge features will be degraded", ex);
        }
    }

    public boolean isAvailable() {
        return available;
    }
}