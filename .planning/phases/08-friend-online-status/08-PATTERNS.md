---
phase: 8
type: patterns
---
# Phase 8: Friend & Online Status — 代码模式映射

## 模式概述

本项目采用 **Handler 驱动架构**，无独立 Service 层。业务逻辑直接在 Handler 中实现，复杂操作通过 TransactionTemplate + ConversationLockManager 协调。

## 1. Handler 模式

### 1.1 模式特征

- **接口**: `Handler<Req, Resp>`，`method` 为路由标识（如 `"friend/add"`）
- **依赖注入**: 构造函数注入，Koin DI 注册为 `single {}`
- **Session 获取**: `currentCoroutineContext().requireSession()`
- **IO 操作**: 使用 `withContext(Dispatchers.IO)` 调用 JPA Repository

### 1.2 Phase 8 需新增的 Handler

| Handler | method | 复杂度 | 参考模板 |
|---|---|---|---|
| `FriendAddHandler` | `friend/add` | 中 | `CreateGroupHandler`（推送+DB操作） |
| `FriendAcceptHandler` | `friend/accept` | 高 | `CreateGroupHandler`（锁+事务+推送） |
| `FriendRejectHandler` | `friend/reject` | 低 | `SetPrivacyHandler`（简单更新+推送） |
| `FriendDeleteHandler` | `friend/delete` | 低 | `KickMemberHandler`（软删除+推送） |
| `FriendListHandler` | `friend/list` | 低 | `ListConversationsHandler`（游标分页） |
| `FriendRequestsHandler` | `friend/requests` | 低 | `GetProfileHandler`（简单查询） |

### 1.3 Handler 模板（以 FriendAddHandler 为例）

```kotlin
class FriendAddHandler(
    private val userRepository: UserRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val friendshipRepository: FriendshipRepository,
    private val pushService: PushService
) : Handler<FriendAddReq, FriendAddResp> {
    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        
        // 校验不能加自己（D-54）
        if (session.userId == req.toUid) {
            throw FriendException(BizCode.SELF_FRIEND)
        }
        
        // 校验目标用户存在
        val targetUser = withContext(Dispatchers.IO) {
            userRepository.findById(req.toUid).orElse(null)
        } ?: throw FriendException(BizCode.USER_NOT_FOUND)
        
        // 校验不是已有好友
        val existing = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(session.userId, req.toUid)
        }
        if (existing != null) throw FriendException(BizCode.ALREADY_FRIEND)
        
        // 双向竞赛检测（D-52）：对方是否也发来了申请
        val reverse = withContext(Dispatchers.IO) {
            friendRequestRepository.findByFromUidAndToUidAndStatus(req.toUid, session.userId, FriendRequestStatus.PENDING)
        }
        
        if (reverse != null) {
            // 自动成为好友
            // ... 事务内完成双向好友关系
        }
        
        // 创建好友申请
        val entity = FriendRequestEntity(fromUid = session.userId, toUid = req.toUid, message = req.message)
        val saved = withContext(Dispatchers.IO) { friendRequestRepository.save(entity) }
        
        // 推送 FRIEND_REQUEST 给目标用户
        val payload = FriendRequestPayload.newBuilder()
            .setRequestId(saved.id!!)
            .setFromUid(session.userId)
            .setMessage(req.message)
            .build()
        pushService.pushEventToUser(req.toUid, PushEventType.FRIEND_REQUEST, payload.toByteString())
        
        return FriendAddResp.newBuilder().setRequestId(saved.id!!).build()
    }
}
```

## 2. Repository 模式

### 2.1 已有 JPA Repository（可直接使用）

| Repository | 文件路径 |
|---|---|
| `UserRepository` | `repository/.../repository/UserRepository.kt` |
| `FriendshipRepository` | `repository/.../repository/FriendshipRepository.kt` |
| `FriendRequestRepository` | `repository/.../repository/FriendRequestRepository.kt` |
| `ConversationRepository` | `repository/.../repository/ConversationRepository.kt` |
| `ConversationMemberRepository` | `repository/.../repository/ConversationMemberRepository.kt` |

### 2.2 需新增的查询方法

**FriendshipRepository 扩展**:
```kotlin
// 游标分页查询好友列表（D-46）
@Query("""SELECT f FROM FriendshipEntity f 
    WHERE f.userId = :userId AND f.deleted = 0
    AND (:cursor IS NULL OR f.id < :cursor) 
    ORDER BY f.id DESC""")
fun findFriendsByUserId(
    @Param("userId") userId: Long,
    @Param("cursor") cursor: Long?,
    pageable: Pageable
): List<FriendshipEntity>

// 软删除好友关系（D-44）
@Modifying
@Query("UPDATE FriendshipEntity f SET f.deleted = 1 WHERE f.userId = :userId AND f.friendId = :friendId")
fun softDeleteByUserIdAndFriendId(userId: Long, friendId: Long)
```

**FriendRequestRepository 扩展**:
```kotlin
// 按状态查询待处理申请（D-51 重复检查）
fun findByFromUidAndToUidAndStatus(fromUid: Long, toUid: Long, status: Int): FriendRequestEntity?

// 查询待处理申请列表
fun findByToUidAndStatusOrderByCreatedAtDesc(toUid: Long, status: Int): List<FriendRequestEntity>
```

### 2.3 Redis Repository（需扩展）

**OnlineStatusRepository** 扩展三值状态存储:
```kotlin
// 当前实现仅存储存在性，需扩展为存储状态 JSON
// Redis key: online:user:{userId}
// Redis value: {"status": 1, "lastActiveAt": 1234567890}
// TTL: 60s（伪在线窗口）
```

## 3. 事务 + 锁模式

### 3.1 已有模式（可直接复用）

```kotlin
// ConversationLockManager — 按会话 ID 分片锁
lockManager.withLock(convId) {
    transactionTemplate.execute {
        // 事务内的操作（JPA save/update）
    }
}
// 事务提交后异步推送
pushService.pushConversationEvent(...)
```

### 3.2 Phase 8 事务场景

| 场景 | 锁 Key | 事务范围 |
|---|---|---|
| friend/accept（D-43） | 私聊会话 ID | 好友记录 + 会话 + 成员 + 系统消息 |
| 双向竞赛自动好友（D-52） | 私聊会话 ID | 双向好友记录 + 会话 + 成员 |

## 4. 推送模式

### 4.1 已有推送方法

```kotlin
// PushService 核心方法
fun pushEventToUser(targetUid: Long, eventType: PushEventType, payloadBytes: ByteString)
suspend fun pushConversationEvent(convId: String, eventType: PushEventType, payloadBytes: ByteString, excludeUids: Set<Long> = emptySet())
```

### 4.2 Phase 8 推送场景

| 场景 | 推送方法 | EventType | Payload |
|---|---|---|---|
| 好友申请 | `pushEventToUser(toUid, ...)` | `FRIEND_REQUEST` | `FriendRequestPayload` |
| 好友接受 | `pushEventToUser(申请者, ...)` | `FRIEND_ACCEPTED` | `FriendAcceptedPayload` |
| 状态变更（D-50） | `pushEventToUser(好友, ...)` | `STATUS_CHANGED` | `StatusChangedPayload` |

## 5. Proto 模式

### 5.1 需新增的 Proto 定义

**PushEventType 扩展**:
```protobuf
STATUS_CHANGED = 14;  // payload = StatusChangedPayload
```

**新增 Payload**:
```protobuf
message StatusChangedPayload {
  int64 uid = 1;
}
```

### 5.2 好友相关消息（friend.proto 已定义）

- `FriendAddReq` / `FriendAddResp` — 发送申请
- `FriendAcceptReq` — 接受申请
- `FriendListResp` / `FriendBrief` — 好友列表
- `FriendRequestPayload` / `FriendAcceptedPayload` — 推送消息

## 6. DI 模式

### 6.1 注册方式

```kotlin
// gateway/.../di/GatewayModule.kt

// 在 handlerModule 中添加
single { FriendAddHandler(get(), get(), get(), get()) }
single { FriendAcceptHandler(get(), get(), get(), get(), get(), get()) }
single { FriendRejectHandler(get(), get(), get()) }
single { FriendDeleteHandler(get(), get(), get(), get()) }
single { FriendListHandler(get(), get()) }
single { FriendRequestsHandler(get(), get()) }

// OnlineStatusService 集成到 ChatService
// ChatService 构造参数添加 OnlineStatusRepository
```

### 6.2 registerHandlers 扩展

```kotlin
registerHandlers(
    registry = koin.get(),
    // ... 已有 Handler
    friendAddHandler = koin.get(),
    friendAcceptHandler = koin.get(),
    // ...
)
```

## 7. 测试模式

### 7.1 测试框架

- JUnit 5 + `kotlinx.coroutines.test.runTest`
- MockK (`mockk`, `coEvery`, `every`, `coVerify`)
- Session 注入: `withContext(SessionKey(session)) { handler.handle(req) }`

### 7.2 事务/锁 Mock 模式

```kotlin
// Mock 锁管理器：直接执行代码块
coEvery { lockManager.withLock(any(), any<suspend () -> kotlin.Any>()) } coAnswers {
    (args[1] as suspend () -> kotlin.Any).invoke()
}

// Mock 事务模板：在事务内执行回调
every { transactionTemplate.execute(any<TransactionCallback<Any?>>()) } answers {
    (it.invocation.args[0] as TransactionCallback<Any?>)
        .doInTransaction(mockk(relaxed = true))
}
```

## 8. Flyway 迁移

### 8.1 需新增的迁移

**V3__add_friend_request_message.sql**:
```sql
-- D-42: 好友申请验证消息持久化
ALTER TABLE friend_requests ADD COLUMN message VARCHAR(255) DEFAULT '' COMMENT '验证消息';
```

## 9. ChatService 集成点

### 9.1 需修改的位置

```kotlin
// ChatService.kt — 生命周期集成

// 登录成功时设置在线状态（D-47）
fun handleLoginSuccess(userId: Long) {
    onlineStatusRepository.setOnline(userId)
    // D-57: 登录触发状态推送
    pushStatusChangeToFriends(userId)
}

// 连接断开时启动伪在线计时器（D-48/D-49）
fun cleanupConnection(userId: Long) {
    if (userStreamRegistry.deviceCount(userId) == 0) {
        // 启动 60s 延迟任务
        schedulePseudoOfflineCheck(userId)
    }
}

// PING 心跳刷新 TTL（D-48）
fun handlePing(userId: Long) {
    onlineStatusRepository.refreshTtl(userId)
}
```

## 10. 文件清单

### 新增文件

| 文件 | 路径 | 模式参考 |
|---|---|---|
| `FriendAddHandler` | `gateway/.../handler/friend/FriendAddHandler.kt` | `CreateGroupHandler` |
| `FriendAcceptHandler` | `gateway/.../handler/friend/FriendAcceptHandler.kt` | `CreateGroupHandler` |
| `FriendRejectHandler` | `gateway/.../handler/friend/FriendRejectHandler.kt` | `SetPrivacyHandler` |
| `FriendDeleteHandler` | `gateway/.../handler/friend/FriendDeleteHandler.kt` | `KickMemberHandler` |
| `FriendListHandler` | `gateway/.../handler/friend/FriendListHandler.kt` | `ListConversationsHandler` |
| `FriendRequestsHandler` | `gateway/.../handler/friend/FriendRequestsHandler.kt` | `GetProfileHandler` |
| `FriendAddHandlerTest` | `gateway/.../handler/friend/FriendAddHandlerTest.kt` | `CreateGroupHandlerTest` |
| `FriendAcceptHandlerTest` | `gateway/.../handler/friend/FriendAcceptHandlerTest.kt` | `CreateGroupHandlerTest` |
| V3 Flyway 迁移 | `repository/.../db/migration/V3__add_friend_request_message.sql` | V2 迁移 |

### 修改文件

| 文件 | 修改内容 |
|---|---|
| `friend.proto` | 补充缺失的请求/响应消息 |
| `message_type.proto` | 新增 `STATUS_CHANGED = 14` |
| `GatewayModule.kt` | 注册 6 个新 Handler + OnlineStatusService |
| `NebulaServer.kt` | registerHandlers 添加新 Handler 参数 |
| `ChatService.kt` | 集成 OnlineStatusRepository 生命周期 |
| `OnlineStatusRepository.kt` | 扩展三值状态 JSON 存储 |
| `FriendshipRepository.kt` | 新增游标分页 + 软删除方法 |
| `FriendRequestRepository.kt` | 新增状态查询方法 |
