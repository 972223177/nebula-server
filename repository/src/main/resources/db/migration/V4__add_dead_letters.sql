-- V4__add_dead_letters.sql
-- 创建死信表：存储投递失败的消息，供补偿任务重试
-- D-73: 消息可靠性 — 死信补偿机制
CREATE TABLE dead_letters (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    msg_id          BIGINT       NULL COMMENT '原始消息 ID（可能为空，若消息未成功入库）',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话ID',
    sender_uid      BIGINT       NOT NULL COMMENT '发送者 UID',
    message_type    INT          NOT NULL COMMENT '消息内容类型',
    content         TEXT         NOT NULL COMMENT '消息文本内容',
    payload         BLOB         NULL COMMENT '消息附加数据',
    client_msg_id   VARCHAR(64)  NULL COMMENT '客户端消息幂等标识',
    client_ts       BIGINT       NOT NULL COMMENT '客户端发送时间戳（毫秒）',
    fail_reason     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '最近一次失败原因',
    fail_count      INT          NOT NULL DEFAULT 0 COMMENT '失败次数',
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '状态: pending / retrying / permanent_failed / retry_success',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_status_created (status, created_at) COMMENT '按状态和创建时间索引，用于补偿任务扫描',
    INDEX idx_client_msg_id (client_msg_id) COMMENT '按客户端消息 ID 索引，用于去重查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='死信表 — 投递失败的消息记录';
