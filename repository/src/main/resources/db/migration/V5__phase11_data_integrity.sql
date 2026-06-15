-- V5__phase11_data_integrity.sql
-- Phase 11: 数据一致性与竞态修复
-- D-80: 好友双向竞赛 — DB 唯一约束 + DuplicateKeyException 幂等 catch
-- H23: friend_requests 无 UNIQUE 约束防止同对用户重复 pending 申请
-- H24: dead_letters.client_msg_id 仅普通索引，需升级为 UNIQUE

-- 1. friend_requests: 防止 (from_uid, to_uid, status) 重复（D-80）
--    确保同一对用户在 status='0'(pending) 时只有一条申请记录
ALTER TABLE friend_requests
    ADD UNIQUE KEY uk_from_to_status (from_uid, to_uid, status);

-- 2. dead_letters: client_msg_id 升级为 UNIQUE 约束（H24）
--    确保同一 client_msg_id 只有一条死信记录，支持幂等去重
ALTER TABLE dead_letters
    DROP INDEX idx_client_msg_id,
    ADD UNIQUE KEY uk_client_msg_id (client_msg_id);

-- 3. friendships: 确保 (较小uid, 较大uid) 唯一，防止双向竞赛创建重复好友关系（D-80）
--    MySQL 8.0.13+ 支持函数索引，LEAST/GREATEST 确保规范化排序后的唯一性
--    注意：V1 已有 uk_friendship(user_id, friend_id)，该约束为单向（A→B 与 B→A 不冲突）
ALTER TABLE friendships
    ADD UNIQUE KEY uk_friendship_pair ((LEAST(user_id, friend_id)), (GREATEST(user_id, friend_id)));
