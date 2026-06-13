---
phase: 7
discussion_date: "2026-06-13"
round: 2
discussion_status: completed  # in_progress | completed，供 /nx-status 判断下一步建议
---

## 第二轮讨论 (2026-06-13)

本轮讨论基于代码审查发现的 5 个新增灰区，在已有 D-01~D-16 基础上补充决策。

### D-17: Entity Schema 变更

**决策**: A1+B3+C1

**理由**:
- `ConversationEntity` 新增 `var status: Int = 0`（0=正常/1=已解散），可扩展 ARCHIVED 等状态
- `ConversationMemberEntity` 新增 `var role: String = "member"`（owner/member），与 proto GroupMember.role 对齐
- `ConversationEntity.updatedAt` 保持 `LocalDateTime` 类型，Handler 层 `LocalDateTime → epoch millis` 映射，无需冗余字段

### D-18: Proto Payload 消息定义

**决策**: 在 `conversation.proto` 定义 5 个 Payload，保留 GROUP_INVITED 枚举

**理由**:
- 需定义: GroupCreatedPayload、MemberJoinedPayload、MemberLeftPayload、MemberKickedPayload、GroupUpdatedPayload、GroupDissolvedPayload
- `GROUP_INVITED=6` 枚举值保留（预留 v2 审批制入群），Phase 7 不产生此事件
- 各 Payload 携带最小必要信息：conversation_id + 操作上下文字段

### D-19: Transaction 策略

**决策**: 编程式事务(TransactionTemplate) + Mutex 按会话串行化

**理由**:
- `TransactionTemplate.execute {}` 保证多表原子性（创建群=写 Conversation + N×Member，要么全成功要么全回滚）
- `ConcurrentHashMap<String, Mutex>` 按 `conversationId` 串行化并发写，防止 memberCount 竞态
- 编辑群聊（单表更新）无需事务包裹
- Repository 层不自行声明 `@Transactional`（协程环境兼容）

### D-20: 私聊会话范围

**决策**: Phase 7 仅查询，不创建

**理由**: 好友通过时自动创建私聊属于 Phase 8 职责。`conversation/list` 返回所有会话（含私聊），通过 `ConversationMemberEntity.findByUserId()` 筛选。Proto 中不定义 `CreatePrivateConvReq`。

### D-21: ConversationBrief 填充策略

**决策**: Entity 扩展字段，单表查询

**理由**: `ConversationEntity` 新增 `lastMessageId`、`lastMessagePreview`（截断 100 字符）、`lastMessageTs`。Phase 6 `SendMessageHandler` 发送消息时原子更新。会话列表单表查询，无需 JOIN messages 表。

---

## 第一轮讨论 (2026-06-13)

---
phase: 7
discussion_date: "2026-06-13"
round: 1

# Phase 7: Conversation — 讨论记录

## 讨论概览

本次讨论覆盖 Phase 7 全部核心灰区，10 个问题均达成明确决策。

---

## 决策记录

### D-01: 会话列表查询策略

**决策**: 单次 JOIN 查询

**理由**: 在 Repository 层写 `@Query` 做 `conversations JOIN conversation_members`，一次 DB 往返返回用户参与的所有会话。`last_message` 字段在消息写入时已同步更新，无需额外 JOIN messages 表。

**影响**: 需在 `ConversationRepository` 新增 `findConversationBriefsByUserId()` 方法。

### D-02: conversation_id 生成策略

**决策**: UUID 统一

**理由**: 所有 conversation_id 统一使用 UUID（与 `ConversationEntity.id` 一致）。简单一致，无需根据私聊/群聊类型区分生成算法。

### D-03: 邀请成员权限控制

**决策**: 任何现有成员均可邀请

**理由**: 检查 inviter 是否为 `conversation_members` 成员即可。符合 ROADMAP 描述的"邀请直接加入，无需审批"。符合 IM 行业惯例。

### D-04: Push 通知策略

**决策**: 聊内推送 — 复用 Phase 6 的 PushService

**理由**:
- 创建群聊 → 推 `GROUP_CREATED` 给初始成员（不包含创建者）
- 邀请成员 → 推 `MEMBER_JOINED` 给群内所有现有成员
- 退群 → 推 `MEMBER_LEFT` 给群内剩余成员
- 踢人 → 推 `MEMBER_KICKED` 给被踢者 + `MEMBER_LEFT` 给剩余成员
- 编辑群 → 推 `GROUP_UPDATED` 给群内所有成员
- 解散群 → 推 `GROUP_DISSOLVED` 给群内所有成员

### D-05: 群人数上限

**决策**: 200 人

**理由**: 与 `ConversationEntity.maxMembers` 默认值一致。适合中小规模群聊，后续可按需调整。

### D-06: group_members 分页策略

**决策**: 全量返回

**理由**: 群人数 ≤200，全量返回简单直接。序列化开销可接受。

### D-07: PullMessagesHandler 安全修复

**决策**: 在 Phase 7 修复

**理由**: Conversation 阶段自然包括成员身份管理。修复方案：在 `handle()` 开头添加 `findByConversationIdAndUserId()` 成员检查，非成员返回 `BizCode.NOT_MEMBER`。

### D-08: 操作响应格式

**决策**: 复用通用 Response（invite, leave, kick, edit 操作）

**理由**: 与项目现有无 Response 消息的操作一致。返回 `Response(code=0, message='success')` 即可。

### D-09: 群解散策略

**决策**: 软删除 — 标记 dissolved 状态，清空成员列表，保留消息记录

**理由**: 用户消息历史可追溯，客户端可展示"该群已解散"。需在 `ConversationEntity` 新增 `status` 字段（0=正常，1=已解散）。

### D-10: create_group 初始成员

**决策**: 需要 `member_uids` 字段

**理由**: Proto 中 `CreateGroupReq` 已定义 `repeated int64 member_uids`。创建者自动成为群主+成员，member_uids 中不允许包含创建者 UID（含创建者的返回 INVALID_PARAM）。member_uids 中的用户直接加入，无需邀请。

### D-11: Payload Proto 管理

**决策**: 在 `conversation.proto` 中定义推送 Payload

**理由**: 在 conversation.proto 中新增 `GroupCreatedPayload`、`MemberJoinedPayload`、`MemberLeftPayload`、`MemberKickedPayload`、`GroupUpdatedPayload`、`GroupDissolvedPayload` 等消息。与 Request/Response 在同一文件，便于维护。

### D-12: Handler 文件组织

**决策**: 一 Handler 一文件

**理由**: 7 个独立 Handler 文件：
- `ListConversationsHandler.kt`
- `CreateGroupHandler.kt`
- `InviteMemberHandler.kt`
- `LeaveGroupHandler.kt`
- `KickMemberHandler.kt`
- `EditGroupHandler.kt`
- `GroupMembersHandler.kt`

### D-13: 会话列表排序与分页

**决策**: 按 `last_updated_at` 倒序 + 游标分页

**理由**: 每次发消息都会更新 `last_updated_at`，自然实现最近活跃会话排前。cursor 传 `last_updated_at` 时间戳。

**影响**: 需要在 `ConvListResp` 中新增 `has_more` 字段的返回值判断逻辑，或确认 proto 是否已定义。

### D-14: 踢人保护机制

**决策**: 禁止踢群主 + 禁止踢自己

**理由**:
- 踢群主 → `BizCode.GROUP_PERM_DENIED`
- 踢自己 → `BizCode.INVALID_PARAM`

### D-15: edit_group 编辑策略

**决策**: 至少传 `name` 或 `avatar_url` 之一，名称最长 128 字符，avatar_url 最长 256 字符

**理由**: 灵活且安全，允许只改名字或只改头像。两者都不传返回 `INVALID_PARAM`。

### D-16: 群解散通知

**决策**: 推送 `GROUP_DISSOLVED` 给所有在线成员

**理由**: 通过 PushService 推送给 `conversation_members` 中所有在线用户。客户端收到后标记会话已解散。

---

## 后续步骤

执行 `/nx-plan 7` 进入阶段规划。
