-- V1_2__seed_users.sql
-- 预置账号导入（D-01）
-- 密码已用 BCrypt cost 12 预哈希
-- 使用 INSERT IGNORE 确保幂等性：首次执行插入，后续执行跳过
-- uid 范围 1000000+ 避免与 Snowflake ID 冲突

INSERT IGNORE INTO users (id, username, password_hash, nickname, avatar, created_at, updated_at)
VALUES
  (1000001, 'admin', '$2a$12$EiOumeJ4wMFSQb7.D.a9l.5tG.xN196R53Hr9m5IMbLKuY/On/S1W', '管理员', '', NOW(), NOW()),
  (1000002, 'testuser1', '$2a$12$ujSU8SMDZvg4p9ULf6ef7eeDHOLnLPp.Jr1gc2oec2JIW8myfUwVe', '测试用户1', '', NOW(), NOW()),
  (1000003, 'testuser2', '$2a$12$mt2xlSIQaUSxtJGpKdpMHuDKRO3AwmQK89BKNJ01XynvC9yD/AMxG', '测试用户2', '', NOW(), NOW());
