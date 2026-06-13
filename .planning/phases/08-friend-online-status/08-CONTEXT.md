---
phase: 8
status: contexted
---
# Phase 8: Friend & Online Status — 上下文

## 阶段目标

实现好友管理（添加/接受/拒绝/删除/列表/请求列表）和在线状态管理（三值状态、伪在线、状态推送）。

**核心价值**: 用户可管理好友关系，实时感知好友在线状态。

## 关联需求

| 需求 ID | 描述 |
|----------|------|
| BIZ-FRIEND-01 | friend/add 发送好友申请（单向，需要确认） |
| BIZ-FRIEND-02 | friend/accept 接受申请，自动创建私聊会话 |
| BIZ-FRIEND-03 | friend/reject 拒绝好友申请 |
| BIZ-FRIEND-04 | friend/delete 删除好友，保留会话但禁止发送 |
| BIZ-FRIEND-05 | friend/list 返回好友列表（游标分页） |
| BIZ-FRIEND-06 | friend/requests 返回待处理的好友申请 |
| BIZ-STATUS-01 | 三值状态：在线/离线/隐藏，懒加载 |
| BIZ-STATUS-02 | 伪在线：断开后 60s 仍显示在线 |
| BIZ-STATUS-03 | 状态变更推送给所有在线好友 |

## 技术决策

### 好友模块

| 编号 | 决策 | 说明 |
|------|------|------|
| D-41 | 单向申请+确认 | friend/add → FRIEND_REQUEST 推送 → 接收方 accept/reject |
| D-42 | 验证消息持久化 | FriendRequestEntity.message 通过 Flyway V3 迁移新增 |
| D-43 | 单事务创建会话 | friend/accept 原子写入：好友记录 + 私聊会话(type=1) + 2 成员 + 系统消息 |
| D-44 | 删除保留会话禁发 | friendship.deleted=true，会话不变。message/send 增加好友关系检查 |
| D-45 | 重加恢复原会话 | 检测已存在 deleted=true 的记录 → 恢复。复用已有私聊会话 |
| D-46 | 游标分页 | 好友列表按 friendship_id 游标分页 |
| D-51 | 重复申请拒绝 | 已有 pending 申请时提示"已有待处理申请" |
| D-52 | 双向竞赛自动好友 | A 申请 B 同时 B 申请 A → 自动成为好友 |
| D-53 | 拒绝后可重申请 | 无冷却期限制 |
| D-54 | 拒绝自己申请 | 返回 BizException |
| D-55 | 不设好友上限 | 简化实现 |
| D-56 | 非好友禁发 | message/send 路径增加好友关系检查，私聊非好友拒绝 |

### 在线状态模块

| 编号 | 决策 | 说明 |
|------|------|------|
| D-47 | 任意设备在线=在线 | UserStreamRegistry 跟踪设备数，全部断开才标记离线 |
| D-48 | 伪在线 + PING 刷新 | 每次 PING 心跳刷新 OnlineStatusRepository TTL |
| D-49 | 伪在线 60 秒 | 连接断开后延迟 60s → 标记离线 |
| D-50 | 推送所有在线好友 | 状态变更 pushEventToUser 广播给所有在线好友 |

### 状态推送细节（补充讨论）

| 编号 | 决策 | 说明 |
|------|------|------|
| D-57 | 全部触发推送 | 登录/下线/断线超时/隐藏切换 均触发好友推送 |
| D-58 | 通知+客户端拉取 | 推送 STATUS_CHANGED 仅带 uid，客户端调用 batchGetStatus 拉取 |
| D-59 | 隐藏用户不推送 | 隐私优先，隐藏状态用户不在好友列表中触发推送 |
| D-60 | 上线时拉取补偿 | 客户端登录后主动调用 batchGetStatus 拉取所有好友最新状态 |

## 实现约束

- 复用已有 Entity: `FriendRequestEntity`、`FriendshipEntity`、`ConversationEntity`、`ConversationMemberEntity`
- 复用已有模式: `ConversationLockManager` + `TransactionTemplate` 事务、`PushService.pushEventToUser()` 推送
- 集成到 `ChatService` 生命周期: 登录/下线时调用 `OnlineStatusRepository.setOnline()/setOffline()`
- Proto 扩展: 需定义 `FriendRequestPayload`、`FriendAcceptedPayload`、`STATUS_CHANGED` PushEventType + `StatusChangedPayload`
- 新增 1v1 会话类型: `CONV_TYPE_PRIVATE = 1`（当前仅有 `CONV_TYPE_GROUP = 2`）
- Flyway V3 迁移: `FriendRequestEntity` 新增 `message VARCHAR(255)` 列

## 灰区已解决

全部 10 个灰区 + 6 个边界场景 + 4 个推送细节 = 20 项决策全部完成。

## 灰区遗留

无。

## 依赖

- Phase 3 (Database): MySQL/Redis 层已就绪，Entity 已存在
- Phase 4 (Handler Framework): Handler 接口/Dispatcher/Koin DI 已就绪
- Phase 5 (User & Auth): 用户认证/Session 已验证
- Phase 6 (Chat & Message): PushService/UserStreamRegistry 已就绪
- Phase 7 (Conversation): ConversationLockManager/TransactionTemplate 模式已验证
