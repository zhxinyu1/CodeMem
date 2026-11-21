ALTER TABLE outbox_event
    ADD COLUMN last_error VARCHAR(500) NULL AFTER retry_count;

ALTER TABLE outbox_event
    ADD COLUMN processed_at DATETIME(3) NULL AFTER last_error;