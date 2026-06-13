---
phase: 7
status: contexted
---

# Phase 7: Conversation — 上下文

## 阶段目标

实现会话列表、群聊创建、成员管理。共 8 个需求 (BIZ-CONV-01~08)：

- **conversation/list** — 返回用户参与的会话列表，含最后一条消息摘要，按最后活跃时间倒序
- **conversation/create_group** — 创建群聊，创建者为唯一群主，可附带初始成员
- **conversation/invite_member** — 任何现有成员可直接邀请，无需审批
- **conversation/leave_group** — 成员退群；群主退群则解散群聊
- **conversation/kick_member** — 仅群主可踢人，禁止踢群主和自己
- **conversation/edit_group_info** — 仅群主可编辑名称/头像，至少传一个
- **conversation/group_members** — 全量返回成员列表
- 群人数上限 200 人强制执行

---

## 关联需求

| 需求 ID | 描述 | 状态 |
|---------|------|------|
| BIZ-CONV-01 | conversation/list 返回会话列表及最后一条消息 | Pending |
| BIZ-CONV-02 | create_group 创建群聊，创建者为唯一群主 | Pending |
| BIZ-CONV-03 | invite_member 直接加入成员，无需审批 | Pending |
| BIZ-CONV-04 | leave_group 成员离开；群主离开则解散群 | Pending |
| BIZ-CONV-05 | kick_member 仅群主可踢人 | Pending |
| BIZ-CONV-06 | edit_group_info 仅群主可编辑 | Pending |
| BIZ-CONV-07 | group_members 返回成员列表 | Pending |
| BIZ-CONV-08 | 群人数上限强制执行 | Pending |

---

## 技术决策

### 查询与数据流

- **D-01:** **单次 JOIN 查询** — `conversation/list` 在 Repository 层用 `@Query` 做 `conversations JOIN conversation_members`，一次 DB 往返。`last_message` 字段在消息写入时由 Phase 6 已同步更新，无需额外 JOIN messages 表。
- **D-02:** **UUID 统一** — 所有 `conversation_id` 统一使用 UUID（与 `ConversationEntity.id` 一致），不按私聊/群聊区分生成策略。
- **D-13:** **按 last_updated_at 倒序 + 游标分页** — 每次发消息更新 `last_updated_at`，最近活跃会话排前。cursor 传时间戳。

### 群聊管理

- **D-03:** **任何现有成员可邀请** — 检查 inviter 是否为 conversation_members 成员即可，符合 IM 行业惯例。
- **D-05:** **群人数上限 200 人** — 与 `ConversationEntity.maxMembers` 默认值一致。
- **D-06:** **group_members 全量返回** — 群人数 ≤200，无需分页。
- **D-08:** **操作复用通用 Response** — invite/leave/kick/edit 返回 `Response(code=0, message='success')`。
- **D-09:** **软删除解散** — 群主退群时标记 Conversation.status=DISSOLVED，清空成员列表，保留消息记录。
- **D-10:** **create_group 需 member_uids** — 创建者自动成为群主+成员，member_uids 中不允许含创建者 UID。
- **D-14:** **禁止踢群主+踢自己** — 踢群主返回 `GROUP_PERM_DENIED`，踢自己返回 `INVALID_PARAM`。
- **D-15:** **edit_group 至少传一个字段** — name（最长 128）或 avatar_url（最长 256），不传返回 `INVALID_PARAM`。

### 推送通知

- **D-04:** **聊内推送，复用 PushService** — 通过 PushService 推送会话事件给在线成员：
  - 创建群聊 → `GROUP_CREATED`（推给初始成员，排除创建者）
  - 邀请成员 → `MEMBER_JOINED`（推给群内现有成员）
  - 退群 → `MEMBER_LEFT`（推给剩余成员）
  - 踢人 → `MEMBER_KICKED`（推给被踢者）+ `MEMBER_LEFT`（推给剩余成员）
  - 编辑群 → `GROUP_UPDATED`（推给所有成员）
  - 解散群 → `GROUP_DISSOLVED`（推给所有成员）
- **D-11:** **Payload Proto 在 conversation.proto 中定义** — 新增 `GroupCreatedPayload`、`MemberJoinedPayload`、`MemberLeftPayload`、`MemberKickedPayload`、`GroupUpdatedPayload`、`GroupDissolvedPayload`。（D-18 补充：`GROUP_INVITED` 枚举值保留但 Phase 7 不触发，无需定义 Payload）

### 安全增强

- **D-07:** **Phase 7 修复 PullMessagesHandler 成员检查** — 在 `handle()` 开头添加 `findByConversationIdAndUserId()` 成员验证，非成员返回 `BizCode.NOT_MEMBER`。

### Handler 组织

- **D-12:** **一 Handler 一文件** — 7 个独立 Handler 文件存放在 `handler/conversation/` 子目录。

### Entity Schema & 数据模型

- **D-17:** **会话实体新增 status 字段** — `ConversationEntity` 新增 `var status: Int = 0`（0=正常，1=已解散，后续可扩展 ARCHIVED 等），DB 加列。`ConversationMemberEntity` 新增 `var role: String = "member"`（owner/member），创建群聊时写入。`updatedAt` 保持 `LocalDateTime` 类型，Handler 层映射为 epoch millis。
- **D-21:** **ConversationEntity 扩展 lastMessage 字段** — `ConversationEntity` 新增 `lastMessageId: Long`、`lastMessagePreview: String`（截断 100 字符）、`lastMessageTs: Long` 三个字段。Phase 6 `SendMessageHandler` 发送消息时原子更新。会话列表单表查询，无需 JOIN messages 表。

### Transaction

- **D-19:** **编程式事务 + Mutex 会话串行化** — 多表操作（创建群聊、邀请成员、退群、踢人、解散群）使用 `TransactionTemplate.execute {}` 保证原子性。`ConcurrentHashMap<String, Mutex>` 按 `conversationId` 串行化并发写，防止 memberCount 等字段竞态。Repository 层不自行声明 `@Transactional`。编辑群聊（单表更新）无需事务包裹。

### 会话生命周期

- **D-20:** **Phase 7 仅查询私聊会话，不创建** — `conversation/list` 返回用户参与的所有会话（含私聊），通过 `ConversationMemberEntity.findByUserId()` 筛选。私聊会话的创建由 Phase 8（Friend）负责。Proto 中不定义 `CreatePrivateConvReq`。

- **语言**: Kotlin（全项目统一）
- **传输**: gRPC 双向流（唯一通信方式）
- **序列化**: Protobuf
- **构建**: Gradle Kotlin DSL，6 个子模块
- **数据库**: MySQL（会话/成员表）+ Redis（去重/未读计数）
- **依赖注入**: Koin
- **编程范式**: suspend 协程 + Handler 接口模式
- **Handler 注册**: Koin 模块 `registerHandlers()` 显式声明

---

## 灰区已解决

| 编号 | 问题 | 决策 |
|------|------|------|
| 1 | 会话列表查询策略 | 单次 JOIN 查询 (D-01) |
| 2 | conversation_id 生成策略 | UUID 统一 (D-02) |
| 3 | 邀请成员权限控制 | 任何现有成员 (D-03) |
| 4 | Push 通知策略 | 聊内推送，复用 PushService (D-04) |
| 5 | 群人数上限 | 200 人 (D-05) |
| 6 | group_members 分页 | 全量返回 (D-06) |
| 7 | PullMessagesHandler 安全修复 | 本阶段修复 (D-07) |
| 8 | 操作响应格式 | 复用通用 Response (D-08) |
| 9 | 群解散策略 | 软删除 (D-09) |
| 10 | create_group 初始成员 | 需要 member_uids (D-10) |
| 11 | Payload Proto 管理 | 在 conversation.proto 中定义 (D-11) |
| 12 | Handler 文件组织 | 一 Handler 一文件 (D-12) |
| 13 | 会话列表排序与分页 | last_updated_at 倒序 + 游标分页 (D-13) |
| 14 | 踢人保护 | 禁止踢群主+踢自己 (D-14) |
| 15 | edit_group 编辑策略 | 至少传一个，有长度校验 (D-15) |
| 16 | 群解散通知 | 推送 GROUP_DISSOLVED (D-16) |
| 17 | Entity Schema 变更 | ConversationEntity + status, ConversationMemberEntity + role, updatedAt 不换类型 (D-17) |
| 18 | Proto Payload 消息定义 | 5 个 Payload 在 conversation.proto 定义，GROUP_INVITED 保留枚举 (D-18) |
| 19 | Transaction 边界 | TransactionTemplate + Mutex 按会话串行化 (D-19) |
| 20 | 私聊会话范围 | Phase 7 仅查询，Phase 8 负责创建 (D-20) |
| 21 | ConversationBrief 填充 | Entity 扩展 lastMessage 字段，单表查询 (D-21) |

## 灰区遗留

无 — 所有讨论均在阶段范围内解决。

---

## Canonical References

**下游 Agent 在规划或实现前必须阅读以下文件。**

### Proto 定义
- `proto/src/main/proto/nebula/conversation/conversation.proto` — 7 个 Request/Response 消息 + ConversationBrief
- `proto/src/main/proto/nebula/group/group.proto` — GroupMember 消息（uid, username, display_name, avatar_url, role, joined_at）
- `proto/src/main/proto/nebula/message_type.proto` — PushEventType 枚举（GROUP_CREATED 等 7 个 conversation 事件）
- `proto/src/main/proto/nebula/envelope.proto` — Direction、Envelope、Message 消息定义

### 设计文档
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.1-接口契约.md` — Handler<ReqT, RespT> 接口定义
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.5-分文件组织.md` — 模块目录和包结构

### Entity 层
- `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt` — 会话实体（id, type, name, avatar, groupOwnerUid, memberCount, maxMembers, status）
- `repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt` — 成员实体（conversationId, userId, lastReadMessageId, unreadCount, deleted, joinedAt）

### Repository 层
- `repository/src/main/kotlin/com/nebula/repository/repository/ConversationRepository.kt` — 基础 JPA CRUD
- `repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt` — 6 个现有方法

### 已有实现
- `service/src/main/kotlin/com/nebula/service/push/PushService.kt` — Phase 6 推送组件，需扩展支持 conversation 事件
- `service/src/main/kotlin/com/nebula/service/push/UserStreamRegistry.kt` — 在线 StreamObserver 映射
- `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` — Handler 注册模式参考
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/HandlerRegistry.kt` — Handler 注册

### 异常体系
- `common/src/main/kotlin/com/nebula/common/exception/ConversationException.kt` — 会话异常
- `common/src/main/kotlin/com/nebula/common/exception/BizCode.kt` — 14xx 段 conversation 错误码

---

## 已有代码洞察

### 可复用资产
- **ConversationRepository** — JPA 基础 CRUD，需新增 JOIN 查询
- **ConversationMemberRepository** — 6 个方法，需新增 deleteByConversationIdAndUserId、countByConversationId
- **PushService** — Phase 6 推送组件，直接复用
- **UserStreamRegistry** — 在线用户映射，直接复用
- **ConversationException** — 6 个 BizCode 已定义，直接使用
- **BizCode** — CONV_NOT_FOUND(1400)、GROUP_FULL(1401)、GROUP_DISSOLVED(1402)、NOT_MEMBER(1403)、GROUP_PERM_DENIED(1404)、ALREADY_IN_GROUP(1405)

### 已有模式
- **Handler 接口模式** — `Handler<ReqT, RespT>` + `ProtoCodec` 零反射序列化
- **Koin 注册模式** — `GatewayModule.kt` 中 `handlerModule` + `registerHandlers()`
- **PushService** — 构建 Envelope(Direction.PUSH, Message, payload) → pushToUser(userId)

### 集成点
- 7 个新 Handler 需注册到 Koin 和 HandlerRegistry
- Proto 需扩展：新增 Payload 消息（GroupCreatedPayload 等）+ 可能修改 ConversationEntity（新增 status 字段）
- Repository 需扩展：ConversationRepository 新增 JOIN 查询方法、ConversationMemberRepository 新增删除/计数方法
- PullMessagesHandler.kt 需添加成员验证（修复 SECURITY FIXME）
- PushService 需支持 conversation 事件推送（新增 pushToConversation() 或扩展 pushMessage()）

---

*Phase: 7-Conversation*
*Context gathered: 2026-06-13*
