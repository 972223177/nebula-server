---
phase: 9
mapper: nx-pattern-mapper
---

# Phase 9: Reconnection — 代码模式映射

## 已识别模式

### 1. Handler 模式（业务 Handler）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/Handler.kt`
- **模式类型**: Interface 定义
- **模式结构**:
  ```kotlin
  interface Handler<Req : Any, Resp : Any> {
      val method: String
      suspend fun handle(req: Req): Resp
  }
  ```
- **实现模板**: `LoginHandler.kt`, `FriendAddHandler.kt`, `BatchGetStatusHandler.kt`
- **关键约定**:
  - 通过 `currentCoroutineContext().requireSession()` 获取 Session（由 AuthInterceptor 注入）
  - 通过构造注入依赖（Repository、Service 等）
  - 通过 Koin module 注册为 `single`，并在对应的 `HandlerCollector` 中注册
  - 异常通过抛出 `BizException` 子类由 `ExceptionInterceptor` 统一处理
- **适用场景**: 所有业务请求处理（Request → Response 模式）
- **映射到**: 本阶段不需要新增 Handler（重连是 ChatService 内部逻辑 + 客户端状态机）

### 2. PushService 模式（事件推送）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt`
- **模式类型**: Service
- **模式结构**:
  ```kotlin
  class PushService(
      private val userStreamRegistry: UserStreamRegistry,
      private val conversationMemberRepository: ConversationMemberRepository
  ) {
      suspend fun pushMessage(convId, chatMessage, excludeUid)
      fun pushReadReceipt(senderUid, payload)
      suspend fun pushConversationEvent(convId, eventType, payloadBytes, excludeUids)
      fun pushEventToUser(targetUid, eventType, payloadBytes)  // ← 核心模板
  }
  ```
- **关键约定**:
  - `pushEventToUser()` 是通用推送方法：查 UserStreamRegistry → 构建 Envelope → 逐个 observer.onNext
  - 单个 observer 异常时 try-catch 保护 + `userStreamRegistry.removeStream()` 清理（D-05 容错）
  - 非 suspend 方法（纯内存操作），suspend 方法含 blocking JPA 查询
- **适用场景**: 服务端主动推送事件给指定用户的在线设备
- **映射到**: **DISCONNECT 事件推送（D-68）** — 直接复用 `pushEventToUser()`，新增 `PushEventType.DISCONNECT`

### 3. SessionRegistry 模式（二级缓存 + 驱逐回调）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/session/SessionRegistry.kt`
- **模式类型**: Registry
- **模式结构**:
  ```kotlin
  class SessionRegistry(private val sessionRepository: SessionRepository) {
      // L1: ConcurrentHashMap<String, Session>
      // L2: Redis SessionRepository
      
      fun onEviction(callback: (token: String) -> Unit)  // 注册驱逐回调
      fun addToLocalCache(session)
      fun removeFromLocalCache(token): Session?
      suspend fun saveToRedis(session)
      suspend fun removeFromRedis(token)
      suspend fun validate(token): Session?
      suspend fun register(session)
      suspend fun unregister(token)  // L1 + L2 + 触发驱逐回调
      suspend fun registerWithDeviceType(session): String?  // 同类型互踢
  }
  ```
- **关键约定**:
  - L1(Local) → L2(Redis) 二级缓存，500ms Redis 超时降级
  - `unregister()` 触发 evictionCallbacks → ChatService 中的回调负责推送 LOGOUT + 关闭连接
  - `registerWithDeviceType()` 内置同类型设备互踢逻辑
- **适用场景**: Session 的注册、验证、驱逐全生命周期管理
- **映射到**: **Redis pipeline 连接清理（RECON-04）** — 需要增强 `unregister()` 或新增批量清理方法

### 4. ChatService 连接生命周期模式（StreamObserver 管理）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt`
- **模式类型**: Service + gRPC StreamObserver 内部类
- **模式结构**:
  ```kotlin
  class ChatService(...) : BindableService {
      private val tokenToObserver = ConcurrentHashMap<String, StreamObserver<Envelope>>()
      
      // eviction callback 注册
      private fun ensureEvictionCallbackRegistered() {
          sessionRegistry.onEviction { token ->
              val observer = tokenToObserver.remove(token)
              observer?.let { /* 推送 LOGOUT + 关闭连接 */ }
          }
      }
      
      // 登录成功处理
      private suspend fun handleLoginSuccess(response, responseObserver) {
          // 1. 反序列化 LoginResp
          // 2. 创建 Session + registerWithDeviceType（可能驱逐旧连接）
          // 3. 更新 tokenToObserver 映射
          // 4. 注册到 UserStreamRegistry
          // 5. 取消旧的延迟离线任务（D-57 重连场景）
          // 6. 标记在线 + 推送状态变更
      }
      
      // 连接清理
      private fun cleanupConnection() {
          tokenToObserver.entries.removeIf { it.value == responseObserver }
          userStreamRegistry.removeStream(uid, responseObserver)
          // D-57: 启动 60s 延迟离线任务
      }
      
      // PING 处理
      private fun handlePing(envelope, responseObserver) {
          // 刷新在线状态 TTL
          // 回复 PONG Envelope
      }
  }
  ```
- **关键约定**:
  - `tokenToObserver` 用于 eviction callback 查找对应连接推送 LOGOUT
  - `ChatStreamObserver` 内部类持有 `userId` 和 `delayedOfflineJob` 引用
  - `handleLoginSuccess` 中取消旧 `delayedOfflineJob` 实现重连时的伪在线续期
  - `cleanupConnection` 中启动 60s 延迟离线任务（D-57 伪在线）
- **适用场景**: gRPC 双向流的连接建立、请求分发、连接清理全生命周期
- **映射到**: **所有 Phase 9 需求** — ChatService 是重连逻辑的核心修改点

### 5. eviction callback + LOGOUT 推送模式（旧连接清理）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` (lines 301-321)
- **模式类型**: 回调机制
- **模式结构**:
  ```kotlin
  // ChatService 中
  sessionRegistry.onEviction { token ->
      val observer = tokenToObserver.remove(token)
      if (observer != null) {
          val logoutEnvelope = Envelope.newBuilder()
              .setDirection(Direction.PUSH)
              .setMessage(Message.newBuilder()
                  .setEventType(PushEventType.LOGOUT)
                  .setContent("相同设备类型在其他地方登录")
                  .build())
              .build()
          observer.onNext(logoutEnvelope)
          observer.onCompleted()
      }
  }
  ```
- **关键约定**:
  - `SessionRegistry.unregister()` → 触发所有 evictionCallbacks
  - 回调中通过 `tokenToObserver` 查找旧连接的 StreamObserver
  - 推送 LOGOUT 后调用 `observer.onCompleted()` 关闭旧连接
- **适用场景**: 同类型设备互踢时的旧连接通知和关闭
- **映射到**: **DISCONNECT 推送（D-68）** — 与 LOGOUT 模式完全相同，仅事件类型不同

### 6. 伪在线延迟离线模式（D-57）

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` (lines 150-172)
- **模式类型**: 延迟任务
- **模式结构**:
  ```kotlin
  // cleanupConnection() 中
  delayedOfflineJob = scope.launch {
      delay(60_000)  // 60s 伪在线窗口
      if (userStreamRegistry.getStreams(uid).isEmpty()) {
          onlineStatusRepository.setOffline(uid)
          pushStatusChangeToFriends(uid, 0)
      }
  }
  
  // handleLoginSuccess() 中（重连时）
  responseObserver.delayedOfflineJob?.cancel()  // 取消旧任务的延迟离线
  ```
- **关键约定**:
  - `ChatStreamObserver` 持有 `delayedOfflineJob: Job?` 引用
  - 60s 延迟后检查是否还有剩余设备在线
  - 重连时通过 `handleLoginSuccess()` 取消旧任务的延迟离线
- **适用场景**: 连接断开后保留 60s 伪在线状态
- **映射到**: **伪在线集成（D-69）** — 伪在线 60s 即为 BACKOFF 阶段，超时后标记离线

### 7. OnlineStatusRepository 模式（Redis 在线状态）

- **源文件**: `repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt`
- **模式类型**: Repository
- **模式结构**:
  ```kotlin
  class OnlineStatusRepository(private val connection: StatefulRedisConnection<String, String>) {
      suspend fun setOnline(userId: Long)    // setex with TTL 60s
      suspend fun setOffline(userId: Long)   // del
      suspend fun refreshTtl(userId: Long)   // expire with TTL 60s
      suspend fun getStatus(userId: Long): OnlineStatusData?
      suspend fun batchGetStatus(userIds): Map<Long, OnlineStatusData?>
  }
  ```
- **关键约定**:
  - key 格式: `online:user:<userId>`，TTL 60s（短 TTL，D-14）
  - 存储 JSON `{"status": 0|1|2, "lastActiveAt": timestamp}`
  - `refreshTtl` 在 PING 处理中调用，维持伪在线状态
- **适用场景**: 用户在线状态的读写和刷新
- **映射到**: **心跳恢复（RECON-02）** — 重连后 PING 触发 refreshTtl 维持在线

### 8. SessionRepository 模式（Redis 键值操作）

- **源文件**: `repository/src/main/kotlin/com/nebula/repository/redis/SessionRepository.kt`
- **模式类型**: Repository
- **模式结构**:
  ```kotlin
  class SessionRepository(private val connection: StatefulRedisConnection<String, String>) {
      suspend fun save(token, userData, ttlSeconds)
      suspend fun findByToken(token): String?
      suspend fun delete(token)
      suspend fun saveRaw(key, value)     // 通用写入
      suspend fun findRaw(key): String?   // 通用读取
      suspend fun deleteKey(key)          // 通用删除
  }
  ```
- **关键约定**:
  - key 格式: `session:token:<token>`，TTL 7 天
  - 提供 `saveRaw/findRaw/deleteKey` 通用方法供设备类型映射等场景使用
  - 当前只支持单 key 操作，**不支持 pipeline 批量操作**
- **适用场景**: Session Token 和通用 Redis key/value 操作
- **映射到**: **Redis pipeline 连接清理（RECON-04）** — 需要新增 pipeline 批量删除方法

### 9. HandlerCollector + Koin Module 注册模式

- **源文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/HandlerCollector.kt`, `UserHandlerModule.kt`, `FrameworkModule.kt`
- **模式类型**: DI 注册
- **模式结构**:
  ```kotlin
  // HandlerCollector 接口
  interface HandlerCollector {
      fun registerAll(registry: HandlerRegistry)
  }
  
  // Collector 实现（每个业务包一个）
  class XxxHandlerCollector(...handlers...) : HandlerCollector {
      override fun registerAll(registry: HandlerRegistry) {
          handlers.forEach { registry.register(it) }
      }
  }
  
  // Koin Module
  val xxxHandlerModule = module {
      single { XxxHandler(get(), get()) }
      single<HandlerCollector> { XxxHandlerCollector(get(), ...) }
  }
  ```
- **关键约定**:
  - 每个业务模块一个 Collector + 一个 Koin Module
  - Koin 的 `getAll<HandlerCollector>()` 自动发现所有 Collector
  - 新增 Handler 只需在 Collector 中加一行，无需修改中央注册代码
- **适用场景**: 新 Handler 的注册和依赖注入
- **映射到**: 本阶段不需要新增 Handler，无需修改注册模式

### 10. Proto PushEventType 枚举模式

- **源文件**: `proto/src/main/proto/nebula/message_type.proto`
- **模式类型**: Proto 枚举定义
- **当前定义**（已有 14 种事件类型，从 CHAT_MESSAGE=1 到 STATUS_CHANGED=14）
- **关键约定**:
  - 服务端推送事件通过 `Envelope.direction = PUSH` + `Message.eventType = PushEventType.XXX` 投递
  - payload 由 eventType 决定对应的 proto message 类型
  - 新增事件类型时递增编号，不修改已有编号
- **适用场景**: 定义服务端推送事件类型
- **映射到**: **DISCONNECT 推送（D-68）** — 可能需要新增 `DISCONNECT = 15` 枚举值

## 新需求 → 模板映射

| 新需求 | 最接近的现有模式 | 模板文件 | 差异说明 |
|--------|---------------|---------|---------|
| **Redis pipeline 连接清理（RECON-04）** | SessionRegistry.unregister() + SessionRepository 单 key 删除 | `SessionRegistry.kt` + `SessionRepository.kt` | 现有是单 key 逐条删除，需要新增 pipeline 批量操作：`SessionRepository` 新增 `pipelineDelete(keys)` 方法，`SessionRegistry` 新增 `batchUnregister(tokens)` 组合方法 |
| **DISCONNECT 推送（D-68）** | eviction callback LOGOUT 推送 + PushService.pushEventToUser() | `ChatService.kt` (lines 301-321) + `PushService.kt` (pushEventToUser) | LOGOUT 推送是 eviction callback 内部直接构建 Envelope，DISCONNECT 需要：1) 新增 `PushEventType.DISCONNECT` 枚举 2) 在清理旧 Channel 前调用 `pushEventToUser()` 推送 DISCONNECT |
| **缓存再投递（D-67）** | ChatService.handleLoginSuccess() 的 tokenToObserver 更新 + 取消延迟离线 | `ChatService.kt` (lines 216-267) | 现有重连流程：新连接注册后直接投递消息。D-67 需要：新连接建立后先缓存消息（在 `handleLoginSuccess` 中标记），旧连接清理完成后再投递到新连接 |
| **重连后心跳恢复（RECON-02）** | ChatService.handlePing() + PingHandler | `ChatService.kt` (lines 275-293) + `PingHandler.kt` | 现有 PING→PONG + refreshTtl。D-62 需要：重连后客户端发起 PING，服务端回复 PONG，确认双向通信正常后恢复标准心跳间隔。服务端侧无需改动（PONG 回复和 refreshTtl 已实现），客户端侧需等待首次 PONG |
| **伪在线集成（D-69）** | ChatService.cleanupConnection() 的 60s 延迟离线 | `ChatService.kt` (lines 150-172) | 现有伪在线 60s 超时后标记离线。D-69 需要：伪在线 60s 即为状态机的 BACKOFF 阶段，60s 内重连成功取消延迟离线，超时后标记离线。服务端侧逻辑已完备（cancel delayedOfflineJob + refreshTtl），无需改动 |

## 新文件模板推荐

### 1. SessionRepository.pipeline 批量操作（新增方法）

- **参考模板**: `SessionRepository.kt` 现有 `delete()` / `deleteKey()` 方法
- **结构建议**: 在 `SessionRepository` 中新增 pipeline 批量删除方法
- **关键差异**:
  - 使用 Lettuce `redis.setAutoFlushCommands(false)` + `redis.flushCommands()` 实现 pipeline
  - 返回成功/失败统计，而非单个删除的 void 返回
  - 调用方（SessionRegistry）使用 `withTimeout` 包裹，保持与现有 500ms 超时保护一致

### 2. PushEventType.DISCONNECT（Proto 新增枚举值）

- **参考模板**: `message_type.proto` 现有枚举值
- **结构建议**: 新增 `DISCONNECT = 15`
- **关键差异**:
  - DISCONNECT 事件 payload 为空，`content` 字段携带原因描述
  - 无需新增 proto message 类型

### 3. ChatService 重连增强（修改现有类）

- **参考模板**: `ChatService.kt` 现有 `handleLoginSuccess()` / `cleanupConnection()` / `ensureEvictionCallbackRegistered()`
- **结构建议**:
  - `handleLoginSuccess()` 中：新增缓存再投递标记（D-67）
  - `cleanupConnection()` 中：新增 DISCONNECT 推送逻辑（D-68）
  - eviction callback 中：原有 LOGOUT 推送保持不变，新增 DISCONNECT 分支
- **关键差异**:
  - 缓存再投递：在旧连接清理完成前，新连接收到的消息先缓存到内存队列，清理完成后投递
  - DISCONNECT 推送：在关闭旧 Channel 前调用 `pushEventToUser()`，区别于 LOGOUT 的 eviction callback 触发

## PATTERNS COMPLETE
