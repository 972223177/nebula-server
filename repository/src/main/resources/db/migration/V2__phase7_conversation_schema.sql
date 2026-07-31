-- ⚠️ 此迁移已 applied，禁止回改：连注释变更也会触发 Flyway checksum mismatch（FlywayValidateException）。
--    如需演进 schema，新增 V{n+1}__*.sql；老库 checksum 不符时执行 `flyway repair`
--    或 `UPDATE flyway_schema_history SET checksum=<本地计算值> WHERE version='2'`（前提列已存在）。
-- Phase 7: 为 conversations 表新增会话状态、最后消息信息列（D-17, D-21）
ALTER TABLE conversations
    ADD COLUMN status INT NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已解散，D-17',
    ADD COLUMN last_message_id BIGINT NOT NULL DEFAULT 0 COMMENT '最后一条消息的 Snowflake ID，D-21',
    ADD COLUMN last_message_preview VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后一条消息的文本预览，D-21',
    ADD COLUMN last_message_ts BIGINT NOT NULL DEFAULT 0 COMMENT '最后一条消息的客户端时间戳(ms)，D-21';

-- Phase 7: 为 conversation_members 表新增角色列（D-17）
ALTER TABLE conversation_members
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'member' COMMENT '角色：协议层枚举 GroupMemberRole 的存储值（小写 snake_case），DB 仅持久化 owner/member，admin 为协议预留位（写入路径未启用），D-17';
