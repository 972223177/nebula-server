CREATE TABLE IF NOT EXISTS users (
    id              BIGINT          NOT NULL PRIMARY KEY COMMENT 'Snowflake ID',
    username        VARCHAR(64)     NOT NULL COMMENT '用户名，登录凭证',
    password_hash   VARCHAR(128)    NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname        VARCHAR(64)     NOT NULL COMMENT '显示名称',
    avatar          VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '头像 URL',
    privacy_status  INT             NOT NULL DEFAULT 0 COMMENT '在线状态可见性：0=所有人, 1=仅好友, 2=隐藏',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversations (
    id              VARCHAR(32)     NOT NULL PRIMARY KEY COMMENT 'UUID',
    type            INT             NOT NULL COMMENT '1=私聊, 2=群聊',
    name            VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '群聊名称',
    avatar          VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '群头像 URL',
    group_owner_uid BIGINT          DEFAULT NULL COMMENT '群主ID（群聊）',
    member_count    INT             NOT NULL DEFAULT 0 COMMENT '当前成员数',
    max_members     INT             NOT NULL DEFAULT 200 COMMENT '群人数上限',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_group_owner (group_owner_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_members (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id     VARCHAR(32)     NOT NULL,
    user_id             BIGINT          NOT NULL,
    joined_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_read_message_id BIGINT         NOT NULL DEFAULT 0 COMMENT '已读的最后消息ID',
    unread_count        INT             NOT NULL DEFAULT 0 COMMENT '未读消息计数',
    deleted             INT             NOT NULL DEFAULT 0 COMMENT '软删除标志',
    UNIQUE KEY uk_member (conversation_id, user_id),
    INDEX idx_user_convs (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id                BIGINT          NOT NULL PRIMARY KEY COMMENT 'Snowflake ID',
    conversation_id   VARCHAR(32)     NOT NULL,
    sender_uid        BIGINT          NOT NULL,
    message_type      INT             NOT NULL COMMENT 'ChatContentType 枚举值',
    content           TEXT            NOT NULL,
    payload           BLOB            DEFAULT NULL COMMENT '附加结构化数据',
    client_message_id VARCHAR(64)     DEFAULT NULL COMMENT '客户端幂等ID',
    client_ts         BIGINT          NOT NULL COMMENT '客户端时间戳(ms)',
    server_ts         BIGINT          NOT NULL COMMENT '服务器时间戳(ms)',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_conv_messages (conversation_id, id) COMMENT '游标分页核心索引',
    UNIQUE KEY uk_client_msg_id (client_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendships (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    friend_id   BIGINT      NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted     INT      NOT NULL DEFAULT 0 COMMENT '软删除',
    UNIQUE KEY uk_friendship (user_id, friend_id),
    INDEX idx_friends (friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friend_requests (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    from_uid    BIGINT      NOT NULL,
    to_uid      BIGINT      NOT NULL,
    status      INT      NOT NULL DEFAULT 0 COMMENT '0=pending, 1=accepted, 2=rejected',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_pending_requests (to_uid, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
