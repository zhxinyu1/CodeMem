-- CodeMem week-1 schema.
-- MySQL is the source of truth for conversations, turns, learner profile and outbox events.

CREATE TABLE conversation (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    learner_id   VARCHAR(64)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conversation_learner_updated (learner_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE message (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT       NOT NULL,
    turn_id           VARCHAR(64)  NULL,
    role              VARCHAR(16)  NOT NULL,
    content           MEDIUMTEXT   NOT NULL,
    token_count       INT          NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_conversation_created (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE turn (
    id                 VARCHAR(64)  NOT NULL,
    conversation_id    BIGINT       NOT NULL,
    trace_id           VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    latency_ms         INT          NULL,
    prompt_tokens      INT          NULL,
    completion_tokens  INT          NULL,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_turn_conversation_created (conversation_id, created_at),
    KEY idx_turn_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE learner_profile (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    learner_id     VARCHAR(64)   NOT NULL,
    display_name   VARCHAR(64)   NOT NULL,
    goal           VARCHAR(500)  NOT NULL,
    stage          VARCHAR(200)  NOT NULL,
    weekly_hours   VARCHAR(32)   NOT NULL,
    preferences    VARCHAR(1000) NOT NULL,
    updated_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learner_profile_learner (learner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Reserved for the asynchronous memory pipeline in week 3.
CREATE TABLE outbox_event (
    id              VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Reserved for state patch/versioning in week 3.
CREATE TABLE state_snapshot (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    learner_id   VARCHAR(64)   NOT NULL,
    project_id   VARCHAR(64)   NOT NULL,
    version      INT           NOT NULL,
    state_json   JSON          NOT NULL,
    summary      VARCHAR(1000) NULL,
    created_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_state_learner_project_version (learner_id, project_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE state_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    learner_id     VARCHAR(64)  NOT NULL,
    project_id     VARCHAR(64)  NOT NULL,
    from_version   INT          NOT NULL,
    to_version     INT          NOT NULL,
    turn_id        VARCHAR(64)  NULL,
    patch_json     JSON         NOT NULL,
    evidence_json  JSON         NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_state_event_learner_created (learner_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;