-- V7_2__seed_auto_accept_accounts.sql
-- 预置 10 个自动通过测试账号
-- 密码 "123456" 已用 BCrypt cost 12 预哈希
-- friend_approval = 1 (AUTO_ACCEPT)
-- 使用 INSERT IGNORE 确保幂等性

INSERT IGNORE INTO users (id, username, password_hash, nickname, avatar, privacy_status, friend_approval, created_at, updated_at)
VALUES
  (1000004, 'autobot01', '$2a$12$/h4YJr7xSBdFFz2s7st6ZeBQ47akyAril3YWH1RPRsBOP0dzxQ4Fy', '自动机器人01', '', 0, 1, NOW(), NOW()),
  (1000005, 'autobot02', '$2a$12$IkO87EIEawJpaT7E..Sg2uZu/7oCH.mFWUY.ijAn6MNy9AZkjRZoq', '自动机器人02', '', 0, 1, NOW(), NOW()),
  (1000006, 'autobot03', '$2a$12$JsarEHApHD4uJLYF8fJORuvJ.txzSDrZRdy3kvNHjEEIqCIar.n.q', '自动机器人03', '', 0, 1, NOW(), NOW()),
  (1000007, 'autobot04', '$2a$12$SLJoSf0VSQhMENFmv1p.HOBqGEFYYsg/xg0WPUbGWirfkFvNf8D1C', '自动机器人04', '', 0, 1, NOW(), NOW()),
  (1000008, 'autobot05', '$2a$12$dHO85ja0yscOWFl..6HeVOai0A7kg5dT3PCE4F3dyoFnTOiDxxxQi', '自动机器人05', '', 0, 1, NOW(), NOW()),
  (1000009, 'autobot06', '$2a$12$eV/9XuL3GktO4z7Jg8F5B.BjbTXNnarnVOowN8dbTEEmNGvTQZICO', '自动机器人06', '', 0, 1, NOW(), NOW()),
  (1000010, 'autobot07', '$2a$12$FDEcYmpoyCyhrVjRD/0XGO5MwjUGAAib5ZJrA6iB835fXqQd.B0VW', '自动机器人07', '', 0, 1, NOW(), NOW()),
  (1000011, 'autobot08', '$2a$12$wK5iE9UFtFrYxx7i62ge2uz/dQ54pgQZvji3f/Mr74bAHlrvYcxEe', '自动机器人08', '', 0, 1, NOW(), NOW()),
  (1000012, 'autobot09', '$2a$12$bCf7pP5TwbmKjyRKEJlWkuoajgOUKdsuKNx7wX1ifS6znuMiS2vfW', '自动机器人09', '', 0, 1, NOW(), NOW()),
  (1000013, 'autobot10', '$2a$12$AwPywgho5wK0g1HsnSfsuuamcH1hrnWrdIreM4gUbFb7LrWDecCKO', '自动机器人10', '', 0, 1, NOW(), NOW());
