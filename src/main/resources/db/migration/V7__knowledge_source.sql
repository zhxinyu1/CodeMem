-- Knowledge sources: the note library the knowledge index reads from.
-- Only one source is ACTIVE at a time; switching a source rebuilds the Postgres index.

CREATE TABLE knowledge_source (
    id              VARCHAR(36)   NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    type            VARCHAR(24)   NOT NULL,
    root_path       VARCHAR(1000) NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'INACTIVE',
    doc_count       INT           NOT NULL DEFAULT 0,
    chunk_count     INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(3)   NOT NULL,
    updated_at      DATETIME(3)   NOT NULL,
    last_indexed_at DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_source_path (root_path(255)),
    KEY idx_knowledge_source_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;