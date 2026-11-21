-- 会话自定义分组：分组表 + conversation.group_id 归属。
-- 删除分组时只把组内会话置回未分组，不删除会话本身。

CREATE TABLE conversation_group (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    learner_id  VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_group_learner_name (learner_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE conversation
    ADD COLUMN group_id BIGINT NULL AFTER learner_id,
    ADD KEY idx_conversation_group (group_id);