-- V6: 扩大 conversation_id 列长度
-- 私聊会话 ID 格式为 "private:<uid1>:<uid2>"，两个 Snowflake ID (19位)
-- 加上前缀和分隔符可达 48 字符，原 VARCHAR(32) 不足 → 扩大为 VARCHAR(64)

ALTER TABLE conversations
    MODIFY COLUMN id VARCHAR(64) NOT NULL COMMENT '会话 ID (private:<uid1>:<uid2> 格式)';

ALTER TABLE conversation_members
    MODIFY COLUMN conversation_id VARCHAR(64) NOT NULL;

ALTER TABLE messages
    MODIFY COLUMN conversation_id VARCHAR(64) NOT NULL;
