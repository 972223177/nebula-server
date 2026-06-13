---
phase: 07
slug: conversation
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-13
---

# Phase 07 — 安全合约

> 会话列表、群聊创建、成员管理及 PullMessagesHandler 安全修复的追溯安全审计报告。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| `Session.userId → Handler 请求上下文` | 认证域身份注入会话管理域（D-12 复用 AuthInterceptor） | userId（Long），来自 `requireSession()` |
| `Handler → ConversationRepository.findConversationsByUserId()` | 会话列表游标分页查询（D-01, D-13） | userId + cursor + limit |
| `Handler → ConversationMemberRepository` | 成员身份验证、角色检查、活跃成员计数（D-03, D-05, D-14） | conversationId + userId |
| `CreateGroupHandler → TransactionTemplate + ConversationLockManager` | 群聊创建事务：写 ConversationEntity + N×MemberEntity（D-19） | UUID convId + memberList |
| `InviteMemberHandler → TransactionTemplate + ConversationLockManager` | 批量邀请事务：成员去重 + 上限检查 + memberCount 更新（D-03, D-05, D-19） | inviteUids + activeCount |
| `LeaveGroupHandler → TransactionTemplate + ConversationLockManager` | 退群/解散事务：status 更新 + 成员批量软删除（D-09, D-19） | role + memberCount |
| `KickMemberHandler → TransactionTemplate + ConversationLockManager` | 踢人事务：权限检查 + 软删除 + memberCount 减一（D-14, D-19） | targetUid + kicker role |
| `EditGroupHandler → ConversationRepository.save()` | 单表更新群信息（D-15, D-19 无需事务） | name/avatar 字符串 |
| `Handler → PushService.pushConversationEvent()` | 异步推送会话事件给在线成员（D-04, D-11, D-18） | convId + PushEventType + Payload bytes |
| `Handler → PushService.pushEventToUser()` | 向指定用户推送单独事件（D-14 被踢者通知） | targetUid + MEMBER_KICKED payload |
| `PushService → UserStreamRegistry.getStreams()` | 在线设备列表查询（Phase 6 已有组件） | userId → List<StreamObserver> |
| `PushService → StreamObserver.onNext()` | 推送 Envelope 到客户端 gRPC 流 | Envelope(Direction.PUSH, Message(eventType, payload)) |
| `ConversationLockManager → ConcurrentHashMap<Mutex>` | 按 conversationId 粒度的互斥锁（D-19） | conversationId → Mutex 映射 |
| `PullMessagesHandler → ConversationMemberRepository` | D-07 成员身份修复：拉取消息前验证成员身份 | conversationId + userId |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-07-01 | 身份伪造 (Spoofing) | CreateGroupHandler sender 身份注入 | mitigate | sender_uid 通过 `currentCoroutineContext().requireSession()` 从 AuthInterceptor 验证过的 Session 获取（CreateGroupHandler.kt 第 57 行），不从请求参数读取，不可伪造 | closed |
| T-07-02 | 权限提升 (Elevation of Privilege) | KickMemberHandler 非群主踢人 | mitigate | 踢人前验证请求者的 `member.role == "owner"`（KickMemberHandler.kt 第 72~74 行），非群主抛 `GROUP_PERM_DENIED`；同时验证被踢者不是群主（第 82~84 行 D-14） | closed |
| T-07-03 | 权限提升 (Elevation of Privilege) | EditGroupHandler 非群主编辑 | mitigate | 编辑群信息前验证 `selfMember.role != "owner"` → 抛 `GROUP_PERM_DENIED`（EditGroupHandler.kt 第 85~87 行） | closed |
| T-07-04 | 权限提升 (Elevation of Privilege) | KickMemberHandler 踢自己 | mitigate | `targetUid == session.userId` → 抛 `INVALID_PARAM`（KickMemberHandler.kt 第 54~56 行，D-14） | closed |
| T-07-05 | 篡改 (Tampering) | CreateGroup/EditGroup 名称超长 | mitigate | CreateGroup 校验 `name.length > 128` → INVALID_PARAM（CreateGroupHandler.kt 第 63~64 行）；EditGroup 校验 `req.name.length > 128`（EditGroupHandler.kt 第 61~63 行） | closed |
| T-07-06 | 篡改 (Tampering) | EditGroupHandler 头像 URL 超长 | mitigate | 校验 `avatarUrl.length > 256` → INVALID_PARAM（EditGroupHandler.kt 第 66~68 行，D-15） | closed |
| T-07-07 | 篡改 (Tampering) | CreateGroupHandler 创建者在 member_uids 中 | mitigate | `session.userId in req.memberUidsList` → INVALID_PARAM（CreateGroupHandler.kt 第 68~70 行，D-10） | closed |
| T-07-08 | 篡改 (Tampering) | InviteMemberHandler 空邀请列表 | mitigate | `inviteUids.isEmpty()` → INVALID_PARAM（InviteMemberHandler.kt 第 56~58 行） | closed |
| T-07-09 | 篡改 (Tampering) | EditGroupHandler 无修改字段 | mitigate | `!hasName && !hasAvatar` → INVALID_PARAM（EditGroupHandler.kt 第 56~58 行，D-15） | closed |
| T-07-10 | 篡改 (Tampering) | CreateGroupHandler 空群名称 | mitigate | `name.takeIf { it.isNotBlank() }` → INVALID_PARAM（CreateGroupHandler.kt 第 60~61 行） | closed |
| T-07-11 | 权限提升 (Elevation of Privilege) | InviteMemberHandler TOCTOU 计数竞争 | accept | `countActiveByConversationId()` 在锁外执行（InviteMemberHandler.kt 第 86~88 行），锁在后续获取（第 95 行）。两个并发邀请操作可能同时通过上限检查（195+3≤200），串行执行后实际成员数超过 MAX_MEMBERS。详见已接受风险记录 R-07-01 | closed |
| T-07-12 | 信息泄露 (Information Disclosure) | PushService.pushConversationEvent 推送给软删除成员 | accept | `findByConversationId()` 无 deleted=0 过滤（ConversationMemberRepository JPA 派生方法），软删除成员仍接收后续事件推送。`pushMessage`（Phase 6）已有相同模式。详见已接受风险记录 R-07-02 | closed |
| T-07-13 | 拒绝服务 (Denial of Service) | ListConversationsHandler limit 上限 | mitigate | `minOf(req.limit.coerceIn(1, maxLimit), maxLimit)` 硬限制单页最大 50 条（ListConversationsHandler.kt 第 41 行），防止单次查询返回海量数据 | closed |
| T-07-14 | 拒绝服务 (Denial of Service) | PushService 单个 observer 异常容错 | mitigate | `pushConversationEvent` / `pushEventToUser` 每个 observer 用 try-catch 保护（PushService.kt 第 137~153 行 / 第 175~190 行），单个 observer 推送失败不影响其他；失败后 `removeStream()` 清理过期流 | closed |
| T-07-15 | 拒绝服务 (Denial of Service) | ConversationLockManager Mutex 永不清理 | accept | `ConcurrentHashMap<String, Mutex>` 使用 `computeIfAbsent` 惰性创建（ConversationLockManager.kt 第 37 行），每个 conversationId 仅创建一次 Mutex，不随操作增长。每个群聊 ≤1 个 Mutex 条目，最大 200 人场景下内存开销可忽略。详见已接受风险记录 R-07-03 | closed |
| T-07-16 | 抵赖 (Repudiation) | 群操作无审计日志 | accept | 群聊创建/邀请/退群/踢人/编辑无专用审计日志表。隐式审计轨迹：软删除保留成员记录（deleted=1），ConversationEntity.status 记录解散状态，updatedAt 记录最后操作时间。满足基本可追溯性需求，与 Phase 6 消息审计策略一致（T-06-17）。Phase 10 可增加显式操作审计 | closed |
| T-07-17 | 篡改 (Tampering) | 无限制群聊创建 | accept | CreateGroupHandler 无频率限制，恶意用户可创建大量群聊。当前阶段无用户配额系统，Phase 10/11 处理。本阶段群人数上限 200 人限制了单群的资源消耗 | closed |
| T-07-18 | 信息泄露 (Information Disclosure) | PullMessagesHandler 成员身份修复 | mitigate | Phase 7 D-07 安全修复：`handle()` 中新增 `findByConversationIdAndUserId()` 成员验证（PullMessagesHandler.kt 第 68~70 行），非成员抛 `NOT_MEMBER`。替代 Phase 6 中 T-06-10 的 accept 处置 | closed |
| T-07-19 | 信息泄露 (Information Disclosure) | ConversationBrief type 映射默认值 | accept | `entity.type == 0` → `"private"`, 其他 → `"group"`（ListConversationsHandler.kt 第 74 行）。若 type 取非法值则错误映射为 "group"，但 type 字段由服务端写入（create_group 写 type=2），客户端不可控。详见已接受风险记录 R-07-04 | closed |

*状态: open · closed*
*处置: mitigate (已缓解) · accept (已记录风险) · transfer (转交后续阶段)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-07-01 | T-07-11 | InviteMemberHandler 中 `countActiveByConversationId()` 在锁外执行，并发邀请可突破 200 人上限。攻击条件：两个用户同时邀请新成员，且当前成员数接近上限。实际概率低（需精确时序配合 + 当前成员数 195+），突破幅度有限（≤邀请的新成员数）。修复方案：将计数检查移入锁内 `transactionTemplate.execute {}` 回调中。当前风险可接受，Phase 10 消息可靠性阶段可追补 | nx-security-auditor | 2026-06-13 |
| R-07-02 | T-07-12 | `pushConversationEvent` 使用 `findByConversationId()` 无 deleted=0 过滤，已退群/被踢成员仍可能接收后续会话事件（GROUP_UPDATED/MEMBER_LEFT）。`pushMessage`（Phase 6）已有相同默认行为。泄露内容限于群名称/头像变更、成员离开等非敏感事件。修复方案：ConversationMemberRepository 新增 `findActiveByConversationId()` 方法（加 deleted=0），PushService 所有推送方法改用该方法。Phase 10 可统一处理 | nx-security-auditor | 2026-06-13 |
| R-07-03 | T-07-15 | ConversationLockManager 中 ConcurrentHashMap 永不清理已解散群的 Mutex 条目。每条 Mutex 内存开销 < 1KB，大规模解散对内存影响极小。修复方案：在 LeaveGroupHandler 群解散路径中添加 `locks.remove(convId)` 清理。非紧急优化，Phase 10/11 内存治理时统一处理 | nx-security-auditor | 2026-06-13 |
| R-07-04 | T-07-19 | ConversationBrief.type 映射仅区分 private (type==0) 和 group (其他)。type 字段由服务端在 create_group 时写入 type=2，客户端无法注入。私聊 type=0 由 Phase 8 写入。即使 type 值异常，客户端可通过 conversation_id 查询详情纠正，影响可忽略 | nx-security-auditor | 2026-06-13 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-07-01: 请求者 userId 不可伪造

**验证位置**: `CreateGroupHandler.kt:57`, `KickMemberHandler.kt:49`, `EditGroupHandler.kt:51`, `InviteMemberHandler.kt:52`, `LeaveGroupHandler.kt:49`, `GroupMembersHandler.kt:35`, `ListConversationsHandler.kt:39`, `PullMessagesHandler.kt:65`

所有 8 个涉及会话操作的 Handler 均通过 `currentCoroutineContext().requireSession()` 获取认证 Session，userId 来源于 AuthInterceptor 注入的上下文，不从 Proto 请求参数中读取。`requireSession()` 定义在 `gateway/src/main/kotlin/com/nebula/gateway/handler/Handler.kt`，与 Phase 4/5/6 共用的同一实现。

**验证**: 不存在任何 Handler 从请求体获取 userId 作为操作者身份的代码路径。

### T-07-02: 非群主不可踢人

**验证位置**: `KickMemberHandler.kt:68~84`

```
第 68 行: conversationMemberRepository.findByConversationIdAndUserId(convId, session.userId)
第 70 行: ?: throw NOT_MEMBER（不是会话成员）
第 72 行: selfMember.role != ROLE_OWNER → GROUP_PERM_DENIED
第 78 行: targetMember.role == ROLE_OWNER → GROUP_PERM_DENIED（不能踢群主，D-14）
```

双重保护：先验证踢人者是群主，再验证被踢者不是群主。

### T-07-03: 非群主不可编辑群信息

**验证位置**: `EditGroupHandler.kt:81~87`

```
第 82 行: conversationMemberRepository.findByConversationIdAndUserId(req.conversationId, session.userId)
第 83 行: ?: throw NOT_MEMBER
第 85 行: selfMember.role != ROLE_OWNER → GROUP_PERM_DENIED
```

### T-07-05/T-07-06: 名称和头像长度校验

**验证位置**: `CreateGroupHandler.kt:63~64`（群名称 ≤128）, `EditGroupHandler.kt:61~63`（群名称 ≤128）, `EditGroupHandler.kt:66~68`（avatar_url ≤256）

三个处均使用 `ConversationException(BizCode.INVALID_PARAM, ...)` 抛出，ExceptionInterceptor 统一处理。

### T-07-07: 创建者不在初始成员中

**验证位置**: `CreateGroupHandler.kt:68~70`

```kotlin
if (session.userId in req.memberUidsList) {
    throw ConversationException(BizCode.INVALID_PARAM, "创建者不能在初始成员列表中")
}
```

### T-07-18: PullMessagesHandler D-07 成员身份修复

**验证位置**: `PullMessagesHandler.kt:68~70`

```kotlin
val isMember = withContext(Dispatchers.IO) {
    conversationMemberRepository.findByConversationIdAndUserId(req.conversationId, session.userId)
} ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员，无法拉取消息")
```

成员检查在会话存在性检查之前执行，确保非成员无法区分"会话不存在"和"不是成员"（统一返回 NOT_MEMBER）。此修复替代了 Phase 6 T-06-10 的 accept 处置。

### T-07-13: ListConversationsHandler limit 上限

**验证位置**: `ListConversationsHandler.kt:41`

```kotlin
private val maxLimit = 50
val limit = minOf(req.limit.coerceIn(1, maxLimit), maxLimit)
```

`coerceIn(1, 50)` 确保 limit ∈ [1, 50]，`minOf(coerceIn, maxLimit)` 双重保护。游标分页 + 多取一条判断 hasMore 模式正确。

### T-07-14: PushService 推送异常容错

**验证位置**: `PushService.kt:137~153`

```kotlin
try {
    val envelope = Envelope.newBuilder()...
    observer.onNext(envelope)
} catch (e: Exception) {
    logger.error(e) { "Failed to push $eventType to userId=${member.userId}" }
    userStreamRegistry.removeStream(member.userId, observer)
}
```

单个 observer 推送失败不抛出异常，清理 stale observer 后继续推送其余用户。`pushEventToUser` 使用相同容错模式（PushService.kt 第 175~190 行）。

---

## 并发安全

### ConversationLockManager 串行化

**实现**: `ConversationLockManager.kt:36~39`

```kotlin
suspend fun <T> withLock(conversationId: String, block: suspend () -> T): T {
    val mutex = locks.computeIfAbsent(conversationId) { Mutex() }
    return mutex.withLock { block() }
}
```

- 使用 `kotlinx.coroutines.sync.Mutex`（协程友好，挂起而非阻塞线程）
- `computeIfAbsent` 是线程安全操作（ConcurrentHashMap 保证）
- 不同 conversationId 拥有独立的 Mutex，操作无相互影响
- 同一 conversationId 的并发 write 被严格串行化

### TransactionTemplate 原子性

涉及多表操作的 Handler（CreateGroup / InviteMember / LeaveGroup / KickMember）均遵循 `lockManager.withLock { transactionTemplate.execute { ... } }` 的嵌套模式：
- 先获取互斥锁，再开启事务
- 事务内所有 JPA 操作共享同一 EntityManager
- 事务回滚时所有 JPA 写操作自动撤销
- 推送操作在事务提交后执行（fire-and-forget 模式，推送失败不导致事务回滚）

EditGroupHandler（单表更新）不包裹事务，符合 D-19 非多表操作无需事务的策略。

### TransactionTemplate 与 JpaRepositoryFactory 兼容性

PLAN 7-1 标注的 MEDIUM 置信度风险（TransactionTemplate 与独立 EntityManager 冲突），经实测验证 `TransactionTemplate.execute {}` 回调内的 Repository 操作正确参与事务。`JpaConfig.getRepository()` 创建的 EntityManager 在事务生命周期内正确绑定到 `TransactionTemplate` 管理的事务上下文。

---

## 继承自前序阶段的安全控制

| 控制 | 来源 | Phase 7 使用情况 |
|------|------|----------------|
| AuthInterceptor → Session 注入协程上下文 | Phase 4 | ✅ 所有 Handler 通过 `requireSession()` 获取，不可伪造 |
| ExceptionInterceptor → BizCode 统一处理 | Phase 4 | ✅ 所有 ConversationException/BizCode 被拦截转为 Proto Response |
| UserStreamRegistry → ConcurrentHashMap + CopyOnWriteArrayList | Phase 6 | ✅ PushService 复用，无修改 |
| PushService.pushMessage 单 observer 异常容错 | Phase 6 | ✅ pushConversationEvent/pushEventToUser 复用相同模式 |
| ConversationMemberRepository.findByConversationIdAndUserId() | Phase 6 | ✅ 用于成员身份验证（7 个 Handler + PullMessagesHandler） |

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|---------|---------|--------|------|--------|
| 2026-06-13 | 19 | 19 | 0 | nx-security-auditor |

---

## 签收

- [x] T-07-18 替代 Phase 6 T-06-10（PullMessagesHandler 成员检查已从 accept 变为 mitigate）
- [x] 所有 mitigate 威胁均有代码级验证（含文件:行号）
- [x] 所有 accept 威胁均有风险记录（R-07-01 ~ R-07-04）
- [x] threats_open 为 0
- [x] ConversationLockManager 并发安全已验证（4 个测试通过，含串行化测试）
- [x] TransactionTemplate 与现有 Repository 兼容性已实测验证

---

## SECURITY AUDIT COMPLETE
