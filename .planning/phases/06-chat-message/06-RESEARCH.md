# Phase 6: Chat & Message — Research

**Researched:** 2026-06-12
**Domain:** Real-time message sending, fan-out delivery, cursor-paginated pull, read receipt
**Confidence:** HIGH

## Summary

Phase 6 implements the core real-time messaging flow of the Nebula Chat Server. It covers 4 business requirements split across 3 API entry points: `chat/send` (message validation + dedup + fan-out push), `message/pull` (cursor-based pagination from MySQL), and `message/read` (read receipt + unread count reset). The phase introduces two new architectural components — **UserStreamRegistry** (userId→StreamObserver mapping for push) and **PushService** (Envelope construction and delivery) — plus a **Step chain pattern** (`SendMessageStep` interface with 4 steps orchestrated by `SendMessageHandler`).

The codebase already provides all the infrastructure: Handler interface, Dispatcher pipeline, Koin DI, gRPC ChatService, SessionRegistry with userIdIndex, MessageRepository with cursor pagination, MessageQueueRepository (Redis Stream), ConversationMemberRepository, and SnowflakeIdGenerator. Phase 6 is about wiring these existing capabilities together with new orchestration logic.

**Primary recommendation:** Use the existing `Handler<ReqT,RespT>` pattern for PullMessagesHandler and ReadReportHandler. Introduce the `SendMessageStep` interface + `SendContext` for chat/send Step chain. Create `UserStreamRegistry` in the gateway module and `PushService` in the gateway module (it needs `StreamObserver<Envelope>` — a gRPC type only available in gateway). Service module currently lacks the gRPC dependency.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### 在线推送架构
- **D-01:** **UserStreamRegistry** — 独立组件管理 userId→StreamObserver 映射，与 ChatService 解耦。ChatService 仅负责 gRPC 流管理。
- **D-02:** **多设备全推** — 消息推送给用户的所有在线设备。
- **D-03:** **推送完整 ChatMessage** — 含 content、message_type、payload（图片为 OSS URL，无二进制大包体）。不同 messageType 统一推送，客户端按类型渲染。

#### 消息扇出编排（chat/send）
- **D-04:** **ACK 时机** — 写入 Redis Stream 后立即返回 SendMessageResp（含 msg_id + server_ts）。推送和未读计数异步执行。
- **D-05:** **推送容错** — 推送失败不影响消息落盘和未读计数。推送是秒级可重试的优化行为。
- **D-06:** **未读计数** — `INCR conversation:{conv_id}:unread:{uid}` 逐个成员递增。成员数通常几十人以内，性能可承受。
- **D-07:** **消息去重** — `SETNX chat:dedup:{client_msg_id} {msg_id}` + TTL 7 天。重复请求忽略。
- **D-08:** **成员验证** — 仅检查 sender_uid 是否为 conversation_members 成员（SISMEMBER）。好友/黑名单验证交由 Phase 8。
- **D-09:** **自消息排除** — 推送时排除 sender_uid。发送者已从 SendMessageResp 获得确认。
- **D-10:** **会话元更新** — SendHandler 同步更新 Redis 中会话的 last_message_id、last_message_preview、last_updated_at。
- **D-11:** **推送触发** — Handler（PushStep）直接调用 PushService，不走 ChatService 拦截。零额外 I/O（消息内容已在 Handler 内存）。
- **D-12:** **逐个单推** — 不批量推送。50 人以下群聊单推耗时 ~5ms 可接受。
- **D-13:** **Step 链模式** — SendMessageHandler 不承担全部逻辑，拆分为 SendMessageStep 接口：
  - `ValidateStep` — 成员验证 + 内容非空 + client_message_id 非空
  - `DedupStep` — Redis SETNX 去重
  - `WriteStep` — Snowflake 生成 msg_id → Redis Stream 写入 → 更新会话元
  - `PushStep` — 调用 PushService 推送给在线成员（排除发送者）
  
  各 Step 通过 `SendContext` 传递共享状态。编排顺序在注册处显式声明。
- **D-14:** **client_message_id 容错** — 强制客户端传入。空值直接返回 INVALID_PARAM，不做服务端自动生成。
- **D-15:** **Push Envelope 构建** — PushService 内部构建完整 Envelope(Direction.PUSH, Message, ChatMessage)。对外暴露 `pushMessage(convId, msg, excludeUid)` 方法。
- **D-16:** **离线成员处理** — Phase 6 仅递增未读计数。离线消息恢复由 Phase 3 的 Redis Stream PEL 机制负责。

#### 消息拉取策略（message/pull）
- **D-17:** **数据源** — MySQL 游标查询，利用 `idx_conv_messages(conversation_id, id)` 索引。
- **D-18:** **翻页方向** — tail 优先 + 往前翻（cursor=0 → 最新 limit 条，cursor>0 → 比 cursor 更旧的 limit 条）。
- **D-19:** **分页大小** — 默认 20 条，最大 100 条。
- **D-20:** **direction 字段保留** — PullMessagesReq.direction 字段保留不删，Phase 10 间隙检测时使用 forward 方向。
- **D-21:** **不存冗余用户信息** — ChatMessage 不存 sender_username/sender_avatar。客户端通过 user/batchGet 按 sender_uid 批量获取。
- **D-22:** **ChatMessage proto 变更** — 移除 sender_username(4)、sender_avatar(5)，新增 receiver_uid(11)。私聊场景填充接收方 UID，群聊填 0。

#### 已读回执推送（message/read）
- **D-23:** **推送范围** — 私聊场景推送 READ_RECEIPT 给原发送者。群聊不推。
- **D-24:** **已读回执 Payload** — `ReadReceiptPayload{conversation_id, reader_uid, msg_id}`，不含时间戳。
- **D-25:** **ReadReceiptProto 已定义** — 见 `message.proto`，对应 PushEventType.READ_RECEIPT。
- **D-26:** **单个 Handler** — ReadReportHandler 内完成全部逻辑（不拆 Step 链）。
- **D-27:** **会话类型判定** — 通过 `ConversationEntity.type` 字段（已存在）判断私聊/群聊。
- **D-28:** **未读计数重置** — `DEL conversation:{conv_id}:unread:{uid}` 删除 Redis 键。接受极低概率的竞态（DEL 后新消息 INCR 覆盖），下次新消息自动修复。

### Claude's Discretion
- Step 链接口和 PushService 的具体包路径由规划者决定
- PushService.pushMessage() 的签名细节（是否挂起、超时等）由实现者决定
- `client_message_id` 去重 TTL 的 7 天值可在实现时根据实际调整

### Deferred Ideas (OUT OF SCOPE)
- **Segment 富文本结构** — 用户期望消息体由 `[text_segment + file_url_segment + text_segment]` 组成而非单一 `content + payload`。需定义 Segment proto 和重构消息结构，属于后续迭代。
- **群聊已读详情** — 群聊场景展示"已读人数/N"，不属于 Phase 6 范围。
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| BIZ-CHAT-01 | chat/send validates message, client_message_id dedup, fanouts to conversation members | Step chain pattern (Validate → Dedup → Write → Push). Redis SETNX for dedup (D-07), ConversationMemberRepository for membership check, MessageQueueRepository for Redis Stream write, PushService for fan-out. SnowflakeIdGenerator for msg_id. |
| BIZ-CHAT-02 | Fan-out: online members push, offline store for pull-on-reconnect | UserStreamRegistry (in-memory) + OnlineStatusRepository (Redis) determine online users. PushService builds Envelope(Direction.PUSH, CHAT_MESSAGE, ChatMessage) and sends via StreamObserver. Offline: unread count INCR via Redis. Phase 3 PEL handles reconnect recovery (D-16). |
| BIZ-MSG-01 | message/pull pulls messages by conversation with cursor-based pagination | MessageRepository.findMessagesBackward() with cursor and limit. Tail-first: cursor=0 means "no cursor constraint" (latest messages). Direction field unused in Phase 6 (D-20). result mapped to ChatMessage proto. |
| BIZ-MSG-02 | message/read reports read status, updates unread count and last_read_message_id | ConversationMemberRepository.updateReadReceipt() updates MySQL. Redis DEL unread key (D-28). ConversationEntity.type determines private/group. Private: PushService sends READ_RECEIPT to sender. |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Message validation (content, client_id) | Handler (gateway) | — | No I/O — pure request field checks in ValidateStep |
| Membership check | Repository (repository) | Handler (gateway) | ConversationMemberRepository SISMEMBER check, invoked by ValidateStep |
| Message dedup | Redis (repository) | Handler (gateway) | Redis SETNX via a new Redis key operation; DedupStep wraps it |
| Message persistence | Redis Stream + MySQL (repository) | — | MessageQueueRepository enqueue to Redis Stream; async batch flush to MySQL |
| Snowflake msg_id | Infrastructure (common) | — | SnowflakeIdGenerator already available in common module |
| Fan-out push | PushService + UserStreamRegistry (gateway) | — | PushService builds Envelope, sends via StreamObserver from UserStreamRegistry |
| Unread count | Redis (repository) | — | INCR/DEL key operations via a new repository utility |
| Message pull (cursor) | Repository (repository) | Handler (gateway) | MessageRepository.findMessagesBackward() — MySQL query |
| Read receipt | Repository (repository) | Handler (gateway) | ConversationMemberRepository.updateReadReceipt() in MySQL |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Lettuce coroutines | (existing in repo) | Redis operations (SETNX, INCR, DEL) | Existing pattern in SessionRepository, PrivacyRepository, OnlineStatusRepository |
| Protobuf Java | (existing in proto) | ChatMessage serialization for push payload | Proto defined in message.proto, generated by Gradle protobuf plugin |
| SnowflakeIdGenerator | (existing in common) | Message ID generation | Existing, used by RegisterHandler for user ID |
| JPA + Spring Data | (existing in repo) | MySQL cursor-based pagination | MessageRepository already has findMessagesBackward/Forward |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx.coroutines | (existing) | Suspend functions for async operations | All Handler.handle() and Step.run() methods |
| Koin core | (existing) | DI for Handler, PushService, UserStreamRegistry | All new components registered via Koin |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| UserStreamRegistry (in-memory) | Redis pub/sub across nodes | Phase 6 is single-node. Redis pub/sub adds latency and complexity not yet needed. |
| Step chain pattern | Single monolithic handler | User chose Step chain pattern (D-13) for separation of concerns. |
| MySQL for unread count | Redis INCR | User chose Redis for performance (D-06). |

**Installation:**
```bash
# No new external dependencies needed — everything is already in the project.
```

**Version verification:** All libraries are already declared in the project's `libs.versions.toml`. No new dependencies required.

## Package Legitimacy Audit

No new external packages are introduced in Phase 6. All dependencies (Lettuce, Protobuf, Koin, kotlinx.coroutines, etc.) are already part of the project and have been vetted in prior phases. No audit needed.

## Architecture Patterns

### System Architecture Diagram

```
Client A (sender)                 Client B (receiver)
     |                                |
     | Envelope(REQUEST, chat/send)   |
     |------------------------------->|
     |                                |
     v                                |
  ChatService.onNext()                |
     |                                |
     v                                |
  Dispatcher.dispatch()               |
     |                                |
     v                                |
  Interceptor Pipeline (Auth/Log/RateLimit/Exception)
     |                                |
     v                                |
  SendMessageHandler                  |
     |                                |
     ├── ValidateStep                 |
     │   └── ConversationMemberRepo   |
     ├── DedupStep                    |
     │   └── Redis SETNX              |
     ├── WriteStep                    |
     │   ├── SnowflakeIdGenerator     |
     │   ├── MessageQueueRepository   |
     │   └── 更新会话元 (Redis)       |
     │                                |
     ├── [返回 SendMessageResp]        |
     │                                |
     └── PushStep (async best-effort) |
         └── PushService              |
             ├── UserStreamRegistry   |
             └── 逐个单推 StreamObserver
                     |
                     v
              Envelope(PUSH, CHAT_MESSAGE, ChatMessage)
                     |
                     v
              Client B.onNext()
```

```
Client B (reader)              MySQL               Client A (original sender)
     |                           |                        |
     | Envelope(REQUEST,         |                        |
     |   message/read)           |                        |
     |-------------------------->|                        |
     |                           |                        |
     v                           |                        |
  ReadReportHandler              |                        |
     |                           |                        |
     ├── ConversationEntity.type)|                        |
     ├── ConvMemberRepo          |                        |
     │   .updateReadReceipt() -->|                        |
     │   └── MySQL UPDATE        |                        |
     ├── Redis DEL unread key    |                        |
     │                           |                        |
     └── [私聊] PushService       |                        |
         └── UserStreamRegistry  |                        |
                  |              |                        |
                  v              |                        |
         Envelope(PUSH, READ_RECEIPT, ReadReceiptPayload)
                  |              |                        |
                  v              |                        |
         Client A.onNext() <-----|------------------------|
```

### Recommended Project Structure
```
gateway/src/main/kotlin/com/nebula/gateway/
├── handler/
│   ├── chat/
│   │   └── send/
│   │       ├── SendMessageHandler.kt    # Step 链编排（method = "chat/send"）
│   │       ├── SendMessageStep.kt       # 接口定义
│   │       ├── SendContext.kt           # 共享上下文（convId, req, msgId, chatMessage 等）
│   │       ├── ValidateStep.kt          # 成员验证 + 内容非空
│   │       ├── DedupStep.kt             # Redis SETNX 去重
│   │       ├── WriteStep.kt             # Snowflake ID → Redis Stream → 会话元更新
│   │       └── PushStep.kt              # PushService 调用
│   └── message/
│       ├── PullMessagesHandler.kt       # 游标分页拉取（method = "message/pull"）
│       └── ReadReportHandler.kt         # 已读回执（method = "message/read"）
├── push/
│   └── PushService.kt                  # 推送服务：Envelope 构建 + 通过 StreamObserver 发送
├── session/
│   └── UserStreamRegistry.kt           # userId → List<StreamObserver<Envelope>> 映射
└── di/
    └── GatewayModule.kt                # 追加 handlerModule 和 registerHandlers()

service/src/main/kotlin/com/nebula/service/
└── (暂时保持空 — PushService 因需 StreamObserver 放在 gateway 模块)
```

### Pattern 1: Step Chain (chat/send)
**What:** 将 `chat/send` 拆分为 4 个独立 Step，通过 `SendContext` 传递共享状态。`SendMessageHandler` 负责编排执行顺序。
**When to use:** 仅 `chat/send` 使用（D-13）。`message/read` 逻辑简单，单个 Handler 完成。
**Example:**
```kotlin
// SendMessageStep 接口定义
interface SendMessageStep {
    /** 执行 Step，返回是否继续下一步。false 表示终止链（如去重命中）。 */
    suspend fun execute(context: SendContext): Boolean
}

// SendContext 共享上下文
data class SendContext(
    var req: SendMessageReq,
    var senderUid: Long,
    var msgId: Long? = null,
    var chatMessage: ChatMessage? = null,
    var bizCode: BizCode = BizCode.OK
)

// SendMessageHandler 编排
class SendMessageHandler(
    private val steps: List<SendMessageStep>
) : Handler<SendMessageReq, SendMessageResp> {
    override val method: String = "chat/send"

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = coroutineContext.requireSession()
        val context = SendContext(req = req, senderUid = session.userId)
        for (step in steps) {
            if (!step.execute(context)) break  // 终止链
        }
        // 根据 context.bizCode 和 context.msgId 构建响应
        if (context.bizCode != BizCode.OK) throw BizException(context.bizCode)
        return SendMessageResp.newBuilder()
            .setMsgId(context.msgId!!)
            .setServerTs(System.currentTimeMillis())
            .build()
    }
}
```

### Pattern 2: Handler Direct Pattern (message/pull, message/read)
**What:** 标准 `Handler<ReqT, RespT>` 实现，Handler 内直接完成全部逻辑。
**When to use:** `message/pull` 和 `message/read` 逻辑简单，不需要 Step 链。
**Example:**
```kotlin
class PullMessagesHandler(
    private val messageRepository: MessageRepository
) : Handler<PullMessagesReq, PullMessagesResp> {
    override val method: String = "message/pull"

    override suspend fun handle(req: PullMessagesReq): PullMessagesResp {
        val cursor = req.cursor
        val limit = req.limit.coerceIn(1, 100)
        // D-18: tail 优先 + 往前翻
        val messages = if (cursor == 0L) {
            // cursor=0: 先查 id > 0 的反向（实际是查最新 limit 条）
            // 使用 max_id 技巧: 查 id < Long.MAX_VALUE 取最新
            messageRepository.findMessagesBackward(
                req.conversationId, Long.MAX_VALUE,
                Pageable.ofSize(limit)
            )
        } else {
            messageRepository.findMessagesBackward(
                req.conversationId, cursor,
                Pageable.ofSize(limit)
            )
        }
        val hasMore = messages.size >= limit
        return PullMessagesResp.newBuilder()
            .addAllMessages(messages.map { it.toChatMessage() })
            .setHasMore(hasMore)
            .build()
    }
}
```

### Anti-Patterns to Avoid
- **Blocking push in WriteStep**: Push 必须在 Write 之后、独立执行。WriteStep 返回响应后 PushStep 可继续（但注意 Handler 已返回 — 考虑 PushStep 前置于 WriteStep，或 PushStep 在 SendMessageHandler 中异步启动）。
- **Shared mutable state in SendContext**: SendContext 是单线程 Step 链，不需要同步原语。

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Message ID generation | Auto-increment or UUID | SnowflakeIdGenerator | Snowflake IDs are time-ordered for cursor pagination, already in the project |
| Redis SETNX dedup | MySQL unique index check | `SETNX chat:dedup:{client_msg_id} {msg_id}` | Single Redis call vs. two-phase MySQL check-and-insert. D-07 choice. |
| Unread count | MySQL COUNT query | Redis INCR per-member | ~5ms for 50 members, vs. expensive COUNT query. D-06 choice. |
| Push delivery | Synchronous blocking delivery | Per-receiver StreamObserver.onNext() | Already async; each push is a non-blocking gRPC write |

**Key insight:** The project already provides all the infrastructure pieces. Phase 6 is about composition, not building from scratch.

## Common Pitfalls

### Pitfall 1: UserStreamRegistry Connection Leak
**What goes wrong:** When a gRPC stream disconnects (onCompleted/onError), the StreamObserver reference remains in UserStreamRegistry, causing stale push targets and memory leaks.
**Why it happens:** ChatService.ChatStreamObserver has `cleanupConnection()` that cleans tokenToObserver but doesn't know about UserStreamRegistry.
**How to avoid:** ChatService must call `UserStreamRegistry.removeUser(userId)` (or `removeStream(userId, observer)`) in `cleanupConnection()`. This requires ChatService to have a reference to UserStreamRegistry.
**Warning signs:** Logs showing push to non-existent streams; heap growth over time.

### Pitfall 2: cursor=0 Edge Case in Message Pull
**What goes wrong:** `cursor=0` is defined as "get latest messages", but `MessageRepository.findMessagesBackward(convId, cursor=0, ...)` generates `WHERE id < 0` which returns empty.
**Why it happens:** MySQL entity IDs (Snowflake) are always > 0. The query `id < 0` matches nothing.
**How to avoid:** Handler must handle cursor=0 as a special case: either bypass the cursor constraint (use `ORDER BY id DESC LIMIT limit` with no WHERE clause on id) or pass `Long.MAX_VALUE` as cursor to get latest messages. Adding a `findLatestMessages()` query method to MessageRepository is cleaner.
**Warning signs:** message/pull with cursor=0 returns empty list.

### Pitfall 3: Race Condition Between Read Receipt DEL and New Message INCR
**What goes wrong:** `ReadReportHandler` does `DEL conversation:{conv_id}:unread:{uid}` while a concurrent chat/send does `INCR` for the same key. The DEL wins, and the INCR value is lost, resulting in stale unread count (showing 1 when there should be 2+).
**Why it happens:** Atomicity gap between DEL and INCR across two different components.
**How to avoid:** This is an accepted tradeoff (D-28). The next new message will re-create the key and auto-correct the count. Document in a comment.
**Warning signs:** Occasional off-by-one unread counts after concurrent read+send.

### Pitfall 4: Push Exception Propagation Cancels Write Flow
**What goes wrong:** A `PushService.pushMessage()` call throws an exception that propagates up through PushStep, causing WriteStep's SendMessageResp to never be returned.
**Why it happens:** Without proper try-catch, any push failure (e.g., broken StreamObserver) bubbles up to SendMessageHandler.
**How to avoid:** PushStep must wrap push calls in try-catch, log errors, and NOT fail the chain. Push is best-effort (D-05). PushService should also catch per-receiver exceptions and continue to next receiver (D-12).
**Warning signs:** SendMessageResp delayed or returning error due to one offline receiver.

### Pitfall 5: Session Context Not Available in PushService
**What goes wrong:** `PushService.pushMessage()` is called from inside the Handler's coroutine context (which has SessionKey set by AuthInterceptor), but if PushService internally launches a new coroutine with `launch { }`, the Session is lost.
**Why it happens:** Session is passed via `CoroutineContext.Element`. New coroutines created with `launch`/`async` outside `coroutineScope` don't inherit the parent's context automatically.
**How to avoid:** PushService should not need Session — it operates on userId lists obtained from ConversationMemberRepository. If it must use coroutines, use `coroutineScope { }` to preserve context. If it needs Session for audit, pass it explicitly as a parameter.

### Pitfall 6: Step Chain Order — Push Before Response
**What goes wrong:** If PushStep runs last (after WriteStep returns response), the response is returned to client before any push is attempted. But if WriteStep returns and then PushStep fails silently, the sender gets ACK but no receiver received the message.
**Why it happens:** Step chain is synchronous — each step blocks the next. The natural order (Validate → Dedup → Write → Push) means push happens after response.
**How to avoid:** This is actually the **desired behavior** per D-04 (ACK after write, push async). The response goes to the sender; push is best-effort. Future Phase 10 (REL-01 ~ REL-04) adds delivery tracking. No action needed — document this as intentional.

## Code Examples

### Step Chain Setup (In SendMessageHandler or its companion)
```kotlin
// SendMessageHandler 编排验证
class SendMessageHandler(
    private val steps: List<SendMessageStep>
) : Handler<SendMessageReq, SendMessageResp> {
    override val method: String = "chat/send"

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = coroutineContext.requireSession()
        val context = SendContext(
            req = req,
            senderUid = session.userId,
            conversationId = req.conversationId
        )

        // 顺序执行 Step 链
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
            else -> throw BizException(context.bizCode)
        }
    }
}
```

### PushService Core Logic
```kotlin
class PushService(
    private val userStreamRegistry: UserStreamRegistry,
    private val conversationMemberRepository: ConversationMemberRepository
) {
    suspend fun pushMessage(
        convId: String,
        chatMessage: ChatMessage,
        excludeUid: Long
    ) {
        // 获取会话所有成员
        val members = conversationMemberRepository
            .findByConversationId(convId)
            .filter { it.userId != excludeUid }

        // 对每个在线成员逐个推送
        for (member in members) {
            val streams = userStreamRegistry.getStreams(member.userId)
            for (observer in streams) {
                try {
                    val envelope = buildMessageEnvelope(chatMessage)
                    observer.onNext(envelope)
                } catch (e: Exception) {
                    // D-05: 推送失败不影响主流程，仅记录日志
                    logger.error(e) { "Push failed for userId=${member.userId}" }
                    // 可选：移除失效的 StreamObserver
                    userStreamRegistry.removeStream(member.userId, observer)
                }
            }
        }
    }

    fun pushReadReceipt(
        senderUid: Long,
        payload: ReadReceiptPayload
    ) {
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

### UserStreamRegistry
```kotlin
class UserStreamRegistry {
    // userId → 在线 StreamObserver 列表（多设备全推，D-02）
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

### ChatService Integration with UserStreamRegistry
```kotlin
// 在 ChatService.handleLoginSuccess() 中，注册 Session 后也注册 StreamObserver：
// userStreamRegistry.register(loginResp.userId, responseObserver)

// 在 ChatService.cleanupConnection() 中，也解除注册：
// userStreamRegistry.removeUser(session.userId)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| (Phase 5) Handlers directly implement Handler | (Phase 6) Step chain for complex handlers | Phase 6 | Separates validation, dedup, write, push concerns. Only for chat/send. |
| ChatService login interception | Push triggered by Handler directly via PushService | Phase 6 | Zero extra I/O — message data already in memory. D-11. |

**Deprecated/outdated:**
- ChatMessage proto fields sender_username(4) and sender_avatar(5) are removed in Phase 6 proto definition. Project must regenerate protobuf before building.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | PushService must be in gateway module because it needs `StreamObserver<Envelope>` (gRPC type) and service module doesn't have gRPC dependency | Standard Stack | Low. Adds gRPC dep to service module instead. Gateway is the natural home since it already has ChatService. |

**No user confirmation needed** — PushService placement in gateway is consistent with existing architecture (ChatService is also in gateway).

## Open Questions

1. **PushStep execution timing** — Does the PushStep run synchronously in the Step chain (before response), or asynchronously after the response? D-04 says "写入 Redis Stream 后立即返回" (ACK before push), but D-13 Step chain order lists PushStep as the last step. If PushStep blocks the response, the sender waits for all push operations to fail/succeed. Recommendation: Run Validate → Dedup → Write (return response), then PushStep executes after response. This can be achieved by having SendMessageHandler return the response immediately after WriteStep, and launching PushStep as a fire-and-forget coroutine.

2. **SessionRegistry.userIdIndex vs UserStreamRegistry** — SessionRegistry already has `userIdIndex` mapping userId→tokens. Should UserStreamRegistry reuse this or maintain its own mapping? Recommendation: Maintain separate mapping. UserStreamRegistry maps userId→StreamObserver<Envelope> (one per device session); SessionRegistry maps userId→tokens (cross-node). They serve different purposes.

3. **DedupStep error handling** — What should DedupStep return when SETNX returns 0 (duplicate detected)? D-07 says "忽略" (ignore). Recommendation: Return a specific BizCode (e.g., `DUPLICATE_MESSAGE`) or simply return `false` to terminate the chain. Use a BizCode so the sender gets a meaningful response.

## Validation Architecture

> Skipped — `workflow.nyquist_validation` is explicitly set to `false` in `.planning/config.json`.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | Handled by Phase 5 AuthInterceptor. Phase 6 handlers require Session (via `coroutineContext.requireSession()`). |
| V5 Input Validation | yes | ValidateStep checks content non-empty, client_message_id non-null. PullMessagesHandler validates limit bounds. |
| V8 Data Protection | yes | ChatMessage payload may contain OSS URLs (no binary data per D-03). No PII in message content — user info fetched separately via user/batchGet. |

### Known Threat Patterns for Kotlin/gRPC Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Message content injection (XSS in payload) | Tampering | Client-side rendering concern; server passes through as-is. Future: content-type validation checks. |
| Push to wrong recipient | Elevation of Privilege | Membership check (ValidateStep) ensures sender is in conversation. PushService only pushes to verified members. |
| Dedup bypass via SETNX race | Spoofing | Single-key SETNX is atomic. No race condition. 7-day TTL limits blind replay window. |

## Sources

### Primary (HIGH confidence)
- `06-CONTEXT.md` — All 28 design decisions documented and locked
- `GatewayModule.kt` — Handler registration pattern (inline reified + registerHandlers function)
- `ChatService.kt` — gRPC bidirectional stream, tokenToObserver, login intercept
- `SessionRegistry.kt` — userIdIndex, token-to-session mapping pattern
- `MessageRepositoryImpl.kt` — Redis Stream enqueue + async batch flush pattern
- `MessageRepository.kt` — findMessagesBackward/findMessagesForward cursor pagination queries
- `ConversationMemberRepository.kt` — findByConversationIdAndUserId, updateReadReceipt, incrementUnreadCount
- `SnowflakeIdGenerator.kt` — ID generation for message IDs
- `MessageEntity.kt` — message entity with idx_conv_messages index
- `ConversationEntity.kt` — conversation entity with type field
- `proto/` — All proto definitions (chat.proto, message.proto, message_type.proto, envelope.proto)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — All libraries already in the project, patterns established in Phases 1~5
- Architecture: HIGH — Step chain pattern, UserStreamRegistry, PushService all have clear design decisions (D-01~D-28) and the existing codebase provides exact analogues
- Pitfalls: HIGH — Most pitfalls derive from known codebase patterns and explicit design decisions; cursor=0 edge case is the main new discovery
- Security: HIGH — Input validation is straightforward; push authorization is enforced by membership check

**Research date:** 2026-06-12
**Valid until:** 2026-07-12 (stable codebase patterns unlikely to change)
