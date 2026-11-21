-- Stage A: agent workflow tracing.
-- agent_run records one workflow execution, agent_step records each role invocation.

CREATE TABLE agent_run (
    id                 VARCHAR(64)  NOT NULL,
    trace_id           VARCHAR(64)  NOT NULL,
    turn_id            VARCHAR(64)  NULL,
    workflow           VARCHAR(32)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    latency_ms         INT          NULL,
    prompt_tokens      INT          NULL,
    completion_tokens  INT          NULL,
    revision_count     INT          NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_run_trace (trace_id),
    KEY idx_agent_run_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE agent_step (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    run_id         VARCHAR(64)  NOT NULL,
    agent_name     VARCHAR(64)  NOT NULL,
    step_no        INT          NOT NULL,
    input_digest   VARCHAR(500) NULL,
    output_digest  VARCHAR(500) NULL,
    status         VARCHAR(16)  NOT NULL,
    latency_ms     INT          NULL,
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_step_run (run_id, step_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;