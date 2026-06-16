# Phase 5: User & Authentication — Pattern Map

**Mapped:** 2026-06-12
**Files analyzed:** 18 (8 new handlers, 1 new repository, 1 new ChatService, 8 modified)
**Analogs found:** 16 / 18

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `gateway/.../handler/user/LoginHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | exact |
| `gateway/.../handler/user/RegisterHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | exact |
| `gateway/.../handler/user/SearchUserHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../handler/user/GetProfileHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../handler/user/BatchGetUserHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../handler/user/BatchGetStatusHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../handler/user/SetPrivacyHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../handler/user/GetPrivacyHandler.kt` | controller | request-response | `gateway/.../handler/PingHandler.kt` | role-match |
| `gateway/.../session/SessionRegistry.kt` | service | CRUD | EXISTS — enhance with device-type index | — |
| `repository/.../redis/PrivacyRepository.kt` | repository | CRUD | `repository/.../redis/OnlineStatusRepository.kt` | exact |
| `gateway/.../ChatService.kt` (new) | gateway | request-response | `Dispatcher.kt` dispatch flow | partial |
| `proto/nebula/user/user.proto` | config | — | EXISTS — add new messages | — |
| `repository/.../repository/UserRepository.kt` | repository | CRUD | EXISTS — add search methods | — |
| `gateway/.../interceptor/AuthInterceptor.kt` | middleware | request-response | EXISTS — enhance extractToken() | — |
| `gateway/.../interceptor/RateLimitInterceptor.kt` | middleware | request-response | EXISTS — enhance extractClientIp() | — |
| `gateway/.../di/GatewayModule.kt` | config | — | EXISTS — register new handlers | — |
| `server/.../NebulaServer.kt` | config | — | EXISTS — wire new handlers + ChatService | — |
| `repository/.../redis/SessionRepository.kt` | repository | CRUD | EXISTS — add saveRaw/findRaw | — |

## Pattern Assignments

### `gateway/.../handler/user/LoginHandler.kt` (controller, request-response)

**Analog:** `gateway/.../handler/PingHandler.kt` (lines 1-43)

**Imports pattern (lines 1-6):**
```kotlin
package com.nebula.gateway.handler.user

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
```

**Handler interface pattern (lines 19-36):**
```kotlin
/**
 * 泛型 Handler 接口，所有业务 Handler 必须实现此接口。
 *
 * Session 通过 CoroutineContext 隐式传递，Handler 内部通过 `coroutineContext.requireSession()` 获取。
 */
interface Handler<Req : Any, Resp : Any> {
    /** 当前 Handler 对应的 method 路由字符串，如 "system/ping"、"user/login" */
    val method: String

    /**
     * 处理业务请求。
     *
     * @param req 反序列化后的请求参数
     * @return 业务处理结果，由 Dispatcher 序列化为 bytes
     */
    suspend fun handle(req: Req): Resp
}
```

**Concrete handler pattern (PingHandler lines 22-43):**
```kotlin
class PingHandler : Handler<Request, Response> {
    override val method: String = "system/ping"

    override suspend fun handle(req: Request): Response {
        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("pong")
            .setMethod(method)
            .build()
    }
}
```

**LoginHandler should use proto-specific types instead of generic Request/Response:**
```kotlin
class LoginHandler(
    private val userRepository: UserRepository,
    private val sessionRegistry: SessionRegistry,
    private val idGenerator: SnowflakeIdGenerator
) : Handler<LoginReq, LoginResp> {
    override val method: String = "user/login"

    override suspend fun handle(req: LoginReq): LoginResp {
        // ... business logic ...
    }
}
```

**Session access pattern from CoroutineContext (SessionKey.kt lines 32-34):**
```kotlin
suspend fun CoroutineContext.requireSession(): Session {
    return this[SessionKey]?.session ?: throw BizException(BizCode.UNAUTHORIZED)
}
```

**Domain exception throwing pattern (UserException.kt lines 8-11):**
```kotlin
class UserException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)

// Usage:
throw UserException(BizCode.USER_NOT_FOUND)
throw UserException(BizCode.AUTH_FAILED)
```

---

### `gateway/.../handler/user/RegisterHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same exact pattern as LoginHandler above.

**Key pattern — proto types for user/register:** Uses the new `RegisterReq` / `RegisterResp` proto messages (to be added to user.proto). Follows same `Handler<ReqT, RespT>` interface.

**Additional pattern — bcrypt usage (from RESEARCH.md):**
```kotlin
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

val encoder = BCryptPasswordEncoder(12)  // cost 12 (D-03)

// Hash during registration:
val passwordHash: String = encoder.encode(req.password)

// Verify during login:
val matches: Boolean = encoder.matches(req.password, storedHash)
```

---

### `gateway/.../handler/user/SearchUserHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** `SearchUserReq` / `SearchUserResp` (existing proto definitions, need cursor/limit fields added).

---

### `gateway/.../handler/user/GetProfileHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** `GetProfileReq` / `GetProfileResp` (existing proto definitions).

**Session access for self-profile vs other's profile:**
```kotlin
val session = coroutineContext.requireSession()
// session.userId for self; req.uid for target user
```

---

### `gateway/.../handler/user/BatchGetUserHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** `BatchIdRequest` / `BatchGetUserResp` (existing proto definitions).

---

### `gateway/.../handler/user/BatchGetStatusHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** `BatchIdRequest` / `BatchGetStatusResp` (existing proto definitions).

**Privacy filter pattern (from RESEARCH.md):**
```kotlin
// D-10: skip users with hide_online_status=true
for (uid in req.uidsList) {
    if (privacyRepository.getHideOnlineStatus(uid)) continue
    val isOnline = onlineStatusRepository.isOnline(uid)
    builder.addStatuses(UserOnlineStatus.newBuilder()
        .setUid(uid)
        .setStatus(if (isOnline) 1 else 0)
        .build())
}
```

---

### `gateway/.../handler/user/SetPrivacyHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** `SetPrivacyReq` (no response needed, or generic Response) / `GetPrivacyResp` for get.

---

### `gateway/.../handler/user/GetPrivacyHandler.kt` (controller, request-response)

**Analog:** `PingHandler.kt` — same Handler interface pattern.

**Proto types:** No request needed (userId from session) / `GetPrivacyResp`.

---

### `gateway/.../session/SessionRegistry.kt` (service, CRUD) — ENHANCE

**Analog:** Current `SessionRegistry.kt` (lines 1-205) — all code patterns extracted from the existing file.

**Existing L1/L2 cache pattern (lines 29-43):**
```kotlin
class SessionRegistry(
    private val sessionRepository: SessionRepository
) {
    /** L1 本地缓存 — token → Session 映射 */
    private val localCache = ConcurrentHashMap<String, Session>()
    /** userId → token 集合索引，用于多设备管理（D-18） */
    private val userIdIndex = ConcurrentHashMap<Long, MutableSet<String>>()
    /** 缓存驱逐回调列表 — 当 Session 被驱逐时通知关闭 StreamObserver（D-20） */
    private val evictionCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    /** Json 实例用于 Session 与 JSON 互转 */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val redisTimeoutMs = 500L
```

**NEW: Device-type index to add (from RESEARCH.md lines 445-448):**
```kotlin
/** 设备类型索引 — "${userId}:${deviceType}" → token，用于同类型设备互踢 */
private val deviceTypeIndex = ConcurrentHashMap<String, String>()
```

**NEW: registerWithDeviceType method pattern (RESEARCH.md lines 458-479):**
```kotlin
suspend fun registerWithDeviceType(
    session: Session,
    observer: StreamObserver<Envelope>? = null
) {
    val key = deviceTypeKey(session.userId, session.deviceType)
    val existingToken = deviceTypeIndex[key]

    if (existingToken != null) {
        // 同设备类型的旧连接存在，触发踢下线
        unregister(existingToken)
    }
    register(session)
    saveDeviceTypeMapping(session)
    deviceTypeIndex[key] = session.token
}

private fun deviceTypeKey(userId: Long, deviceType: String): String =
    "$userId:$deviceType"
```

**Eviction callback pattern (lines 52-54):**
```kotlin
fun onEviction(callback: (token: String) -> Unit) {
    evictionCallbacks.add(callback)
}
```

**Unregister triggers eviction callbacks (lines 196-200):**
```kotlin
suspend fun unregister(token: String) {
    removeFromLocalCache(token)
    removeFromRedis(token)
    evictionCallbacks.forEach { it(token) }
}
```

---

### `repository/.../redis/PrivacyRepository.kt` (repository, CRUD) — NEW

**Analog:** `OnlineStatusRepository.kt` (lines 1-41) — exact same Redis repository pattern.

**Redis repository constructor pattern (OnlineStatusRepository lines 17-21):**
```kotlin
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OnlineStatusRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
```

**Key prefix + TTL constants pattern (OnlineStatusRepository lines 23-25):**
```kotlin
companion object {
    private const val KEY_PREFIX = "online:user:"
    private const val TTL_SECONDS = 60L
}
```

**SETEX / GET / DEL operations pattern (OnlineStatusRepository lines 28-40):**
```kotlin
suspend fun setOnline(userId: Long, statusData: String) {
    redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, statusData)
}

suspend fun isOnline(userId: Long): Boolean {
    return redis.get("$KEY_PREFIX$userId") != null
}
```

**PrivacyRepository should use this exact pattern with key prefix `privacy:user:` and 7-day TTL.**

---

### `gateway/.../ChatService.kt` (gateway, request-response) — NEW

**Response interception pattern (D-04/D-05, from CONTEXT.md):**

The ChatService is a new gRPC bidirectional stream service that:
1. Receives `Envelope` (Direction.REQUEST) from client
2. Calls `Dispatcher.dispatch()`
3. **Intercepts LoginResp in the response path** — detects `response.getMethod() == "user/login"`, calls `SessionRegistry.registerWithDeviceType()`
4. Handles same-device-type kick via eviction callbacks sending `LOGOUT` push notification
5. Sends `LoginResp` back to client

**Key integration pattern — ChatService intercepts after Dispatcher (D-05):**
```kotlin
// Pseudocode for the interception point:
val envelopeRequest = Envelope.parseFrom(bytes)
val response = dispatcher.dispatch(envelopeRequest.request)

if (envelopeRequest.request.method == "user/login" && response.result.size > 0) {
    val loginResp = LoginResp.parseFrom(response.result.toByteArray())
    val session = Session(
        userId = loginResp.userId,
        token = loginResp.token,
        deviceType = extractDeviceType(envelopeRequest),
        deviceId = extractDeviceId(envelopeRequest),
        connectionId = connectionId
    )
    // Register with device-type-aware eviction (AUTH-05/AUTH-06)
    sessionRegistry.registerWithDeviceType(session, currentObserver)
}
```

---

### `proto/nebula/user/user.proto` (config) — ENHANCE

**Existing messages (lines 12-100)** — keep all existing definitions. Add:

**NEW: RegisterReq / RegisterResp (Claude's Discretion):**
```protobuf
// ---- user/register ----
message RegisterReq {
  string username = 1;
  string password = 2;
  string nickname = 3;
  string avatar = 4;
}

message RegisterResp {
  int64 uid = 1;
  string token = 2;
}
```

**ENHANCE: SearchUserReq with cursor/limit fields:**
```protobuf
message SearchUserReq {
  string keyword = 1;
  int64 cursor = 2;     // 游标：上一页最后一条的 created_at 毫秒时间戳，首次传 0
  int32 limit = 3;      // 每页数量，默认 20，最大 20
}

message SearchUserResp {
  repeated UserBrief users = 1;
  int64 next_cursor = 2;  // 下一页游标
  bool has_more = 3;      // 是否有更多数据
}
```

---

### `repository/.../repository/UserRepository.kt` (repository, CRUD) — ENHANCE

**Analog:** Current `UserRepository.kt` (lines 1-12).

**Existing JPA repository pattern:**
```kotlin
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByUsername(username: String): UserEntity?
}
```

**NEW: Add search methods for cursor pagination (RESEARCH.md lines 554-581):**
```kotlin
// LIKE %keyword% fuzzy match (D-07)
fun findByUsernameContaining(keyword: String, pageable: Pageable): List<UserEntity>

// Cursor pagination (D-08): WHERE createdAt < cursor ORDER BY createdAt DESC
fun findByUsernameContainingAndCreatedAtBefore(
    keyword: String, cursor: LocalDateTime, pageable: Pageable
): List<UserEntity>

// Username uniqueness check for registration
fun existsByUsername(username: String): Boolean
```

---

### `gateway/.../interceptor/AuthInterceptor.kt` (middleware) — ENHANCE

**Analog:** Current `AuthInterceptor.kt` (lines 1-73).

**Extend skipMethods to include user/login and user/register (line 26):**
```kotlin
private val skipMethods: Set<String> = setOf("system/ping", "user/login", "user/register")
```

**Implement extractToken() (line 66-68, RESEARCH.md Pitfall 2 resolution):**
```kotlin
open fun extractToken(request: Request): String? {
    // Phase 5: Extract from Request.metadata map or params
    // Option A: via Request.metadata["authorization"] (if metadata field added to proto)
    // Option B: Token is in LoginReq.token for login/reconnect only
    return null
}
```

**Auth interceptor intercept pattern (lines 29-55):**
```kotlin
override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
    val method = request.method
    if (method in skipMethods) {
        return chain.proceed(request)
    }
    val token = extractToken(request)
        ?: return Response.newBuilder()
            .setCode(BizCode.UNAUTHORIZED.code)
            .setMsg(BizCode.UNAUTHORIZED.msg)
            .build()

    val session = sessionRegistry.validate(token)
        ?: return Response.newBuilder()
            .setCode(BizCode.TOKEN_INVALID.code)
            .setMsg(BizCode.TOKEN_INVALID.msg)
            .build()

    return withContext(SessionKey(session)) {
        chain.proceed(request)
    }
}
```

---

### `gateway/.../interceptor/RateLimitInterceptor.kt` (middleware) — ENHANCE

**Analog:** Current `RateLimitInterceptor.kt` (lines 1-86).

**Implement extractClientIp() (line 69):**
```kotlin
private fun extractClientIp(request: Request): String {
    // Phase 5: Extract from Request metadata map
    // return request.metadata["x-forwarded-for"] ?: request.metadata["remote-addr"] ?: "unknown"
    return "unknown"
}
```

**The existing Semaphore-based per-user concurrency limiting pattern (lines 28-59):**
```kotlin
class RateLimitInterceptor(
    private val permitsPerUser: Int = DEFAULT_PERMITS_PER_USER,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS
) : Interceptor {
    private val userSemaphores = ConcurrentHashMap<String, Semaphore>()
```

**For registration IP limiting (D-02), add a RegisterRateLimiter (RESEARCH.md lines 527-549):**
```kotlin
class RegisterRateLimiter {
    private val ipRequestTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxRequests = 5
    private val windowMs = 60 * 60 * 1000L  // 1 hour

    fun tryAcquire(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val times = ipRequestTimes.getOrPut(ip) { mutableListOf() }
        synchronized(times) {
            times.removeAll { now - it > windowMs }
            if (times.size >= maxRequests) return false
            times.add(now)
            return true
        }
    }
}
```

---

### `gateway/.../di/GatewayModule.kt` (config) — ENHANCE

**Analog:** Current `GatewayModule.kt` (lines 1-79).

**Handler registration pattern in Koin module (lines 44-46):**
```kotlin
val handlerModule = module {
    single { PingHandler() }
    // Phase 5+: Add user handlers:
    single { LoginHandler(get(), get(), get()) }
    single { RegisterHandler(get(), get(), get()) }
    single { SearchUserHandler(get()) }
    single { GetProfileHandler(get()) }
    single { BatchGetUserHandler(get()) }
    single { BatchGetStatusHandler(get(), get()) }
    single { SetPrivacyHandler(get()) }
    single { GetPrivacyHandler(get()) }
}
```

**HandlerRegistry registration pattern (lines 61-78):**
```kotlin
fun registerHandlers(
    registry: HandlerRegistry,
    protoCodec: ProtoCodec,
    pingHandler: PingHandler,
    loginHandler: LoginHandler,
    // ... more handlers
) {
    // PingHandler: method="system/ping", Req=Request, Resp=Response
    val pingReqCodec = ProtoCodec.buildCodec(Request::class)
    val pingRespCodec = ProtoCodec.buildCodec(Response::class)
    registry.register(HandlerEntry(
        handler = pingHandler,
        reqClass = Request::class,
        respClass = Response::class,
        parseFrom = pingReqCodec.parseFrom,
        toByteArray = pingRespCodec.toByteArray
    ))

    // LoginHandler: method="user/login", Req=LoginReq, Resp=LoginResp
    val loginReqCodec = ProtoCodec.buildCodec(LoginReq::class)
    val loginRespCodec = ProtoCodec.buildCodec(LoginResp::class)
    registry.register(HandlerEntry(
        handler = loginHandler,
        reqClass = LoginReq::class,
        respClass = LoginResp::class,
        parseFrom = loginReqCodec.parseFrom,
        toByteArray = loginRespCodec.toByteArray
    ))
}
```

**AuthInterceptor skipMethods update (line 33):**
```kotlin
single<Interceptor> {
    AuthInterceptor(get(), skipMethods = setOf("system/ping", "user/login", "user/register"))
}
```

---

### `server/.../NebulaServer.kt` (config) — ENHANCE

**Analog:** Current `NebulaServer.kt` (lines 1-115).

**Koin initialization pattern (lines 98-100):**
```kotlin
startKoin {
    modules(frameworkModule, handlerModule)
}
```

**HandlerRegistry wiring pattern (lines 104-107):**
```kotlin
val registry = GlobalContext.get().get<HandlerRegistry>()
val codec = GlobalContext.get().get<ProtoCodec>()
// Phase 5: Get all handlers from Koin
val pingHandler = GlobalContext.get().get<PingHandler>()
val loginHandler = GlobalContext.get().get<LoginHandler>()
// ... etc.

registerHandlers(registry, codec, pingHandler, loginHandler, ...)

// Phase 5: Start ChatService with LoginResp interception
val chatServer = ChatServer(config, sessionRegistry)
```

---

### `repository/.../redis/SessionRepository.kt` (repository, CRUD) — ENHANCE

**Analog:** Current `SessionRepository.kt` (lines 1-46).

**NEW: Add saveRaw/findRaw for device-type mapping (Pitfall 5 prevention):**
```kotlin
/** 通用 Redis 字符串写入（用于设备类型映射等场景） */
suspend fun saveRaw(key: String, value: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
    redis.setex(key, ttlSeconds, value)
}

/** 通用 Redis 字符串读取 */
suspend fun findRaw(key: String): String? {
    return redis.get(key)
}

/** 删除 Redis key */
suspend fun deleteKey(key: String) {
    redis.del(key)
}
```

---

## Shared Patterns

### Exception Handling
**Source:** `ExceptionInterceptor.kt` (lines 20-54)
**Apply to:** All handlers (via Pipeline, not per-handler)
```kotlin
class ExceptionInterceptor : Interceptor {
    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(request)
        } catch (e: BizException) {
            Response.newBuilder()
                .setCode(e.bizCode.code)
                .setMsg(e.message ?: e.bizCode.msg)
                .setMethod(request.method)
                .build()
        } catch (e: IllegalArgumentException) {
            Response.newBuilder()
                .setCode(BizCode.INVALID_PARAM.code)
                .setMsg(e.message ?: BizCode.INVALID_PARAM.msg)
                .setMethod(request.method)
                .build()
        } catch (e: Exception) {
            logger.error(e) { "Unhandled exception for method ${request.method}" }
            Response.newBuilder()
                .setCode(BizCode.INTERNAL_ERROR.code)
                .setMsg(BizCode.INTERNAL_ERROR.msg)
                .setMethod(request.method)
                .build()
        }
    }
}
```

**Domain exception pattern (UserException.kt):**
```kotlin
class UserException(bizCode: BizCode, msg: String = bizCode.msg) : BizException(bizCode, msg)
// Usage in handlers: throw UserException(BizCode.USER_NOT_FOUND)
```

### Response Building Pattern
**Source:** `Dispatcher.kt` (lines 83-95)
**Apply to:** All handlers (Dispatcher wraps handler results into Response automatically)
```kotlin
val result = (entry.handler as Handler<Any, Any>).handle(req)
val resultBytes = protoCodec.serialize(entry, result)
return Response.newBuilder()
    .setCode(BizCode.OK.code)
    .setMethod(method)
    .setResult(ByteString.copyFrom(resultBytes))
    .build()
```
Handlers return typed proto messages (LoginResp, SearchUserResp, etc). The Dispatcher handles wrapping them into `Response.result`.

### Logging Pattern
**Source:** `LogInterceptor.kt` (lines 19-38) and `SessionRegistry.kt` (line 203)
**Apply to:** All service classes
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

// In class companion object:
companion object {
    private val logger = KotlinLogging.logger {}
}

// Usage:
logger.info { "message $variable" }
logger.warn(e) { "warning with exception: $context" }
logger.error(e) { "error occurred" }
```

### Session Access in Handlers
**Source:** `SessionKey.kt` (lines 32-34)
**Apply to:** All authenticated handlers (not LoginHandler/RegisterHandler which are skipMethods)
```kotlin
// In handle():
val session = coroutineContext.requireSession()
// session.userId, session.token, session.deviceType, session.deviceId
```

**Warning (from Handler.kt doc lines 11-13):** Handler 内部若使用 `launch { }` 或 `withContext(Dispatchers.Default)` 启动新协程，Session 在新协程的 CoroutineContext 中不可见。必须使用 `coroutineScope { }` / `supervisorScope { }` 结构化并发保持上下文传递。

### Testing Pattern (Analogs)
**Source:** `PingHandlerTest.kt` (lines 1-35)
**Apply to:** All new handler tests
```kotlin
class LoginHandlerTest {
    private val handler = LoginHandler(mockk(), mockk(), mockk())

    @Test
    fun loginReturnsLoginRespWithToken() = runTest {
        val req = LoginReq.newBuilder().setUsername("test").setPassword("pass").build()
        val resp = handler.handle(req)
        assertEquals(1001L, resp.userId)
        assertNotNull(resp.token)
    }

    @Test
    fun methodIsUserLogin() {
        assertEquals("user/login", handler.method)
    }
}
```

**Source:** `SessionRegistryTest.kt` (lines 1-110) — for testing enhanced SessionRegistry
```kotlin
class SessionRegistryTest {
    private lateinit var sessionRepository: SessionRepository
    private lateinit var registry: SessionRegistry

    @BeforeEach
    fun setUp() {
        sessionRepository = mockk<SessionRepository>()
        registry = SessionRegistry(sessionRepository)
    }

    @Test
    fun registerWithDeviceTypeEvictsSameDeviceType() = runTest {
        coEvery { sessionRepository.save(any(), any()) } returns Unit
        coEvery { sessionRepository.delete(any()) } returns Unit

        val session1 = Session(1001L, "token-1", "android", "dev-1", "conn-1")
        val session2 = Session(1001L, "token-2", "android", "dev-2", "conn-2")

        registry.registerWithDeviceType(session1)
        registry.registerWithDeviceType(session2)

        // session1 should be evicted (same userId + deviceType)
        assertNull(registry.getFromLocalCache("token-1"))
        assertNotNull(registry.getFromLocalCache("token-2"))
    }
}
```

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `gateway/.../ChatService.kt` | gateway | request-response | No existing gRPC bidirectional stream service — this is the first Gateway-layer gRPC service. Use Dispatcher pattern as closest structural match for response interception. |

## Metadata

**Analog search scope:** `gateway/src/main/kotlin/com/nebula/gateway/handler/`, `gateway/src/main/kotlin/com/nebula/gateway/session/`, `gateway/src/main/kotlin/com/nebula/gateway/interceptor/`, `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/`, `repository/src/main/kotlin/com/nebula/repository/redis/`, `repository/src/main/kotlin/com/nebula/repository/repository/`, `repository/src/main/kotlin/com/nebula/repository/entity/`, `common/src/main/kotlin/com/nebula/common/exception/`, `common/src/main/kotlin/com/nebula/common/`
**Files scanned:** 30+ source files
**Pattern extraction date:** 2026-06-12
