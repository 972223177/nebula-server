# Phase 4: Handler Framework — Pattern Map

**Mapped:** 2026-06-12
**Files analyzed:** 16 new files (14 production + 2 test files)
**Analogs found:** 8 with partial/exact match / 16 total

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `Handler.kt` | interface | suspend request-response | `BizException.kt` (class/KDoc conventions) | partial |
| `Session.kt` | data model | data | `ServerConfig.kt`, `SslConfig.kt` | exact |
| `SessionKey.kt` | context key | data (CoroutineContext) | — no analog — | none |
| `SessionRegistry.kt` | service | CRUD + L1/L2 cache | `SessionRepository.kt` | role-match |
| `ProtoCodec.kt` | utility | data transform | `ConfigLoader.kt` (object singleton pattern) | role-match |
| `HandlerRegistry.kt` | registry | CRUD (ConcurrentHashMap) | — no analog — | none |
| `Dispatcher.kt` | orchestrator | suspend request-response | `ChatServer.kt` (coroutine scope pattern) | partial |
| `Interceptor.kt` | interface | suspend chain-of-responsibility | — no analog — | none |
| `AuthInterceptor.kt` | middleware | suspend request-response | `SessionRepository.kt` (suspend Redis pattern) | partial |
| `LogInterceptor.kt` | middleware | suspend request-response | — no analog — | none |
| `ExceptionInterceptor.kt` | middleware | suspend error-handling | `BizException.kt` + `BizCode.kt` | exact (error) |
| `RateLimitInterceptor.kt` | middleware | stub request-response | — no analog — | none |
| `PingHandler.kt` | handler | suspend request-response | — no analog — | none |
| `GatewayModule.kt` | config | DI registration | — no analog (Koin new) — | none |
| `HandlerRegistryTest.kt` | test | test | — no tests exist yet — | none |
| `AuthInterceptorTest.kt` | test | test | — no tests exist yet — | none |

---

## Pattern Assignments

### `Handler.kt` (interface, suspend request-response)

**Analog:** `common/.../exception/BizException.kt` — project KDoc conventions and Kotlin generic interface style

**Imports & package pattern** (lines 1-2):
```kotlin
package com.nebula.gateway.handler

// Import pattern for cross-module types
import com.nebula.chat.Request
import com.nebula.chat.Response
```

**KDoc style** — copy from `BizException.kt` lines 5-12:
```kotlin
/**
 * 业务异常基类，所有领域异常均继承此类。
 *
 * 通过 [BizCode] 枚举统一管理错误码和错误消息，上层拦截器可据此进行统一格式化响应。
 * 继承链：BizException <- 领域异常 <- RuntimeException
 */
open class BizException(
    val bizCode: BizCode,
    override val message: String = bizCode.msg
) : RuntimeException(message)
```

**Generic interface pattern** — RESEARCH.md Pattern 1 provides the exact code:
```kotlin
interface Handler<Req : Any, Resp : Any> {
    val method: String
    suspend fun handle(req: Req): Resp
}
```

**CoroutineContext Key pattern** — `SessionKey` as inline data object:
```kotlin
data object SessionKey : CoroutineContext.Key<Session>

suspend fun CoroutineContext.requireSession(): Session {
    return this[SessionKey] ?: throw BizException(BizCode.UNAUTHORIZED)
}
```

---

### `Session.kt` (data model, data)

**Analog:** `common/.../config/ServerConfig.kt` (data class + KDoc parameter docs)

**Imports pattern** (none needed — pure data class):

**Full file pattern** — copy structure from `ServerConfig.kt` lines 1-11:
```kotlin
package com.nebula.gateway.session

/**
 * Session 会话数据模型。
 *
 * 由 AuthInterceptor 在认证通过后创建，通过 CoroutineContext 隐式传递给 Handler。
 * 字段对应设计文档 4.2 Token 方案中的 Session 数据结构。
 *
 * @param userId 用户 ID
 * @param token 会话 Token（随机 UUID 字符串）
 * @param deviceType 客户端设备类型（如 "android", "ios", "web"）
 * @param deviceId 客户端设备唯一标识
 * @param connectionId gRPC 连接唯一标识（对应 StreamObserver 关联）
 */
data class Session(
    val userId: Long,
    val token: String,
    val deviceType: String,
    val deviceId: String,
    val connectionId: String
)
```

---

### `SessionRegistry.kt` (service, CRUD + L1/L2 cache)

**Analog:** `repository/.../redis/SessionRepository.kt` — suspend Redis operations, companion object constants

**Imports pattern** — copy from `SessionRepository.kt` lines 1-6:
```kotlin
package com.nebula.gateway.session

import com.nebula.repository.redis.SessionRepository
import kotlinx.coroutines.CopyOnWriteArrayList  // or java.util.concurrent
```

**Companion object / const pattern** — copy from `SessionRepository.kt` lines 22-24:
```kotlin
companion object {
    private const val KEY_PREFIX = "session:token:"
    private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L  // 7 天
}
```

**Core class pattern** — RESEARCH.md Pattern 5 provides the full implementation:
```kotlin
class SessionRegistry(
    private val sessionRepository: SessionRepository
) {
    private val localCache = ConcurrentHashMap<String, Session>()    // token → Session
    private val userIdIndex = ConcurrentHashMap<Long, MutableSet<String>>() // userId → tokens
    private val evictionCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    fun onEviction(callback: (token: String) -> Unit) {
        evictionCallbacks.add(callback)
    }

    suspend fun validate(token: String): Session? {
        return getFromLocalCache(token) ?: queryFromRedis(token)?.also {
            addToLocalCache(it)
        }
    }

    suspend fun register(session: Session) {
        addToLocalCache(session)
        saveToRedis(session)
    }

    suspend fun unregister(token: String) {
        removeFromLocalCache(token)
        removeFromRedis(token)
        evictionCallbacks.forEach { it(token) }
    }
}
```

**Suspend delegate pattern** — copy from `SessionRepository.kt` lines 20:
```kotlin
private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())
```

---

### `ProtoCodec.kt` (utility, data transform)

**Analog:** `server/.../config/ConfigLoader.kt` — `object` singleton pattern with KDoc

**Object singleton pattern** — copy from `ConfigLoader.kt` lines 14-28:
```kotlin
/**
 * Proto 编解码器 — MethodHandles 预编译，运行时零反射开销。
 *
 * 注册 Handler 时一次性查找并缓存方法引用：
 * - parseFrom: static method, (byte[]) → ProtoMsg
 * - toByteArray: instance method, () → byte[]
 */
object ProtoCodec {
```

**MethodHandles core pattern** — from RESEARCH.md Pattern 4 lines 428-459:
```kotlin
fun buildCodec(protoClass: KClass<*>): CodecPair {
    val javaClass = protoClass.java
    val lookup = MethodHandles.lookup()

    val parseFromHandle = lookup.findStatic(
        javaClass,
        "parseFrom",
        MethodType.methodType(javaClass, ByteArray::class.java)
    )

    val toByteArrayHandle = lookup.findVirtual(
        javaClass,
        "toByteArray",
        MethodType.methodType(ByteArray::class.java)
    )

    return CodecPair(
        parseFrom = { bytes ->
            @Suppress("UNCHECKED_CAST")
            parseFromHandle.invoke(bytes) as Any
        },
        toByteArray = { obj -> toByteArrayHandle.invoke(obj) as ByteArray }
    )
}

data class CodecPair(
    val parseFrom: (ByteArray) -> Any,
    val toByteArray: (Any) -> ByteArray
)
```

---

### `HandlerRegistry.kt` (registry, CRUD lookup)

**Analog:** No direct analog in codebase. Based on RESEARCH.md Pattern 3.

**Core pattern** — from RESEARCH.md Pattern 3:
```kotlin
data class HandlerEntry(
    val handler: Handler<*, *>,
    val reqClass: KClass<*>,
    val respClass: KClass<*>,
    val parseFrom: (ByteArray) -> Any,
    val toByteArray: (Any) -> ByteArray
)

class HandlerRegistry {
    private val registry = ConcurrentHashMap<String, HandlerEntry>()

    fun register(entry: HandlerEntry) {
        check(registry.putIfAbsent(entry.handler.method, entry) == null) {
            "Duplicate method: ${entry.handler.method}"
        }
    }

    fun get(method: String): HandlerEntry? = registry[method]
}
```

**Import pattern:**
```kotlin
package com.nebula.gateway.dispatcher

import com.nebula.gateway.handler.Handler
import org.slf4j.kotlin  // or KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
```

---

### `Dispatcher.kt` (orchestrator, suspend request-response)

**Analog:** `server/.../server/ChatServer.kt` — coroutine scope, lifecycle management, KDoc style

**Coroutine scope pattern** — copy KDoc + scope pattern from `ChatServer.kt` lines 9-23:
```kotlin
/**
 * 请求分发器 — Pipeline 编排入口。
 *
 * 职责：
 * - 接收 Envelope Request，根据 method 查找 Handler
 * - 通过 ProtoCodec 反序列化请求参数
 * - 通过 Interceptor Pipeline 链执行请求处理
 * - 序列化结果为 Response 返回
 *
 * 设计决策引用：
 * - D-02: CoroutineScope(Dispatchers.IO + SupervisorJob) 全局单作用域
 * - D-14: 返回完整 Response proto，不直接操作 StreamObserver
 * - D-15: dispatch() 签名：suspend fun dispatch(envelopeRequest: Request): Response
 */
class Dispatcher(
    private val handlerRegistry: HandlerRegistry,
    private val interceptors: List<Interceptor>,
    private val protoCodec: ProtoCodec
) {
```

**Core dispatch pattern** — from RESEARCH.md Example 1:
```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() +
    CoroutineExceptionHandler { _, e ->
        logger.error(e) { "Unhandled exception in dispatcher scope" }
    })

suspend fun dispatch(envelopeRequest: Request): Response {
    val method = envelopeRequest.method
    val entry = handlerRegistry.get(method)
        ?: return Response.newBuilder()
            .setCode(BizCode.NOT_FOUND.code)
            .setMsg("method not found: $method")
            .build()

    @Suppress("UNCHECKED_CAST")
    val req = protoCodec.deserialize(entry, envelopeRequest.params)

    val handlerChain = object : Interceptor.Chain {
        override val request: Request = envelopeRequest
        override suspend fun proceed(request: Request): Response {
            @Suppress("UNCHECKED_CAST")
            val result = (entry.handler as Handler<Any, Any>).handle(req)
            val resultBytes = protoCodec.serialize(entry, result)
            return Response.newBuilder()
                .setCode(BizCode.OK.code)
                .setMethod(method)
                .setResult(resultBytes)
                .build()
        }
    }

    val pipeline = interceptors.foldRight(handlerChain) { interceptor, chain ->
        InterceptorChain(interceptor, chain)
    }

    return pipeline.proceed(envelopeRequest)
}
```

**InterceptorChain helper:**
```kotlin
class InterceptorChain(
    private val interceptor: Interceptor,
    private val next: Interceptor.Chain
) : Interceptor.Chain {
    override val request: Request get() = next.request
    override suspend fun proceed(request: Request): Response {
        return interceptor.intercept(request, next)
    }
}
```

---

### `Interceptor.kt` (interface, suspend chain-of-responsibility)

**Analog:** No direct analog. Based on RESEARCH.md Pattern 2.

**Core interface pattern** — from RESEARCH.md lines 340-358:
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response

/**
 * 拦截器接口 — suspend 版本的责任链模式（GoF Chain of Responsibility）。
 *
 * 通过 Koin 以 List<Interceptor> 方式注入，Dispatcher 组装为链。
 * 执行顺序（D-07）：
 *   AuthInterceptor → LogInterceptor → RateLimitInterceptor → ExceptionInterceptor
 */
interface Interceptor {
    /** 拦截处理请求 */
    suspend fun intercept(request: Request, chain: Chain): Response

    /** 责任链接口 */
    interface Chain {
        /** 请求的当前 Request 对象（拦截器可修改后传递） */
        val request: Request

        /** 继续执行下一个拦截器（或最终 Handler） */
        suspend fun proceed(request: Request): Response
    }
}
```

---

### `AuthInterceptor.kt` (middleware, suspend request-response)

**Analog:** `repository/.../redis/SessionRepository.kt` — suspend Redis operations, companion object, KDoc

**Core pattern** — from RESEARCH.md Example 2:
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.session.SessionKey
import com.nebula.gateway.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

class AuthInterceptor(
    private val sessionRegistry: SessionRegistry,
    private val skipMethods: Set<String> = setOf("system/ping")
) : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val method = request.method

        // 白名单方法跳过认证
        if (method in skipMethods) {
            return chain.proceed(request)
        }

        // 从 Request 中提取 token
        val token = extractToken(request)
            ?: return Response.newBuilder()
                .setCode(BizCode.UNAUTHORIZED.code)
                .setMsg(BizCode.UNAUTHORIZED.msg)
                .build()

        // 验证 Session
        val session = sessionRegistry.validate(token)
            ?: return Response.newBuilder()
                .setCode(BizCode.TOKEN_INVALID.code)
                .setMsg(BizCode.TOKEN_INVALID.msg)
                .build()

        // 注入 Session 到 CoroutineContext
        return withContext(SessionKey(session)) {
            chain.proceed(request)
        }
    }

    private fun extractToken(request: Request): String? {
        // TODO: Phase 5 确定 token 传递方式后实现
        return null
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
```

**Logging pattern** — copy from `ConfigLoader.kt` line 30:
```kotlin
private val log = KotlinLogging.logger {}
```

---

### `LogInterceptor.kt` (middleware, suspend request-response)

**Analog:** No direct analog. Logging pattern from `ConfigLoader.kt` (KotlinLogging).

**Core pattern:**
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.measureTimeMillis

class LogInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val method = request.method

        val response = measureTimeMillis {
            chain.proceed(request)
        }.let { elapsed ->
            log.info { "[$method] completed in ${elapsed}ms" }
            // 实际响应存储在 measureTimeMillis 之外
        }

        // Simpler approach:
        val start = System.currentTimeMillis()
        val resp = chain.proceed(request)
        val elapsed = System.currentTimeMillis() - start

        if (resp.code != 200) {
            log.warn { "[$method] failed: code=${resp.code} msg=${resp.msg} (${elapsed}ms)" }
        } else {
            log.info { "[$method] success (${elapsed}ms)" }
        }

        return resp
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
```

---

### `ExceptionInterceptor.kt` (middleware, suspend error-handling)

**Analog:** `common/.../BizCode.kt` (exception status code enum) + `common/.../exception/BizException.kt` (exception hierarchy)

**BizCode import pattern** — copy from `BizException.kt` line 3:
```kotlin
import com.nebula.common.BizCode
```

**Core exception mapping pattern** — from RESEARCH.md Example 3:
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.github.oshai.kotlinlogging.KotlinLogging

class ExceptionInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(request)
        } catch (e: BizException) {
            // D-10: BizException → 业务状态码
            Response.newBuilder()
                .setCode(e.bizCode.code)
                .setMsg(e.message ?: e.bizCode.msg)
                .setMethod(request.method)
                .build()
        } catch (e: IllegalArgumentException) {
            // D-10: 参数异常 → BAD_REQUEST
            Response.newBuilder()
                .setCode(BizCode.INVALID_PARAM.code)
                .setMsg(e.message ?: BizCode.INVALID_PARAM.msg)
                .setMethod(request.method)
                .build()
        } catch (e: Exception) {
            // D-10: 未预期异常 → INTERNAL_ERROR(9000)，不暴露堆栈
            logger.error(e) { "Unhandled exception for method ${request.method}" }
            Response.newBuilder()
                .setCode(BizCode.INTERNAL_ERROR.code)
                .setMsg(BizCode.INTERNAL_ERROR.msg)
                .setMethod(request.method)
                .build()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
```

---

### `RateLimitInterceptor.kt` (middleware, stub request-response)

**Analog:** No direct analog. Stub implementation.

**Stub pattern:**
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 限流拦截器 — Token Bucket 算法实现。
 *
 * D-08 要求实现，Phase 11 精细化调优。
 * 当前实现为骨架占位：已认证用户按 userId 限流，未认证按 IP 限流。
 */
class RateLimitInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        // TODO: Phase 11 实现完整限流逻辑
        // val userId = coroutineContext[SessionKey]?.userId
        // val key = userId?.toString() ?: extractClientIp(request)
        // if (!tokenBucket.tryConsume(key)) {
        //     return Response.newBuilder()
        //         .setCode(BizCode.RATE_LIMITED.code)
        //         .setMsg(BizCode.RATE_LIMITED.msg)
        //         .build()
        // }
        return chain.proceed(request)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
```

---

### `PingHandler.kt` (handler, suspend request-response)

**Analog:** No direct analog. Simple Handler implementation.

**Core pattern:**
```kotlin
package com.nebula.gateway.handler

import com.nebula.chat.Request  // or EmptyRequest proto
import com.nebula.chat.Response  // or EmptyResponse proto

/**
 * 应用层心跳 Handler — method = "system/ping"。
 *
 * 双重心跳策略（D-27）的业务层组件：
 * - 检测 NAT/代理导致的半开连接
 * - 与普通业务消息走在同一数据通道上
 * - AuthInterceptor 和 LogInterceptor 通过 skipMethods 跳过此 Handler
 *
 * D-30: 心跳请求不经过认证/日志拦截，直接返回 PONG。
 */
class PingHandler : Handler<Request, Response> {  // Or appropriate proto types
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

---

### `GatewayModule.kt` (config, DI registration)

**Analog:** No direct analog (Koin is new to the project). Based on RESEARCH.md Pattern 6.

**Koin module pattern** — from RESEARCH.md lines 532-563:
```kotlin
package com.nebula.gateway.di

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.Interceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.session.SessionRegistry
import org.koin.dsl.module

/**
 * 框架级 Koin 模块 — 注册所有基础设施组件。
 */
val frameworkModule = module {
    single { HandlerRegistry() }
    single { ProtoCodec }
    single { SessionRegistry(get()) }  // SessionRepository from Koin

    // 拦截器以 List<Interceptor> 注入 — 顺序由 D-07 决定
    single<Interceptor> { AuthInterceptor(get(), skipMethods = setOf("system/ping")) }
    single<Interceptor> { LogInterceptor() }
    single<Interceptor> { RateLimitInterceptor() }
    single<Interceptor> { ExceptionInterceptor() }
}

/**
 * 业务 Handler Koin 模块 — 注册业务 Handler。
 */
val handlerModule = module {
    single { PingHandler() }
}
```

**Koin init pattern** — for `NebulaServer.kt` update (from RESEARCH.md):
```kotlin
startKoin {
    modules(frameworkModule, handlerModule)
}
```

---

## Test Patterns

### `HandlerRegistryTest.kt` (test, JUnit5 + MockK + runTest)

**Analog:** No existing tests in project. Based on RESEARCH.md Example 4.

**Test pattern** — from RESEARCH.md lines 822-853:
```kotlin
package com.nebula.gateway.dispatcher

import com.nebula.gateway.handler.PingHandler
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HandlerRegistryTest : KoinTest {

    private val handlerRegistry by inject<HandlerRegistry>()

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(module {
            single { HandlerRegistry() }
        })
    }

    @Test
    fun registerAndLookupHandler() = runTest {
        val handler = PingHandler()
        val entry = HandlerEntry(
            handler = handler,
            reqClass = Unit::class,
            respClass = Unit::class,
            parseFrom = { bytes -> Any() },
            toByteArray = { ByteArray(0) }
        )
        handlerRegistry.register(entry)

        val found = handlerRegistry.get("system/ping")
        assertNotNull(found)
        assertEquals(handler, found.handler)
    }
}
```

---

### `AuthInterceptorTest.kt` (test, JUnit5 + MockK + runTest)

**Test pattern** — from RESEARCH.md Example 5:
```kotlin
package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.session.SessionRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AuthInterceptorTest {

    @Test
    fun skipAuthForSystemPing() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptor = AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping"))

        val request = Request.newBuilder().setMethod("system/ping").build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(200, resp.code)
        coVerify(inverse = true) { sessionRegistry.validate(any()) }
    }

    @Test
    fun rejectWhenTokenInvalid() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        coEvery { sessionRegistry.validate(any()) } returns null

        val interceptor = AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping"))

        val request = Request.newBuilder().setMethod("user/login").build()
        val mockChain = mockk<Interceptor.Chain>()

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(BizCode.TOKEN_INVALID.code, resp.code)
        coVerify(exactly = 1) { sessionRegistry.validate(any()) }
        coVerify(inverse = true) { mockChain.proceed(any()) }
    }
}
```

---

## Shared Patterns

### KDoc Conventions (Chinese)
**Source:** All existing `common/` and `server/` module files
**Apply to:** All new files

```kotlin
/**
 * 中文 KDoc 说明该类型的职责和用途。
 *
 * （可选：关键设计决策引用 D-编号）。
 *
 * @param paramName 参数说明
 */
```

### Package Declaration
**Source:** All existing files
**Apply to:** All new files
```kotlin
package com.nebula.gateway.{dispatcher|interceptor|codec|session|handler}
```

### Logging
**Source:** `server/.../config/ConfigLoader.kt` line 30
**Apply to:** All interceptor and service files
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

// In companion object:
private val log = KotlinLogging.logger {}
```

### Protobuf Builder Pattern
**Source:** `envelope.proto` — `Request`/`Response` messages
**Apply to:** Dispatcher, all Interceptors, PingHandler
```kotlin
Response.newBuilder()
    .setCode(BizCode.OK.code)
    .setMsg("ok")
    .setMethod(method)
    .setResult(resultBytes)
    .build()
```

### Suspend Function Pattern
**Source:** `SessionRepository.kt`, `OnlineStatusRepository.kt`
**Apply to:** All new interface/service files
```kotlin
suspend fun methodName(param: Type): ReturnType {
    // suspend body
}
```

### Error Response Pattern
**Source:** `BizCode.kt` enum
**Apply to:** AuthInterceptor, ExceptionInterceptor
```kotlin
Response.newBuilder()
    .setCode(BizCode.UNAUTHORIZED.code)
    .setMsg(BizCode.UNAUTHORIZED.msg)
    .build()
```

### Coroutine Scope Pattern
**Source:** `ChatServer.kt` lines 41-49 + RESEARCH.md Pattern Dispatcher
**Apply to:** Dispatcher
```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() +
    CoroutineExceptionHandler { _, e ->
        logger.error(e) { "Unhandled exception in dispatcher scope" }
    })
```

---

## No Analog Found

Files with no close match in the codebase (planner should use RESEARCH.md patterns instead):

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `Interceptor.kt` | interface | chain-of-responsibility | 项目首次引入拦截器链模式 |
| `SessionKey.kt` | context key | data | Kotlin CoroutineContext.Key — 标准库 API |
| `HandlerRegistry.kt` | registry | ConcurrentHashMap lookup | 项目首次引入 Handler 注册模式 |
| `RateLimitInterceptor.kt` | middleware | stub | 限流为全新功能 |
| `PingHandler.kt` | handler | request-response | 首个 Handler 实现 |
| `GatewayModule.kt` | config | DI | Koin 首次引入项目 |
| All test files | test | test | 项目尚无现有测试 |

---

## Metadata

**Analog search scope:**
- `server/src/main/kotlin/` — `ChatServer.kt`, `ConfigLoader.kt`
- `common/src/main/kotlin/` — `BizCode.kt`, `BizException.kt`, all config data classes
- `repository/src/main/kotlin/` — `SessionRepository.kt`, `OnlineStatusRepository.kt`, `RedisConfig.kt`
- 设计文档 `08-Handler层设计/` — 8.1~8.5
- 设计文档 `04-认证与会话/` — 4.1, 4.2

**Files scanned:** ~25 source files + 5 design docs
**Pattern extraction date:** 2026-06-12
