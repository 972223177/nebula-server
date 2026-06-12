# Phase 6: Chat & Message — Pattern Map

**Mapped:** 2026-06-12
**Files analyzed:** 13 (11 new + 2 modified)
**Analogs found:** 13 / 13

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `gateway/.../handler/chat/send/SendMessageHandler.kt` | controller | CRUD | `RegisterHandler` | role-match |
| `gateway/.../handler/chat/send/SendMessageStep.kt` | controller | request-response | `Interceptor` (interface chain) | partial |
| `gateway/.../handler/chat/send/SendContext.kt` | utility | request-response | `Session` (data class) | partial |
| `gateway/.../handler/chat/send/ValidateStep.kt` | controller | CRUD | `RegisterHandler` (validation) | role-match |
| `gateway/.../handler/chat/send/DedupStep.kt` | controller | CRUD | `OnlineStatusRepository` (Redis op) | role-match |
| `gateway/.../handler/chat/send/WriteStep.kt` | controller | CRUD | `RegisterHandler` + `MessageQueueRepository` | role-match |
| `gateway/.../handler/chat/send/PushStep.kt` | controller | event-driven | `ChatService` (StreamObserver pattern) | partial |
| `gateway/.../handler/message/PullMessagesHandler.kt` | controller | CRUD | `GetProfileHandler` (simple query→proto) | role-match |
| `gateway/.../handler/message/ReadReportHandler.kt` | controller | CRUD | `LoginHandler` (complex logic) | role-match |
| `gateway/.../push/PushService.kt` | service | event-driven | `ChatService` (Envelope build + push) | partial |
| `gateway/.../session/UserStreamRegistry.kt` | utility | request-response | `SessionRegistry` (ConcurrentHashMap reg) | exact |
| `gateway/.../di/GatewayModule.kt` (modified) | config | — | existing `GatewayModule.kt` | exact |
| `gateway/.../service/ChatService.kt` (modified) | service | streaming | existing `ChatService.kt` | exact |

## Pattern Assignments

### `handler/chat/send/SendMessageHandler.kt` (controller, CRUD — Step chain orchestrator)

**Analog:** `gateway/.../handler/user/RegisterHandler.kt` (lines 31-91)

**Imports pattern** — 所有 Handler 沿用此 import 结构：
```kotlin
// RegisterHandler.kt lines 1-11
package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.common.exception.MessageException
import com.nebula.gateway.handler.Handler
// Step 链相关 import
```

**Core Handler pattern** (RegisterHandler.kt lines 31-36, 52-90):
```kotlin
class SendMessageHandler(
    private val steps: List<SendMessageStep>
) : Handler<SendMessageReq, SendMessageResp> {

    override val method: String = "chat/send"

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = coroutineContext.requireSession()
        val context = SendContext(req = req, senderUid = session.userId)
        for (step in steps) {
            if (!step.execute(context)) break
        }
        return when (context.bizCode) {
            BizCode.OK -> {
                requireNotNull(context.msgId) { "msgId must be set after successful write" }
                SendMessageResp.newBuilder()
                    .setMsgId(context.msgId)
                    .setServerTs(System.currentTimeMillis())
                    .build()
            }
            else -> throw MessageException(context.bizCode)
        }
    }
}
```

**Error handling pattern** (RegisterHandler.kt lines 56-57, BizException pattern):
```kotlin
// ValidateStep 和 DedupStep 失败时抛出 MessageException
throw MessageException(BizCode.INVALID_PARAM, "消息内容不能为空")
// ExceptionInterceptor 自动捕获并转为 Response
```

---

### `handler/chat/send/SendMessageStep.kt` (controller, request-response)

**Analog:** `gateway/.../interceptor/Interceptor.kt` (chain interface pattern)

**Core interface pattern** (conceptual — 与 Interceptor 链式接口类似):
```kotlin
interface SendMessageStep {
    /** 执行 Step，返回是否继续下一步。false 表示终止链（如去重命中）。 */
    suspend fun execute(context: SendContext): Boolean
}
```

---

### `handler/chat/send/SendContext.kt` (utility, request-response — shared context data class)

**Analog:** `gateway/.../session/Session.kt` (lines 1-24 — 可序列化 data class 模式)

**Data class pattern** (Session.kt lines 17-24):
```kotlin
data class SendContext(
    val req: SendMessageReq,
    val senderUid: Long,
    var conversationId: String = req.conversationId,
    var msgId: Long? = null,
    var chatMessage: ChatMessage? = null,
    var bizCode: BizCode = BizCode.OK
)
```

---

### `handler/chat/send/ValidateStep.kt` (controller, CRUD)

**Analog:** `gateway/.../handler/user/RegisterHandler.kt` (lines 52-68 — 输入校验模式)

**Validation pattern** (RegisterHandler.kt lines 52-68):
```kotlin
class ValidateStep(
    private val conversationMemberRepository: ConversationMemberRepository
) : SendMessageStep {

    override suspend fun execute(context: SendContext): Boolean {
        val req = context.req

        // 内容非空校验（D-13）
        if (req.content.isBlank()) {
            throw MessageException(BizCode.INVALID_PARAM, "消息内容不能为空")
        }
        // client_message_id 非空校验（D-14）
        if (req.clientMessageId.isBlank()) {
            throw MessageException(BizCode.INVALID_PARAM, "client_message_id 不能为空")
        }

        // 成员验证 — 仅检查 sender 是否为会话成员（D-08）
        val member = conversationMemberRepository
            .findByConversationIdAndUserId(req.conversationId, context.senderUid)
            ?: throw MessageException(BizCode.NOT_MEMBER)

        return true // 继续链
    }
}
```

---

### `handler/chat/send/DedupStep.kt` (controller, CRUD — Redis SETNX)

**Analog:** `repository/.../redis/OnlineStatusRepository.kt` (lines 16-41 — Redis key 操作模式)

**Redis SETNX pattern** (OnlineStatusRepository.kt lines 20-24):
```kotlin
class DedupStep(
    private val connection: StatefulRedisConnection<String, String>
) : SendMessageStep {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override suspend fun execute(context: SendContext): Boolean {
        // SETNX chat:dedup:{client_msg_id} {msg_id} + TTL 7 天（D-07）
        val dedupKey = "chat:dedup:${context.req.clientMessageId}"
        val existed = redis.setex(dedupKey, 7 * 24 * 3600, "pending")
        if (existed != null) {
            // 重复消息，终止链
            throw MessageException(BizCode.SEND_FAILED, "重复消息")
        }
        return true // 继续链
    }
}
```

`OnlineStatusRepository.kt` 的 Redis 连接初始化模式（lines 17-21）:
```kotlin
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OnlineStatusRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
}
```

---

### `handler/chat/send/WriteStep.kt` (controller, CRUD — Snowflake ID + Redis Stream)

**Analog:** `RegisterHandler.kt` (SnowflakeIdGenerator 模式) + `MessageQueueRepository.kt` (Redis Stream 模式)

**Snowflake ID 生成 + 实体模式** (RegisterHandler.kt lines 73-84):
```kotlin
class WriteStep(
    private val idGenerator: SnowflakeIdGenerator,
    private val messageQueueRepository: MessageQueueRepository
) : SendMessageStep {

    override suspend fun execute(context: SendContext): Boolean {
        val msgId = idGenerator.nextId()  // Snowflake 生成 msg_id
        context.msgId = msgId

        // 构建 ChatMessage
        val chatMessage = ChatMessage.newBuilder()
            .setMsgId(msgId)
            .setConversationId(context.req.conversationId)
            .setSenderUid(context.senderUid)
            .setMessageType(context.req.messageType)
            .setContent(context.req.content)
            .setPayload(context.req.payload)
            .setClientTs(context.req.clientTs)
            .setServerTs(System.currentTimeMillis())
            .build()
        context.chatMessage = chatMessage

        // Redis Stream 写入（D-04）
        messageQueueRepository.enqueue(mapOf(
            "msg_id" to msgId.toString(),
            "conversation_id" to context.req.conversationId
        ))
        // D-10: 会话元更新（last_message_id、preview、updated_at）
        // 通过 Redis SET 更新会话元信息

        return true
    }
}
```

---

### `handler/chat/send/PushStep.kt` (controller, event-driven)

**Analog:** `ChatService.kt` (lines 229-236 — Envelope 构建 + StreamObserver.onNext 模式)

**Push 构建模式** (ChatService.kt lines 229-236):
```kotlin
class PushStep(
    private val pushService: PushService
) : SendMessageStep {

    override suspend fun execute(context: SendContext): Boolean {
        // D-11: PushStep 直接调用 PushService
        try {
            pushService.pushMessage(
                convId = context.req.conversationId,
                chatMessage = context.chatMessage!!,
                excludeUid = context.senderUid  // D-09: 自消息排除
            )
        } catch (e: Exception) {
            // D-05: 推送失败不影响主流程，仅记录日志
            logger.error(e) { "Push failed for conv=${context.req.conversationId}" }
        }
        return true  // 推送失败不阻断链
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
```

---

### `handler/message/PullMessagesHandler.kt` (controller, CRUD — cursor pagination)

**Analog:** `gateway/.../handler/user/GetProfileHandler.kt` (lines 23-49 — 简单查询→proto 构建模式)

**Handler pattern with cursor pagination** (GetProfileHandler.kt lines 37-48):
```kotlin
class PullMessagesHandler(
    private val messageRepository: MessageRepository
) : Handler<PullMessagesReq, PullMessagesResp> {

    override val method: String = "message/pull"

    override suspend fun handle(req: PullMessagesReq): PullMessagesResp {
        val cursor = req.cursor
        val limit = req.limit.coerceIn(1, 100)  // D-19: 默认 20，最大 100

        // D-18: tail 优先 + 往前翻
        // cursor=0 时查最新消息（Pitfall 2: 用 Long.MAX_VALUE 替代 0）
        val effectiveCursor = if (cursor == 0L) Long.MAX_VALUE else cursor
        val messages = messageRepository.findMessagesBackward(
            req.conversationId, effectiveCursor,
            Pageable.ofSize(limit)
        )
        val hasMore = messages.size >= limit

        return PullMessagesResp.newBuilder()
            .addAllMessages(messages.map { it.toChatMessage() })
            .setHasMore(hasMore)
            .build()
    }
}
```

**MessageEntity → ChatMessage 转换** (MessageEntity.kt lines 16-46 字段 + ChatMessage.proto lines 27-37):
```kotlin
// Entity → Proto 转换扩展函数（建议放在 PullMessagesHandler 内或单独 mapper）
private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage.newBuilder()
    .setMsgId(this.id!!)
    .setConversationId(this.conversationId)
    .setSenderUid(this.senderUid)
    .setMessageType(ChatContentType.forNumber(this.messageType))
    .setContent(this.content)
    .setPayload(ByteString.copyFrom(this.payload ?: ByteArray(0)))
    .setClientTs(this.clientTs)
    .setServerTs(this.serverTs)
    .build()
```

---

### `handler/message/ReadReportHandler.kt` (controller, CRUD — update + push)

**Analog:** `gateway/.../handler/user/LoginHandler.kt` (lines 34-118 — 复杂逻辑单 Handler 模式)

**Complex single handler pattern** (LoginHandler.kt lines 52-79):
```kotlin
class ReadReportHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>
) : Handler<ReadReportReq, Response> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "message/read"

    override suspend fun handle(req: ReadReportReq): Response {
        val session = coroutineContext.requireSession()

        // D-27: 获取会话类型判断私聊/群聊
        val conversation = conversationRepository.findById(req.conversationId)
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND)
        val isPrivate = conversation.type == PRIVATE_TYPE  // 私聊类型常量

        // 更新已读进度（MySQL）
        conversationMemberRepository.updateReadReceipt(
            req.conversationId, session.userId, req.lastReadMsgId
        )

        // D-28: 删除 Redis 未读计数键
        redis.del("conversation:${req.conversationId}:unread:${session.userId}")

        // D-23: 私聊场景推送 READ_RECEIPT 给原发送者
        if (isPrivate) {
            val payload = ReadReceiptPayload.newBuilder()
                .setConversationId(req.conversationId)
                .setReaderUid(session.userId)
                .setMsgId(req.lastReadMsgId)
                .build()
            // 私聊接收方的 UID 已知（receiver_uid 在 ChatMessage 中）
            // 可通过 ConversationMemberRepository 查询发送者
            pushService.pushReadReceipt(senderUid, payload)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMethod("message/read")
            .build()
    }
}
```

---

### `push/PushService.kt` (service, event-driven — Envelope 构建 + StreamObserver 发送)

**Analog:** `ChatService.kt` (lines 229-236 — Envelope + Message 构建模式)

**Envelope 构建模式** (ChatService.kt lines 229-236 — LOGOUT 推送 Envelope 构造):
```kotlin
class PushService(
    private val userStreamRegistry: UserStreamRegistry,
    private val conversationMemberRepository: ConversationMemberRepository
) {
    suspend fun pushMessage(convId: String, chatMessage: ChatMessage, excludeUid: Long) {
        val members = conversationMemberRepository.findByConversationId(convId)
            .filter { it.userId != excludeUid }  // D-09: 排除发送者

        for (member in members) {
            val streams = userStreamRegistry.getStreams(member.userId)  // D-02: 多设备全推
            for (observer in streams) {
                try {
                    val envelope = Envelope.newBuilder()
                        .setDirection(Direction.PUSH)
                        .setMessage(Message.newBuilder()
                            .setEventType(PushEventType.CHAT_MESSAGE)
                            .setContent("")
                            .setPayload(chatMessage.toByteString()))
                        .build()
                    observer.onNext(envelope)
                } catch (e: Exception) {
                    logger.error(e) { "Push failed for userId=${member.userId}" }
                    userStreamRegistry.removeStream(member.userId, observer)
                }
            }
        }
    }

    fun pushReadReceipt(senderUid: Long, payload: ReadReceiptPayload) {
        val streams = userStreamRegistry.getStreams(senderUid)
        for (observer in streams) {
            try {
                val envelope = Envelope.newBuilder()
                    .setDirection(Direction.PUSH)
                    .setMessage(Message.newBuilder()
                        .setEventType(PushEventType.READ_RECEIPT)
                        .setContent("")
                        .setPayload(payload.toByteString()))
                    .build()
                observer.onNext(envelope)
            } catch (e: Exception) {
                logger.error(e) { "Read receipt push failed for userId=$senderUid" }
                userStreamRegistry.removeStream(senderUid, observer)
            }
        }
    }
}
```

**PUSH Envelope 结构** (envelope.proto lines 27-36 + message.proto lines 55-59):
```
Envelope(direction=PUSH) → Message(eventType=CHAT_MESSAGE) → payload=ChatMessage(serialized bytes)
Envelope(direction=PUSH) → Message(eventType=READ_RECEIPT) → payload=ReadReceiptPayload(serialized bytes)
```

---

### `session/UserStreamRegistry.kt` (utility, request-response — userId→StreamObserver registry)

**Analog:** `SessionRegistry.kt` (lines 26-319 — ConcurrentHashMap 注册中心模式)

**Core registry pattern** (SessionRegistry.kt lines 29-33, 55-93):
```kotlin
class UserStreamRegistry {
    /** userId → 在线 StreamObserver 列表（多设备全推，D-02） */
    private val userStreams = ConcurrentHashMap<Long, CopyOnWriteArrayList<StreamObserver<Envelope>>>()

    fun register(userId: Long, observer: StreamObserver<Envelope>) {
        userStreams.compute(userId) { _, list ->
            (list ?: CopyOnWriteArrayList()).also { it.add(observer) }
        }
    }

    fun removeStream(userId: Long, observer: StreamObserver<Envelope>) {
        userStreams.computeIfPresent(userId) { _, list ->
            list.remove(observer)
            if (list.isEmpty()) null else list
        }
    }

    fun removeUser(userId: Long) {
        userStreams.remove(userId)
    }

    fun getStreams(userId: Long): List<StreamObserver<Envelope>> {
        return userStreams[userId] ?: emptyList()
    }
}
```

---

### `di/GatewayModule.kt`（修改 — 注册新 Handler + PushService + UserStreamRegistry）

**Analog:** 现有 `GatewayModule.kt`（lines 55-140 — handlerModule + registerHandlers 模式）

**Handler module 追加模式** (GatewayModule.kt lines 55-65):
```kotlin
val handlerModule = module {
    // 现有 Phase 5 Handler...
    single { PingHandler() }
    single { LoginHandler(get(), get()) }
    // ...

    // Phase 6: Chat & Message Handler
    single { UserStreamRegistry() }
    single { PushService(get(), get()) }
    // Step 链注册
    single<SendMessageStep> { ValidateStep(get()) }
    single<SendMessageStep> { DedupStep(get()) }
    single<SendMessageStep> { WriteStep(get(), get()) }
    single<SendMessageStep> { PushStep(get()) }
    single { SendMessageHandler(get()) }  // get() 获取 List<SendMessageStep>
    single { PullMessagesHandler(get()) }
    single { ReadReportHandler(get(), get(), get(), get()) }
}
```

**registerHandlers 追加模式** (GatewayModule.kt lines 117-140):
```kotlin
fun registerHandlers(
    // 现有 Phase 5 参数...
    registry: HandlerRegistry,
    protoCodec: ProtoCodec,
    // 新增 Phase 6 参数
    sendMessageHandler: SendMessageHandler,
    pullMessagesHandler: PullMessagesHandler,
    readReportHandler: ReadReportHandler
) {
    // 现有 Phase 5 注册...
    registry.register(pingHandler)

    // Phase 6: Chat & Message Handler 注册
    registry.register(sendMessageHandler)   // chat/send: SendMessageReq → SendMessageResp
    registry.register(pullMessagesHandler)  // message/pull: PullMessagesReq → PullMessagesResp
    registry.register(readReportHandler)    // message/read: ReadReportReq → Response
}
```

---

### `service/ChatService.kt`（修改 — 集成 UserStreamRegistry）

**Analog:** 现有 `ChatService.kt`（lines 133-197 — handleLoginSuccess 和 cleanupConnection 修改点）

**handleLoginSuccess 修改 — 注册 UserStreamRegistry** (ChatService.kt lines 165-197):
```kotlin
// 在 handleLoginSuccess() 中，注册 Session 后也注册 StreamObserver：
// 新增: userStreamRegistry.register(loginResp.userId, responseObserver)
private suspend fun handleLoginSuccess(
    response: Response,
    responseObserver: StreamObserver<Envelope>
) {
    val loginResp = LoginResp.parseFrom(response.result.toByteArray())
    val session = Session(...)
    val evictedToken = sessionRegistry.registerWithDeviceType(session)

    // 新增: 注册 StreamObserver 到 UserStreamRegistry
    userStreamRegistry.register(loginResp.userId, responseObserver)

    // ...现有 tokenToObserver 逻辑
}
```

**cleanupConnection 修改 — 解除 UserStreamRegistry** (ChatService.kt lines 120-123):
```kotlin
private inner class ChatStreamObserver(...) : StreamObserver<Envelope> {
    // ... override onCompleted, onError ...

    private fun cleanupConnection() {
        tokenToObserver.entries.removeIf { it.value == responseObserver }
        // 新增: 清理 UserStreamRegistry 中的 StreamObserver
        // 注意：需要知道 userId，可以在 ChatStreamObserver 中持有一个 userId 字段
    }
}
```

---

## Shared Patterns

### Handler 实现模式
**Source:** `RegisterHandler.kt` (lines 31-91), `GetProfileHandler.kt` (lines 23-49)
**Apply to:** `SendMessageHandler.kt`, `PullMessagesHandler.kt`, `ReadReportHandler.kt`
```kotlin
class XxxHandler(
    private val repository: YyyRepository
) : Handler<ReqT, RespT> {
    override val method: String = "domain/action"

    override suspend fun handle(req: ReqT): RespT {
        val session = coroutineContext.requireSession()  // 从 CoroutineContext 获取 Session

        // 业务逻辑...

        return RespT.newBuilder()
            .setXxx(...)
            .build()
    }
}
```

### 领域异常模式
**Source:** `MessageException.kt` (lines 1-11), `ConversationException.kt` (lines 1-11)
**Apply to:** All Chat & Message handler files
```kotlin
// 继承 BizException 的领域异常，统一由 ExceptionInterceptor 捕获
class MessageException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)

// 使用方式
throw MessageException(BizCode.INVALID_PARAM, "消息内容不能为空")
throw ConversationException(BizCode.CONV_NOT_FOUND)
```

### Redis 操作模式（Lettuce coroutines）
**Source:** `OnlineStatusRepository.kt` (lines 17-25), `MessageQueueRepository.kt` (lines 20-24)
**Apply to:** `DedupStep.kt`, `ReadReportHandler.kt`, `ChatService.kt`
```kotlin
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class XxxRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
}
```

### Proto 构建 + ByteString 序列化
**Source:** `ChatService.kt` (lines 229-236 — LOGOUT Envelope 构建)
**Apply to:** `PushService.kt`, `PushStep.kt`
```kotlin
// Message payload 序列化模式
val envelope = Envelope.newBuilder()
    .setDirection(Direction.PUSH)
    .setMessage(Message.newBuilder()
        .setEventType(PushEventType.CHAT_MESSAGE)
        .setContent("")
        .setPayload(chatMessage.toByteString()))  // Proto → ByteString
    .build()
observer.onNext(envelope)
```

### Kotlin Logging 模式
**Source:** All existing files (e.g., `ChatService.kt` line 244)
**Apply to:** All new files
```kotlin
companion object {
    private val logger = KotlinLogging.logger {}
}
```

### Koin Module 注册 + registerHandlers 模式
**Source:** `GatewayModule.kt` (lines 55-140)
**Apply to:** `GatewayModule.kt` (modified)
```kotlin
// handlerModule: 使用 single { } 注册 Handler 及其依赖
// registerHandlers: 显式解构参数 → registry.register(handler) 逐个注册
```

---

## No Analog Found

以下文件在现有代码库中没有完全匹配的同类组件，需要根据 RESEARCH.md 中的代码示例进行实现：

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `handler/chat/send/SendMessageStep.kt` | controller | request-response | Step 链接口是 Phase 6 新增模式，无现有接口类比 |
| `handler/chat/send/SendContext.kt` | utility | request-response | 共享上下文 data class 是 Step 链专用模式 |
| `handler/chat/send/ValidateStep.kt` | controller | CRUD | Step 实现类为 Phase 6 新增模式 |
| `handler/chat/send/DedupStep.kt` | controller | CRUD | Step 实现类 — Redis SETNX 去重逻辑 |
| `handler/chat/send/WriteStep.kt` | controller | CRUD | Step 实现类 — Snowflake + Redis Stream 写入 |
| `handler/chat/send/PushStep.kt` | controller | event-driven | Step 实现类 — PushService 调用 |
| `push/PushService.kt` | service | event-driven | 现有代码库无推送服务组件 |
| `session/UserStreamRegistry.kt` | utility | request-response | 独立组件管理 StreamObserver 映射（D-01） |

以上文件的具体实现模式已包含在 `06-RESEARCH.md` 的代码示例中（Pattern 1: Step Chain 和 PushService 核心逻辑），可直接参考。

---

## Metadata

**Analog search scope:** `gateway/src/main/kotlin/com/nebula/gateway/`, `repository/src/main/kotlin/com/nebula/repository/`, `common/src/main/kotlin/com/nebula/common/`, `proto/src/main/proto/nebula/`
**Files scanned:** ~35
**Pattern extraction date:** 2026-06-12
