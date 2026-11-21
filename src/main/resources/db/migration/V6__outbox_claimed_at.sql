-- Outbox 卡死恢复改用「认领时间」，避免正常处理超过 5 分钟的事件被误重置。
ALTER TABLE outbox_event
    ADD COLUMN claimed_at DATETIME(3) NULL AFTER processed_at;

CREATE INDEX idx_outbox_status_claimed ON outbox_event (status, claimed_at);