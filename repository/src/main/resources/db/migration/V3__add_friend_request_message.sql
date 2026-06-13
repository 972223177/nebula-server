-- V3: friend_requests 表新增 message 字段（D-42）
-- 好友申请附言，最长 255 字符，默认为空字符串
ALTER TABLE friend_requests ADD COLUMN message VARCHAR(255) NOT NULL DEFAULT '' COMMENT '好友申请附言，D-42';
