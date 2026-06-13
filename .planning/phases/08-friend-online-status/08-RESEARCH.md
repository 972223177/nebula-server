---
phase: 8
researcher: nx-researcher
---
# Phase 8 技术研究：Friend & Online Status

## 研究范围

- 好友模块：添加/接受/拒绝/删除/列表/请求列表 6 个 Handler
- 在线状态模块：三值状态存储、伪在线 TTL 刷新、状态变更推送、ChatService 生命周期集成
- Proto 扩展：FriendRequestPayload、FriendAcceptedPayload、STATUS_CHANGED PushEventType + StatusChangedPayload
- Flyway V3 迁移：FriendRequestEntity 新增 message 列
- message/send 路径增强：私聊非好友禁止发送

## 技术栈上下文

- 语言：Kotlin
- 框架：gRPC 双向流（Netty 底层）+ 自定义 Handler/Dispatcher（非 Ktor，非 Spring Boot Web）
- 数据库：MySQL (Spring Data JPA + Exposed DSL 混用) + Redis (Lettuce 协程客户端)
- DI：Koin
- 事务：Spring `TransactionTemplate`（编程式）+ `ConversationLockManager`（Mutex 分片锁）
- 推送：`PushService.pushEventToUser()` + `UserStreamRegistry`（ConcurrentHashMap + CopyOnWriteArrayList）

---

## 一、好友模块技术方案

### 方案 1: 好友关系存储模型（D-41, D-44, D-45）

#### 当前 Entity 分析

**FriendshipEntity** (`repository/src/main/kotlin/com/nebula/repository/entity/FriendshipEntity.kt`)：
- `userId` / `friendId`：单向记录（A→B 存一条，B→A 是否存另一条取决于设计）
- `deleted`：软删除标记（0=正常, 1=已删除）
- 已有唯一索引 `uk_friendship (user_id, friend_id)`

**FriendRequestEntity** (`repository/src/main/kotlin/com/nebula/repository/entity/FriendRequestEntity.kt`)：
- `fromUid` / `toUid`：申请人/接收人
- `status`：0=pending, 1=accepted, 2=rejected
- **缺失**：`message VARCHAR(255)` 列 → 需 Flyway V3 迁移新增

#### 存储策略：双向记录 vs 单向记录

| 方案 | 存储方式 | 查询好友列表 | 优点 | 缺点 |
|------|----------|-------------|------|------|
| A (推荐) | 单向记录（仅存一条，`userId < friendId` 排序） | `WHERE user_id=? OR friend_id=? AND deleted=0` | 存储量减半，无数据一致性问题 | 查询需 OR 条件 |
| B | 双向记录（A→B 和 B→A 各一条） | `WHERE user_id=? AND deleted=0` | 查询简单 | 接受时需插入两条，存储翻倍 |

**推荐方案 A（单向记录）**：接受好友申请时只创建一条 FriendshipEntity，查询时 `userId = :uid OR friendId = :uid AND deleted = 0`。

**理由**：
1. 好友关系是对称的，不需要区分方向
2. 避免两条记录不一致的风险（如一条 deleted=1 另一条 deleted=0）
3. 游标分页按 `friendship_id`（自增主键）排序，OR 条件不影响排序
4. 唯一约束 `(user_id, friend_id)` 配合插入时排序保证不重复

**实现要点**：
```kotlin
// FriendshipRepository 新增查询方法
@Query("""
    SELECT f FROM FriendshipEntity f 
    WHERE (f.userId = :uid OR f.friendId = :uid) AND f.deleted = 0
    AND (:cursor IS NULL OR f.id < :cursor)
    ORDER BY f.id DESC
""")
fun findFriendsByUserId(
    @Param("uid") uid: Long,
    @Param("cursor") cursor: Long?,
    pageable: Pageable
): List<FriendshipEntity>
```

#### 好友 ID 提取辅助方法
```kotlin
fun FriendshipEntity.getFriendUid(selfUid: Long): Long =
    if (userId == selfUid) friendId else userId
```

---

### 方案 2: friend/add — 单向申请 + 双向竞赛检测（D-41, D-51, D-52, D-53, D-54, D-55）

#### 完整流程

```
friend/add (fromUid=A, toUid=B)
  │
  ├─ 校验: A != B (D-54: SELF_FRIEND)
  │
  ├─ 检查已存在好友关系:
  │   friendshipRepo.findByUserIdAndFriendId(A, B)  // 单向查找
  │   ├─ 存在且 deleted=0 → ALREADY_FRIEND (D-51)
  │   └─ 不存在或 deleted=1 → 继续
  │
  ├─ 检查双向竞赛 (D-52):
  │   friendRequestRepo.findByFromUidAndToUid(B, A)
  │   ├─ 存在且 status=0 (pending) → 自动成为好友 (D-52 双向竞赛)
  │   │   ├─ 更新该请求 status=1
  │   │   ├─ 创建好友记录
  │   │   ├─ 创建私聊会话 (同 accept 流程)
  │   │   ├─ 推送 FRIEND_ACCEPTED 给 B
  │   │   └─ 返回 FriendAddResp
  │   └─ 不存在 → 继续
  │
  ├─ 检查重复申请 (D-51):
  │   friendRequestRepo.findByFromUidAndToUid(A, B)
  │   └─ 存在且 status=0 → BizException("已有待处理申请")
  │
  ├─ 创建 FriendRequestEntity (D-53: 拒绝后可重申请)
  │   fromUid=A, toUid=B, status=0, message=req.message
  │
  ├─ 推送 FRIEND_REQUEST 给 B (通过 pushEventToUser)
  │
  └─ 返回 FriendAddResp(requestId=...)
```

#### 双向竞赛事务处理

双向竞赛是最复杂的场景：A 向 B 申请，同时 B 也向 A 申请了（B 的申请已在 friend_requests 表中 status=0）。

**单事务原子操作**（使用 TransactionTemplate）：
```kotlin
transactionTemplate.execute {
    // 1. 更新 B→A 的 pending 请求为 accepted
    reverseRequest.status = 1  // accepted
    friendRequestRepository.save(reverseRequest)
    
    // 2. 创建单向好友记录 (userId < friendId)
    val (smaller, larger) = if (A < B) A to B else B to A
    val friendship = FriendshipEntity(userId = smaller, friendId = larger)
    friendshipRepository.save(friendship)
    
    // 3. 创建私聊会话 + 2 成员 + 系统消息 (同 accept 流程)
    // ...
}
```

---

### 方案 3: friend/accept — 单事务创建会话（D-43）

这是 Phase 8 最核心的事务操作。参考 `CreateGroupHandler` 的 `lockManager.withLock() + transactionTemplate.execute()` 模式。

#### 事务内容（4 个原子操作）

```
friend/accept (requestId)
  │
  ├─ 加载 FriendRequestEntity
  │   ├─ 不存在 → REQUEST_NOT_FOUND
  │   ├─ status != 0 → REQUEST_HANDLED
  │   └─ toUid != session.userId → FORBIDDEN (只能接受发给自己的)
  │
  ├─ 检查是否已是好友（防御性检查）
  │
  ├─ TransactionTemplate.execute {  // D-43 单事务
  │   │
  │   ├─ 1. 更新请求状态: request.status = 1, request.updatedAt = now
  │   │
  │   ├─ 2. 创建/恢复好友记录 (D-45: 检测 deleted=1 的记录)
  │   │      if (existing?.deleted == 1) existing.deleted = 0
  │   │      else 新建 FriendshipEntity(userId=smaller, friendId=larger)
  │   │
  │   ├─ 3. 创建私聊会话 (D-43)
  │   │      ConversationEntity(type = CONV_TYPE_PRIVATE = 1)
  │   │      convId = UUID.randomUUID().toString()
  │   │
  │   ├─ 4. 创建 2 个 ConversationMemberEntity
  │   │      member(A), member(B)
  │   │
  │   └─ 5. 写入系统消息（可选，后续 Phase 实现）
  │ }
  │
  ├─ 事务提交后异步推送:
  │   ├─ pushEventToUser(fromUid, FRIEND_ACCEPTED, FriendAcceptedPayload)  // 通知申请人
  │   └─ pushEventToUser(toUid, FRIEND_ACCEPTED, FriendAcceptedPayload)    // 通知接收人
  │
  └─ 返回 Response(OK)
```

#### 私聊会话 ID 生成规则

私聊会话 ID 格式：`private:{smallerUid}:{largerUid}`

这样设计的好处：
- D-45 重加恢复：可以直接通过 ID 找到原会话
- 两个用户之间的私聊会话唯一
- 不需要在 `conversations` 表上额外加唯一约束

```kotlin
private fun buildPrivateConvId(uid1: Long, uid2: Long): String {
    val (smaller, larger) = if (uid1 < uid2) uid1 to uid2 else uid2 to uid1
    return "private:$smaller:$larger"
}
```

#### 会话 type 常量

当前代码中 `CreateGroupHandler` 定义了 `CONV_TYPE_GROUP = 2`。需要新增：
- `CONV_TYPE_PRIVATE = 1`（私聊）
- 数据库表 `conversations.type` 注释已定义：`1=私聊, 2=群聊`

---

### 方案 4: friend/reject（D-41, D-53）

```
friend/reject (requestId)
  │
  ├─ 加载 FriendRequestEntity
  │   ├─ 不存在 → REQUEST_NOT_FOUND
  │   ├─ status != 0 → REQUEST_HANDLED
  │   └─ toUid != session.userId → FORBIDDEN
  │
  ├─ 更新: request.status = 2 (rejected), request.updatedAt = now
  │   friendRequestRepository.save(request)
  │
  └─ 返回 Response(OK)
```

不需要推送（申请人下次拉取请求列表时会看到 rejected 状态，或通过轮询感知）。如果产品需要即时通知，可后续添加 FRIEND_REJECTED 事件。

---

### 方案 5: friend/delete（D-44, D-56）

```
friend/delete (friendUid)
  │
  ├─ 查找好友记录:
  │   friendshipRepo.findByUserIdAndFriendId(smaller, larger)  // 排序后查找
  │   └─ 不存在或 deleted=1 → FRIEND_NOT_FOUND
  │
  ├─ 软删除: friendship.deleted = 1
  │   friendshipRepository.save(friendship)
  │
  └─ 返回 Response(OK)
```

**D-44 关键设计**：
- 只删除好友记录，**不删除私聊会话**，**不移除会话成员**
- message/send 路径增加好友关系检查（D-56）：私聊 + 非好友 → 拒绝发送

---

### 方案 6: message/send 路径增强（D-44, D-56）

在 `ValidateStep` 或新增独立 Step 中增加私聊好友关系校验：

```kotlin
// 在 ValidateStep.execute() 末尾追加：
// 私聊场景下检查好友关系（D-56）
val conv = conversationRepository.findById(req.conversationId).orElse(null)
if (conv != null && conv.type == CONV_TYPE_PRIVATE) {
    val (smaller, larger) = sortUids(context.senderUid, /* 对方UID */)
    val friendship = friendshipRepository.findByUserIdAndFriendId(smaller, larger)
    if (friendship == null || friendship.deleted == 1) {
        throw SendMessageException(BizCode.NOT_FRIEND, "非好友无法发送私聊消息")
    }
}
```

**注意**：需要获取对方 UID。私聊会话 ID 格式为 `private:smaller:larger`，可以从中解析。或者通过 `conversationMemberRepository.findByConversationId()` 找非发送者的成员。

推荐方案：从会话 ID 解析（避免额外 DB 查询）：
```kotlin
private fun getPeerUidFromPrivateConvId(convId: String, selfUid: Long): Long? {
    val parts = convId.removePrefix("private:").split(":")
    if (parts.size != 2) return null
    val uid1 = parts[0].toLongOrNull() ?: return null
    val uid2 = parts[1].toLongOrNull() ?: return null
    return if (uid1 == selfUid) uid2 else if (uid2 == selfUid) uid1 else null
}
```

---

### 方案 7: friend/list 游标分页（D-46）

按 `friendship_id`（自增主键）降序游标分页，复用现有 `ConversationRepository.findConversationsByUserId` 的游标模式：

```kotlin
override suspend fun handle(req: FriendListReq): FriendListResp {
    val session = currentCoroutineContext().requireSession()
    val pageable = PageRequest.of(0, 20)  // 固定每页 20
    
    val friendships = withContext(Dispatchers.IO) {
        friendshipRepository.findFriendsByUserId(session.userId, null, pageable)
    }
    
    // 批量获取好友信息
    val friendUids = friendships.map { it.getFriendUid(session.userId) }
    val userMap = withContext(Dispatchers.IO) {
        userRepository.findAllById(friendUids).associateBy { it.id!! }
    }
    
    // 批量获取在线状态
    val hiddenUsers = privacyRepository.batchGetHideOnlineStatus(friendUids)
    // 对非隐藏用户逐一查询在线状态
    
    val builder = FriendListResp.newBuilder()
    for (f in friendships) {
        val friendUid = f.getFriendUid(session.userId)
        val user = userMap[friendUid] ?: continue
        val status = if (friendUid in hiddenUsers) 2  // 隐藏
                     else if (onlineStatusRepository.isOnline(friendUid)) 1  // 在线
                     else 0  // 离线
        builder.addFriends(FriendBrief.newBuilder()
            .setUid(friendUid)
            .setUsername(user.username)
            .setDisplayName(user.nickname)
            .setAvatarUrl(user.avatar)
            .setStatus(status)
            .setCreatedAt(f.createdAt!!.toEpochMilli())
            .build())
    }
    return builder.build()
}
```

**关于游标**：当前 `FriendListReq` proto 定义为空消息（无 cursor/limit 字段）。需要扩展或一次性返回全部好友。根据 D-46 游标分页的决策，建议扩展 proto：

```protobuf
message FriendListReq {
    int64 cursor = 1;  // 游标：上一页最后一条的 friendship_id，首次传 0
    int32 limit = 2;   // 每页数量，默认 20
}
```

---

### 方案 8: friend/requests（D-41）

```kotlin
override suspend fun handle(req: FriendRequestsReq): FriendRequestsResp {
    val session = currentCoroutineContext().requireSession()
    
    val requests = withContext(Dispatchers.IO) {
        friendRequestRepository.findByToUidAndStatus(session.userId, 0)  // 只查 pending
    }
    
    val fromUids = requests.map { it.fromUid }
    val userMap = withContext(Dispatchers.IO) {
        userRepository.findAllById(fromUids).associateBy { it.id!! }
    }
    
    val builder = FriendRequestsResp.newBuilder()
    for (r in requests) {
        val fromUser = userMap[r.fromUid] ?: continue
        builder.addRequests(FriendRequestItem.newBuilder()
            .setRequestId(r.id!!)
            .setFromUid(r.fromUid)
            .setFromUsername(fromUser.username)
            .setFromAvatar(fromUser.avatar)
            .setMessage(r.message ?: "")  // D-42: 验证消息
            .setStatus("pending")
            .setCreatedAt(r.createdAt!!.toEpochMilli())
            .build())
    }
    return builder.build()
}
```

---

## 二、在线状态模块技术方案

### 方案 9: OnlineStatusRepository 三值状态存储（D-47, D-48, D-49）

#### 当前实现分析

`OnlineStatusRepository` (`repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt`)：
- Redis key: `online:user:{userId}`
- `setOnline(userId, statusData)` → `SETEX key 60 statusData`
- `setOffline(userId)` → `DEL key`
- `isOnline(userId)` → `GET key != null`

当前只有二值状态（在线/离线），需要扩展为三值状态。

#### 扩展方案

**Redis value 存储 JSON**：
```json
{"status": 1, "lastActiveAt": 1718123456789}
```
- status: 0=离线, 1=在线, 2=隐藏
- lastActiveAt: 最后活跃时间毫秒时间戳

**扩展 API**：
```kotlin
class OnlineStatusRepository(connection: StatefulRedisConnection<String, String>) {
    // 现有方法保留但修改 value 格式
    
    /** 标记用户在线（D-48: PING 刷新 TTL） */
    suspend fun setOnline(userId: Long) {
        val data = OnlineStatusData(status = 1, lastActiveAt = System.currentTimeMillis())
        redis.setex(key(userId), TTL_SECONDS, json.encodeToString(data))
    }
    
    /** 标记用户隐藏（在线但对他人不可见） */
    suspend fun setHidden(userId: Long) {
        val data = OnlineStatusData(status = 2, lastActiveAt = System.currentTimeMillis())
        redis.setex(key(userId), TTL_SECONDS, json.encodeToString(data))
    }
    
    /** 标记离线（删除 key） */
    suspend fun setOffline(userId: Long) {
        redis.del(key(userId))
    }
    
    /** 获取完整状态数据 */
    suspend fun getStatus(userId: Long): OnlineStatusData? {
        val raw = redis.get(key(userId)) ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            null
        }
    }
    
    /** 刷新 TTL（PING 心跳时调用，D-48） */
    suspend fun refreshTtl(userId: Long) {
        redis.expire(key(userId), TTL_SECONDS)
    }
    
    /** 批量获取状态（D-58: 客户端拉取） */
    suspend fun batchGetStatus(userIds: List<Long>): Map<Long, OnlineStatusData?> {
        // 使用 MGET 批量查询
    }
}

@Serializable
data class OnlineStatusData(
    val status: Int,        // 0=离线, 1=在线, 2=隐藏
    val lastActiveAt: Long  // 毫秒时间戳
)
```

#### 伪在线 60 秒 TTL 刷新机制（D-48, D-49）

```
PING 心跳到达 ChatService.handlePing()
  │
  ├─ 刷新 UserStreamRegistry 心跳时间（现有逻辑）
  │
  └─ 刷新 Redis TTL: onlineStatusRepository.refreshTtl(userId)  // D-48
       └─ EXPIRE online:user:{userId} 60
```

连接断开时（D-49）：
```
ChatService.ChatStreamObserver.cleanupConnection()
  │
  ├─ 从 tokenToObserver 移除
  ├─ userStreamRegistry.removeStream(userId, observer)
  │
  └─ 检查 UserStreamRegistry 中该 userId 是否还有其他设备
       ├─ 有 → 不触发离线（D-47: 任意设备在线=在线）
       └─ 无 → 不立即删除 Redis key（D-49: 伪在线 60s）
            └─ Redis key 自然过期 → 60s 后自动离线
```

**关键实现**：ChatService 的 `cleanupConnection()` 需要修改：
```kotlin
private fun cleanupConnection() {
    tokenToObserver.entries.removeIf { it.value == responseObserver }
    userId?.let { uid ->
        userStreamRegistry.removeStream(uid, responseObserver)
        
        // D-47: 检查是否还有其他设备在线
        val remainingStreams = userStreamRegistry.getStreams(uid)
        if (remainingStreams.isEmpty()) {
            // D-49: 不主动删除 Redis key，让 TTL 自然过期（伪在线 60s）
            // 60s 后 Redis key 自动过期 → 标记离线
            // 如果需要立即通知好友，可以在 scope.launch 中延迟 60s 后检查
            scope.launch {
                delay(60_000)
                // 60s 后再次检查是否仍然没有设备在线
                if (userStreamRegistry.getStreams(uid).isEmpty()) {
                    onlineStatusRepository.setOffline(uid)
                    notifyStatusChange(uid, offline = true)
                }
            }
        }
    }
}
```

---

### 方案 10: 状态变更推送（D-50, D-57, D-58, D-59, D-60）

#### 推送时机（D-57）

| 事件 | 触发 | 推送条件 |
|------|------|----------|
| 用户登录 | `handleLoginSuccess()` | 状态变为在线，广播给所有在线好友 |
| 用户下线 | `cleanupConnection()` 中无剩余设备 | 60s 后无重连 → 广播离线 |
| 断线超时 | 60s TTL 过期 | 延迟任务检查后广播 |
| 隐藏切换 | `SetPrivacyHandler` 调用 `setHidden()` | 广播给所有在线好友（D-59: 隐藏时推送 status=2） |
| 取消隐藏 | `SetPrivacyHandler` 调用 `setOnline()` | 广播给所有在线好友 |

#### 推送流程（D-50, D-58）

```
状态变更触发
  │
  ├─ 1. 更新 Redis: setOnline / setHidden / setOffline
  │
  ├─ 2. 查询该用户的所有好友 (D-50)
  │      friendshipRepo.findFriendsByUserId(uid)
  │
  ├─ 3. 过滤：排除隐藏用户 (D-59: 隐藏用户不推送)
  │      privacyRepo.batchGetHideOnlineStatus(friendUids)
  │
  └─ 4. 逐个推送 STATUS_CHANGED (D-58: 仅带 uid)
         friendUids.forEach { friendUid ->
             pushService.pushEventToUser(friendUid, STATUS_CHANGED, StatusChangedPayload { uid })
         }
```

#### StatusChangedPayload 设计

```protobuf
// 在 friend.proto 中定义
message StatusChangedPayload {
    int64 uid = 1;  // 状态变更的用户 UID
}
```

客户端收到 `STATUS_CHANGED` 推送后，调用 `user/batchGetStatus` 拉取实际状态（D-58）。

#### D-60：客户端上线拉取补偿

客户端登录后主动调用 `user/batchGetStatus` 拉取所有好友状态。服务端无需额外处理，客户端负责。

---

### 方案 11: ChatService 生命周期集成

#### 集成点

| 生命周期事件 | 位置 | 操作 |
|-------------|------|------|
| 用户登录成功 | `handleLoginSuccess()` | `onlineStatusRepository.setOnline(userId)` + 广播 STATUS_CHANGED |
| PING 心跳 | `handlePing()` | `onlineStatusRepository.refreshTtl(userId)` |
| 连接关闭（无剩余设备） | `cleanupConnection()` | 延迟 60s 检查 → `setOffline(userId)` + 广播 |
| 连接关闭（有剩余设备） | `cleanupConnection()` | 不操作（D-47） |

#### ChatService 依赖变更

ChatService 需要新增 `OnlineStatusRepository` 和 `FriendshipRepository` 依赖：

```kotlin
class ChatService(
    private val dispatcher: Dispatcher,
    private val sessionRegistry: SessionRegistry,
    private val registry: HandlerRegistry,
    private val userStreamRegistry: UserStreamRegistry,
    // Phase 8 新增
    private val onlineStatusRepository: OnlineStatusRepository,
    private val friendshipRepository: FriendshipRepository,
    private val pushService: PushService,
    private val privacyRepository: PrivacyRepository
) : BindableService {
```

**注意**：`ChatService` 当前在 `NebulaServer.kt` 中手动构造（不走 Koin），需要同步修改。

---

## 三、Proto 扩展方案

### 需要新增/修改的 Proto 消息

#### 1. friend.proto 扩展

在 `friend.proto` 末尾追加推送事件 Payload：

```protobuf
// ---- 推送事件 Payload ----

// 好友申请推送（FRIEND_REQUEST）
message FriendRequestPayload {
    int64 request_id = 1;    // 申请 ID
    int64 from_uid = 2;      // 申请人 UID
    string from_username = 3; // 申请人用户名
    string from_avatar = 4;   // 申请人头像 URL
    string message = 5;       // 申请附言
    int64 created_at = 6;     // 申请时间（毫秒时间戳）
}

// 好友接受推送（FRIEND_ACCEPTED）
message FriendAcceptedPayload {
    int64 from_uid = 1;       // 被接受的申请人 UID
    int64 to_uid = 2;         // 接受者 UID
    string conversation_id = 3; // 自动创建的私聊会话 ID
}

// 状态变更推送（STATUS_CHANGED，D-58）
message StatusChangedPayload {
    int64 uid = 1;            // 状态变更的用户 UID
}
```

#### 2. message_type.proto 扩展

新增 `STATUS_CHANGED` 枚举值：

```protobuf
enum PushEventType {
    // ... 现有值 ...
    STATUS_CHANGED = 14;       // payload = StatusChangedPayload
}
```

#### 3. friend.proto FriendListReq 扩展（D-46 游标分页）

```protobuf
message FriendListReq {
    int64 cursor = 1;  // 游标：上一页最后一条的 friendship_id，首次传 0
    int32 limit = 2;   // 每页数量，默认 20，最大 100
}
```

#### 4. FriendRequestEntity 扩展（D-42）

Flyway V3 迁移 SQL：

```sql
-- V3__friend_request_message.sql
ALTER TABLE friend_requests
    ADD COLUMN message VARCHAR(255) NOT NULL DEFAULT '' COMMENT '好友申请附言，D-42';
```

同步更新 `FriendRequestEntity`：
```kotlin
class FriendRequestEntity(
    // ... 现有字段 ...
    var message: String = ""  // D-42 新增
)
```

---

## 四、数据流图

### 4.1 好友添加流程

```
Client A                     Server                      Client B
   │                           │                            │
   │── friend/add (toUid=B) ──►│                            │
   │                           │── 校验 (A!=B, 非已有好友)    │
   │                           │── 检测双向竞赛               │
   │                           │   ├─ 无竞赛: 创建申请        │
   │                           │   │    ├─ INSERT friend_requests │
   │                           │   │    └─ PUSH FRIEND_REQUEST ──►│
   │◄── FriendAddResp ────────│                            │
   │                           │                            │
   │                           │   └─ 有竞赛: 自动成为好友     │
   │                           │        ├─ UPDATE friend_requests │
   │                           │        ├─ INSERT friendships     │
   │                           │        ├─ INSERT conversation    │
   │                           │        ├─ INSERT 2 members       │
   │                           │        ├─ PUSH FRIEND_ACCEPTED ─►│
   │                           │        └─ PUSH FRIEND_ACCEPTED ─►│ (A)
```

### 4.2 好友接受流程（单事务）

```
Client B                     Server                         Client A
   │                           │                               │
   │── friend/accept ─────────►│                               │
   │                           │── 加载 request                 │
   │                           │── 校验 (status=pending, toUid=B) │
   │                           │                               │
   │                           │── TransactionTemplate {        │
   │                           │     1. request.status = 1     │
   │                           │     2. 创建/恢复 friendship    │
   │                           │     3. 创建 conversation       │
   │                           │     4. 创建 2 members          │
   │                           │   }                            │
   │                           │                               │
   │                           │── PUSH FRIEND_ACCEPTED ──────►│
   │◄── Response(OK) ─────────│── PUSH FRIEND_ACCEPTED ──────►│ (B 自己也收到)
```

### 4.3 在线状态变更推送

```
用户 A 登录/下线/切换隐藏
   │
   ├─ 1. Redis: SET online:user:A {"status":1, ...}
   │
   ├─ 2. 查询 A 的所有好友: [B, C, D]
   │
   ├─ 3. 过滤隐藏用户: batchGetHideOnlineStatus([B, C, D]) → {D}
   │     D 设置了隐藏 → 不推送给 D
   │
   ├─ 4. 推送给 B, C:
   │     pushEventToUser(B, STATUS_CHANGED, StatusChangedPayload{uid=A})
   │     pushEventToUser(C, STATUS_CHANGED, StatusChangedPayload{uid=A})
   │
   └─ B, C 客户端收到推送 → 调用 batchGetStatus([A]) 拉取实际状态
```

---

## 五、依赖分析

### 5.1 新增文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `gateway/.../handler/friend/AddFriendHandler.kt` | Handler | friend/add |
| `gateway/.../handler/friend/AcceptFriendHandler.kt` | Handler | friend/accept |
| `gateway/.../handler/friend/RejectFriendHandler.kt` | Handler | friend/reject |
| `gateway/.../handler/friend/DeleteFriendHandler.kt` | Handler | friend/delete |
| `gateway/.../handler/friend/ListFriendsHandler.kt` | Handler | friend/list |
| `gateway/.../handler/friend/ListFriendRequestsHandler.kt` | Handler | friend/requests |
| `gateway/.../handler/chat/send/FriendCheckStep.kt` | Step | message/send 好友关系检查（或集成到 ValidateStep） |
| `repository/.../db/migration/V3__friend_request_message.sql` | Flyway | message 列迁移 |

### 5.2 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `FriendRequestEntity.kt` | 新增 `message` 字段 |
| `FriendshipRepository.kt` | 新增 `findFriendsByUserId()` 方法 |
| `FriendRequestRepository.kt` | 可能新增查询方法 |
| `OnlineStatusRepository.kt` | 扩展为三值状态 + JSON value |
| `ChatService.kt` | 新增 OnlineStatusRepository/PushService 依赖，生命周期集成 |
| `NebulaServer.kt` | ChatService 构造参数变更，注册 6 个 Friend Handler |
| `GatewayModule.kt` | 注册 6 个 Friend Handler + FriendCheckStep |
| `friend.proto` | 新增 FriendRequestPayload/FriendAcceptedPayload/StatusChangedPayload；扩展 FriendListReq |
| `message_type.proto` | 新增 STATUS_CHANGED = 14 |
| `ValidateStep.kt` / `SendMessageHandler.kt` | 增加私聊好友关系检查 |

### 5.3 Koin DI 注册

```kotlin
// handlerModule 新增
single { AddFriendHandler(get(), get(), get()) }          // FriendRequestRepo + FriendshipRepo + PushService
single { AcceptFriendHandler(get(), get(), get(), get(), get(), get()) } // FriendRequestRepo + FriendshipRepo + ConvRepo + ConvMemberRepo + TxTemplate + PushService
single { RejectFriendHandler(get()) }                     // FriendRequestRepo
single { DeleteFriendHandler(get()) }                     // FriendshipRepo
single { ListFriendsHandler(get(), get(), get(), get()) } // FriendshipRepo + UserRepo + OnlineStatusRepo + PrivacyRepo
single { ListFriendRequestsHandler(get(), get()) }        // FriendRequestRepo + UserRepo
```

### 5.4 依赖关系图

```
Phase 8 Handler 依赖:
  ├── repository 层 (已存在)
  │   ├── FriendshipRepository (已存在，需扩展查询方法)
  │   ├── FriendRequestRepository (已存在)
  │   ├── ConversationRepository (已存在)
  │   ├── ConversationMemberRepository (已存在)
  │   ├── UserRepository (已存在)
  │   ├── OnlineStatusRepository (已存在，需扩展)
  │   └── PrivacyRepository (已存在)
  │
  ├── gateway 层 (已存在)
  │   ├── PushService (已存在)
  │   ├── UserStreamRegistry (已存在)
  │   ├── ConversationLockManager (已存在)
  │   └── TransactionTemplate (已存在)
  │
  └── proto 层 (需扩展)
      ├── friend.proto → 新增 Payload 消息
      └── message_type.proto → 新增 STATUS_CHANGED
```

---

## 六、风险点和注意事项

### 风险 1：ChatService 依赖膨胀

**问题**：ChatService 当前已有 4 个依赖（Dispatcher, SessionRegistry, HandlerRegistry, UserStreamRegistry），Phase 8 新增 4 个（OnlineStatusRepository, FriendshipRepository, PushService, PrivacyRepository），构造函数参数达到 8 个。

**缓解**：
- 方案 A：引入一个 `ConnectionLifecycleManager` 封装 OnlineStatusRepository + PushService + PrivacyRepository + FriendshipRepository，ChatService 只依赖此管理器
- 方案 B：保持现状，ChatService 的构造函数在 `NebulaServer.kt` 中手动管理，Kotlin 命名参数可读性尚可

**推荐方案 B**（简单优先），但需要在 ChatService 构造函数中使用命名参数增强可读性。

### 风险 2：双向竞赛的事务边界

**问题**：`friend/add` 的双向竞赛检测和自动成为好友在单个 TransactionTemplate 内完成，但事务中涉及 INSERT friendships + INSERT conversation + INSERT members，如果私聊会话 ID 冲突（`private:smaller:larger`），事务会回滚。

**缓解**：
- 事务前先检查 `conversationRepository.findById(privateConvId)` 是否存在
- 使用 `ConversationLockManager.withLock(privateConvId)` 保护并发

### 风险 3：伪在线 60s 延迟任务泄漏

**问题**：`cleanupConnection()` 中 `scope.launch { delay(60_000) }` 创建的延迟任务在用户 60s 内重连后仍然会执行，导致错误地将用户标记为离线。

**缓解**：
- 在 `ChatStreamObserver` 中维护一个 `var delayedOfflineJob: Job?` 
- 延迟任务执行前检查 `UserStreamRegistry.getStreams(uid).isNotEmpty()`
- 重连时取消之前的延迟任务：
```kotlin
// handleLoginSuccess 中
delayedOfflineJob?.cancel()
delayedOfflineJob = null
```

### 风险 4：好友列表查询性能

**问题**：`friend/list` 需要批量查询好友信息 + 在线状态 + 隐私设置，涉及多次 DB/Redis 调用。

**缓解**：
- 使用 JPA `findAllById(friendUids)` 一次查询所有用户信息
- 使用 `PrivacyRepository.batchGetHideOnlineStatus()` 一次 MGET 所有隐私设置
- 在线状态逐个查询（`isOnline` 是单 key 查询，无法批量，但 Redis GET 极快）

### 风险 5：message/send 好友检查的性能开销

**问题**：每次发送私聊消息都需要额外查询好友关系。

**缓解**：
- 从私聊会话 ID 解析对方 UID（`private:smaller:larger`），避免查询数据库获取对方 ID
- 好友关系查询使用主键/唯一索引查找（`userId + friendId` 有唯一索引），极快
- 仅在 `conv.type == CONV_TYPE_PRIVATE` 时执行检查

### 风险 6：OnlineStatusRepository JSON 序列化兼容

**问题**：现有 `setOnline(userId, statusData)` 的 `statusData` 参数是 String，已有调用方可能传入非 JSON 格式。

**缓解**：
- 搜索所有 `onlineStatusRepository.setOnline()` 调用点，确保统一使用 JSON 格式
- 当前代码中 `OnlineStatusRepository` 只在 `BatchGetStatusHandler` 中被使用（`isOnline()`），没有直接调用 `setOnline()` 的地方
- Phase 8 是第一个主动调用 `setOnline()` 的 Phase

### 风险 7：Proto 生成代码的包路径一致性

**问题**：新增的 `FriendRequestPayload`、`FriendAcceptedPayload`、`StatusChangedPayload` 需要与 `PushEventType` 中的引用路径一致。

**分析**：
- `message_type.proto` 中 `FRIEND_REQUEST = 2` 注释 `payload = FriendRequestPayload`，但 `FriendRequestPayload` 定义在 `friend.proto` (package `com.nebula.chat.friend`)
- `PushEventType` 在 `com.nebula.chat` package 中
- 当前 `GroupCreatedPayload` 等定义在 `conversation.proto` (package `com.nebula.chat.conversation`)，在 Kotlin 代码中通过 `import com.nebula.chat.conversation.GroupCreatedPayload` 引用

**结论**：`FriendRequestPayload` 等定义在 `friend.proto` 的 `com.nebula.chat.friend` package 中，Kotlin 代码中通过 `import com.nebula.chat.friend.FriendRequestPayload` 引用，与现有模式一致。PushEventType 枚举值和 Payload 的对应关系通过代码中的 `pushEventToUser` 调用时手动指定，proto 层面不强制约束。

---

## 七、实现路径建议

### 推荐实现顺序

1. **Proto 扩展** → 定义 `FriendRequestPayload`、`FriendAcceptedPayload`、`StatusChangedPayload`、`STATUS_CHANGED`
2. **Flyway V3** → `friend_requests.message` 列迁移
3. **FriendRequestEntity 更新** → 新增 `message` 字段
4. **Repository 扩展** → `FriendshipRepository.findFriendsByUserId()`、`OnlineStatusRepository` 三值状态
5. **好友 Handler** → AddFriendHandler（含双向竞赛）→ AcceptFriendHandler（含单事务）→ RejectFriendHandler → DeleteFriendHandler → ListFriendsHandler → ListFriendRequestsHandler
6. **在线状态集成** → ChatService 生命周期修改 → 状态推送逻辑
7. **message/send 增强** → ValidateStep 中增加私聊好友关系检查
8. **DI 注册** → GatewayModule + NebulaServer

### 代码组织

```
gateway/src/main/kotlin/com/nebula/gateway/handler/friend/
├── AddFriendHandler.kt
├── AcceptFriendHandler.kt
├── RejectFriendHandler.kt
├── DeleteFriendHandler.kt
├── ListFriendsHandler.kt
└── ListFriendRequestsHandler.kt
```

## 参考资源

- [现有代码] `gateway/.../handler/conversation/CreateGroupHandler.kt` — 事务+锁+推送模式参考
- [现有代码] `gateway/.../handler/conversation/InviteMemberHandler.kt` — TransactionTemplate 使用模式
- [现有代码] `gateway/.../handler/chat/send/SendMessageHandler.kt` — Step 链模式
- [现有代码] `gateway/.../handler/chat/send/ValidateStep.kt` — 验证 Step 模式
- [现有代码] `gateway/.../push/PushService.kt` — pushEventToUser 推送方法
- [现有代码] `gateway/.../session/UserStreamRegistry.kt` — 设备数跟踪
- [现有代码] `gateway/.../service/ChatService.kt` — 生命周期集成点
- [现有代码] `repository/.../redis/PrivacyRepository.kt` — batch MGET 模式
- [现有代码] `repository/.../redis/OnlineStatusRepository.kt` — Redis 状态存储基类
- [现有代码] `proto/src/main/proto/nebula/friend/friend.proto` — 好友 Proto 定义
- [现有代码] `proto/src/main/proto/nebula/message_type.proto` — PushEventType 枚举
- [设计决策] `.planning/phases/08-friend-online-status/08-CONTEXT.md` — D-41 到 D-60

## RESEARCH COMPLETE
