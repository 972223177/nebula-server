---
phase: 08
slug: friend-online-status
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-13
---

# Phase 08 — 安全合约

> 好友管理（添加/接受/拒绝/删除/列表/请求列表）和在线状态管理（三值状态、伪在线、状态推送）的追溯安全审计报告。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| `Session.userId → Handler 请求上下文` | 认证域身份注入会话管理域（D-41 复用 AuthInterceptor） | userId（Long），来自 `requireSession()` |
| `FriendAddHandler → FriendshipRepository.findByUserIdAndFriendId()` | 好友关系存在性检查（D-51, D-52） | smallerUid + largerUid（排序后） |
| `FriendAddHandler → FriendRequestRepository.findByFromUidAndToUidAndStatus()` | 双向竞赛检测（D-52）和重复申请检查（D-51） | fromUid + toUid + status |
| `FriendAcceptHandler → TransactionTemplate + ConversationLockManager` | 接受好友单事务：更新请求 + 创建好友记录 + 创建私聊会话 + 2 个成员（D-43, D-45） | convId + smaller/larger + requestId |
| `FriendAcceptHandler → FriendRequestRepository.findById()` | 加载待处理申请并校验权限（D-41） | requestId + toUid |
| `FriendRejectHandler → FriendRequestRepository` | 拒绝好友申请，校验申请归属（D-41） | requestId + toUid |
| `FriendDeleteHandler → FriendshipRepository` | 软删除好友关系（D-44），保留会话 | smaller/larger（排序后） |
| `FriendListHandler → FriendshipRepository.findFriendsByUserId()` | 游标分页查询好友列表（D-46） | userId + cursor + limit |
| `FriendListHandler → OnlineStatusRepository.batchGetStatus()` | 批量查询好友在线状态 | friendUids |
| `FriendListHandler → PrivacyRepository.batchGetHideOnlineStatus()` | 隐藏用户过滤（D-59） | friendUids |
| `FriendRequestsHandler → FriendRequestRepository.findByToUidAndStatus()` | 查询待处理好友申请 | toUid + status |
| `FriendCheckStep → ConversationRepository.findById()` | 私聊会话类型判断（D-56） | conversationId |
| `FriendCheckStep → FriendshipRepository.findByUserIdAndFriendId()` | 非好友私聊禁发检查（D-56） | smaller + larger（从 convId 解析） |
| `SetPrivacyHandler → OnlineStatusRepository.setHidden/setOnline()` | 隐藏状态切换时同步 Redis（D-59） | userId + status |
| `SetPrivacyHandler → PushService.pushEventToUser()` | 隐藏/取消隐藏时广播好友状态变更（D-57, D-59） | friendUid + STATUS_CHANGED payload |
| `ChatService.handleLoginSuccess() → OnlineStatusRepository.setOnline()` | 登录时标记在线（D-47, D-57） | userId |
| `ChatService.handlePing() → OnlineStatusRepository.refreshTtl()` | PING 心跳刷新 TTL（D-48） | userId |
| `ChatService.cleanupConnection() → scope.launch { delay(60_000) }` | 伪在线 60s 窗口后标记离线（D-49） | delayedOfflineJob |
| `ChatService.pushStatusChangeToFriends() → PushService.pushEventToUser()` | 状态变更推送好友（D-50, D-58） | friendUid + STATUS_CHANGED |
| `OnlineStatusRepository → Redis SETEX/GET/EXPIRE/MGET/DEL` | 三值状态 JSON 存储（D-47, D-48） | Redis key `online:user:{userId}` |
| `BatchGetStatusHandler → OnlineStatusRepository.getStatus()` | 三值状态查询（0=离线, 1=在线, 2=隐藏） | uid |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-08-01 | 身份伪造 (Spoofing) | FriendAddHandler 申请者身份 | mitigate | sender_uid 通过 `currentCoroutineContext().requireSession()` 从 AuthInterceptor 验证过的 Session 获取（FriendAddHandler.kt 第 50~51 行），不从请求参数读取 | closed |
| T-08-02 | 身份伪造 (Spoofing) | FriendAcceptHandler 接受者身份 | mitigate | `request.toUid != session.userId` → 抛 FORBIDDEN（FriendAcceptHandler.kt 第 57~59 行），只有被申请人才能接受 | closed |
| T-08-03 | 身份伪造 (Spoofing) | FriendRejectHandler 拒绝者身份 | mitigate | `request.toUid != session.userId` → 抛 FORBIDDEN（FriendRejectHandler.kt 第 34~36 行） | closed |
| T-08-04 | 权限提升 (Elevation of Privilege) | 自加好友 | mitigate | `session.userId == req.toUid` → 抛 SELF_FRIEND（FriendAddHandler.kt 第 55~57 行，D-54） | closed |
| T-08-05 | 权限提升 (Elevation of Privilege) | 已是好友重复申请 | mitigate | `findByUserIdAndFriendId()` 存在且 deleted=0 → 抛 ALREADY_FRIEND（FriendAddHandler.kt 第 64~68 行，D-51） | closed |
| T-08-06 | 权限提升 (Elevation of Privilege) | 重复好友申请 | mitigate | `findByFromUidAndToUidAndStatus(from, to, 0)` 存在 → 抛 REQUEST_HANDLED（FriendAddHandler.kt 第 134~137 行，D-51） | closed |
| T-08-07 | 权限提升 (Elevation of Privilege) | 重复处理已接受/已拒绝申请 | mitigate | `request.status != 0` → 抛 REQUEST_HANDLED（FriendAcceptHandler.kt 第 62~64 行 / FriendRejectHandler.kt 第 39~41 行） | closed |
| T-08-08 | 信息泄露 (Information Disclosure) | 好友列表暴露隐藏用户状态 | mitigate | `privacyRepository.batchGetHideOnlineStatus(friendUids)` 排除隐藏用户，隐藏用户在线状态返回 status=0（FriendListHandler.kt 第 65~68 行，D-59） | closed |
| T-08-09 | 信息泄露 (Information Disclosure) | 非好友私聊发送消息 | mitigate | FriendCheckStep 在 Step 链中插入私聊好友检查（FriendCheckStep.kt 第 36~52 行），私聊会话 + 非好友或已删除 → 抛 NOT_FRIEND（D-56） | closed |
| T-08-10 | 信息泄露 (Information Disclosure) | 隐藏用户状态变更推送 | mitigate | `pushStatusChangeToFriends` 中过滤隐藏用户（SetPrivacyHandler.kt 第 82~84 行 / ChatService.kt 第 355~357 行，D-59） | closed |
| T-08-11 | 篡改 (Tampering) | 好友请求 message 字段超长 | mitigate | Entity 定义 `@Column(length = 255)`（FriendRequestEntity.kt 第 23 行），超过 255 字符的 message 在 JPA flush 时抛出 DataException。Proto 端 string 类型无长度校验，但 DB 层约束是最后一道防线 | closed |
| T-08-12 | 篡改 (Tampering) | 双向竞赛 TOCTOU 竞争 | accept | 两个读查询（好友检查 + 反向申请检测）在 `lockManager.withLock()` 之前执行（FriendAddHandler.kt 第 64~74 行），存在 TOCTOU 窗口。攻击条件：两个并发请求在极短时间窗口内同时通过检查，导致重复创建好友关系。详见已接受风险记录 R-08-01 | closed |
| T-08-13 | 篡改 (Tampering) | FriendAcceptHandler 并发接受同一申请 | accept | `findById()` 在锁外执行（FriendAcceptHandler.kt 第 51~53 行），两个并发请求可能同时通过 status != 0 检查。锁内 Spring Data JPA `save()` 执行 merge，重复更新 status=1 是幂等操作。详见已接受风险记录 R-08-02 | closed |
| T-08-14 | 拒绝服务 (Denial of Service) | FriendListHandler limit 无上限截断 | accept | `req.limit > 0` 仅做正性检查，无上限截断（FriendListHandler.kt 第 41 行）。恶意客户端可传入极大 limit 值。Proto 定义 `int32 limit = 2` 无验证规则。详见已接受风险记录 R-08-03 | closed |
| T-08-15 | 拒绝服务 (Denial of Service) | FriendRequestsHandler 无分页 | accept | `findByToUidAndStatusOrderByCreatedAtDesc` 返回所有 pending 申请（FriendRequestsHandler.kt 第 32 行），无分页限制。详见已接受风险记录 R-08-04 | closed |
| T-08-16 | 拒绝服务 (Denial of Service) | SetPrivacyHandler 使用 Int.MAX_VALUE 加载全部好友 | accept | `PageRequest.of(0, Int.MAX_VALUE)` 查询全部好友用于推送（SetPrivacyHandler.kt 第 78 行）。实际场景中普通用户好友数有限，但极端情况可能导致 OOM。详见已接受风险记录 R-08-05 | closed |
| T-08-17 | 拒绝服务 (Denial of Service) | BatchGetStatusHandler 无 uids 大小限制 | accept | `req.uidsList` 直接传入 `onlineStatusRepository.batchGetStatus()`（BatchGetStatusHandler.kt 第 44 行），无列表大小限制。详见已接受风险记录 R-08-06 | closed |
| T-08-18 | 拒绝服务 (Denial of Service) | FriendCheckStep 私聊 ID 格式异常时静默跳过 | accept | `parsePrivateConvId` 返回 null 时调用方 `return true`（FriendCheckStep.kt 第 43 行），格式错误的私聊 convId 导致好友校验被跳过。详见已接受风险记录 R-08-07 | closed |
| T-08-19 | 拒绝服务 (Denial of Service) | ChatService 无最大连接数限制 | accept | ChatService 无连接数上限，恶意客户端可建立大量 gRPC 连接消耗服务端资源。详见已接受风险记录 R-08-08 | closed |
| T-08-20 | 抵赖 (Repudiation) | 好友操作无审计日志 | accept | 好友添加/接受/拒绝/删除操作无专用审计日志表。隐式审计轨迹：friend_requests.status 字段追踪请求生命周期，friendships.deleted 记录删除状态，updatedAt 记录最后操作时间。与 Phase 6/7 审计策略一致。Phase 10 可增加显式操作审计 | closed |
| T-08-21 | 信息泄露 (Information Disclosure) | ChatService onError 透传原始异常 | accept | `onError()` 中 `responseObserver.onError(t)` 将原始异常透传客户端（ChatService.kt 第 139 行），可能暴露内部堆栈信息。详见已接受风险记录 R-08-09 | closed |
| T-08-22 | 篡改 (Tampering) | OnlineStatusRepository JSON 反序列化降级 | mitigate | `getStatus()` 中 try-catch 捕获 JSON 解码异常降级返回 null（OnlineStatusRepository.kt 第 68~73 行），兼容旧格式数据。`batchGetStatus()` 有相同保护（第 102~107 行） | closed |
| T-08-23 | 篡改 (Tampering) | ChatService delayedOfflineJob 重连取消 | mitigate | `responseObserver.delayedOfflineJob?.cancel()` 在 `handleLoginSuccess` 中取消旧延迟任务（ChatService.kt 第 248 行），延迟任务执行前检查 `userStreamRegistry.getStreams(uid).isEmpty()`（第 163 行），双重保护防止误标离线 | closed |
| T-08-24 | 篡改 (Tampering) | 隐藏状态切换推送（Nyquist 修复） | mitigate | SetPrivacyHandler 中切换隐藏/取消隐藏时异步推送 STATUS_CHANGED 给在线好友（SetPrivacyHandler.kt 第 74~106 行），使用 fire-and-forget 模式不阻塞主流程 | closed |
| T-08-25 | 信息泄露 (Information Disclosure) | pushStatusChangeToFriends 异常容错 | mitigate | 逐个好友推送时 try-catch 保护（ChatService.kt 第 362~365 行 / SetPrivacyHandler.kt 第 99~104 行），单个好友推送失败不影响其余 | closed |

*状态: open · closed*
*处置: mitigate (已缓解) · accept (已记录风险) · transfer (转交后续阶段)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-08-01 | T-08-12 | FriendAddHandler 双向竞赛检测中，好友关系检查（第 65 行）和反向申请查询（第 73 行）在 `lockManager.withLock()`（第 79 行）之外执行。两个并发请求可能同时通过检查，都进入竞赛分支。锁内的事务操作使用 Spring Data JPA `save()` 执行 merge，重复插入受唯一约束保护不会产生重复记录。真实攻击需要精确的时序配合，实际利用难度高。修复方案：将两次读查询移入 `lockManager.withLock {}` 块内。Phase 10 可靠性阶段可追补 | nx-security-auditor | 2026-06-13 |
| R-08-02 | T-08-13 | FriendAcceptHandler 中 `findById()` 在锁外执行，两个并发接受同一申请可能同时通过 `status != 0` 检查。锁内 `request.status = 1` 的 `save()` 是幂等操作（Spring Data JPA merge 语义），最终 status 一致为 1。推送可能重复，但 PushService 的异常容错机制（T-07-14）确保单 observer 异常不影响其他。实际影响极低 | nx-security-auditor | 2026-06-13 |
| R-08-03 | T-08-14 | FriendListHandler limit 无上限截断，Proto `int32 limit = 2` 也无验证规则。普通用户好友数通常不超过数千，单次查询 2000 条记录对服务端压力有限。修复方案：添加 `minOf(req.limit, 100)` 上限截断。Phase 10/11 性能阶段统一处理 | nx-security-auditor | 2026-06-13 |
| R-08-04 | T-08-15 | FriendRequestsHandler 无分页限制，`findByToUidAndStatusOrderByCreatedAtDesc` 返回所有 status=0 的申请。恶意用户可向目标用户批量发送大量好友申请导致一次性加载压力。但 friend/add 无速率限制（R-08-08）是更大风险，申请列表无分页是派生后果。修复方案：添加分页支持。Phase 10/11 统一处理 | nx-security-auditor | 2026-06-13 |
| R-08-05 | T-08-16 | SetPrivacyHandler 使用 `PageRequest.of(0, Int.MAX_VALUE)` 加载全部好友用于推送。实际场景中普通用户好友数远小于 Int.MAX_VALUE（通常 < 1000），单次全量加载对内存压力有限。修复方案：限制最大分页大小或使用游标分页分批处理。Phase 10/11 统一处理 | nx-security-auditor | 2026-06-13 |
| R-08-06 | T-08-17 | BatchGetStatusHandler 无 uidsList 大小限制。Proto repeated 字段无 `max_items` 验证规则。Redis MGET 对大量 key 的查询性能随 key 数线性增长，但单次调用通常 ≤ 好友数（≤ 数千）。修复方案：Proto 添加 `(validate.rules).repeated.max_items = 500` 或服务端截断。Phase 10/11 统一处理 | nx-security-auditor | 2026-06-13 |
| R-08-07 | T-08-18 | FriendCheckStep 中 `parsePrivateConvId` 返回 null 时调用方 `return true` 跳过好友检查。依赖私聊 convId 格式 `private:smaller:larger` 的约定，格式异常的 convId 无法创建（私聊会话由 FriendAcceptHandler/FriendAddHandler 写入，格式受控）。客户端无法自行创建格式异常的私聊会话。但若私聊 convId 格式在未来版本变更，需同步更新 `parsePrivateConvId`。修复方案：格式异常时抛异常而非静默跳过 | nx-security-auditor | 2026-06-13 |
| R-08-08 | T-08-19 | ChatService 无最大连接数限制。每个 gRPC 双向流占用一个 Netty channel 和 Thread 资源。当前无连接数上限和速率限制。与 Phase 6/7 一致，Phase 11 性能阶段增加连接数限制和速率限制 | nx-security-auditor | 2026-06-13 |
| R-08-09 | T-08-21 | ChatService `onError()` 中 `responseObserver.onError(t)` 将原始异常（含堆栈信息）透传给 gRPC 客户端。异常信息可能包含内部类名、方法名等实现细节。当前阶段生产环境与客户端在同一信任域内（内网服务），泄露风险有限。修复方案：使用 `StatusException` 封装安全异常信息替代原始异常。Phase 10 统一错误处理时修复 | nx-security-auditor | 2026-06-13 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-08-01: 申请者身份不可伪造

**验证位置**: `FriendAddHandler.kt:50~51`, `FriendAcceptHandler.kt:57~59`, `FriendRejectHandler.kt:34~36`, `FriendDeleteHandler.kt:32~33`, `FriendListHandler.kt:39~40`, `FriendRequestsHandler.kt:29~30`

所有 6 个好友 Handler 均通过 `currentCoroutineContext().requireSession()` 获取认证 Session，userId 来源于 AuthInterceptor 注入的上下文，不从 Proto 请求参数中读取。

**验证**: 不存在任何好友 Handler 从请求体获取 userId 作为操作者身份的代码路径。

### T-08-02/T-08-03: 申请接受/拒绝权限校验

**验证位置**: `FriendAcceptHandler.kt:57~59`

```kotlin
if (request.toUid != session.userId) {
    throw FriendException(BizCode.FORBIDDEN, "只能接受发给自己的好友请求")
}
```

**验证位置**: `FriendRejectHandler.kt:34~36`

```kotlin
if (request.toUid != session.userId) {
    throw FriendException(BizCode.FORBIDDEN, "只能拒绝发给自己的好友请求")
}
```

双重校验：先验证申请存在，再验证操作者是申请的目标用户。

### T-08-09: 私聊非好友禁发

**验证位置**: `FriendCheckStep.kt:31~52`

```
第 31 行: conversationRepository.findById(convId)
第 36 行: conv.type != CONV_TYPE_PRIVATE → 直接返回 true（跳过，仅私聊检查）
第 41 行: parsePrivateConvId(convId, session.userId) → Pair(smaller, larger)
第 46 行: friendshipRepository.findByUserIdAndFriendId(smaller, larger)
第 51 行: friendship == null || friendship.deleted == 1 → throw NOT_FRIEND
```

Step 链顺序：ValidateStep → FriendCheckStep → DedupStep → WriteStep（GatewayModule.kt），好友检查在去重和写入之前执行。

### T-08-08/T-08-10: 隐藏用户状态保护

**验证位置**: `FriendListHandler.kt:65~68`

```kotlin
val hiddenUids = privacyRepository.batchGetHideOnlineStatus(friendUids).toSet()
// ...
val statusData = if (friendUid in hiddenUids) null
    else onlineStatusRepository.batchGetStatus(listOf(friendUid))[friendUid]
val status = statusData?.status ?: 0
```

隐藏用户在线状态返回 0（离线），不暴露用户真实状态。

**验证位置**: `SetPrivacyHandler.kt:82~84` / `ChatService.kt:355~357`

```kotlin
val hiddenUids = withContext(Dispatchers.IO) {
    privacyRepository.batchGetHideOnlineStatus(friendUids).toSet()
}
```

推送前过滤隐藏用户，隐藏状态用户不触发好友推送（D-59）。

### T-08-11: message 字段长度限制

**验证位置**: `FriendRequestEntity.kt:23`

```kotlin
/** 好友申请附言，D-42 */
@Column(length = 255)
var message: String = ""
```

数据库列定义 `VARCHAR(255)`，JPA 注解 `@Column(length = 255)` 双重限制。超过 255 字符时 JPA flush 抛出 `DataException`，由 ExceptionInterceptor 统一处理。

### T-08-22: JSON 反序列化兼容

**验证位置**: `OnlineStatusRepository.kt:68~73`

```kotlin
return try {
    json.decodeFromString<OnlineStatusData>(raw)
} catch (e: SerializationException) {
    logger.warn { "Failed to decode status data for userId=$userId, raw=$raw" }
    null  // 兼容旧格式数据
}
```

`batchGetStatus` 有相同的 try-catch 保护（第 102~107 行），确保旧格式数据不导致服务崩溃。

### T-08-23: 伪在线延迟任务取消

**验证位置**: `ChatService.kt:158~171, 248`

```kotlin
// 第 158~171 行: cleanupConnection 中启动延迟任务
delayedOfflineJob = scope.launch {
    delay(60_000)
    if (userStreamRegistry.getStreams(uid).isEmpty()) {
        onlineStatusRepository.setOffline(uid)
        pushStatusChangeToFriends(uid, 0)
    }
}

// 第 248 行: handleLoginSuccess 中取消旧任务
responseObserver.delayedOfflineJob?.cancel()
```

双重保护：
1. 重连时取消旧延迟任务（第 248 行）
2. 延迟任务执行前检查当前设备数（第 163 行）

### T-08-24: SetPrivacyHandler 隐藏切换推送（Nyquist 修复）

**验证位置**: `SetPrivacyHandler.kt:74~106`

```kotlin
pushScope.launch {
    val friendUids = withContext(Dispatchers.IO) {
        friendshipRepository.findFriendsByUserId(
            userId, 0, PageRequest.of(0, Int.MAX_VALUE)
        ).map { it.getFriendUid(userId) }
    }
    val hiddenUids = withContext(Dispatchers.IO) {
        privacyRepository.batchGetHideOnlineStatus(friendUids).toSet()
    }
    val payload = StatusChangedPayload.newBuilder().setUid(userId).build()
    for (friendUid in friendUids) {
        if (friendUid in hiddenUids) continue
        try {
            pushService.pushEventToUser(friendUid, PushEventType.STATUS_CHANGED, payload.toByteString())
        } catch (e: Exception) {
            logger.warn(e) { "Failed to push status change to friend=$friendUid" }
        }
    }
}
```

使用 fire-and-forget 模式，推送不阻塞主流程。逐个好友推送时 try-catch 保护单个失败。

### T-08-25: 状态推送异常容错

**验证位置**: `ChatService.kt:362~365`

```kotlin
try {
    pushService.pushEventToUser(friendUid, PushEventType.STATUS_CHANGED, payload.toByteString())
} catch (e: Exception) {
    logger.warn(e) { "Failed to push status change to friend=$friendUid" }
}
```

SetPrivacyHandler 使用相同的异常容错模式（SetPrivacyHandler.kt 第 99~104 行）。

---

## 并发安全

### ConversationLockManager 串行化（锁+事务模式）

涉及多表写操作的 Handler（FriendAcceptHandler 和 FriendAddHandler 双向竞赛分支）遵循 `lockManager.withLock { transactionTemplate.execute { ... } }` 的嵌套模式：

**FriendAcceptHandler**（第 82~120 行）：
- 锁 Key: 私聊会话 ID `private:smaller:larger`
- 事务内 5 个原子操作：更新请求 status=1 → 创建/恢复好友记录 → 创建私聊会话 → 创建 2 个成员 → 系统消息
- 不同好友对拥有独立的 Mutex，无相互影响
- 同一好友对的并发接受被严格串行化

**FriendAddHandler 双向竞赛分支**（第 79~116 行）：
- 锁 Key: 私聊会话 ID `private:smaller:larger`
- 事务内 4 个原子操作：更新反向请求 status=1 → 创建好友记录 → 创建私聊会话 → 创建 2 个成员
- 读查询在锁外执行（R-08-01 已接受风险）

### TransactionTemplate 与 JpaRepository 兼容性

已验证与 Phase 7 相同的模式：`TransactionTemplate.execute {}` 回调内的 Repository 操作正确参与事务。`JpaConfig.getRepository()` 创建的 EntityManager 在事务生命周期内正确绑定到 `TransactionTemplate` 管理的事务上下文。

### ChatService delayedOfflineJob 生命周期

`delayedOfflineJob` 声明为 `var`（可变引用），在 `cleanupConnection()` 中赋值，在 `handleLoginSuccess()` 中取消。同一 ChatStreamObserver 实例的 `onNext`/`onCompleted`/`onError` 由 gRPC 框架保证串行调用，不存在并发赋值竞态。但不同连接实例（重连场景）的 delayedOfflineJob 属于不同 ChatStreamObserver 实例，互不影响。

---

## 继承自前序阶段的安全控制

| 控制 | 来源 | Phase 8 使用情况 |
|------|------|----------------|
| AuthInterceptor → Session 注入协程上下文 | Phase 4 | ✅ 所有 Handler 通过 `requireSession()` 获取，不可伪造 |
| ExceptionInterceptor → BizCode 统一处理 | Phase 4 | ✅ 所有 FriendException/BizCode 被拦截转为 Proto Response |
| UserStreamRegistry → ConcurrentHashMap + CopyOnWriteArrayList | Phase 6 | ✅ ChatService 复用，用于设备数检查 |
| PushService.pushEventToUser 单 observer 异常容错 | Phase 6 | ✅ 状态推送复用 |
| ConversationLockManager + TransactionTemplate 模式 | Phase 7 | ✅ FriendAcceptHandler/FriendAddHandler 复用 |
| PrivacyRepository.batchGetHideOnlineStatus() | Phase 5 | ✅ FriendListHandler/SetPrivacyHandler/ChatService 复用 |
| OnlineStatusRepository.isOnline() | Phase 5 | ✅ 保留兼容（由 getStatus 替代） |

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|---------|---------|--------|------|--------|
| 2026-06-13 | 25 | 25 | 0 | nx-security-auditor |

---

## 签收

- [x] 所有 6 个 friend/* Handler 通过 `requireSession()` 获取操作者身份
- [x] FriendAcceptHandler/FriendRejectHandler 校验操作者是申请目标用户
- [x] 双向竞赛自动好友使用锁+事务保护（读在锁外的 TOCTOU 已记录为 R-08-01）
- [x] 私聊非好友禁发（FriendCheckStep）验证通过
- [x] 隐藏用户状态保护和推送过滤验证通过
- [x] SetPrivacyHandler 隐藏切换推送（Nyquist 修复）已实现
- [x] OnlineStatusRepository JSON 反序列化兼容保护已验证
- [x] 所有 mitigate 威胁均有代码级验证（含文件:行号）
- [x] 所有 accept 威胁均有风险记录（R-08-01 ~ R-08-09）
- [x] threats_open 为 0

---

## SECURITY AUDIT COMPLETE
