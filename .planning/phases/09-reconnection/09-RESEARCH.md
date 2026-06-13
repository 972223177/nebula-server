---
phase: 9
researcher: nx-researcher-1
---
# Phase 9 Reconnection 技术研究

## 研究范围

本阶段实现客户端断线重连的服务端侧支持逻辑，涵盖：
1. **连接清理机制** — Redis pipeline 原子化清理旧连接 + 安全关闭旧 gRPC Channel
2. **缓存再投递模式** — 新连接建立后缓存消息，旧连接清理完成后投递到新连接
3. **DISCONNECT 推送** — 利用 PushService.pushEventToUser() 在关闭旧 Channel 前推送 DISCONNECT 事件
4. **心跳恢复流程** — 重连后的 PING/PONG 交互，确认双向通信正常后恢复标准心跳
5. **多设备重连场景** — 同一用户多设备同时重连时的竞争条件处理
6. **伪在线集成** — 60s 伪在线与重连 BACKOFF 状态结合

## 技术栈上下文

- **语言**: Kotlin
- **传输**: gRPC 双向流（Netty 底层）
- **Redis**: Lettuce 协程客户端（`RedisCoroutinesCommands`）
- **Session 管理**: `SessionRegistry`（L1 ConcurrentHashMap + L2 Redis）、`UserStreamRegistry`（ConcurrentHashMap<Long, CopyOnWriteArrayList<StreamObserver>>）
- **推送**: `PushService.pushEventToUser()` + `UserStreamRegistry.getStreams()`
- **心跳**: 双重心跳（gRPC keepalive + 应用层 PING/PONG via Direction.PING/PONG）
- **在线状态**: `OnlineStatusRepository`（Redis JSON, 60s TTL, D-57）
- **离线消息**: `MessageQueueRepository`（Redis Stream）+ `message/pull` 接口

---

## 1. 连接清理机制

### 方案 1: Redis pipeline 原子化清理旧连接（D-65）

#### 清理项分析

旧连接需要清理的 Redis 数据包括：

| 清理项 | Redis Key | 操作 |
|--------|-----------|------|
| 旧 Session Token | `session:token:{oldToken}` | DEL |
| 旧设备类型映射 | `session:{userId}:{deviceType}` | DEL |
| 旧连接在 UserStreamRegistry 的引用 | 内存操作 | removeStream(userId, oldObserver) |
| 旧 token→observer 映射 | 内存操作 | tokenToObserver.remove(oldToken) |

#### Redis pipeline 实现

Lettuce 的 `RedisCoroutinesCommands` 本身是异步协程 API，每个 suspend 调用返回结果前已发送命令。要实现 pipeline（批量发送，减少 RTT），需要使用底层的 `StatefulRedisConnection` 的 `setAutoFlush(false)` + `flushCommands()` 模式：

```kotlin
// 注意：RedisCoroutinesCommands 不支持直接 pipeline，
// 需要降级到 RedisAsyncCommands 或 RedisReactiveCommands
// 推荐方案：使用 connection.setAutoFlush(false) + flushCommands()

suspend fun atomicCleanup(userId: Long, oldToken: String, deviceType: String) {
    // 1. 关闭 autoFlush，进入 pipeline 模式
    connection.setAutoFlush(false)

    try {
        // 2. 批量发送命令（不等待响应）
        val async = RedisAsyncCommandsImpl(connection.reactive())
        async.del("session:token:$oldToken")
        async.del("session:${userId}:${deviceType}")

        // 3. 一次性 flush 所有命令
        connection.flushCommands()
    } finally {
        // 4. 恢复 autoFlush
        connection.setAutoFlush(true)
    }
    // 等待所有命令完成（可选：使用 CountDownLatch 或 then() 链）
}
```

**与现有代码的兼容性**:
- 现有 `SessionRepository` 和 `OnlineStatusRepository` 均使用 `RedisCoroutinesCommands`（协程 API），不支持 pipeline
- 需要新建立一个使用 `RedisAsyncCommands` 的 pipeline 工具方法，或在 `SessionRepository` 中新增 `batchDelete(keys: List<String>)` 方法
- 当前项目使用共享 `StatefulRedisConnection<String, String>`（`RedisConfig.kt`），可直接复用

**推荐指数**: ⭐⭐⭐⭐⭐ — D-65 已锁定此方案

#### 方案 1A: 纯协程方式（替代方案）

如果不想引入 `RedisAsyncCommands`，也可以使用多个并发的协程调用同时执行 DEL：

```kotlin
suspend fun parallelCleanup(userId: Long, oldToken: String, deviceType: String) {
    coroutineScope {
        launch { sessionRepository.delete(oldToken) }
        launch { sessionRepository.deleteKey("session:${userId}:${deviceType}") }
    }
}
```

**优点**: 代码简单，复用现有 `SessionRepository` API
**缺点**: 不是真正的 pipeline，仍然是 2 个独立 RTT；无法保证最终一致性顺序
**推荐指数**: ⭐⭐⭐

#### 方案 1B: Lua 脚本（严格原子性）

```lua
-- cleanup_connection.lua
local token = KEYS[1]
local deviceKey = KEYS[2]
redis.call('DEL', 'session:token:' .. token)
redis.call('DEL', deviceKey)
return 1
```

**优点**: Redis 服务端原子执行，无需客户端协调
**缺点**: 需要管理 Lua 脚本加载，比 pipeline 重；D-65 已明确不需要严格原子性
**推荐指数**: ⭐⭐⭐

#### 旧 gRPC Channel 安全关闭

清理旧连接的核心是关闭 `StreamObserver`。现有代码中 `ChatService` 已有 `tokenToObserver` 映射：

```kotlin
// 现有代码位置: ChatService.kt tokenToObserver
private val tokenToObserver = ConcurrentHashMap<String, StreamObserver<Envelope>>()

// 现有 eviction callback 模式（ChatService.kt:301-321）:
sessionRegistry.onEviction { token ->
    val observer = tokenToObserver.remove(token)
    if (observer != null) {
        // 先推送 DISCONNECT（Phase 9 新增）
        val disconnectEnvelope = Envelope.newBuilder()
            .setDirection(Direction.PUSH)
            .setMessage(Message.newBuilder()
                .setEventType(PushEventType.DISCONNECT)  // 新增枚举值
                .setContent("服务端即将断开连接，触发重连流程")
                .build())
            .build()
        observer.onNext(disconnectEnvelope)
        // 然后关闭连接
        observer.onCompleted()
    }
}
```

**关闭顺序**:
1. 推送 DISCONNECT（D-68）
2. 调用 `observer.onCompleted()` 优雅关闭（先 `onNext(DISCONNECT)` 再 `onCompleted()`）
3. `ChatStreamObserver.onCompleted()` → `cleanupConnection()` 自动触发

**风险**: `observer.onNext()` 在连接已损坏时可能抛出异常。现有代码已有 try-catch 模式（PushService 容错模式），需要覆盖此场景。

**涉及的文件**:
- `gateway/.../service/ChatService.kt` — tokenToObserver + eviction callback
- `gateway/.../session/SessionRegistry.kt` — unregister() + evictionCallbacks
- `gateway/.../session/Session.kt` — Session 数据模型（含 connectionId）
- `gateway/.../session/UserStreamRegistry.kt` — removeStream()

---

## 2. 缓存再投递模式

### 方案: 新连接建立后缓存消息，待旧连接清理后投递（D-67）

#### 流程设计

```
新连接建立 (handleLoginSuccess 调用)
  │
  ├─ Step 1: 创建临时缓冲区（如 ConcurrentLinkedQueue<Envelope>）
  │          绑定到新 ChatStreamObserver
  │
  ├─ Step 2: 注册新 Session（registerWithDeviceType）
  │          触发 eviction callback → 清理旧连接
  │          eviction callback 内部:
  │            2a. 推送 DISCONNECT 给旧连接
  │            2b. 关闭旧连接 (onCompleted)
  │            2c. 清理 Redis (pipeline DEL)
  │
  ├─ Step 3: 等待旧连接清理完成
  │          通过回调/CompletableDeferred 等待
  │
  ├─ Step 4: 将缓冲区中缓存的消息投递到新连接
  │          逐一 observer.onNext(envelope)
  │
  └─ Step 5: 新连接开始正常接收实时推送
```

#### 实现方案

**方案 A: ChatStreamObserver 内部缓冲区 + CompletableDeferred**

```kotlin
private inner class ChatStreamObserver(
    private val responseObserver: StreamObserver<Envelope>
) : StreamObserver<Envelope> {

    /** 缓存再投递缓冲区 — 旧连接清理完成前缓存的消息 */
    private val pendingBuffer = ConcurrentLinkedQueue<Envelope>()

    /** 旧连接清理完成信号 */
    private val oldConnectionCleared = CompletableDeferred<Unit>()

    /** 是否已进入正常投递模式 */
    @Volatile
    var deliveryActive = false

    /**
     * 向此连接投递 Envelope。
     * 旧连接清理完成前 → 缓存到 pendingBuffer
     * 旧连接清理完成后 → 直接 onNext
     */
    fun deliver(envelope: Envelope) {
        if (deliveryActive) {
            responseObserver.onNext(envelope)
        } else {
            pendingBuffer.add(envelope)
        }
    }

    /**
     * 激活投递 — 将缓存的消息全部投递。
     * 在旧连接清理完成后由 handleLoginSuccess 调用。
     */
    suspend fun activateDelivery() {
        // 标记旧连接清理完成
        oldConnectionCleared.complete(Unit)

        // 投递所有缓存消息
        while (true) {
            val envelope = pendingBuffer.poll() ?: break
            try {
                responseObserver.onNext(envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to deliver cached envelope after reconnect" }
                break
            }
        }
        deliveryActive = true
    }
}
```

**优点**: 精确控制，不丢消息
**缺点**: 需要修改 ChatStreamObserver，增加复杂度

**推荐指数**: ⭐⭐⭐⭐⭐ — D-67 已锁定

**方案 B: PushService 延迟投递**

修改 `PushService.pushEventToUser()` 和 `UserStreamRegistry`，在 `register()` 时检查是否存在旧连接，如果存在则标记新 StreamObserver 为"pending"状态。

**优点**: 复用 PushService 现有架构
**缺点**: 侵入 PushService，职责不清
**推荐指数**: ⭐⭐

#### 与 handleLoginSuccess 的集成

现有 `handleLoginSuccess` 流程（ChatService.kt:216-267）需要扩展：

```kotlin
private suspend fun handleLoginSuccess(
    response: Response,
    responseObserver: StreamObserver<Envelope>
) {
    // ... 现有 Session 创建 ...

    // Step 1: 注册 Session（触发 eviction callback → 清理旧连接）
    val evictedToken = sessionRegistry.registerWithDeviceType(session)

    // Step 2: 更新 tokenToObserver 映射
    tokenToObserver[session.token] = responseObserver

    // Step 3: 注册到 UserStreamRegistry
    userStreamRegistry.register(loginResp.userId, responseObserver)

    // Step 4: 等待旧连接清理完成（如果有旧连接）
    if (evictedToken != null) {
        // eviction callback 是同步执行的（在 unregister 的协程中）
        // 但旧连接的 onCompleted() → cleanupConnection() 可能异步
        // 需要确保 cleanupConnection() 已完成
        // 方案：在 eviction callback 中设置 CompletableDeferred
    }

    // Step 5: 激活缓存投递
    (responseObserver as? ChatStreamObserver)?.activateDelivery()

    // Step 6: 标记在线 + 推送状态变更
    // ...
}
```

**关键问题**: eviction callback 的执行时序。当前 `SessionRegistry.unregister()` 是同步执行 eviction callbacks（`evictionCallbacks.forEach { it(token) }`），但 callback 内部调用 `observer.onCompleted()` 后，`ChatStreamObserver.onCompleted()` → `cleanupConnection()` 是在 gRPC 线程异步执行的。需要确保 `cleanupConnection()` 完成后再激活投递。

**解决**: 在 `ChatStreamObserver` 中添加 `cleanupComplete` 信号，`cleanupConnection()` 完成时设置。

**涉及的文件**:
- `gateway/.../service/ChatService.kt` — ChatStreamObserver 扩展 + handleLoginSuccess 修改
- `gateway/.../session/UserStreamRegistry.kt` — register() 不变

---

## 3. DISCONNECT 推送

### 方案: 利用 PushService.pushEventToUser() 推送 DISCONNECT（D-68）

#### Proto 扩展

需要在 `message_type.proto` 中新增 `DISCONNECT` 枚举值：

```protobuf
enum PushEventType {
  // ... 现有值 ...
  DISCONNECT = 15;  // 服务端推送断连通知，触发客户端重连流程（D-68）
}
```

`DISCONNECT` 推送不需要 payload，仅 `content` 字段携带原因即可。

**注意**: 现有 `PushEventType` 最大值为 14（STATUS_CHANGED），新增 `DISCONNECT = 15`。

#### 推送时机

在 eviction callback 中，关闭旧连接前推送：

```kotlin
// 修改位置: ChatService.ensureEvictionCallbackRegistered() 内部
sessionRegistry.onEviction { token ->
    val observer = tokenToObserver.remove(token)
    if (observer != null) {
        // Step 1: 推送 DISCONNECT 通知
        try {
            val disconnectEnvelope = Envelope.newBuilder()
                .setDirection(Direction.PUSH)
                .setMessage(Message.newBuilder()
                    .setEventType(PushEventType.DISCONNECT)
                    .setContent("连接将被关闭，请触发重连流程")
                    .build())
                .build()
            observer.onNext(disconnectEnvelope)
        } catch (e: Exception) {
            // 连接可能已损坏，推送失败不阻塞清理
            logger.warn(e) { "Failed to push DISCONNECT, connection may already be broken" }
        }

        // Step 2: 关闭连接
        observer.onCompleted()
    }
}
```

#### 替代方案：复用 PushService.pushEventToUser()

不直接通过 `tokenToObserver` 推送，而是通过 `PushService.pushEventToUser()` 推送。这样更统一，但需要确保推送到正确的旧连接。

**问题**: `PushService.pushEventToUser()` 通过 `UserStreamRegistry.getStreams(targetUid)` 查找所有在线设备，无法区分新旧连接。

**结论**: 不适合使用 `PushService.pushEventToUser()` 推送 DISCONNECT。因为：
1. 需要推送到**即将被关闭的旧连接**，而不是新连接
2. 新连接此时可能已经注册到 UserStreamRegistry，会导致错误地推送到新连接
3. 使用 `tokenToObserver` 精确推送更可靠

**推荐**: 直接在 eviction callback 中通过 `tokenToObserver` 推送，不走 PushService。

**涉及的文件**:
- `proto/src/main/proto/nebula/message_type.proto` — 新增 DISCONNECT = 15
- `gateway/.../service/ChatService.kt` — eviction callback 修改

---

## 4. 心跳恢复流程

### 方案: 等待首次 PONG 后恢复标准心跳（D-62）

#### 服务端视角

从服务端角度看，心跳恢复流程是**无状态的**：

```
客户端断连 → 服务端检测到 onError/onCompleted → cleanupConnection()
                                                      │
                                                      └─ 60s 伪在线（BACKOFF）

客户端重连 → 复用 login Token 验证 → handleLoginSuccess()
                                          │
                                          ├─ 注册新 Session
                                          ├─ 注册新 StreamObserver
                                          ├─ 取消旧的 60s 延迟离线任务
                                          └─ 正常接收 PING → 回复 PONG
```

服务端不需要特殊的心跳恢复逻辑。客户端重连后，发送 PING → 服务端回复 PONG（`handlePing()` 已有此逻辑），客户端确认双向通信正常后恢复标准心跳间隔。

#### 现有 PING/PONG 处理

```kotlin
// ChatService.kt:275-293 — 无需修改
private fun handlePing(
    envelope: Envelope,
    responseObserver: StreamObserver<Envelope>
) {
    // D-57: 刷新在线状态 TTL
    (responseObserver as? ChatStreamObserver)?.userId?.let { uid ->
        scope.launch {
            withContext(Dispatchers.IO) {
                onlineStatusRepository.refreshTtl(uid)
            }
        }
    }

    // 回复 PONG
    val pongEnvelope = Envelope.newBuilder()
        .setDirection(Direction.PONG)
        .setRequestId(envelope.requestId)
        .build()
    responseObserver.onNext(pongEnvelope)
}
```

**服务端无需修改**: 现有的 `handlePing()` 已能正确处理重连后的 PING 请求。客户端负责管理重连后的心跳恢复时机（D-62 是客户端决策）。

**涉及的文件**: 无（服务端无需修改）

---

## 5. 多设备重连场景

### 竞争条件分析

同一用户多设备同时重连时的竞争条件：

#### 场景 1: 设备 A 重连中，设备 B 也重连

```
时间线 →
设备A: 断连 → login(Token) → registerWithDeviceType → 旧连接清理 → 完成
设备B: 断连 → login(Token) → registerWithDeviceType → 旧连接清理 → 完成
```

**问题**: 设备 A 重连成功注册了 Session，设备 B 重连时 `registerWithDeviceType` 检测到同设备类型（如都是 "android"）的旧 token（设备 A 的 token），会驱逐设备 A 的连接。

**分析**: 这是**预期行为**。`registerWithDeviceType`（D-05 同类型互踢）保证同类型设备只有一个连接。如果两台设备同时重连且类型相同，后完成注册的会踢掉先完成的。这是正确的行为。

#### 场景 2: 同一设备快速重连（旧连接尚未清理完）

```
时间线 →
连接1: 断连 → cleanupConnection() 开始（60s 延迟离线任务启动）
连接2: 重连 → login(Token) → handleLoginSuccess()
               ├─ registerWithDeviceType → 发现同类型设备旧连接
               ├─ eviction callback → 关闭连接1
               └─ 注册成功
```

**问题**: 连接1 的 `cleanupConnection()` 可能在连接2 注册完成后才执行完，导致：
1. 连接2 注册后，连接1 的 `cleanupConnection()` 误将连接2 的 StreamObserver 从 UserStreamRegistry 移除
2. 60s 延迟离线任务取消不及时，错误标记离线

**解决方案**:

1. **`handleLoginSuccess()` 中取消旧延迟任务**（已在 Phase 8 实现）:
```kotlin
// ChatService.kt:248 — 已存在
responseObserver.delayedOfflineJob?.cancel()
```

2. **`cleanupConnection()` 增加防御性检查**:
```kotlin
private fun cleanupConnection() {
    tokenToObserver.entries.removeIf { it.value == responseObserver }
    userId?.let { uid ->
        // 仅当当前 observer 仍在注册表中时才移除
        val currentStreams = userStreamRegistry.getStreams(uid)
        if (currentStreams.any { it == responseObserver }) {
            userStreamRegistry.removeStream(uid, responseObserver)
        }

        // 检查是否还有其他设备在线
        if (userStreamRegistry.getStreams(uid).isEmpty()) {
            delayedOfflineJob = scope.launch {
                delay(60_000)
                if (userStreamRegistry.getStreams(uid).isEmpty()) {
                    onlineStatusRepository.setOffline(uid)
                    pushStatusChangeToFriends(uid, 0)
                }
            }
        }
    }
}
```

#### 场景 3: Redis pipeline 清理旧连接时的并发

**问题**: 两个重连请求几乎同时到达，同时调用 Redis pipeline 清理旧 Session token。

**分析**: Redis pipeline 的 DEL 操作是幂等的（删除不存在的 key 返回 0，不影响其他命令）。即使两个 pipeline 同时执行，也不会产生数据一致性问题。D-65 的"最终一致性"决策正是基于此。

**涉及的文件**:
- `gateway/.../service/ChatService.kt` — cleanupConnection() 防御性检查

---

## 6. 伪在线集成

### 方案: 伪在线 60s = BACKOFF 阶段（D-69）

#### 状态机对应关系

| 重连状态机阶段 | 服务端表现 | 伪在线状态 |
|---------------|-----------|-----------|
| INITIAL | 连接正常 | 在线（status=1） |
| BACKOFF | 连接断开，60s 窗口内 | 在线（status=1，TTL 自然衰减） |
| CONNECTING | 客户端尝试重连 | 在线（重连中，TTL 可能已过期但仍在尝试） |
| CONNECTED | 重连成功，重新注册 | 在线（status=1，TTL 刷新） |
| （超时） | 60s 后无重连 | 离线（DEL key） |

#### 当前实现（Phase 8 已完成）

```kotlin
// ChatService.ChatStreamObserver.cleanupConnection() — Phase 8 实现
userId?.let { uid ->
    delayedOfflineJob = scope.launch {
        delay(60_000)  // 60s 伪在线 = BACKOFF 窗口
        if (userStreamRegistry.getStreams(uid).isEmpty()) {
            onlineStatusRepository.setOffline(uid)  // 超时标记离线
            pushStatusChangeToFriends(uid, 0)
        }
    }
}
```

#### 集成点

1. **重连成功时取消延迟离线任务**（已在 Phase 8 实现）:
```kotlin
// handleLoginSuccess() — 已存在
responseObserver.delayedOfflineJob?.cancel()
```

2. **BACKOFF 期间消息处理** — 复用 Redis 离线消息队列（D-64）:
   - 断连期间消息照常写入 Redis Stream（`WriteStep` 已有逻辑）
   - 重连后客户端调用 `message/pull` 拉取（Phase 6 已有实现）
   - **零新开发量**

3. **重连后状态恢复** — `handleLoginSuccess()` 已包含:
   ```kotlin
   // 已有逻辑
   onlineStatusRepository.setOnline(loginResp.userId)
   pushStatusChangeToFriends(loginResp.userId, 1)
   ```

**结论**: Phase 8 已为伪在线集成打下基础，Phase 9 无需新增代码。唯一需要确保的是 `cleanupConnection()` 中的延迟任务在重连时被取消。

**涉及的文件**: 无（Phase 8 已实现）

---

## 7. 综合实现路径建议

### 推荐实现顺序

```
Wave 1: Proto 扩展 + 基础设施
  ├── Task 1: message_type.proto 新增 DISCONNECT = 15
  ├── Task 2: SessionRepository 新增 batchDelete() pipeline 方法
  └── Task 3: 新增 ReconnectManager 辅助类（可选）

Wave 2: ChatService 连接清理增强
  ├── Task 1: eviction callback 增加 DISCONNECT 推送
  ├── Task 2: ChatStreamObserver 增加缓存再投递缓冲区
  ├── Task 3: handleLoginSuccess 集成缓存再投递激活
  └── Task 4: cleanupConnection() 增加多设备防御性检查

Wave 3: DI 注册 + 测试
  ├── Task 1: GatewayModule 注册（如无新增 Handler 则跳过）
  ├── Task 2: 单元测试（连接清理、缓存再投递）
  └── Task 3: 集成测试（重连全流程）
```

### 代码修改清单

| 文件 | 操作 | 修改内容 |
|------|------|----------|
| `proto/.../message_type.proto` | Modify | 新增 `DISCONNECT = 15` |
| `repository/.../redis/SessionRepository.kt` | Modify | 新增 `batchDelete(keys: List<String>)` pipeline 方法 |
| `gateway/.../service/ChatService.kt` | Modify | eviction callback 推送 DISCONNECT + ChatStreamObserver 缓存再投递 + cleanupConnection 防御性检查 |
| `proto/.../message_type.proto` 生成的 Java 代码 | Regenerate | 自动包含新枚举值 |

### 无需修改的文件

| 文件 | 理由 |
|------|------|
| `PushService.kt` | DISCONNECT 推送不通过 PushService，直接走 tokenToObserver |
| `SessionRegistry.kt` | eviction callback 模式已支持，无需修改 |
| `UserStreamRegistry.kt` | register/getStreams/removeStream API 足够 |
| `OnlineStatusRepository.kt` | Phase 8 已完成三值状态 + 60s TTL |
| `LoginHandler.kt` | Token 重连验证逻辑已就绪 |
| `PingHandler.kt` | 服务端无状态 PONG 逻辑已就绪 |
| `MessageQueueRepository.kt` | 离线消息队列已就绪 |
| `NebulaServer.kt` | 无新 Handler 依赖，无需修改 |

### 关键接口设计

```kotlin
// 新增: SessionRepository.batchDelete() — Redis pipeline 批量删除
suspend fun batchDelete(keys: List<String>) {
    if (keys.isEmpty()) return
    connection.setAutoFlush(false)
    try {
        val async = RedisAsyncCommandsImpl(connection.reactive())
        keys.forEach { async.del(it) }
        connection.flushCommands()
    } finally {
        connection.setAutoFlush(true)
    }
}

// 新增: ChatStreamObserver 缓存投递接口
fun deliver(envelope: Envelope)  // 外部调用：PushService 等投递消息时使用
suspend fun activateDelivery()   // handleLoginSuccess 调用
```

---

## 8. 潜在风险和注意事项

### 风险 1: eviction callback 中 observer.onNext() 抛出异常

**问题**: 推送 DISCONNECT 时，旧连接可能已经损坏（网络断开、对端已关闭），`onNext()` 抛出异常。

**缓解**: 在 eviction callback 中使用 try-catch 包裹 DISCONNECT 推送。异常不阻止后续的 `onCompleted()` 和连接清理。现有 `PushService` 已有此容错模式（D-05）。

### 风险 2: 缓存再投递缓冲区内存泄漏

**问题**: 如果旧连接清理超时或异常，`pendingBuffer` 中的消息可能永远不会投递，导致内存泄漏。

**缓解**:
- 设置缓冲区上限（如 1000 条），超出上限直接丢弃最旧消息
- 在 `ChatStreamObserver` 的 `onError()/onCompleted()` 中清理缓冲区
- 设置超时机制（如 10s），超时后强制激活投递

### 风险 3: Redis pipeline 与协程 API 兼容性

**问题**: `RedisCoroutinesCommands` 不直接支持 pipeline 模式，需要降级到 `RedisAsyncCommands` 或 `RedisReactiveCommands`。

**缓解**:
- 在 `SessionRepository` 中新增独立的 `batchDelete()` 方法，内部使用 `RedisAsyncCommands`
- 或创建一个 `RedisPipeline` 工具类，封装 `setAutoFlush(false)/flushCommands()` 模式
- 使用 `connection.reactive()` 获取底层响应式 API（所有 Lettuce 连接共享同一个底层 Netty channel）

### 风险 4: 多设备竞争导致误踢

**问题**: 多设备同时重连时，`registerWithDeviceType()` 的互踢逻辑可能导致正常设备被误踢。

**缓解**: 这是 D-05 设计的设计行为，不是 bug。同类型设备只保留最新连接是业务需求。确保 `cleanupConnection()` 的防御性检查（确认 observer 仍在注册表中再移除）。

### 风险 5: 60s 伪在线窗口内消息丢失

**问题**: 伪在线 60s 期间，客户端显示在线但实际无法接收消息。服务端尝试推送消息到旧连接（已断开），推送失败。

**缓解**: 
- PushService 的 `pushEventToUser()` 已有 try-catch 容错（D-05），推送失败时自动调用 `userStreamRegistry.removeStream()`
- 断连期间的消息通过 Redis 离线消息队列存储（D-64），重连后 `message/pull` 拉取
- **零新开发量**：现有机制已覆盖

### 风险 6: DISCONNECT 推送在连接已断开时的行为

**问题**: 如果旧连接已经因网络问题断开，`observer.onNext(DISCONNECT)` 会失败。

**缓解**: 这是正常情况。客户端已经断连，无法收到 DISCONNECT 通知。客户端自行通过心跳超时检测断连并触发重连。DISCONNECT 通知是优化（减少客户端检测延迟），不是必需机制（D-68）。

### 风险 7: Proto 生成代码需要重新编译

**问题**: 新增 `DISCONNECT = 15` 后，所有引用 `PushEventType` 的代码需要重新编译。特别是 `when` 表达式中如果使用了 exhaustive 匹配，新增枚举值会导致编译错误。

**缓解**: 检查所有 `when (pushEventType) { ... }` 表达式，确保新增的 `DISCONNECT` 有分支处理，或使用 `else` 兜底。

---

## 参考资源

- [现有代码] `gateway/.../service/ChatService.kt` — 主要修改点（eviction callback、handleLoginSuccess、ChatStreamObserver）
- [现有代码] `gateway/.../session/SessionRegistry.kt` — eviction callback 注册模式
- [现有代码] `gateway/.../session/UserStreamRegistry.kt` — StreamObserver 注册/移除
- [现有代码] `gateway/.../push/PushService.kt` — pushEventToUser 推送模式（容错参考）
- [现有代码] `repository/.../redis/SessionRepository.kt` — 需要扩展 batchDelete
- [现有代码] `repository/.../redis/OnlineStatusRepository.kt` — 60s TTL 伪在线
- [现有代码] `repository/.../redis/MessageQueueRepository.kt` — Redis Stream 离线消息
- [现有代码] `proto/.../message_type.proto` — 需要新增 DISCONNECT = 15
- [设计决策] `.planning/phases/09-reconnection/09-CONTEXT.md` — D-61~D-69
- [讨论日志] `.planning/phases/09-reconnection/09-DISCUSSION-LOG.md` — 灰区讨论记录
- [Lettuce Pipeline 文档] https://github.com/redis/lettuce/wiki/Pipelining-and-command-flushing

## RESEARCH COMPLETE
