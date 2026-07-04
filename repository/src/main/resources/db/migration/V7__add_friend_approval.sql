-- V7__add_friend_approval.sql
-- 好友申请通过模式字段（D-新增）
-- 0=WAIT_APPROVAL（等待同意，默认）
-- 1=AUTO_ACCEPT（自动通过）
-- 2=AUTO_REJECT（自动拒绝）

ALTER TABLE users ADD COLUMN friend_approval TINYINT NOT NULL DEFAULT 0 AFTER privacy_status;
