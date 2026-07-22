-- V8__dedup_friend_requests.sql
-- D-80 修复补充：清理 friend_requests 中因历史重复插入产生的
-- (from_uid, to_uid, status) 重复行，使其重新满足 uk_from_to_status 唯一约束。
-- 同一 (from_uid, to_uid, status) 组内仅保留 id 最大的一条，删除其余重复行。
DELETE r1 FROM friend_requests r1
INNER JOIN friend_requests r2
  ON r1.from_uid = r2.from_uid
 AND r1.to_uid = r2.to_uid
 AND r1.status = r2.status
 AND r1.id < r2.id;
