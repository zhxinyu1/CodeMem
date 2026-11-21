CREATE TABLE turn_extraction (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    turn_id            VARCHAR(64)  NOT NULL,
    learner_id         VARCHAR(64)  NOT NULL,
    project_id         VARCHAR(64)  NOT NULL,
    summary            VARCHAR(1000) NULL,
    memory_json        JSON         NOT NULL,
    state_patch_json   JSON         NULL,
    status             VARCHAR(16)  NOT NULL,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_turn_extraction_turn (turn_id),
    KEY idx_turn_extraction_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;