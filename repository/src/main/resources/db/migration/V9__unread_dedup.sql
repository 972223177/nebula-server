-- §七 Durable Outbox：未读自增幂等去重表
-- 每条已计入未读的 (会话, 消息) 记录一行，主键 (conv_id, msg_id) 保证 INSERT IGNORE
-- 天然幂等，使 fan-out worker 在 PEL 重投 / 进程崩溃重启时能安全重试未读自增
-- （不双计、不漏计），消除原 fire-and-forget 与粗粒度 Redis 锁带来的未读漂移风险。
CREATE TABLE IF NOT EXISTS unread_dedup (
    conv_id VARCHAR(64) NOT NULL,
    msg_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conv_id, msg_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
