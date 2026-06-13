---
phase: 9
generated: 2026-06-13
based_on: 09-RESEARCH.md + 09-PATTERNS.md + 09-CONTEXT.md
---

# Phase 9: Reconnection — 执行计划

## 概述

实现客户端断线重连的服务端支持逻辑。纯服务端视角，客户端重连状态机由客户端自行管理。

**核心变更**:
- Proto: 新增 `PushEventType.DISCONNECT = 15`
- Repository: `SessionRepository` 新增 Redis pipeline 批量删除
- Service: `ChatService` eviction callback 推送 DISCONNECT + 缓存再投递 + 防御性检查

---

## Wave 分组

- **Wave 1**（无依赖，可并行）:
  - Plan 9-1: Proto 扩展 + Redis pipeline 连接清理

- **Wave 2**（依赖 Wave 1；两个 Plan 可并行，无文件重叠）:
  - Plan 9-2: DISCONNECT 推送 + 缓存再投递
  - Plan 9-3: 缓存再投递缓冲区

- **Wave 3**（依赖 Wave 2）:
  - Plan 9-4: 测试（单元测试 + 集成测试）

---

## Plan 9-1: Proto 扩展 + Redis pipeline 连接清理

### 目标

为 DISCONNECT 推送提供基础设施支持：新增 Proto 枚举值 + Redis pipeline 批量删除方法。

### 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `proto/src/main/proto/nebula/message_type.proto` | 新增 `DISCONNECT = 15` 枚举值 | `gradle build` 编译通过 | PushEventType 包含 DISCONNECT=15 |
| 2 | modify | `repository/src/main/kotlin/com/nebula/repository/redis/SessionRepository.kt` | 新增 `batchDelete(keys: List<String>)` pipeline 批量删除方法 | 单元测试验证批量删除 | 支持 pipeline 批量删除多个 key，异常时恢复 autoFlush |
| 3 | create | `gateway/src/test/kotlin/com/nebula/gateway/service/ReconnectCleanupTest.kt` | 新增 SessionRepository.batchDelete 的单元测试 | 测试通过 | 验证正常删除、空列表、异常恢复场景 |

### 关键实现细节

**SessionRepository.batchDelete()**:
```kotlin
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
```
- 使用 Lettuce `setAutoFlush(false)` + `flushCommands()` 实现 pipeline
- `RedisAsyncCommandsImpl(connection.reactive())` 通过连接共享的 reactive API 获取异步命令接口（Lettuce 6.x+ 支持）
- `finally` 块保证即使异常也能恢复 autoFlush
- 调用方使用 `withTimeout(redisTimeoutMs)` 包裹，与现有 500ms 超时保护一致

---

## Plan 9-2: DISCONNECT 推送

### 目标

在旧连接关闭前推送 DISCONNECT 事件，使客户端感知即将断连并触发重连流程。

### 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | 在 `ensureEvictionCallbackRegistered()` 的 eviction callback 中，关闭旧连接前推送 DISCONNECT | 单元测试验证 DISCONNECT 推送 | 旧连接在关闭前收到 DISCONNECT Envelope（PushEventType.DISCONNECT + content 描述） |
| 2 | create | `gateway/src/test/kotlin/com/nebula/gateway/service/DisconnectPushTest.kt` | 新增 DISCONNECT 推送的单元测试 | 测试通过 | 验证推送成功、推送失败（异常容错）、推送后连接关闭 |

### 关键实现细节

**eviction callback 修改**（ChatService.kt）:
```kotlin
sessionRegistry.onEviction { token ->
    val observer = tokenToObserver.remove(token)
    if (observer != null) {
        // Step 1: 推送 DISCONNECT 通知（D-68）
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

        // Step 2: 关闭连接（触发 cleanupConnection）
        observer.onCompleted()
    }
}
```

- **不使用 PushService.pushEventToUser()**: 因为需要通过 `tokenToObserver` 精确推送到**即将被关闭的旧连接**，而不是通过 `UserStreamRegistry` 推送到所有设备
- **异常容错**: 推送失败时 try-catch 保护，不阻止后续连接清理

---

## Plan 9-3: 缓存再投递缓冲区

### 目标

新连接建立后缓存消息，旧连接清理完成后投递到新连接，确保重连期间不丢消息（D-67）。

### 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | ChatStreamObserver 增加 `pendingBuffer`（ConcurrentLinkedQueue）和 `deliveryActive` 标记 | 编译通过 | 新连接在清理完成前投递的消息被缓存 |
| 2 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | ChatStreamObserver 新增 `deliver()` 和 `activateDelivery()` 方法 | 单元测试验证 | deliver() 在非激活状态缓存消息，激活状态直接 onNext；activateDelivery() 投递所有缓存消息后标记激活 |
| 3 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | `handleLoginSuccess()` 中在旧连接清理完成后调用 `activateDelivery()` | 单元测试验证 | 重连流程：注册Session → 等待旧连接清理 → 激活投递 |
| 4 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | `cleanupConnection()` 增加防御性检查（确认 observer 仍在注册表中再移除） | 单元测试验证 | 多设备重连场景不误删新连接的 StreamObserver |
| 5 | modify | `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` | ChatStreamObserver 设置缓冲区上限（1000 条），超限丢弃最旧消息；设置超时（10s），超时强制激活投递；在 `onError()`/`onCompleted()` 中清理 pendingBuffer | 单元测试验证 | 防止内存泄漏 |

### 关键实现细节

**ChatStreamObserver 扩展**:
```kotlin
private inner class ChatStreamObserver(
    private val responseObserver: StreamObserver<Envelope>
) : StreamObserver<Envelope> {
    /**
     * 缓存再投递缓冲区 — 使用 ConcurrentLinkedQueue（无界、无锁、高性能 FIFO，
     * 适合生产者-消费者缓存模式，与 PushService 的 CopyOnWriteArrayList 和
     * SessionRegistry 的 ConcurrentHashMap 不同，此处需要 FIFO 顺序保证）
     */
    private val pendingBuffer = ConcurrentLinkedQueue<Envelope>()
    
    /** 是否已进入正常投递模式 */
    @Volatile
    var deliveryActive = false

    /** 缓冲区上限 */
    companion object {
        private const val MAX_PENDING = 1000
        private const val DELIVERY_TIMEOUT_MS = 10_000L
    }

    fun deliver(envelope: Envelope) {
        if (deliveryActive) {
            responseObserver.onNext(envelope)
        } else {
            // 超限保护
            if (pendingBuffer.size >= MAX_PENDING) {
                pendingBuffer.poll()
            }
            pendingBuffer.add(envelope)
        }
    }

    suspend fun activateDelivery() {
        // 投递所有缓存消息（使用 withContext(Dispatchers.Default) 避免阻塞 gRPC 事件循环线程）
        // 使用 Default 而非 IO，因为 onNext() 是非阻塞的 gRPC 调用
        withContext(Dispatchers.Default) {
            while (true) {
                val envelope = pendingBuffer.poll() ?: break
                try {
                    responseObserver.onNext(envelope)
                } catch (e: Exception) {
                    // 与 PushService 容错模式一致（D-05）：单个消息异常不影响剩余缓存投递
                    logger.error(e) { "Failed to deliver cached envelope after reconnect" }
                }
            }
        }
        deliveryActive = true
    }

    /** 连接清理时清理缓冲区，防止内存泄漏 */
    fun cleanupPending() {
        pendingBuffer.clear()
    }
}

// 在 ChatStreamObserver.onCompleted() 和 onError() 中调用 cleanupPending()
override fun onCompleted() {
    cleanupPending()
    cleanupConnection()
    responseObserver.onCompleted()
}
```

**集成点说明（W4）**：`deliver()` 是缓存再投递的入口方法。所有通过 `UserStreamRegistry.getStreams()` 获取 StreamObserver 后调用 `onNext()` 的代码路径，需要切换为调用 `deliver()`。具体包括：
- `PushService.pushEventToUser()` — 通过 `getStreams(targetUid)` 获取 observer 后调用 `deliver()`
- `PushService.pushMessage()` — 同上，但推送给会话成员
- `PushService.pushConversationEvent()` — 同上
- 其他通过 `UserStreamRegistry` 获取 observer 并直接 `onNext()` 的代码

**实施建议**：在 PushService 的 `pushEventToUser()` 中，将 `observer.onNext(envelope)` 替换为 `(observer as? ChatStreamObserver)?.deliver(envelope) ?: observer.onNext(envelope)`。这样 PushService 无需感知 ChatStreamObserver 内部实现，`deliver()` 方法会根据 `deliveryActive` 状态决定缓存还是直接投递。

**说明**：当前 UserStreamRegistry 仅接受 ChatStreamObserver 实例（通过 handleLoginSuccess 中的 `require(responseObserver is ChatStreamObserver)` 保证），因此 `as?` 降级分支实际不会触发。保留 `?: observer.onNext(envelope)` 仅为防御性编程，防止未来引入其他 StreamObserver 实现时被遗漏。

**cleanupConnection() 防御性检查**:
```kotlin
private fun cleanupConnection() {
    // 使用 values.remove() 精确匹配当前 observer 实例（D-67 并发安全）
    // 避免 entries.removeIf 遍历所有条目时误匹配其他线程新注册的 observer
    tokenToObserver.values.remove(responseObserver)
    userId?.let { uid ->
        // 防御性检查：仅当当前 observer 仍在注册表中时才移除
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

**handleLoginSuccess() 集成**:
```kotlin
// 现有流程中，registerWithDeviceType 触发 eviction callback
val evictedToken = sessionRegistry.registerWithDeviceType(session)

// ... 现有 tokenToObserver 更新和 UserStreamRegistry 注册 ...

// 如果有旧连接被驱逐，激活缓存投递
if (evictedToken != null) {
    // eviction callback 在 registerWithDeviceType 中同步执行完成
    // 注意：如果旧连接清理超过 10s，缓冲区超时保护会强制激活投递。
    // 此时旧连接可能仍在，投递到旧连接的消息在 onCompleted 后丢失。
    // 这是"防饿死"权衡：宁可丢失少量消息也不阻塞新连接。
    // 丢失的消息可通过 Phase 10 的 gap detect + auto-pull 恢复。
    (responseObserver as? ChatStreamObserver)?.activateDelivery()
} else {
    // 无旧连接，直接激活投递
    (responseObserver as? ChatStreamObserver)?.let {
        it.deliveryActive = true
    }
}
```

---

## Plan 9-4: 测试（单元测试 + 集成测试）

### 目标

验证重连全流程的正确性：DISCONNECT 推送、连接清理、缓存再投递、多设备竞争。

### 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `gateway/src/test/kotlin/com/nebula/gateway/service/ChatServiceReconnectTest.kt` | 重连全流程集成测试 | 测试通过 | 模拟完整重连场景：登录→断连→重连→消息恢复 |
| 2 | create | 同上文件 | 多设备竞争测试 | 测试通过 | 两设备同类型同时重连，后注册的踢掉先注册的 |
| 3 | create | 同上文件 | 缓存再投递测试 | 测试通过 | 重连期间消息被缓存，清理后投递到新连接 |
| 4 | create | 同上文件 | DISCONNECT 推送测试 | 测试通过 | 旧连接收到 DISCONNECT 后关闭 |
| 5 | create | 同上文件 | 缓冲区上限保护测试 | 测试通过 | 超过 1000 条缓存时丢弃最旧消息 |
| 6 | create | 同上文件 | 缓冲区超时保护测试 | 测试通过 | 10s 后强制激活投递 |
| 7 | create | 同上文件 | 伪在线集成测试 | 测试通过 | 重连取消 60s 延迟离线任务，不标记离线 |

### 测试场景

| 测试 | 步骤 | 预期 |
|------|------|------|
| 正常重连 | 登录→断连→60s 内重连 | 旧连接收到 DISCONNECT，新连接正常接收消息，不标记离线 |
| 超时重连 | 登录→断连→60s 后重连 | 标记离线后再重连，按正常登录流程处理 |
| 多设备竞争 | 设备 A 断连→设备 A 重连中→设备 B 同类型重连 | 设备 A 被驱逐（预期行为），设备 B 成功注册 |
| 缓存上限 | 重连期间推送 2000 条消息 | 丢弃最旧 1000 条，保留最新 1000 条 |
| 推送异常 | 旧连接已损坏时推送 DISCONNECT | 异常被 try-catch 捕获，正常执行 onCompleted |

---

## 验证

### 构建验证
```bash
# 全量构建
./gradlew build

# Proto 编译检查
./gradlew :proto:generateProto

# Gateway 模块测试
./gradlew :gateway:test
```

### 代码质量检查
- 所有新增代码必须包含中文 KDoc 注释
- 异常处理遵循现有模式（try-catch + 日志 + 降级）
- 单元测试覆盖率 > 80%（按代码行数）

## 成功标准

1. Proto 编译通过，PushEventType 包含 DISCONNECT=15
2. SessionRepository 支持 pipeline 批量删除
3. 旧连接关闭前推送 DISCONNECT 事件
4. 重连期间消息被缓存，旧连接清理完成后投递到新连接
5. 缓冲区有上限保护（1000 条）和超时保护（10s）
6. 多设备重连场景不误删正常连接的 StreamObserver
7. 伪在线 60s 内重连成功不触发离线标记
8. 所有单元测试通过
