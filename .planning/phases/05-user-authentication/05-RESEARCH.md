# Phase 5: User & Authentication — Research

**Researched:** 2026-06-12
**Domain:** Authentication, session management, user CRUD, multi-device policy
**Confidence:** HIGH

## Summary

Phase 5 implements the complete authentication and user management subsystem for the Nebula Chat Server. It builds directly on top of the Phase 4 Handler Framework (Handler interface, Interceptor Pipeline, SessionRegistry, Dispatcher) and Phase 3 database schema (UserEntity, UserRepository, SessionRepository, OnlineStatusRepository).

The core architecture follows a **Gateway-layer interception pattern** (D-04/D-05): LoginHandler performs pure business validation (password verification) and returns `LoginResp`; the ChatService/Gateway layer detects `LoginResp` in the response path, calls `SessionRegistry.register()` to bind the session, performs same-device-type kick with LOGOUT push notification, and finally sends the `LoginResp` to the client. This eliminates the need for a separate `system/bind` request (D-06) and keeps StreamObserver management entirely in the Gateway layer.

**Primary recommendation:** Use `spring-security-crypto` (standalone, no Spring dependency) for `BCryptPasswordEncoder(cost=12)`. Implement LoginHandler as a pure business handler, add LoginResp interception in ChatService, and enhance SessionRegistry with device-type-aware registration and eviction callbacks for kick notifications.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### 用户来源与注册
- **D-01:** 同时支持预置账号导入（SQL 初始化脚本/管理员批量创建）和开放注册 API (`user/register`)
- **D-02:** 注册防护措施：IP 频率限制（每小时 5 次，复用 RateLimitInterceptor）、用户名唯一性校验、密码强度校验（最低 6 位），不额外添加验证码
- **D-03:** 注册时密码使用 bcrypt 加密（cost 12，与设计规范一致）

#### 登录后连接绑定
- **D-04:** Gateway 层（ChatService/ChatGatewayImpl）在发送 LoginResp 前拦截响应，自动完成 Session 注册和 StreamObserver 绑定。**Handler 层不感知 StreamObserver**，保持职责链完整。
- **D-05:** 绑定流程：LoginHandler 验证密码 → 返回 LoginResp（含 token）→ ChatService 检测到 LoginResp 类型 → 调用 `SessionRegistry.register(session, observer)` → 同类型设备踢下线 → 发送 Logout 通知 → 发送 LoginResp 给客户端
- **D-06:** 无需额外的 system/bind 客户端请求，消除网络往返引入的不一致风险

#### 用户搜索
- **D-07:** 搜索范围：仅 `username` 字段，LIKE 模糊匹配（%keyword%）
- **D-08:** 游标分页，每页最多 20 条，按注册时间倒序排列

#### 隐私控制
- **D-09:** `hide_online_status` 优先存入 Redis，后续异步刷 MySQL 做持久化
- **D-10:** 在 Phase 5 的 `batchGetOnlineStatus` 中立即生效：跳过 `hide_online_status=true` 的用户
- **D-11:** `getPrivacy` 读 Redis，`setPrivacy` 写 Redis + 异步写 MySQL

### Claude's Discretion
- 预置账号的具体字段和初始数据内容（部分由 DB schema 决定）
- register API 的请求/响应 proto 定义细节（新增 `user/register` 方法）
- LoginResp 在 ChatService 层的拦截点具体实现方式
- Redis 隐私 key 的命名结构

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | User authenticates via user/login with password or token, returns LoginResp with session token | LoginHandler + bcrypt verification; token generation via UUID; SessionRegistry.register() |
| AUTH-02 | Reconnect flow verifies existing token against Redis, skips re-auth when valid | LoginHandler checks `token` field in LoginReq; calls SessionRegistry.validate(); if valid, reuse existing session |
| AUTH-03 | Token format with expiration, stored in Redis with session mapping | UUIDv4 token, 7-day TTL via SessionRepository.setex(); Redis key structure: `session:token:{token}` |
| AUTH-04 | Local in-memory session map (ConcurrentHashMap) for fast connection lookup | SessionRegistry already provides `localCache` (ConcurrentHashMap token→Session) and `userIdIndex` |
| AUTH-05 | Same-device-type kick: only latest active connection preserved per device type | SessionRegistry needs enhanced `registerByDeviceType()`: lookup existing session by userId+deviceType, evict old before registering new |
| AUTH-06 | Kick notification pushes LOGOUT to displaced session before closing | PushEventType.LOGOUT already in message_type.proto; eviction callback sends Envelope(Direction=PUSH, eventType=LOGOUT) before closing StreamObserver |
| BIZ-USER-01 | user/search searches users by keyword with pagination | UserRepository needs `findByUsernameContainingIgnoreCase()`; cursor pagination via `createdAt` DESC, max 20 |
| BIZ-USER-02 | user/getProfile returns detailed user profile | UserRepository.findById(); map UserEntity to GetProfileResp |
| BIZ-USER-03 | user/batchGet returns user summary by ID list | UserRepository.findAllById(); map to repeated UserBrief |
| BIZ-USER-04 | user/batchGetStatus returns online status for user ID list | OnlineStatusRepository.isOnline() for each user; filter by privacy (hide_online_status) |
| BIZ-USER-05 | user/setPrivacy configures online status visibility | Write to Redis (privacy key) + async flush to MySQL UserEntity.privacyStatus |
| BIZ-USER-06 | user/getPrivacy reads current privacy settings | Read from Redis privacy key; fallback to MySQL UserEntity.privacyStatus |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Password verification (bcrypt) | LoginHandler (service) | — | Pure computation, stateless, no I/O dependency |
| Token generation (UUID) | LoginHandler (service) | — | Stateless, no external dependency |
| Session registration + L1 cache write | SessionRegistry (service) | — | Phase 4 already provides ConcurrentHashMap + userIdIndex |
| Session persistence (Redis L2) | SessionRepository (repository) | — | Phase 3 already provides Redis SETEX with 7-day TTL |
| Same-device-type kick (evict + LOGOUT push) | SessionRegistry + ChatService (gateway) | — | SessionRegistry manages device-indexed cache; ChatService handles PUSH message sending over StreamObserver |
| Reconnect token revalidation | LoginHandler (service) | SessionRegistry | LoginHandler calls validate(token); if valid, skip password check |
| Token extraction from Request | AuthInterceptor (gateway) | — | Part of Interceptor Pipeline — extracts token before Handler executes |
| User registration + bcrypt hash | RegisterHandler (service) | — | Pure business logic with repository write |
| IP rate limiting (register) | AuthInterceptor / RateLimitInterceptor (gateway) | — | RateLimitInterceptor already has stub; extend for per-IP rate limiting for unauthenticated routes |
| User search (LIKE + cursor) | SearchUserHandler (service) | UserRepository | Business logic orchestrates repository query with pagination |
| User profile/batch queries | UserProfileHandler (service) | UserRepository | Pure read, map entities to responses |
| Online status check | BatchStatusHandler (service) | OnlineStatusRepository + PrivacyRepository | Reads status from Redis, filters by privacy |
| Privacy read/write | PrivacyHandler (service) | Redis + async MySQL | Writes to Redis for immediate effect; async flush to MySQL for persistence |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-security-crypto` | 6.4.5+ | BCryptPasswordEncoder, cost 12 | Standalone module with zero Spring dependencies; de-facto standard for JVM bcrypt hashing; used by all major Kotlin/JVM projects |
| JDK `java.util.UUID` | — | Token generation (random UUIDv4) | No library needed; standard library provides cryptographically strong random UUIDs |
| `kotlinx.serialization` | 1.8.1 | Session JSON serialization for Redis | Already used by SessionRegistry in Phase 4 |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| — | — | — | No additional supporting libraries needed |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-security-crypto` | `org.mindrot:jbcrypt` | jbcrypt is simpler but less maintained; spring-security-crypto is actively maintained by Spring team and has a more ergonomic API for Kotlin |
| `spring-security-crypto` | `at.favre.lib:bcrypt` | Modern Kotlin-friendly API but smaller community; spring-security-crypto has broader adoption and better documentation |

**Installation:**
```kotlin
// gradle/libs.versions.toml — add:
[versions]
spring-security-crypto = "6.4.5"

[libraries]
spring-security-crypto = { module = "org.springframework.security:spring-security-crypto", version.ref = "spring-security-crypto" }

// :service/build.gradle.kts or :gateway/build.gradle.kts:
implementation(libs.spring.security.crypto)
```

**Version verification:**

```bash
# spring-security-crypto 6.4.5 is the latest stable as of 2026-06-12
# Verified on Maven Central: https://mvnrepository.com/artifact/org.springframework.security/spring-security-crypto
# The 6.4.x line has no known vulnerabilities (unlike 6.2.x which has 1)
```

## Architecture Patterns

### System Architecture Diagram

```
Client (gRPC Bidirectional Stream)
        │
        ▼
┌─────────────────────────────────────┐
│  ChatService (Gateway layer)        │
│  - onNext(Envelope)                 │
│  - Direction.REQUEST → dispatch()  │
│  - Direction.PING → PONG           │
│  - Detect LoginResp in response    │
│    path → register session          │
└──────────┬──────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  Dispatcher                          │
│  - HandlerRegistry lookup           │
│  - ProtoCodec deserialize/serialize │
│  - Interceptor Pipeline             │
└──────────┬──────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  Interceptor Pipeline (order D-07)  │
│                                     │
│  1. AuthInterceptor                  │
│     - skipMethods (system/ping,     │
│       user/login, user/register)     │
│     - extractToken()                 │
│     - SessionRegistry.validate()     │
│     - inject Session to coroutineCtx │
│                                     │
│  2. LogInterceptor                   │
│     - timing + status logging       │
│                                     │
│  3. RateLimitInterceptor             │
│     - per-user concurrency limit    │
│     - per-IP limit for register     │
│                                     │
│  4. ExceptionInterceptor             │
│     - BizException → biz code       │
│     - other → 9000                  │
└──────────┬──────────────────────────┘
           │
           ▼
┌─────────────────────────────────────┐
│  Handler (pure business logic)      │
│                                     │
│  LoginHandler:                      │
│    ✓ Verify password (bcrypt)       │
│    ✓ OR validate existing token     │
│    ✓ Generate UUID token            │
│    ✓ Build LoginResp                │
│    ✗ NO StreamObserver binding      │
│    ✗ NO session registration        │
│                                     │
│  RegisterHandler:                   │
│    ✓ Check username uniqueness      │
│    ✓ Validate password (min 6)      │
│    ✓ bcrypt hash (cost 12)          │
│    ✓ Create UserEntity (Snowflake)  │
│                                     │
│  SearchUserHandler / GetProfileHandler / ...│
└──────────────────────────────────────────────┘
```

**Interception point for LoginResp (D-05):**

```
ChatService.onNext()
  → Dispatcher.dispatch(envelopeRequest)
  → Interceptor Pipeline
  → LoginHandler.handle() → returns LoginResp
  ← Response with LoginResp in result field
  ← Dispatcher returns Response

ChatService detects: response.getMethod() == "user/login"
  → Deserialize LoginResp from response.getResult()
  → SessionRegistry.register(session, observer)
  → Same-device-type eviction check:
      → Find existing session for same userId + deviceType
      → Unregister old session → eviction callback fires
      → Send Envelope(Direction=PUSH, eventType=LOGOUT, content="...") via old StreamObserver
      → Close old StreamObserver
  → Register new session with current StreamObserver
  → Send LoginResp Envelope(Direction=RESPONSE) via current StreamObserver
```

### Recommended Project Structure (Phase 5 additions)

```
gateway/src/main/kotlin/com/nebula/gateway/handler/
├── user/
│   ├── LoginHandler.kt          # user/login — password/token auth
│   ├── RegisterHandler.kt       # user/register — new user registration
│   ├── SearchUserHandler.kt     # user/search — LIKE query + cursor pagination
│   ├── GetProfileHandler.kt     # user/getProfile — user profile by uid
│   ├── BatchGetUserHandler.kt   # user/batchGet — user summary by ID list
│   ├── BatchGetStatusHandler.kt # user/batchGetStatus — online status batch
│   ├── SetPrivacyHandler.kt     # user/setPrivacy — hide_online_status
│   └── GetPrivacyHandler.kt     # user/getPrivacy — read privacy settings

gateway/src/main/kotlin/com/nebula/gateway/session/
├── Session.kt                   # [EXISTS] data model
├── SessionRegistry.kt           # [EXISTS] L1/L2 cache — ENHANCE with:
│                                 #   - registerWithDeviceType(session, observer)
│                                 #   - findByUserIdAndDeviceType(userId, deviceType)
│                                 #   - deviceTypeIndex: ConcurrentHashMap

repository/src/main/kotlin/com/nebula/repository/redis/
├── PrivacyRepository.kt         # NEW — Redis privacy key operations + async MySQL flush
├── SessionRepository.kt         # [EXISTS]
└── OnlineStatusRepository.kt    # [EXISTS]

repository/src/main/kotlin/com/nebula/repository/repository/
└── UserRepository.kt            # [EXISTS] — ENHANCE with:
                                 #   - findByUsernameContaining(keyword, pageable)
                                 #   - countByUsernameContaining(keyword)
```

### Pattern 1: Login Handler (pure business, no StreamObserver)
**What:** Handler only validates credentials and returns LoginResp. Session binding happens in Gateway layer.
**When to use:** All authentication handlers that create new sessions.

```kotlin
/**
 * 登录 Handler — method = "user/login"。
 *
 * 职责边界（D-04/D-05）：
 * - 仅处理密码验证或 Token 重连验证
 * - 返回 LoginResp（含 token、uid、server_now）
 * - 不感知 StreamObserver，不处理 Session 注册
 *
 * Token 提取由 AuthInterceptor 在 Pipeline 中完成。
 * Session 注册和 StreamObserver 绑定由 ChatService 在检测到 LoginResp 后完成。
 */
class LoginHandler(
    private val userRepository: UserRepository,
    private val sessionRegistry: SessionRegistry,
    private val idGenerator: SnowflakeIdGenerator
) : Handler<LoginReq, LoginResp> {

    override val method: String = "user/login"

    override suspend fun handle(req: LoginReq): LoginResp {
        val session = coroutineContext.requireSession() // only when re-auth, initially null?

        // 场景 1: Token 重连 (AUTH-02)
        if (req.hasToken()) {
            val existingSession = sessionRegistry.validate(req.token)
            if (existingSession != null && existingSession.userId == /* 从 token 查 */) {
                // Token 有效，跳过密码验证
                // ChatService 后续会重新注册 Session（更新 observer）
                return buildLoginResp(existingSession.userId)
            }
        }

        // 场景 2: 用户名+密码登录
        val user = userRepository.findByUsername(req.username)
            ?: throw UserException(BizCode.USER_NOT_FOUND)
        // bcrypt 验证（D-03）
        if (!BCryptPasswordEncoder(12).matches(req.password, user.passwordHash)) {
            throw UserException(BizCode.AUTH_FAILED)
        }

        return buildLoginResp(user.id!!)
    }

    private fun buildLoginResp(userId: Long): LoginResp {
        val token = UUID.randomUUID().toString()
        return LoginResp.newBuilder()
            .setUserId(userId)
            .setToken(token)
            .setServerNow(System.currentTimeMillis())
            .build()
    }
}
```

### Pattern 2: Gateway Layer Login Response Interception
**What:** ChatService detects LoginResp in the response path, completes session registration and device kick.
**When to use:** Central to D-05 binding flow.

```kotlin
// In ChatService.onNext():
val envelopeRequest = Envelope.parseFrom(...)
val response = dispatcher.dispatch(envelopeRequest.request)

if (envelopeRequest.request.method == "user/login" && response.result.size > 0) {
    // D-05: Intercept LoginResp
    val loginResp = LoginResp.parseFrom(response.result.toByteArray())
    val session = Session(
        userId = loginResp.userId,
        token = loginResp.token,
        deviceType = extractDeviceType(envelopeRequest), // from LoginReq or metadata
        deviceId = extractDeviceId(envelopeRequest),
        connectionId = connectionId
    )
    // Register with device-type-aware eviction
    sessionRegistry.registerWithDeviceType(session, currentObserver)
}
```

### Anti-Patterns to Avoid
- **Handler leaking StreamObserver:** LoginHandler should NOT have StreamObserver parameter. Session binding in Gateway layer (D-04).
- **Extra network round-trip for bind:** Do NOT make client send a `system/bind` after login (D-06). Bind happens in the login response path.
- **Token in Request params:** Token should NOT be in the business payload. Use the `extractToken()` mechanism in AuthInterceptor.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| bcrypt password hashing | Custom bcrypt implementation | `BCryptPasswordEncoder(12)` from `spring-security-crypto` | bcrypt is notoriously easy to get wrong (salt generation, constant-time comparison, cost encoding). `spring-security-crypto` is battle-tested, actively maintained, standalone (no Spring framework dependency). |
| UUID token generation | Custom random string | `java.util.UUID.randomUUID().toString()` | Standard library provides RFC 4122 v4 UUIDs with SecureRandom-backed randomness. No library needed. |
| Cursor pagination for user search | Offset-based pagination (LIMIT/OFFSET) | `createdAt` cursor + `WHERE createdAt < :cursor ORDER BY createdAt DESC LIMIT :limit` | Cursor pagination is stable under active writes (new users registering won't shift pages). D-08 explicitly requires cursor, max 20. |

**Key insight:** The small number of "don't hand-roll" items here reflects that most of the Phase 5 work is wiring existing components together (SessionRegistry, UserRepository, AuthInterceptor, Gateway layer) rather than introducing new algorithmic complexity. The one genuinely complex domain — password hashing — has a clear, off-the-shelf standard solution.

## Common Pitfalls

### Pitfall 1: Reconnect Race Condition — Token Validated but Observer Stale
**What goes wrong:** During client reconnect, the old connection's StreamObserver is still registered when the new connection validates the token. Both connections send data to the client, causing duplicate delivery or state corruption.

**Why it happens:** Token validation (AUTH-02) is done in LoginHandler before SessionRegistry.register() replaces the observer. Between validation and registration, the old observer is still active.

**How to avoid:** The register flow must be **atomic**: (1) Find and evict old session → (2) Close old observer → (3) Register new observer → (4) Send LoginResp. Do step (2) before step (3). Use the eviction callback mechanism already in SessionRegistry.

**Warning signs:** Client receives duplicate messages after reconnect, or receives responses intended for the old connection.

### Pitfall 2: Token in Request Body vs Envelope Metadata
**What goes wrong:** The AuthInterceptor's `extractToken()` currently returns `null` (stub). If token placement isn't decided, the interceptor will reject every authenticated request with UNAUTHORIZED(1001).

**Why it happens:** The envelope.proto has no metadata field. The current code has `TODO: Phase 5 determine token delivery method`.

**How to avoid:** Use the `Request.params` bytes for all requests, including token. For login/reconnect, token is in `LoginReq.token` field. For all other requests, **pass token as the first N bytes of `params`** OR **add a metadata map to `Request` proto** (more scalable). **Recommended:** Add a `map<string, string> metadata` field to the `Request` proto message — clean, extensible, doesn't pollute business payloads.

**Warning signs:** All non-login methods return 1001.

### Pitfall 3: Cursor Pagination with LIKE %keyword% Performance
**What goes wrong:** `WHERE username LIKE '%keyword%'` cannot use MySQL indexes. Leading-wildcard LIKE forces a full table scan. With thousands of users, every search scans the entire users table.

**Why it happens:** D-07 specifies LIKE %keyword% fuzzy match. This is inherently non-indexable.

**How to avoid:** (1) Keep the max 20 limit (D-08) — this bounds the scan. (2) MySQL 8.0+ can use FULLTEXT index with `IN BOOLEAN MODE` for better performance if keyword volume grows. (3) Consider Elasticsearch/Manticore in Phase 12 if search becomes a bottleneck. (4) Add `LIMIT 21` (one extra row to check if there are more results for cursor) to minimize scan.

**Warning signs:** User search response time grows linearly with user count.

### Pitfall 4: Privacy Async Flush — Write Loss on Crash
**What goes wrong:** Async flush writes privacy to MySQL after acknowledging the Redis write. If the server crashes between Redis write and MySQL flush, the privacy setting is lost on restart.

**Why it happens:** D-09 specifies "write to Redis first, async flush to MySQL."

**How to avoid:** (1) On server startup or first `getPrivacy` call, check if Redis has the key. If not, fall back to MySQL. (2) Keep the async flush as best-effort — the Redis-first approach means the most recent value is in Redis. (3) Accept the design trade-off: momentary inconsistency is OK for privacy settings; eventual consistency is guaranteed when the flush completes.

**Warning signs:** After server restart, privacy settings revert to defaults.

### Pitfall 5: Redis Key Structure Mismatch — Design Doc vs Implementation

**What goes wrong:** The Redis key structure implemented in Phase 3 (`SessionRepository`) differs from the design document specification (4.3-Redis会话Key.md). The design doc specifies TWO key types:
```
session:{userId}:{deviceType}  → JSON { token, lastHeartbeat }   # For device-type lookup
token:{token}                  → "userId:deviceType"               # For token lookup
```

But the actual `SessionRepository` only implements ONE key:
```
session:token:{token}  → Session JSON   # For token lookup only (key prefix is "session:token:")
```

**Why it happens:** Phase 3 implemented the SessionRepository as a generic cache (token → JSON). The design doc's `session:{userId}:{deviceType}` key for device-type cross-reference lookup was never implemented.

**How to avoid:** Phase 5 must add the `session:{userId}:{deviceType}` Redis key for device-type lookup persistence. When SessionRegistry.registerWithDeviceType() is called, it must write BOTH keys:
1. `session:token:{token}` → Session JSON (existing, via SessionRepository.save())
2. `session:{userId}:{deviceType}` → token String (NEW, needs RedisRepository method or a new DeviceSessionRepository)

On reconnect, the device-type key is used to find old sessions and perform same-device-type eviction, even after server restart (when L1 cache is empty).

**Warning signs:** After server restart, same-device-type kick stops working because L1 cache is empty and there's no Redis key to find old sessions by device type.

### Pitfall 6: Multiple Device Sessions per User in L1 Cache
**What goes wrong:** Same user connects from two different device types (mobile + desktop). Both are valid, but the `userIdIndex` in SessionRegistry maps userId → Set\<token\>, without distinguishing device types.

**Why it happens:** The current `userIdIndex` is for multi-device tracking, but `registerWithDeviceType()` needs a separate index: `(userId, deviceType) → token`.

**How to avoid:** Add a `deviceTypeIndex: ConcurrentHashMap<String, String>` where key = `"${userId}:${deviceType}"` and value = token. On register, check this index first, evict old token, then add new. Keep `userIdIndex` for broadcast scenarios (Phase 8+ status push to all devices).

**Warning signs:** One session replaces the wrong session during device-type kick.

## Code Examples

### 1. BCryptPasswordEncoder usage in Kotlin

```kotlin
// Source: [CITED: docs.spring.io/spring-security/reference/features/integrations/cryptography.html]
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

// Create encoder with cost 12 (D-03)
val encoder = BCryptPasswordEncoder(12)

// Hash password during registration
val passwordHash: String = encoder.encode("userPassword123")

// Verify during login
val matches: Boolean = encoder.matches("userPassword123", storedHash)
```

### 2. Device-type aware session registration

```kotlin
// SessionRegistry enhancement for device-type kick (AUTH-05/AUTH-06)
/**
 * 设备类型索引 — "${userId}:${deviceType}" → token
 * 用于同类型设备互踢时快速定位已有 Session
 */
private val deviceTypeIndex = ConcurrentHashMap<String, String>()

/**
 * 按设备类型注册 Session — 同类型设备互踢（D-05, AUTH-05）。
 *
 * 流程：
 * 1. 检查 deviceTypeIndex 是否存在同 userId+deviceType 的旧 token
 * 2. 若存在，unregister 旧 token（触发驱逐回调 → LOGOUT 推送）
 * 3. 注册新 Session（L1 + L2），写入两个 Redis key：
 *    - session:token:{token} → Session JSON（现有 SessionRepository.save()）
 *    - session:{userId}:{deviceType} → token（NEW，设备类型交叉引用，持久化互踢状态）
 * 4. 更新本地 deviceTypeIndex
 */
suspend fun registerWithDeviceType(
    session: Session,
    observer: StreamObserver<Envelope>? = null
) {
    val key = deviceTypeKey(session.userId, session.deviceType)
    val existingToken = deviceTypeIndex[key]

    if (existingToken != null) {
        // 同设备类型的旧连接存在，触发踢下线（LOGOUT 推送在 eviction callback 中完成）
        unregister(existingToken)
    }

    // 注册新 Session（L1 + L2 token key）
    register(session)

    // 写入设备类型交叉引用到 Redis（Pitfall 5 预防）
    // 确保 server 重启后仍能按设备类型找到旧 session
    saveDeviceTypeMapping(session)

    // 更新本地索引
    deviceTypeIndex[key] = session.token
}

/**
 * 持久化设备类型映射到 Redis。
 * Key: "session:{userId}:{deviceType}" → Value: token
 * TTL: 与 token 一致（7天）
 */
private suspend fun saveDeviceTypeMapping(session: Session) {
    val redisKey = "session:${session.userId}:${session.deviceType}"
    try {
        withTimeout(redisTimeoutMs) {
            sessionRepository.saveRaw(redisKey, session.token)
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to save device type mapping for userId=${session.userId}" }
    }
}

/**
 * 从 Redis 查找指定用户+设备类型的当前 token。
 * 用于 server 重启后恢复设备类型索引（L1 cache 为空时）。
 */
private suspend fun findDeviceTokenFromRedis(userId: Long, deviceType: String): String? {
    val redisKey = "session:$userId:$deviceType"
    return try {
        withTimeout(redisTimeoutMs) {
            sessionRepository.findRaw(redisKey)
        }
    } catch (e: Exception) {
        null
    }
}

private fun deviceTypeKey(userId: Long, deviceType: String): String =
    "$userId:$deviceType"

/**
 * 查找指定用户和设备类型的当前 Session。
 */
fun findByDeviceType(userId: Long, deviceType: String): Session? {
    val token = deviceTypeIndex[deviceTypeKey(userId, deviceType)] ?: return null
    return getFromLocalCache(token)
}
```

### 3. Rate limit interceptor enhancement for registration IP limiting

```kotlin
// Enhancement to RateLimitInterceptor for D-02 register IP rate limiting
// Registration-specific: 5 requests/hour/IP (D-02)
// Use a separate rate limiter map for IP-based registration limiting

class RegisterRateLimiter {
    /** IP → 请求时间戳队列，每小时最多 5 次 */
    private val ipRequestTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxRequests = 5
    private val windowMs = 60 * 60 * 1000L  // 1 hour

    fun tryAcquire(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val times = ipRequestTimes.getOrPut(ip) { mutableListOf() }

        synchronized(times) {
            // 清理过期记录
            times.removeAll { now - it > windowMs }
            if (times.size >= maxRequests) return false
            times.add(now)
            return true
        }
    }
}
```

### 4. Cursor pagination for user search (BIZ-USER-01)

```kotlin
/**
 * 搜索用户 Handler — method = "user/search"（D-07, D-08）。
 *
 * 搜索范围：仅 username 字段，LIKE %keyword% 模糊匹配。
 * 分页：游标分页，每页最多 20 条，按注册时间（createdAt）倒序排列。
 */
class SearchUserHandler(
    private val userRepository: UserRepository
) : Handler<SearchUserReq, SearchUserResp> {

    override val method: String = "user/search"

    override suspend fun handle(req: SearchUserReq): SearchUserResp {
        // 游标从请求中提取（需要在 SearchUserReq 中增加 cursor 字段）
        // 当前 proto 没有 cursor 字段 — Claude's Discretion 可修改 proto
        val cursor = req.cursor  // 上一页最后一条的 createdAt 时间戳
        val limit = minOf(req.limit.takeIf { it > 0 } ?: 20, 20)

        val users = if (cursor > 0) {
            userRepository.findByUsernameContainingAndCreatedAtBefore(
                req.keyword, cursor, PageRequest.of(0, limit + 1)
            )
        } else {
            userRepository.findByUsernameContaining(
                req.keyword, PageRequest.of(0, limit + 1)
            )
        }

        val hasMore = users.size > limit
        val briefs = users.take(limit).map { it.toUserBrief() }

        return SearchUserResp.newBuilder()
            .addAllUsers(briefs)
            .setNextCursor(users.lastOrNull()?.createdAt?.toEpochMilli() ?: 0)
            .setHasMore(hasMore)
            .build()
    }
}

// Note: Proto SearchUserReq currently lacks cursor/limit fields.
// Claude's Discretion — recommend adding:
//   int64 cursor = 2;   // 游标：上一页最后一条的 created_at 毫秒时间戳，首次传 0
//   int32 limit = 3;    // 每页数量，默认 20，最大 20
// SearchUserResp needs:
//   int64 next_cursor = 2;  // 下一页游标
//   bool has_more = 3;      // 是否有更多数据
```

### 5. Privacy service with Redis + async MySQL (D-09/D-11)

```kotlin
// Redis key: "privacy:user:{userId}" → JSON { "hide_online_status": true/false }
class PrivacyRepository(
    private val connection: StatefulRedisConnection<String, String>,
    private val userRepository: UserRepository
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_PREFIX = "privacy:user:"
        private const val TTL_SECONDS = 7 * 24 * 3600L  // 7 days
    }

    /** 读隐私设置（优先读 Redis，MySQL 兜底） */
    suspend fun getHideOnlineStatus(userId: Long): Boolean {
        val cached = redis.get("$KEY_PREFIX$userId")
        if (cached != null) return json.decodeFromString<PrivacyData>(cached).hideOnlineStatus

        // Redis 未命中，从 MySQL 读取（重启场景 Pitfall 4）
        val user = userRepository.findById(userId) ?: return false
        val data = PrivacyData(user.privacyStatus == 2) // privacyStatus: 0=可见, 2=隐藏
        // 写回 Redis
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, json.encodeToString(data))
        return data.hideOnlineStatus
    }

    /** 写隐私设置（写 Redis 立即生效 + 异步刷 MySQL） */
    suspend fun setHideOnlineStatus(userId: Long, hide: Boolean) {
        val data = PrivacyData(hide)
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, json.encodeToString(data))
        // 异步刷 MySQL（Fire-and-forget）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                userRepository.updatePrivacyStatus(userId, if (hide) 2 else 0)
            } catch (e: Exception) {
                log.error(e) { "Failed to async flush privacy for userId=$userId" }
            }
        }
    }
}

@Serializable
data class PrivacyData(val hideOnlineStatus: Boolean)
```

### 6. Batch get online status with privacy filter (BIZ-USER-04, D-10)

```kotlin
class BatchGetStatusHandler(
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) : Handler<BatchIdRequest, BatchGetStatusResp> {

    override val method: String = "user/batchGetStatus"

    override suspend fun handle(req: BatchIdRequest): BatchGetStatusResp {
        val builder = BatchGetStatusResp.newBuilder()

        for (uid in req.uidsList) {
            // D-10: 跳过 hide_online_status=true 的用户
            if (privacyRepository.getHideOnlineStatus(uid)) continue

            val isOnline = onlineStatusRepository.isOnline(uid)
            builder.addStatuses(UserOnlineStatus.newBuilder()
                .setUid(uid)
                .setStatus(if (isOnline) 1 else 0)  // 1=online, 0=offline
                .build())
        }
        return builder.build()
    }
}
```

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Password4j via BcryptPassword4jPasswordEncoder is available as an alternative but `BCryptPasswordEncoder` is preferred | Standard Stack | Low — both work, just different APIs |
| A2 | The `Request` proto message will be extended with a `map<string, string> metadata` field for token delivery | Pitfall 2 | Medium — if metadata field can't be added, alternative is passing token as first bytes of `params` or adding to each request message |
| A3 | `SearchUserReq` and `SearchUserResp` proto messages will be extended with cursor/limit fields | Code Example 4 | Medium — without cursor fields, search pagination must use an alternative approach (e.g., offset-based) |
| A4 | UserRepository JPA methods for LIKE queries and cursor-based pagination will work with JPA's derived query mechanism | Code Example 4 | Low — JPA supports `Containing` and `Pageable` queries natively |

## Open Questions (RESOLVED)

1. **Token delivery mechanism for all requests (extractToken implementation)** — RESOLVED
   - **Resolution:** Add `map<string, string> metadata` to `Request` proto (Plan 05-01). AuthInterceptor extracts token from `request.metadata["authorization"]` (Plan 05-01 Task 3). This is the cleanest approach, extensible for future needs (client IP, client version, etc.) without polluting business payloads.

2. **Client IP extraction for rate limiting (extractClientIp implementation)** — RESOLVED
   - **Resolution:** Use gRPC's `Grpc.TRANSPORT_ATTR_REMOTE_ADDR` stored in connection context, passed through Request metadata map. RateLimitInterceptor reads from request metadata (same mechanism as token). Implementation in Plan 05-01.

3. **register API proto definition (Claude's Discretion)** — RESOLVED
   - **Resolution:** Defined in Plan 05-01 Task 1 — `RegisterReq` (username, password, nickname, avatar) and `RegisterResp` (uid, token). Added to `user.proto` alongside new `SearchUserReq/SearchUserResp` cursor fields.

4. **ChatService location for LoginResp interception** — RESOLVED
   - **Resolution:** ChatService is a new gRPC bidirectional stream service implementation created in Plan 05-02 Task 1. It wraps the `server` module's `NebulaServer` logic and owns LoginResp interception for Session binding.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Gson for Session JSON | kotlinx.serialization | Phase 4 quick task | All JSON handling in SessionRegistry uses kotlinx.serialization |
| Offset pagination | Cursor pagination (D-08) | D-08 decision | Stable under active writes; max 20 per page |
| Three options for login binding (system/bind, Handler callback, StreamObserver in login) | Gateway layer interception (D-04/D-05) | Phase 5 discussion | No extra round-trip; Handler stays pure; ChatService owns binding |
| Privacy only in MySQL | Redis first + async MySQL flush (D-09) | Phase 5 discussion | Immediate effect on batchGetOnlineStatus; eventual consistency for persistence |

## Environment Availability

> Skip this section: Phase 5 is code-only changes (new Handler implementations, Repository enhancements, Gateway layer wiring). All external dependencies (MySQL, Redis, JVM, Gradle) are already confirmed available from Phase 2/3.

## Sources

### Primary (HIGH confidence)
- **[Context7: spring-security-crypto]** `BCryptPasswordEncoder` API and Kotlin usage — [docs.spring.io/spring-security/reference/features/integrations/cryptography.html](https://docs.spring.io/spring-security/reference/features/integrations/cryptography.html)
- **[VERIFIED: codebase]** Session.kt, SessionRegistry.kt — Phase 4 Session architecture
- **[VERIFIED: codebase]** AuthInterceptor.kt — `extractToken()` stub, `skipMethods` pattern
- **[VERIFIED: codebase]** AuthInterceptor.kt — `extractToken()` opens the token extraction responsibility to Phase 5
- **[VERIFIED: codebase]** SessionRepository.kt — Redis key `session:token:{token}`, 7-day TTL
- **[VERIFIED: codebase]** OnlineStatusRepository.kt — Redis key `online:user:{userId}`
- **[VERIFIED: codebase]** UserEntity.kt — `username`, `passwordHash`, `privacyStatus` fields
- **[VERIFIED: codebase]** UserRepository.kt — `findByUsername()`, needs search enhancement
- **[VERIFIED: codebase]** UserException.kt — User domain exception class exists
- **[VERIFIED: codebase]** BizCode.kt — TOKEN_EXPIRED(1100), TOKEN_INVALID(1101), AUTH_FAILED(1102), USER_NOT_FOUND(1200), USERNAME_EXISTS(1201)
- **[VERIFIED: codebase]** PushEventType — LOGOUT = 4 defined in message_type.proto
- **[VERIFIED: codebase]** DeviceType — MOBILE(1), DESKTOP(2), WEB(3) defined in common.proto
- **[VERIFIED: codebase]** LoginReq/LoginResp — proto definitions exist with all fields
- **[VERIFIED: codebase]** Phase 4 PATTERNS.md — Handler interface, SessionKey, CoroutineContext pattern

### Secondary (MEDIUM confidence)
- **[CITED: mvnrepository.com]** `spring-security-crypto` 6.4.5 — Maven Central, standalone without Spring framework dependency
- **[VERIFIED: codebase]** GatewayModule.kt — `skipMethods = setOf("system/ping")`, must extend for login/register
- **[VERIFIED: codebase]** NebulaServer.kt — `registerHandlers()` call site, where Phase 5 handlers register
- **[VERIFIED: codebase]** RateLimitInterceptor.kt — `extractClientIp()` stub, needs implementation for D-02

### Tertiary (LOW confidence)
- None — all claims verified via codebase or official docs

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — spring-security-crypto is the de-facto standard; verified on Maven Central and official docs
- Architecture: HIGH — SessionRegistry, Interceptor Pipeline, and Gateway integration points verified in existing code
- Pitfalls: HIGH — all based on real-world chat system race conditions and the specific decisions in CONTEXT.md

**Research date:** 2026-06-12
**Valid until:** 2026-07-12 (stable dependencies, all verified)
