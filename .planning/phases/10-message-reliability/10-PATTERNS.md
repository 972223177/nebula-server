---
phase: 10
mapper: nx-pattern-mapper
---
# Phase 10: Message Reliability — 代码模式映射

## 已识别模式

### 1. Proto 消息定义模式

#### 1a. Push Event Payload 模式

**模板文件**: `proto/src/main/proto/nebula/message/message.proto` — `ReadReceiptPayload`

```protobuf
// 已读回执推送 payload（PUSH EventType=READ_RECEIPT）
message ReadReceiptPayload {
  string conversation_id = 1;
  int64 reader_uid = 2;
  int64 msg_id = 3;
}
```

**关键约定**:
- Push payload 定义在对应业务模块的 `.proto` 文件中
- 通过 `message_type.proto` 中的 `PushEventType` 枚举关联（`DELIVERY_ACK = 13` 已定义）
- Payload 字段使用 protobuf 标准类型（int64, string）
- 使用 `option java_multiple_files = true; option java_package = "..."` 包声明

**PushEventType 已在 message_type.proto 中预定义**:
```
DELIVERY_ACK = 13;  // payload = DeliveryAckPayload
```
无需修改枚举，只需定义 `DeliveryAckPayload` 消息体。

#### 1b. 请求/响应消息模式

**模板文件**: `proto/src/main/proto/nebula/message/message.proto` — `PullMessagesReq/Resp`

```protobuf
message PullMessagesReq {
  string conversation_id = 1;
  int64 cursor = 2;
  int32 limit = 3;
  string direction = 4;
}

message PullMessagesResp {
  repeated ChatMessage messages = 1;
  bool has_more = 2;
}
```

**关键约定**:
- Req/Resp 成对定义
- 分页查询使用 `cursor` + `limit` + `has_more` 模式
- 文件放在对应业务模块的 proto 包下

**模板文件**: `proto/src/main/proto/nebula/chat/chat.proto` — `SendMessageReq/Resp`

```protobuf
message SendMessageReq {
  string conversation_id = 1;
  ChatContentType message_type = 2;
  string content = 3;
  bytes payload = 4;
  int64 client_ts = 5;
  string client_message_id = 6;
}

message SendMessageResp {
  int64 msg_id = 1;
  int64 server_ts = 2;
}
```

**关键约定**:
- `SendMessageResp` 需要新增 `int64 seq = 3;` 字段支持序列号间隙检测（D-74）

#### 1c. Proto 文件组织结构

| 文件 | 用途 |
|------|------|
| `proto/src/main/proto/nebula/message_type.proto` | 枚举：ChatContentType、PushEventType |
| `proto/src/main/proto/nebula/envelope.proto` | 通信框架：Envelope、Direction、Request、Response、Message |
| `proto/src/main/proto/nebula/message/message.proto` | 消息业务：ChatMessage、ReadReport、PullMessages |
| `proto/src/main/proto/nebula/chat/chat.proto` | 聊天业务：SendMessageReq/Resp |
| `proto/src/main/proto/nebula/conversation/conversation.proto` | 会话业务 + 推送事件 Payload |

---

### 2. Handler 模式

#### 2a. Handler 接口

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/Handler.kt`

```kotlin
interface Handler<Req : Any, Resp : Any> {
    val method: String
    suspend fun handle(req: Req): Resp
}
```

**关键约定**:
- `method` 字符串格式：`"domain/action"`（如 `"chat/send"`、`"message/read"`）
- Session 通过 `coroutineContext.requireSession()` 获取

#### 2b. 简单 Handler（仅委托 Service）

**模板文件**: `PullMessagesHandler.kt`

```kotlin
class PullMessagesHandler(
    private val messageService: MessageService
) : Handler<PullMessagesReq, PullMessagesResp> {

    override val method: String = "message/pull"

    override suspend fun handle(req: PullMessagesReq): PullMessagesResp {
        val session = currentCoroutineContext().requireSession()
        return messageService.pullMessages(req, session.userId)
    }
}
```

**适用场景**: `MessageSeqHandler`（仅委托 Service 的序列号同步请求）

#### 2c. 中等复杂度 Handler（Service + Redis 操作 + Push）

**模板文件**: `ReadReportHandler.kt`

```kotlin
class ReadReportHandler(
    private val messageService: MessageService,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>
) : Handler<ReadReportReq, Response> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "message/read"

    override suspend fun handle(req: ReadReportReq): Response {
        val session = currentCoroutineContext().requireSession()
        // 1. 委托 Service
        messageService.readReport(req, session.userId)
        // 2. Redis 操作
        redis.del("conversation:${req.conversationId}:unread:${session.userId}")
        // 3. 条件推送
        ...
        return Response.newBuilder().setCode(200).setMethod("message/read").build()
    }
}
```

**适用场景**: `DeliveryAckHandler`（需要 Redis Hash 操作 + Push DELIVERY_ACK）

#### 2d. 复杂 Handler（Redis 去重 + Service + 异步推送）

**模板文件**: `SendMessageHandler.kt`

```kotlin
class SendMessageHandler(
    private val messageService: MessageService,
    private val pushService: PushService,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val connection: StatefulRedisConnection<String, String>,
    private val scope: CoroutineScope
) : Handler<SendMessageReq, SendMessageResp> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "chat/send"

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        // Redis SETNX 去重
        // 委托 Service
        // 异步 fire-and-forget 推送
        return response
    }
}
```

---

### 3. Repository 模式

#### 3a. JPA Repository 接口（MySQL 查询）

**模板文件**: `repository/src/main/kotlin/com/nebula/repository/repository/FriendRequestRepository.kt`

```kotlin
interface FriendRequestRepository : JpaRepository<FriendRequestEntity, Long> {
    fun findByToUidAndStatus(toUid: Long, status: Int): List<FriendRequestEntity>
    fun findByFromUidAndToUid(fromUid: Long, toUid: Long): FriendRequestEntity?
    fun findByFromUidAndToUidAndStatus(fromUid: Long, toUid: Long, status: Int): FriendRequestEntity?
    fun findByToUidAndStatusOrderByCreatedAtDesc(toUid: Long, status: Int): List<FriendRequestEntity>
}
```

**关键约定**:
- 继承 `JpaRepository<EntityType, IdType>` 获得基础 CRUD
- 方法命名遵循 Spring Data JPA 规范
- 需要游标分页时使用 `@Query` + `Pageable`（参看 `ConversationRepository`、`MessageRepository`）

#### 3b. JPA Entity 定义

**模板文件**: `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt`

```kotlin
@Entity
@Table(name = "conversations")
class ConversationEntity(
    @Column(nullable = false)
    var type: Int,

    @Column(length = 128)
    var name: String = "",

    // ... 更多属性
) {
    @Id
    @Column(length = 32)
    var id: String? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
```

**关键约定**:
- 使用 `@Entity` + `@Table` 注解
- 主构造参数声明业务字段
- `@Id` 和审计字段（createdAt/updatedAt）定义在类体中
- Snowflake ID 用 `Long` 类型，UUID 用 `String` 类型

#### 3c. Redis Repository 模式

**模板文件**: `repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt`

```kotlin
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OnlineStatusRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        private const val KEY_PREFIX = "online:user:"
        private const val TTL_SECONDS = 60L
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun setOnline(userId: Long) { ... }
    suspend fun getStatus(userId: Long): OnlineStatusData? { ... }
}
```

**关键约定**:
- 构造注入 `StatefulRedisConnection<String, String>`
- `@OptIn(ExperimentalLettuceCoroutinesApi::class)` 注解
- `companion object` 存放 key 前缀、TTL 等常量
- JSON 序列化使用 `kotlinx.serialization`
- 可通过 `koin.declare()` 注册到 Koin 容器

**模板文件（非序列化 Redis 操作）**: `repository/src/main/kotlin/com/nebula/repository/redis/SessionRepository.kt`

```kotlin
class SessionRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        private const val KEY_PREFIX = "session:token:"
        private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L
    }

    suspend fun save(token: String, userData: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.setex("$KEY_PREFIX$token", ttlSeconds, userData)
    }

    suspend fun findByToken(token: String): String? {
        return redis.get("$KEY_PREFIX$token")
    }
}
```

**Redis Hash 操作参考**: Lettuce 的 `RedisCoroutinesCommands` 支持 `hset`、`hget`、`hdel`、`hgetall` 等方法，适合 `msg:{msg_id}:delivery` Hash 存储。

---

### 4. Flyway 迁移模式

| 迁移文件 | 模式 | 说明 |
|---------|------|------|
| `V1__init_schema.sql` | `CREATE TABLE` | 新建表结构 |
| `V2__phase7_conversation_schema.sql` | `ALTER TABLE ADD COLUMN` | 现有表新增列 |
| `V3__add_friend_request_message.sql` | `ALTER TABLE ADD COLUMN` | 现有表新增列 |

**关键约定**:
- 文件命名：`V<版本号>__<描述>.sql`
- 仅执行 DDL，不含 DML（数据迁移）
- 使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` 建表
- 放在 `repository/src/main/resources/db/migration/` 目录

**Phase 10 死信表使用 CREATE TABLE 模式（参看 V1）**，因为 `dead_letters` 是一张新表。

---

### 5. Koin DI 注册模式

#### 5a. 模块组织结构

```
gateway/src/main/kotlin/com/nebula/gateway/di/
├── FrameworkModule.kt        # 基础设施：HandlerRegistry, ProtoCodec, Interceptors
├── ServiceModule.kt          # Service 层
├── ChatHandlerModule.kt      # Chat & Message Handler
├── ConversationHandlerModule.kt  # Conversation Handler
├── FriendHandlerModule.kt    # Friend Handler
├── UserHandlerModule.kt      # User Handler
└── GatewayModule.kt          # 模块聚合入口
```

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/ChatHandlerModule.kt`

```kotlin
val chatHandlerModule = module {
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { UserStreamRegistry() }
    single { PushService(get(), get()) }

    // Handler 注册
    single { SendMessageHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) }
    single { PullMessagesHandler(get()) }
    single { ReadReportHandler(get(), get(), get(), get(), get()) }

    // HandlerCollector 注册
    single<HandlerCollector> { ChatHandlerCollector(get(), get(), get()) }
}
```

**关键约定**:
- 每个业务域一个独立 module
- Handler 通过 `single { ... }` 注册，依赖自动注入
- Collector 注册为 `single<HandlerCollector>`
- 自定义 CoroutineScope 使用 `named(...)` 限定符

#### 5b. 聚合入口

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt`

```kotlin
val gatewayModules = listOf(
    serviceModule,
    frameworkModule,
    userHandlerModule,
    chatHandlerModule,
    conversationHandlerModule,
    friendHandlerModule
)
```

**关键约定**: 新模块需要追加到 `gatewayModules` 列表。

#### 5c. Repository 层注册（ModuleInitializer 模式）

**模板文件**: `repository/src/main/kotlin/com/nebula/repository/init/RepositoryModuleInitializer.kt`

```kotlin
class RepositoryModuleInitializer : ModuleInitializer, KoinComponent {
    override val name = "repository"
    override val dependencies = listOf("common")

    override fun init() {
        // 初始化 JPA + Redis
        // ...
        // 注册到 Koin 容器
        koin.declare<DeadLetterRepository>(deadLetterRepo)
    }
}
```

**Phase 10 新增的 Redis/Mysql Repository 通过 `koin.declare()` 在此注册**。

---

### 6. HandlerCollector 注册模式

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/chat/ChatHandlerCollector.kt`

```kotlin
class ChatHandlerCollector(
    private val sendMessageHandler: SendMessageHandler,
    private val pullMessagesHandler: PullMessagesHandler,
    private val readReportHandler: ReadReportHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(sendMessageHandler)
        registry.register(pullMessagesHandler)
        registry.register(readReportHandler)
    }
}
```

**关键约定**:
- 实现 `HandlerCollector` 接口
- 构造注入所有 Handler
- `registerAll` 中逐一调用 `registry.register()`
- 在 Koin module 中注册为 `single<HandlerCollector>`

---

### 7. 定时任务/补偿任务模式

**模板文件**: `repository/src/main/kotlin/com/nebula/repository/repository/impl/MessageRepositoryImpl.kt` — `startFlushTimer()`

```kotlin
fun startFlushTimer() {
    CoroutineScope(Dispatchers.IO).launch {
        while (!stopped) {
            delay(500)  // 500ms 间隔
            flushBatch()
        }
    }
}
```

**关键约定**:
- 使用 `CoroutineScope(Dispatchers.IO).launch` 启动后台协程
- 循环中使用 `delay(interval)` 控制调度间隔
- 通过 `stopped` 标志控制优雅停止
- 异常在循环内部 try-catch 处理，防止循环终止

**Phase 10 适用**: `DeadLetterCompensator` 应使用此模式，间隔改为 10 分钟（`delay(600_000)`）。

---

### 8. Push/Delivery 推送模式

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt`

**关键约定**:
- `pushReadReceipt()` 方法：通过 `UserStreamRegistry.getStreams()` 获取在线设备，构建 PUSH Envelope
- 推送 Envelope 构建模式：
  ```kotlin
  val envelope = Envelope.newBuilder()
      .setDirection(Direction.PUSH)
      .setRequestId("")
      .setMessage(Message.newBuilder()
          .setEventType(PushEventType.DELIVERY_ACK)  // 或 READ_RECEIPT
          .setContent("")
          .setPayload(payload.toByteString())
          .build())
      .build()
  observer.onNext(envelope)
  ```
- 每个 observer try-catch 保护，异常时 `userStreamRegistry.removeStream()`

**Phase 10 适用**: `PushService` 需要新增 `pushDeliveryAck()` 方法，复用现有推送模式。

---

## 新需求 → 模板映射

| Plan | 新组件 | 最接近的现有模式 | 模板文件 | 差异说明 |
|------|--------|---------------|---------|---------|
| **10-01** | `DeliveryAckPayload` proto | `ReadReceiptPayload` | `message.proto` | 新增 `server_acked` bool 字段；`PushEventType` 中 `DELIVERY_ACK=13` 已定义 |
| **10-01** | `DeadLetterQueryReq/Resp` proto | `PullMessagesReq/Resp` | `message.proto` | 分页参数改用 `page`/`page_size` 而非 cursor；需 `status` 过滤字段 |
| **10-01** | `RetryDeadLetterReq/Resp` proto | `SendMessageReq/Resp` | `chat.proto` | 简单请求-响应模式，只需 `dead_letter_id` 字段 |
| **10-01** | `SendMessageResp.seq` 扩展 | `SendMessageResp` | `chat.proto` | 现有消息新增字段，非新增消息；需追加 `int64 seq = 3` |
| **10-01** | `MessageSeqReq/Resp` proto | `ReadReportReq/Resp` | `message.proto` | 需 `conversation_id` + `last_seen_seq` 字段 |
| **10-01** | Flyway V4 `dead_letters` 表 | V1 `CREATE TABLE` | `V1__init_schema.sql` | 新表创建，字段：id, message_id, conversation_id, sender_id, payload, failed_at, retry_count, max_retries, last_error, status |
| **10-02** | `RedisDeliveryTracker` | `OnlineStatusRepository` | `OnlineStatusRepository.kt` | 使用 Redis Hash（HSET/HGET/HDEL）而非 JSON string；hash key 为 `msg:{msg_id}:delivery`，fields 为 `{uid}:status` |
| **10-02** | `DeliveryAckHandler` | `ReadReportHandler` | `ReadReportHandler.kt` | 非客户端请求，而是连接层投递成功后的内部回调；推送 DELIVERY_ACK 给发送者；需新增 `PushService.pushDeliveryAck()` |
| **10-02** | `PushService.pushDeliveryAck()` 新增 | `PushService.pushReadReceipt()` | `PushService.kt` | 复用完全相同的推送 Envelope 构建模式，仅改 `PushEventType.DELIVERY_ACK` 和 payload 类型 |
| **10-02** | pendingBuffer 接管逻辑 | `SendMessageHandler.asyncUnreadAndPush()` | `SendMessageHandler.kt` | Phase 9 pendingBuffer 投递成功 → `RedisDeliveryTracker.deliver()`，10 次失败 → 死信入表 |
| **10-03** | `SeqGapDetector` | `SendMessageHandler` (Redis INCR 部分) | `SendMessageHandler.kt` | 使用 Redis INCR 自增；新类而非 Handler，内部逻辑检测间隙触发 forward 拉取 |
| **10-03** | `MessageSeqHandler` | `PullMessagesHandler` | `PullMessagesHandler.kt` | 简单 Handler，委托 Service 返回序列号信息；method = `"message/seq"` |
| **10-04** | `DeadLetterEntity` | `ConversationEntity` | `ConversationEntity.kt` | 新 Entity，映射 `dead_letters` 表；需 `@Table(name = "dead_letters")` |
| **10-04** | `DeadLetterRepository` (JPA) | `FriendRequestRepository` | `FriendRequestRepository.kt` | 基础 CRUD + 按状态查询 + 分页查询 |
| **10-04** | `DeadLetterCompensator` | `MessageRepositoryImpl.startFlushTimer()` | `MessageRepositoryImpl.kt` | 间隔改为 10 分钟（`delay(600_000)`）；扫描死信重试 + 更新状态 |
| **10-04** | `DeadLetterQueryHandler` | `ReadReportHandler` | `ReadReportHandler.kt` | method = `"admin/dead-letters"`；返回分页结果；无需 session 校验（运维接口） |
| **10-04** | `RetryDeadLetterHandler` | `PullMessagesHandler` | `PullMessagesHandler.kt` | method = `"admin/retry-dead-letter"`；简单委托 Service 处理单条死信重试 |
| **10-04** | Koin DI 注册 | `ChatHandlerModule.kt` + `RepositoryModuleInitializer.kt` | `ChatHandlerModule.kt` + `RepositoryModuleInitializer.kt` | 新增 module 或追加到已有 module；Redis/MySQL Repository 通过 `koin.declare()` 注册；新增 `GatewayModule.kt` 聚合 |
| **10-04** | `MessageReliabilityHandlerCollector` | `ChatHandlerCollector.kt` | `ChatHandlerCollector.kt` | 注册 DeliveryAckHandler、DeadLetterQueryHandler、RetryDeadLetterHandler、MessageSeqHandler |

## 差异点详细说明

### DeliveryAckPayload vs ReadReceiptPayload
- `ReadReceiptPayload` 有三个字段：`conversation_id`, `reader_uid`, `msg_id`
- `DeliveryAckPayload` 需：`msg_id`, `conversation_id`, `server_acked`（bool 标记）
- 协议设计中 `DELIVERY_ACK` 是服务端自动触发，无需客户端响应

### RedisDeliveryTracker vs OnlineStatusRepository
- `OnlineStatusRepository` 使用 `SET key JSON` + `GET key` 存储 JSON 序列化的对象
- `RedisDeliveryTracker` 需要使用 Redis **Hash** 结构：`HSET msg:{msg_id}:delivery {uid}:status <status_value>`
- 每个消息对应一个 Hash，每个接收者对应一个 field
- 使用 Lettuce 的 `hset`/`hget`/`hdel`/`hgetall` 命令

### Admin API 特殊说明
- 当前代码库**没有**既有的 Admin API 模式
- `DeadLetterQueryHandler` 和 `RetryDeadLetterHandler` 是新引入的管理接口
- 建议方法路由使用 `"admin/..."` 前缀，便于拦截器区分（可在 AuthInterceptor 的 skipMethods 中额外放行，或在后续添加 AdminInterceptor）
- 响应格式复用 `Response` Envelope（code + msg + result bytes）

### 死信补偿任务 vs 消息刷写任务
- `MessageRepositoryImpl.startFlushTimer()` 使用 `delay(500)` + 无界循环
- `DeadLetterCompensator` 需要：
  - `delay(600_000)`（10 分钟间隔）
  - 单线程执行（无需并行，避免重复处理）
  - 最多 5 次重试（通过 `retry_count` 字段控制）
  - 永久失败日志告警（logger.warn/error）
  - 建议使用 `while (active) { try { ... } catch { ... } }` 模式防止异常终止

## 推荐实现顺序与模板复用

```
10-01 (Proto + Flyway)
  ├── 模板: ReadReceiptPayload → DeliveryAckPayload
  ├── 模板: PullMessagesReq/Resp → DeadLetterQueryReq/Resp
  ├── 模板: V1__init_schema → V4__add_dead_letters_table
  └── SendMessageResp 追加 seq 字段
      ↓
10-02 (三态跟踪 + DeliveryAck)
  ├── 模板: OnlineStatusRepository → RedisDeliveryTracker  (Hash 变体)
  ├── 模板: ReadReportHandler → DeliveryAckHandler
  └── 模板: PushService.pushReadReceipt() → pushDeliveryAck()
      ↓
10-03 (间隙检测)
  ├── 模板: PullMessagesHandler → MessageSeqHandler
  └── 新增: SeqGapDetector (Redis INCR + 间隙检测逻辑)
      ↓
10-04 (死信 + 补偿 + Admin API + DI)
  ├── 模板: ConversationEntity → DeadLetterEntity
  ├── 模板: FriendRequestRepository → DeadLetterRepository
  ├── 模板: MessageRepositoryImpl.startFlushTimer → DeadLetterCompensator
  ├── 模板: ReadReportHandler → DeadLetterQueryHandler (Admin)
  ├── 模板: PullMessagesHandler → RetryDeadLetterHandler (Admin)
  ├── 模板: ChatHandlerCollector → MessageReliabilityHandlerCollector
  ├── 模板: ChatHandlerModule → MessageReliabilityModule (Koin)
  └── 模板: RepositoryModuleInitializer → 注册 DeadLetterRepository/RedisRepository
```

## PATTERNS COMPLETE
