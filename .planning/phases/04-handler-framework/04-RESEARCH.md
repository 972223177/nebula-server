# Phase 4: Handler Framework - Research

**Researched:** 2026-06-12
**Domain:** Kotlin Handler 接口契约、Dispatcher 路由、Koin DI、Interceptor Pipeline、Session 双级缓存、ProtoCodec、双重心跳
**Confidence:** HIGH

## Summary

本阶段构建 `:gateway` 模块的核心骨架 — 泛型 Handler 接口、Dispatcher 请求分发、Koin 依赖注入、Interceptor Pipeline（认证/日志/限流/异常兜底）、SessionRegistry 双级缓存、ProtoCodec 零反射序列化，以及应用层心跳 Handler。Phase 5+ 的业务 Handler 全部继承此框架。

关键技术发现：

1. **Handler 接口与设计文档 v1.2 8.1 有差异** — 设计文档定义 `fun handle(req: Req, session: Session): Resp`，但 CONTEXT.md D-01 锁定为 `suspend fun handle(req: Req): Resp`，Session 通过 CoroutineContext 隐式传递（D-03），这是关键架构变更 [CITED: CONTEXT.md D-01/D-03]

2. **Koin 4.1.0 是最新版**（2025-06 发布），已在 Maven Central 验证 [VERIFIED: search.maven.org]。项目当前未使用 Koin，需在 `libs.versions.toml` 新增依赖声明，在 `:gateway` 和 `:server` 模块的 `build.gradle.kts` 中添加 implementation 依赖。

3. **ProtoCodec 的 MethodHandles 预编译方案** — 设计文档 8.3 的 `ProtoCodec` 通过 `method → (request proto class, response proto class)` 做运行时反射。D-12 升级为预编译 MethodHandles 缓存策略 — Handler 注册时缓存 `parseFrom()`/`toByteArray()` 的方法引用，运行时零反射开销。

4. **双重心跳（D-27~D-31）** — ChatServer.kt（Phase 2）gRPC keepalive 配置需从原有参数升级为业界精细化标准；新增应用层 PING/PONG 通过 `system/ping` Handler 走标准 Dispatcher 路由。

5. **SessionRegistry 双级缓存** — D-18 锁定 L1（ConcurrentHashMap）+ L2（Redis SessionRepository）。D-19 定义细粒度 API 方法。缓存一致性通过 D-20 回调机制实现。

**Primary recommendation:** 由于 Handler 接口从同步非 suspend 变为 suspend + CoroutineContext 传递 Session，这是与设计文档最显著的偏差。所有 Interceptor、Dispatcher、Pipeline 必须统一为 suspend 变体。Koin 注册模式按设计文档 8.2 的显式 `register()` 方式实现。

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Handler 接口定义为 `suspend fun handle(req: Req): Resp` — 所有 Handler 和 Interceptor 统一为 suspend 函数，与全局协程生态一致。
- **D-02:** Dispatcher 使用 `CoroutineScope(Dispatchers.IO + SupervisorJob)` — 全局单作用域，ChatServer 级别生命周期管理，SupervisorJob 隔离单个 Handler 异常。
- **D-03:** Session 通过 CoroutineContext 隐式传递 — Handler 接口不显式携带 Session 参数，由 AuthInterceptor 注入协程上下文，Handler KDoc 中注明获取方式。
- **D-04:** 协程异常通过 `CoroutineExceptionHandler` 统一捕获，送入 ExceptionInterceptor 处理。
- **D-05:** Interceptor 接口全部定义为 suspend，Pipeline 统一为协程链。
- **D-06:** 采用设计文档 8.3 的 Interceptor/Chain 接口模式（GoF Chain of Responsibility），适配为 suspend 版本。拦截器通过 Koin `List<Interceptor>` 注入。
- **D-07:** 拦截器执行顺序：AuthInterceptor → LogInterceptor → RateLimitInterceptor → ExceptionInterceptor（ExceptionInterceptor 作为链尾包裹 Handler）。
- **D-08:** 实现 4 个拦截器：AuthInterceptor、LogInterceptor、RateLimitInterceptor、ExceptionInterceptor。
- **D-09:** AuthInterceptor 内部维护 `skipMethods: Set<String>` 白名单控制跳过认证的方法（如 "system/ping"）。
- **D-10:** ExceptionInterceptor 三态异常处理：BizException→业务状态码、IllegalArgumentException→BAD_REQUEST、未预期异常→INTERNAL_ERROR(9000) 不暴露堆栈细节。
- **D-11:** HandlerRegistry 持有 `ConcurrentHashMap<String, HandlerEntry>`，Entry 包含 Handler 实例 + Req/Resp 的序列化方法引用。运行时通过 method 查表。
- **D-12:** ProtoCodec 采用预编译方法引用缓存策略 — 注册时缓存 `parseFrom()`/`toByteArray()` 的方法引用 (MethodHandles)，运行时零反射开销。
- **D-13:** HandlerRegistry 自身持有类型信息 — HandlerEntry 包含 Handler + Req KClass + Resp KClass + 序列化/反序列化方法引用。
- **D-14:** Dispatcher 返回完整 `Response` proto 对象，不直接操作 StreamObserver，保持与 gRPC 的解耦。
- **D-15:** `Dispatcher.dispatch()` 签名：`suspend fun dispatch(envelopeRequest: Request): Response`。
- **D-16:** Session 数据模型字段：`userId`, `token`, `deviceType`, `deviceId`, `connectionId`。
- **D-17:** SessionRegistry 放在 :gateway 模块，依赖 :repository 模块的 SessionRepository（Redis 操作）。
- **D-18:** SessionRegistry 采用本地内存 (ConcurrentHashMap) + Redis 二级缓存策略。本地作为 L1 缓存，Redis 作为持久化存储。
- **D-19:** SessionRegistry API 采用统一入口 + 细粒度方法：`addToLocalCache()`/`removeFromLocalCache()`/`getFromLocalCache()`、`saveToRedis()`/`removeFromRedis()`/`queryFromRedis()`、以及组合方法 `validate()`/`register()`/`unregister()`。
- **D-20:** Session 缓存一致性采用注册回调机制 — SessionRegistry 提供 `(token) -> Unit` 回调注册点，ChatGatewayImpl 在创建 Session 时注册。本地驱逐时通知关闭对应 StreamObserver。
- **D-21:** 沿用设计文档 8.5 的标准包结构：
  - `com.nebula.gateway.dispatcher` — HandlerRegistry, Dispatcher
  - `com.nebula.gateway.interceptor` — Interceptor, AuthInterceptor, LogInterceptor, ExceptionInterceptor, RateLimitInterceptor
  - `com.nebula.gateway.codec` — ProtoCodec
  - `com.nebula.gateway.session` — Session, SessionRegistry
  - `com.nebula.gateway.handler.{domain}` — 按业务域分包
- **D-22（已废弃）：** ~~心跳完全由 gRPC 内置 keepalive 机制处理~~ → D-27 替代
- **D-27:** 双重心跳策略：gRPC keepalive（传输层）快速检测 TCP 断开 + 应用层 PING/PONG（业务层）检测 NAT/代理半开连接。
- **D-28:** 应用层心跳通过普通 Handler `method = "system/ping"` 实现，走标准 Dispatcher + Pipeline 路由。
- **D-29:** 心跳超时优雅降级：T1（450s 无 PING）标记可疑 → T2（900s 仍无 PING）强制断开。
- **D-30:** AuthInterceptor 和 LogInterceptor 跳过 `"system/ping"` 方法。
- **D-31:** Proto `envelope.proto` Direction 枚举还原 PING(4)/PONG(5) 为有效值。更新 ChatServer.kt keepalive 配置。
- **D-23:** 测试框架使用 JUnit5 + MockK，与项目现有配置一致。
- **D-24:** 单元测试覆盖框架各组件：HandlerRegistry、Dispatcher、各 Interceptor、ProtoCodec、SessionRegistry。
- **D-25:** suspend 函数测试使用 `runTest { }` (kotlinx-coroutines-test) + MockK `coEvery{}`/`coVerify{}`。
- **D-26:** Dispatcher + Pipeline 采取 Mock 全链路测试 — MockHandlerRegistry + MockInterceptorChain 验证编排顺序。

### Claude's Discretion
- 心跳 Handler 的 `system/ping` 方法名可协商
- 应用层 PING 超时 T1/T2 数值可在实现阶段根据实际测试调整（参考：T1=450s, T2=900s）
- gRPC keepalive 配置中的 Timeout 和 MaxConnectionIdle 等参数可在实现时根据运行环境微调
- 心跳 Handler 是否需要独立单元测试由实现者决定

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within Phase 4 scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| HNDL-01 | Generic Handler interface: Handler<ReqT, RespT> with method() binding | 设计文档 8.1 定义基础接口，D-01/D-03 锁定为 suspend 变体 + Session 通过 CoroutineContext 传递 |
| HNDL-02 | Dispatcher deserializes Request payload, routes to Handler, serializes Response | 设计文档 8.3 提供 Pipeline 流程，D-11~D-15 锁定 HandlerRegistry + ProtoCodec + dispatch 签名 |
| HNDL-03 | Koin module registers all Handlers with explicit method→Handler bindings | 设计文档 8.2 提供注册模式，Koin 4.1.0 已验证可用 [VERIFIED: Maven Central] |
| HNDL-04 | Interceptor Pipeline: authentication, logging, exception handling in chain | 设计文档 8.3 定义 Interceptor/Chain 模式，D-06~D-09 锁定顺序 + skipMethods |
| HNDL-05 | BizException converts to typed gRPC Status, ExceptionInterceptor catches and formats | 设计文档 8.4 异常处理，D-10 锁定三态映射，BizCode.kt + BizException.kt (Phase 2) 可直接复用 |
| HNDL-06 | Handler directories organized by domain | D-21 锁定包结构，设计文档 8.5 提供目录模板 |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Handler 接口契约 | gateway 模块 | — | 泛型接口定义在 `com.nebula.gateway.handler`，供 Phase 5+ 业务 Handler 实现 |
| Dispatcher 路由分发 | gateway 模块 | — | 接收 Request → 查 HandlerRegistry → 序列化/反序列化 → 返回 Response |
| Interceptor Pipeline | gateway 模块 | — | 4 个拦截器全部在 gateway 模块实现，Chain of Responsibility 模式 |
| Koin 注册管理 | gateway 模块 | server 模块 | Koin module 定义在 gateway；server 模块的 NebulaServer.kt 执行 `startKoin { }` 加载 |
| ProtoCodec | gateway 模块 | proto 模块 | 编解码逻辑在 gateway，消费 proto 模块生成的 Java/Kotlin 类 |
| SessionRegistry | gateway 模块 | repository 模块 | SessionRegistry 在 gateway 模块实现，依赖 repository 模块的 SessionRepository(Redis) |
| Session L1 缓存 | gateway 模块 | — | ConcurrentHashMap 本地缓存，AuthInterceptor 读取 |
| 应用层心跳 | gateway 模块 | — | `system/ping` Handler 注册到 Dispatcher，走标准 Pipeline |
| gRPC keepalive 配置 | server 模块 | — | 已在 ChatServer.kt (Phase 2) 中配置，需要更新为精细化参数 (D-27) |

## Standard Stack

### Core Dependencies (新增)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.insert-koin:koin-core` | 4.1.0 | 依赖注入框架 | D-06 锁定；Kotlin 生态最主流的 DI 框架，DSL 友好，零反射 [VERIFIED: search.maven.org, Context7] |
| `io.insert-koin:koin-test` | 4.1.0 | Koin 测试支持 | D-23 锁定；配合 JUnit5 的 KoinTestExtension 进行 DI 测试 [CITED: Context7 /insertkoinio/koin] |
| `io.insert-koin:koin-test-junit5` | 4.1.0 | JUnit5 Koin 集成 | JUnit 5 扩展支持 (@RegisterExtension) [CITED: Context7 /insertkoinio/koin] |
| `io.mockk:mockk` | 1.13.x | suspend 函数 Mock | D-23/D-25 锁定；`coEvery`/`coVerify` 原生支持 Kotlin 协程 [CITED: Context7 /mockk/mockk] |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | 协程测试 | D-25 锁定；`runTest` 函数、虚拟时间控制 [CITED: Context7 /kotlin/kotlinx.coroutines] |
| `org.jetbrains.kotlin:kotlin-reflect` | 2.1.20 | KClass 反射获取 | D-13 需要；已存在于 libs.versions.toml [CITED: libs.versions.toml] |

### 已存在依赖（无需新增）

| Library | Version | 用途 |
|---------|---------|------|
| `com.nebula:proto` (module) | — | Proto Request/Response 类型的来源 |
| `com.nebula:service` (module) | — | gateway 模块通过 :service → :repository 间接依赖 SessionRepository |
| `com.nebula:repository` (module) | — | SessionRepository (Redis) L2 缓存依赖 |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Koin 4.1.0 | Spring DI / Dagger Hilt | Koin 最轻量、Kotlin 原生 DSL、无注解处理、无编译期代码生成、JAR 仅 ~200KB |
| MockK | Mockito | MockK 原生支持 Kotlin 特性：suspend 函数 (coEvery)、data class、object、KProperty |
| kotlinx-coroutines-test | runBlocking 手动测试 | runTest 提供虚拟时间控制、delay 自动跳过、TestScope 生命周期管理 |

**Installation:**
```toml
# gradle/libs.versions.toml — 新增
[versions]
koin = "4.1.0"
mockk = "1.13.14"
kotlinx-coroutines = "1.9.0"

[libraries]
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-test = { module = "io.insert-koin:koin-test", version.ref = "koin" }
koin-test-junit5 = { module = "io.insert-koin:koin-test-junit5", version.ref = "koin" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
```

```kotlin
// gateway/build.gradle.kts — 新增依赖
dependencies {
    implementation(project(":service"))
    implementation(project(":proto"))
    
    // Phase 4: Handler Framework
    implementation(libs.koin.core)
    implementation(libs.kotlin.reflect)
    
    // Test
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

```kotlin
// server/build.gradle.kts — 修改：ChatGatewayImpl 需要注册到 gRPC Server
dependencies {
    implementation(project(":gateway"))   // 已有
    implementation(project(":proto"))     // 已有
    implementation(project(":common"))    // 已有
    
    // Phase 4: Koin 启动
    implementation(libs.koin.core)
}
```

## Package Legitimacy Audit

> 本阶段通过 Maven Central 引入依赖。slopcheck 不适用于 Maven 仓库（pip 未安装），以下基于 Maven Central 和 Context7 验证。

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `io.insert-koin:koin-core` | Maven Central | 8+ yrs | Very High | github.com/insertkoin/koin | N/A (Maven) | [VERIFIED: search.maven.org, Context7] |
| `io.insert-koin:koin-test` | Maven Central | 7+ yrs | High | github.com/insertkoin/koin | N/A (Maven) | [VERIFIED: search.maven.org, Context7] |
| `io.insert-koin:koin-test-junit5` | Maven Central | 7+ yrs | High | github.com/insertkoin/koin | N/A (Maven) | [VERIFIED: search.maven.org, Context7] |
| `io.mockk:mockk` | Maven Central | 6+ yrs | High | github.com/mockk/mockk | N/A (Maven) | [CITED: Context7 /mockk/mockk] |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Maven Central | 6+ yrs | Very High | github.com/Kotlin/kotlinx.coroutines | N/A (Maven) | [CITED: Context7 /kotlin/kotlinx.coroutines] |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**Note:** All packages are well-established Maven Central artifacts from reputable organizations (InsertKoin, JetBrains, MockK).

## Architecture Patterns

### System Architecture Diagram

```
                        ┌──────────────────────────────────────────────────────┐
                        │                   Phase 4 Deliverables               │
                        │                                                      │
                        │  ┌─────────────────────┐  ┌──────────────────────┐  │
            Request ────►│   ChatGatewayImpl     │  │   Koin Initialization │  │
            (gRPC stream)│   (入口 - Phase 4+ 补)  │  │   startKoin {         │  │
                        │   onRequest(req) ──────┼─►│     modules(modules) │  │
                        └─────────┬───────────────┘  └──────────────────────┘  │
                                  │                                              │
                                  ▼ dispatch(envelopeRequest)                    │
                        ┌──────────────────────────────────────────────────┐   │
                        │              Dispatcher                          │   │
                        │  ┌──────────────┐  ┌─────────────┐              │   │
                        │  │HandlerRegistry│  │ ProtoCodec  │              │   │
                        │  │ CMap<String,  │  │ MethodHandle │              │   │
                        │  │   HandlerEntry│  │ parseFrom() │              │   │
                        │  └──────┬───────┘  │ toByteArray │              │   │
                        │         │          └──────┬──────┘              │   │
                        └─────────┼─────────────────┼──────────────────────┘   │
                                  │                 │                          │
                                  ▼                 ▼                          │
                        ┌────────────────────────────────────────────────────┐ │
                        │              Interceptor Pipeline                 │ │
                        │                                                    │ │
                        │   AuthInterceptor ──► LogInterceptor ──►           │ │
                        │     (skipMethods:                                    │ │
                        │      "system/ping") │                               │ │
                        │                      ▼                              │ │
                        │   RateLimitInterceptor ──► ExceptionInterceptor     │ │
                        │                          (链尾，包裹 Handler)       │ │
                        └──────────────────────────┬─────────────────────────┘ │
                                                   │                           │
                                                   ▼ Handler.handle(req)       │
                        ┌──────────────────────────────────────────────────┐   │
                        │            PingHandler                           │   │
                        │            (system/ping，跳过 Auth/Log)           │   │
                        └──────────────────────────────────────────────────┘   │
                                                                               │
                        ┌──────────────────────────────────────────────────┐   │
                        │            SessionRegistry                       │   │
                        │  ┌───────────────────┐  ┌───────────────────┐   │   │
                        │  │ L1: ConcurrentHashMap │  │ L2: SessionRepo   │   │
                        │  │ (token → Session)     │  │ (Redis)           │   │
                        │  └───────────────────┘  └───────────────────┘   │   │
                        │         authInterceptor 读取                      │   │
                        └──────────────────────────────────────────────────┘   │
                                                                               │
                        ┌──────────────────────────────────────────────────┐   │
                        │    Response ────► StreamObserver.onNext(resp)    │   │
                        └──────────────────────────────────────────────────┘   │
                        └──────────────────────────────────────────────────────┘

数据流向:
  gRPC Stream(onRequest) ──► Dispatcher.dispatch(Request)
    ──► HandlerRegistry.lookup(method) ──► ProtoCodec.deserialize(params)
    ──► AuthInterceptor ──► LogInterceptor ──► RateLimitInterceptor
    ──► ExceptionInterceptor(包裹) ──► Handler.handle(req)
    ──► ProtoCodec.serialize(result) ──► Response ──► StreamObserver
```

### Recommended Project Structure

```
gateway/src/main/kotlin/com/nebula/gateway/
├── (预留) ChatGatewayImpl.kt              // gRPC service 入口 (Phase 4+ 补充)
├── dispatcher/
│   ├── HandlerRegistry.kt                 // ConcurrentHashMap + HandlerEntry
│   └── Dispatcher.kt                      // Pipeline 编排入口
├── interceptor/
│   ├── Interceptor.kt                     // suspend interface + Chain
│   ├── AuthInterceptor.kt                 // Session 验证 + 注入 CoroutineContext
│   ├── LogInterceptor.kt                  // 请求/响应/耗时日志
│   ├── RateLimitInterceptor.kt            // Token Bucket 限流
│   └── ExceptionInterceptor.kt            // 三态异常映射
├── codec/
│   └── ProtoCodec.kt                      // MethodHandles 预编译缓存
├── session/
│   ├── Session.kt                         // data class Session
│   └── SessionRegistry.kt                 // L1(Local) + L2(Redis)
└── handler/
    ├── PingHandler.kt                     // system/ping 心跳
    └── (预留) domain/                     // Phase 5+ 按域分包
```

### Pattern 1: Handler 接口 + CoroutineContext Session 传递

**What:** 泛型 Handler 接口定义请求处理契约。Session 不显式传参，通过 CoroutineContext 由 AuthInterceptor 注入，Handler 通过拦截器工具函数获取。

**When to use:** 所有业务 Handler（Phase 5+）继承此接口。

**Example:**
```kotlin
// Source: CONTEXT.md D-01/D-03 + 设计文档 8.1 适配
/**
 * 泛型 Handler 接口，所有业务 Handler 必须实现此接口。
 *
 * Session 通过 CoroutineContext 隐式传递，Handler 内部通过拦截器工具函数获取：
 * ```kotlin
 * val session = coroutineContext[SessionKey] ?: throw ...
 * ```
 *
 * @param Req 请求 Proto 消息类型
 * @param Resp 响应 Proto 消息类型
 */
interface Handler<Req : Any, Resp : Any> {
    /** 当前 Handler 对应的 method 路由字符串 */
    val method: String

    /** 处理业务请求，Session 通过 CoroutineContext 获取 */
    suspend fun handle(req: Req): Resp
}

/**
 * Session 在 CoroutineContext 中的 Key。
 * AuthInterceptor 在 intercept() 中通过 `withElement(SessionKey, session)` 注入。
 */
data object SessionKey : CoroutineContext.Key<Session>

/**
 * 获取当前协程上下文的 Session
 * @throws BizException(TOKEN_INVALID) 若未通过认证
 */
suspend fun CoroutineContext.requireSession(): Session {
    return this[SessionKey] ?: throw BizException(BizCode.UNAUTHORIZED)
}
```

### Pattern 2: Interceptor Pipeline (suspend Chain of Responsibility)

**What:** GoF Chain of Responsibility 适配为 suspend 版本。Interceptor 通过 Koin `List<Interceptor>` 注入，Pipeline 按 D-07 顺序执行。

**When to use:** 所有请求处理流程。

**Example:**
```kotlin
// Source: CONTEXT.md D-05/D-06/D-07 + 设计文档 8.3
/**
 * 拦截器接口 — suspend 版本的责任链模式。
 * 通过 Koin 以 List<Interceptor> 方式注入，Dispatcher 组装为链。
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

// 使用方式 — Dispatcher 中组装
// val pipeline = interceptors.foldRight(handlerChain) { interceptor, next ->
//     InterceptorChain(interceptor, next)
// }
```

### Pattern 3: HandlerRegistry + HandlerEntry

**What:** `ConcurrentHashMap<String, HandlerEntry>` 存储 method→Handler 映射。HandlerEntry 包含 Handler 实例、Req/Resp 的 KClass 和序列化方法引用。

**When to use:** Dispatcher 路由查找、注册时构建 ProtoCodec 方法引用。

**Example:**
```kotlin
// Source: CONTEXT.md D-11/D-12/D-13
/**
 * Handler 注册条目 — 持有 Handler 实例 + 类型信息 + 序列化方法引用。
 *
 * @param handler Handler 实例
 * @param reqClass Req 的 KClass
 * @param respClass Resp 的 KClass
 * @param parseFrom Req 的 parseFrom(bytes) 方法引用
 * @param toByteArray Resp 的 toByteArray() 方法引用
 */
data class HandlerEntry(
    val handler: Handler<*, *>,
    val reqClass: KClass<*>,
    val respClass: KClass<*>,
    val parseFrom: (ByteArray) -> Any,
    val toByteArray: (Any) -> ByteArray
)

/**
 * Handler 注册中心 — 线程安全的 method→Handler 映射表。
 */
class HandlerRegistry {
    private val registry = ConcurrentHashMap<String, HandlerEntry>()

    fun register(entry: HandlerEntry) {
        registry[entry.handler.method] = entry
    }

    fun get(method: String): HandlerEntry? = registry[method]
}
```

### Pattern 4: ProtoCodec — MethodHandles 预编译方法引用

**What:** Precompiled method handles for zero-reflection serialization. At registration time, `parseFrom(ByteArray)` and `toByteArray()` method references are captured via `MethodHandles.lookup()`.

**When to use:** Handler 注册时构建方法引用, Dispatcher 运行时调用。

**Example:**
```kotlin
// Source: CONTEXT.md D-12 + Java MethodHandles API
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Proto 编解码器 — MethodHandles 预编译，运行时零反射开销。
 *
 * 注册 Handler 时一次性查找并缓存方法引用：
 * - parseFrom: static method, (byte[]) → ProtoMsg
 * - toByteArray: instance method, () → byte[]
 */
object ProtoCodec {
    private val logger = KotlinLogging.logger {}

    /**
     * 为指定 Proto 类构建序列化/反序列化方法引用。
     *
     * @param protoClass Proto 消息的 KClass
     * @return Pair(parseFrom: (ByteArray)->Any, toByteArray: (Any)->ByteArray)
     */
    fun buildCodec(protoClass: KClass<*>): CodecPair {
        val javaClass = protoClass.java
        val lookup = MethodHandles.lookup()

        // parseFrom(byte[]) → ProtoMsg (static method)
        val parseFromHandle = lookup.findStatic(
            javaClass,
            "parseFrom",
            MethodType.methodType(javaClass, ByteArray::class.java)
        )

        // toByteArray() → byte[] (instance method)
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
}
```

### Pattern 5: SessionRegistry L1/L2 双级缓存

**What:** L1 为 `ConcurrentHashMap<String, Session>` 本地缓存（读优先），L2 为 Redis SessionRepository（持久化）。D-20 回调机制处理驱逐时的连接关闭。

**When to use:** AuthInterceptor 认证时读取 Session，Handler 获取当前用户上下文。

**Example:**
```kotlin
// Source: CONTEXT.md D-16/D-17/D-18/D-19/D-20

/**
 * Session 数据模型。
 */
data class Session(
    val userId: Long,
    val token: String,
    val deviceType: String,
    val deviceId: String,
    val connectionId: String
)

/**
 * Session 注册中心 — L1(ConcurrentHashMap) + L2(Redis) 二级缓存。
 * L1 提供毫秒级内存读取，L2 Redis 提供跨节点持久化。
 */
class SessionRegistry(
    private val sessionRepository: SessionRepository
) {
    private val localCache = ConcurrentHashMap<String, Session>()    // token → Session
    private val userIdIndex = ConcurrentHashMap<Long, MutableSet<String>>() // userId → tokens
    private val evictionCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    /** 注册缓存失效回调 — 当 Session 被驱逐时通知关闭 StreamObserver */
    fun onEviction(callback: (token: String) -> Unit) {
        evictionCallbacks.add(callback)
    }

    /** 综合验证 — 从 L1 读取，未命中则查 L2 */
    suspend fun validate(token: String): Session? {
        return getFromLocalCache(token) ?: queryFromRedis(token)?.also {
            addToLocalCache(it)
        }
    }

    /** 注册新 Session — 写入 L1 + L2 */
    suspend fun register(session: Session) {
        addToLocalCache(session)
        saveToRedis(session)
    }

    /** 注销 Session — 移除 L1 + L2 + 触发回调 */
    suspend fun unregister(token: String) {
        removeFromLocalCache(token)
        removeFromRedis(token)
        evictionCallbacks.forEach { it(token) }
    }

    // ... 细粒度方法: addToLocalCache(), removeFromLocalCache(), etc.
}
```

### Pattern 6: Koin 模块注册

**What:** 所有 Handler 和基础设施组件通过 Koin module 显式注册。HandlerRegistry 在 Koin 启动时一次性注册所有 Handler 的方法映射。

**When to use:** 应用启动时 `startKoin { modules(...) }`。

**Example:**
```kotlin
// Source: CONTEXT.md D-06 + 设计文档 8.2 + Context7 Koin 4.1.0
import org.koin.dsl.module

/**
 * 框架级 Koin 模块 — 注册所有基础设施组件。
 */
val frameworkModule = module {
    single { HandlerRegistry() }
    single { ProtoCodec }
    single { SessionRegistry(get()) }  // SessionRepository 从 Koin 注入

    // 拦截器以 List 形式注入 — 顺序由 D-07 决定
    single<Interceptor> { AuthInterceptor(get(), skipMethods = setOf("system/ping")) }
    single<Interceptor> { LogInterceptor() }
    single<Interceptor> { RateLimitInterceptor() }
    single<Interceptor> { ExceptionInterceptor() }
}

/**
 * 业务 Handler Koin 模块 — Phase 5+ 按域注册。
 */
val handlerModule = module {
    single { PingHandler() }
}

/**
 * 启动时加载（NebulaServer.kt 或独立初始化）：
 * ```kotlin
 * startKoin {
 *     modules(frameworkModule, handlerModule)
 * }
 * ```
 */
```

### Anti-Patterns to Avoid

- **Handler 接口接收 Session 参数：** 设计文档 8.1 定义 `fun handle(req, session)`，但 D-03 已改为 CoroutineContext 隐式传递。不要在设计文档的旧模式下实现。
- **Interceptor 非 suspend：** 所有 Interceptor 和 Chain 必须定义为 suspend 函数（D-05），同步版本无法适配协程异常处理链。
- **Dispatcher 操作 StreamObserver：** D-14 明确 Dispatcher 返回 Response 对象，不直接写 StreamObserver。写 StreamObserver 的逻辑在 ChatGatewayImpl 中。
- **ProtoCodec 运行时反射：** 不要使用 `Method.invoke()` 做运行时反射。D-12 要求 MethodHandles 预编译，在注册时一次性查找。
- **手动管理 Koin：** 不要 `get()` 或者全局变量。统一通过 Koin DI 注入。

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 依赖注入容器 | 手动 Service Locator 或全局单例 | Koin | D-06 锁定；DSL 驱动、零反射、测试友好（KoinTestExtension 一键 Mock） |
| 协程 Mock | runBlocking + 真实协程 | MockK coEvery/coVerify | D-25 锁定；原生支持 suspend 函数的 stub 和验证，与 kotlinx-coroutines-test 集成 |
| suspend 函数测试 | runBlocking 长时间等待 | runTest (kotlinx-coroutines-test) | D-25 锁定；虚拟时间控制、delay 跳过、精确的时间断言 |
| Protobuf 反射序列化 | Method.invoke 运行时反射 | MethodHandles 预编译 | D-12 锁定；MethodHandles 比反射快 10-50 倍，在注册时而非运行时执行查找 |
| 线程可见性/并发安全 | synchronized 锁全部读 | ConcurrentHashMap / CopyOnWriteArrayList | HandlerRegistry 读多写少（运行时不写，仅启动时注册），CHM 提供细粒度锁 |

**Key insight:** Koin 替代了手动 DI、MockK 替代了 runBlocking 测试、MethodHandles 替代了反射。这些替代方案都是各自领域的 Kotlin 标准，且相互之间存在清晰的集成边界。

## Runtime State Inventory

> 本阶段为 Greenfield 框架构建，不涉及重命名/重构/迁移。跳过此章节。

## Common Pitfalls

### Pitfall 1: CoroutineContext 传递失效 — Session 在独立协程中不可见

**What goes wrong:** 如果 Handler 内部用 `launch { }` 或 `withContext(Dispatchers.Default)` 启动新协程，AuthInterceptor 注入的 SessionKey 在新协程的 CoroutineContext 中不存在，导致 `requireSession()` 抛出 UNAUTHORIZED。

**Why it happens:** Kotlin 的 `launch` 和 `withContext` **不自动继承**父协程的 `CoroutineContext` 中的自定义元素。只有 `coroutineScope` 和 `supervisorScope` 等结构化并发原语会继承。

**How to avoid:** 
- Handler 内部如果需要并发，使用 `coroutineScope { }` 或 `supervisorScope { }` 保持上下文传递
- 或者显式传递 `coroutineContext + SessionKey(session)` 给新的协程构建器
- KDoc 中注明此陷阱

**Warning signs:** 业务 Handler 内部使用 `launch(Dispatchers.IO)` 后读取 Session 为 null。

### Pitfall 2: SupervisorJob 与 CoroutineExceptionHandler 配合

**What goes wrong:** `SupervisorJob + Dispatchers.IO` 作用域下的子协程抛出异常，ExceptionInterceptor 可能收不到 — 因为 SupervisorJob 不会像普通 Job 那样向上传播异常。

**Why it happens:** SupervisorJob 的设计意图是隔离子协程异常（类似 Java 的 `Thread.uncaughtExceptionHandler`）。子协程的异常需要在子协程自己的 CoroutineContext 中显式设置 `CoroutineExceptionHandler`。

**How to avoid:** D-04 要求 `CoroutineExceptionHandler` 在 Dispatcher 的作用域中定义。建议在 `CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e -> ... })` 中创建。

**Warning signs:** 子协程抛出 BizException 但 ExceptionInterceptor 未触发，客户端收到 gRPC 内部错误而非业务错误码。

### Pitfall 3: MethodHandles 与 Kotlin object 类的兼容性

**What goes wrong:** Kotlin 的 `object` 类（用于无参 Handler 如 `Unit`）的 `parseFrom()` 静态方法查找可能失败 — Protobuf 对 empty message 的处理方式与普通 message 不同。

**Why it happens:** `com.google.protobuf.GeneratedMessage` 的 `parseFrom(byte[])` 在空消息场景下行为依赖于具体生成代码。

**How to avoid:** 
- 在 `buildCodec()` 中添加对 `Unit::class` 或特殊标记类的 guard block
- 为无参 Handler 提供专门的编解码路径（直接返回空的默认实例）

**Warning signs:** `java.lang.NoSuchMethodException: parseFrom(byte[])` 或 `IllegalAccessException`。

### Pitfall 4: Koin module 多次注册相同 method 导致覆盖

**What goes wrong:** 两个不同的 Koin module 注册了相同的 `method` 字符串（如两个地方都注册 `"user/login"`），后注册的静默覆盖之前的，且无编译期或启动期警告。

**Why it happens:** `ConcurrentHashMap.put()` 在 key 冲突时覆盖旧值。Koin module 的加载顺序影响最终生效的 Handler。

**How to avoid:** 
- 在 `HandlerRegistry.register()` 中添加重复检测：`check(registry.putIfAbsent(method, entry) == null) { "Duplicate method: $method" }`
- 统一在单个 `registryModule` 中集中注册所有 method

### Pitfall 5: ProtoCodec 反序列化 NotNull 断言

**What goes wrong:** 客户端发送的 `Request.params` 为空 `ByteArray(0)`（如无参接口），`parseFrom(emptyBytes)` 可能返回默认实例而非抛异常，但某些 Handler 的逻辑假定 req 非空。

**Why it happens:** Protobuf 的 `parseFrom(byte[])` 对空字节数组的处理是：尝试解析，如果消息的所有字段都是 optional 则返回默认实例。这在 Protobuf 语义中是正确的。

**How to avoid:** 
- 在 Dispatcher 的反序列化步骤中添加空载荷检查
- 如果 proto 定义中 `params` 字段标记为 required（proto3 无 required），需要做边界处理

## Code Examples

### Example 1: Dispatcher 完整实现

```kotlin
// Source: CONTEXT.md D-02/D-04/D-14/D-15 + 设计文档 8.3
import com.nebula.chat.Request
import com.nebula.chat.Response
import kotlinx.coroutines.*

class Dispatcher(
    private val handlerRegistry: HandlerRegistry,
    private val interceptors: List<Interceptor>,
    private val protoCodec: ProtoCodec
) {
    /** 全局 Dispatcher 作用域 — ChatServer 级别生命周期 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            // ExceptionInterceptor 应处理所有异常，这里兜底防止 JVM 崩溃
            logger.error(e) { "Unhandled exception in dispatcher scope" }
        })

    /**
     * 分发请求：
     * 1. 根据 method 查找 Handler
     * 2. 反序列化 params bytes → Req
     * 3. 通过 Interceptor Pipeline
     * 4. 序列化结果 → Response
     */
    suspend fun dispatch(envelopeRequest: Request): Response {
        val method = envelopeRequest.method
        val entry = handlerRegistry.get(method)
            ?: return Response.newBuilder()
                .setCode(BizCode.NOT_FOUND.code)
                .setMsg("method not found: $method")
                .build()

        // 反序列化
        @Suppress("UNCHECKED_CAST")
        val req = protoCodec.deserialize(entry, envelopeRequest.params)

        // 构建 Pipeline 链尾 — 最终调用 Handler
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

        // 折叠拦截器链
        val pipeline = interceptors.foldRight(handlerChain) { interceptor, chain ->
            InterceptorChain(interceptor, chain)
        }

        return pipeline.proceed(envelopeRequest)
    }
}

/** Interceptor Chain 包装 — 实现非尾结点的链节 */
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

### Example 2: AuthInterceptor 实现

```kotlin
// Source: CONTEXT.md D-03/D-09 + Session 验证
class AuthInterceptor(
    private val sessionRegistry: SessionRegistry,
    private val skipMethods: Set<String> = setOf("system/ping", "user/login")
) : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val method = request.method

        // 白名单方法跳过认证
        if (method in skipMethods) {
            return chain.proceed(request)
        }

        // 从 Request metadata 中提取 token（实际来源由 ChatGatewayImpl 从 Envelope 提取）
        val token = extractToken(request) // 实现取决于 Envelope 结构
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
        // TODO: 从 Request/Envelope 提取 token
        // 取决于 Phase 5 中 Envelope 的 token 放置位置
        return request.paramsList.firstOrNull { it.key == "token" }?.value
    }
}
```

### Example 3: ExceptionInterceptor 三态映射

```kotlin
// Source: CONTEXT.md D-10 + 设计文档 8.4
class ExceptionInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(request)
        } catch (e: BizException) {
            // BizException → 业务状态码
            Response.newBuilder()
                .setCode(e.bizCode.code)
                .setMsg(e.message ?: e.bizCode.msg)
                .setMethod(request.method)
                .build()
        } catch (e: IllegalArgumentException) {
            // 参数异常 → BAD_REQUEST
            Response.newBuilder()
                .setCode(BizCode.INVALID_PARAM.code)
                .setMsg(e.message ?: BizCode.INVALID_PARAM.msg)
                .setMethod(request.method)
                .build()
        } catch (e: Exception) {
            // 未预期异常 → INTERNAL_ERROR，不泄露堆栈
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

### Example 4: KoinTestExtension 测试 HandlerRegistry

```kotlin
// Source: CONTEXT.md D-23/D-24/D-25 + Context7 Koin + MockK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class HandlerRegistryTest : KoinTest {

    private val handlerRegistry by inject<HandlerRegistry>()
    private val sessionRepo = mockk<SessionRepository>()

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(module {
            single { HandlerRegistry() }
            single { SessionRegistry(sessionRepo) }
            single<Interceptor> { AuthInterceptor(get()) }
        })
    }

    @Test
    fun `register and lookup handler`() = runTest {
        val handler = PingHandler()
        val entry = HandlerEntry(
            handler = handler,
            reqClass = handler.reqClass,
            respClass = handler.respClass,
            parseFrom = { bytes -> handler.reqClass.java.parseFrom(bytes) },
            toByteArray = { obj -> (obj as com.google.protobuf.Message).toByteArray() }
        )
        handlerRegistry.register(entry)

        val found = handlerRegistry.get("system/ping")
        assertNotNull(found)
        assertEquals(handler, found.handler)
    }
}
```

### Example 5: runTest + coEvery 测试 AuthInterceptor

```kotlin
// Source: CONTEXT.md D-25/D-26
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

    @Test
    fun `skip auth for system-ping`() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptor = AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping"))

        val request = Request.newBuilder().setMethod("system/ping").build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(200, resp.code)
        // 验证 SessionRegistry 没有被调用
        coVerify(inverse = true) { sessionRegistry.validate(any()) }
    }

    @Test
    fun `reject when token invalid`() = runTest {
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

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| 设计文档 8.1: `fun handle(req, session)` | `suspend fun handle(req)` + CoroutineContext Session | D-01/D-03 | Handler 签名去掉了 Session 参数，改为从 CoroutineContext 获取 |
| 设计文档 8.3: 运行时反射编解码 | MethodHandles 预编译方法引用 | D-12 | 零反射开销，性能提升 10-50 倍 |
| 设计文档 8.4: 仅有 BizException 映射 | 三态异常映射 + INTERNAL_ERROR(9000) | D-10 | 增加了 IllegalArgumentException 和未预期的异常的兜底 |
| 原心跳策略: 纯 gRPC keepalive | 双重心跳: gRPC + 应用层 PING/PONG | D-22→D-27 | 增加应用层 PING 覆盖 NAT/代理半开连接场景 |
| 设计文档: Spring DI 或手动 DI | Koin 4.1.0 | D-06 | 轻量级 DI 框架，Kotlin DSL，零反射 |

**Deprecated/outdated:**
- 设计文档 8.1 的 Handler 接口签名（`fun handle(req, session)`）：已被 D-01/D-03 替代
- 设计文档 8.3 的运行时反射 ProtoCodec：已被 D-12 的 MethodHandles 方案替代
- 单重心跳策略（纯 gRPC keepalive）：已被 D-27 双重心跳替代

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | MethodHandles.lookup().findStatic/findVirtual 对 Protobuf generated message 的 parseFrom 和 toByteArray 方法兼容 | ProtoCodec Pattern | Protobuf 4.29.3 较新，某些 generated 方法签名可能与标准 MethodHandles 查找不兼容；需验证 |
| A2 | Koin `get<SessionRepository>()` 在 :gateway 模块中可正确解析 | Koin Pattern | :gateway 依赖 :service → :repository，但 Koin 需要显示加载 repository 模块的 module 定义 |
| A3 | ConcurrentHashMap + CopyOnWriteArrayList 满足 SessionRegistry 的并发需求 | SessionRegistry Pattern | 生产环境下 10K+ 并发连接时，CHM 的桶锁可能成为瓶颈 |
| A4 | gRPC keepalive 的精细化参数（maxConnectionAge=1800s 等）在 ChatServer.kt 中与当前 gRPC 版本兼容 | ChatServer.kt 修改 | gRPC 1.81.0 支持这些参数，但具体版本需确认 NettyServerBuilder API 兼容性 |
| A5 | kotlinx-coroutines-test 1.9.0 与 kotlinx-coroutines-core 1.9.0 完全兼容 | 测试框架 | 同版本号的协程测试库理论上兼容 |

## Open Questions (RESOLVED)

1. **ProtoCodec 对 Unit/empty Handler 的处理** [RESOLVED → Plan 01 Task 2]
   - What we know: 设计文档 8.2 中某些 Handler 的 Resp 类型为 `Unit::class`（如 `friend/delete`），D-13 要求 HandlerEntry 包含 Resp KClass
   - What's unclear: Protobuf `parseFrom(byte[])` 对空载荷的兼容性，以及 Unit 类型如何映射到 Proto 消息
   - Resolution: Plan 01 Task 2 为 ProtoCodec 增加 empty bytes guard clause，对无参 Handler 返回空消息/空字节
   - Recommendation: 为无参 Handler 定义一个 `EmptyRequest` / `EmptyResponse` Proto 消息，或在 ProtoCodec 中增加 guard：if (protoClass == Unit::class) 直接返回空消息/空字节

2. **AuthInterceptor 从 Request 提取 token 的方式** [RESOLVED → Plan 02 Task 2]
   - What we know: Request.proto 定义 `method` 和 `bytes params` 两个字段，没有显式的 token 字段
   - What's unclear: token 是放在 `params` 中随第一个请求发送，还是需要通过 Envelope metadata 传递
   - Resolution: Plan 02 Task 2 中 AuthInterceptor 先实现 token 提取的扩展点（extractToken() 方法返回 TODO），Phase 5 填入具体实现
   - Recommendation: 等待 Phase 5 确定 token 传递方式。Phase 4 的 AuthInterceptor 先实现 token 提取的扩展点（接口或抽象方法），Phase 5 填入具体实现

3. **RateLimitInterceptor 的限流策略参数** [RESOLVED → Plan 02 Task 2]
   - What we know: D-08 要求实现 RateLimitInterceptor，基于 userId (已认证) 或 IP (未认证) 限流
   - What's unclear: 限流阈值（每分钟 N 次）是硬编码还是配置化，以及是否需要滑动窗口或令牌桶
   - Resolution: Plan 02 Task 2 将 RateLimitInterceptor 实现为骨架（stub/skeleton），使用默认限流阈值，Phase 11 精细化调优
   - Recommendation: 使用令牌桶算法（`java.util.concurrent.Semaphore` 或 `Bucket4j`），限流阈值通过配置加载。Phase 4 先实现骨架和默认值，Phase 11 精细化调优

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JDK) | Kotlin 编译 + gRPC | ✓ | JDK 21.0.11 | — |
| Gradle | 构建系统 | ✓ (via wrapper) | 9.5.1 | — |
| Koin 4.1.0 | DI 框架 | ✓ (Maven, 构建时解析) | — | — |
| MockK | 测试 Mock | ✓ (Maven, 构建时解析) | — | — |
| kotlinx-coroutines-test | 协程测试 | ✓ (Maven, 构建时解析) | 1.9.0 | — |

**Missing dependencies with no fallback:** 无 — 所有依赖为 Maven 构建时依赖，Gradle 在构建时自动下载解析。

## Validation Architecture

> SKIPPED: `workflow.nyquist_validation` is explicitly `false` in `.planning/config.json`. 本阶段验证通过 Phase 4 CONTEXT.md D-23~D-26 定义的测试策略执行。

## Security Domain

> `security_enforcement` 未在 config.json 中显式设置（absent = enabled），但本阶段为 Handler 框架层，而非业务安全逻辑的实现阶段。以下列出框架本身的安全相关项。

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Partial（本阶段建立认证拦截器框架） | AuthInterceptor 作为 Pipeline 首层，skipMethods 控制白名单；具体认证逻辑在 Phase 5 实现 |
| V3 Session Management | Partial（本阶段建立 SessionRegistry） | L1 缓存 + L2 Redis 持久化；Session 数据模型（userId, token, deviceType 等）；具体创建/销毁在 Phase 5 |
| V5 Input Validation | Yes（ProtoDeserialization 边界） | Dispatcher 层对 method 长度/格式校验；ProtoCodec 对反序列化结果的边界合理性检查 |
| V7 Error Handling | Yes（ExceptionInterceptor） | BizException→业务码、IllegalArgument→BAD_REQUEST、未预期→9000（不暴露堆栈） |
| V9 Communication Security | No（Phase 2 SSL 已处理） | gRPC keepalive 配置已在 Phase 2 ChatServer.kt 中实现 |

### Known Threat Patterns for Phase 4

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| 未认证请求通过 Dispatcher 访问业务 Handler | Spoofing | AuthInterceptor 作为 Pipeline 首层，skipMethods 严格控制白名单（仅 system/ping 和 login）|
| 异常信息泄露至客户端 | Information Disclosure | ExceptionInterceptor 捕获所有未预期异常后，仅返回 `INTERNAL_ERROR(9000)` + "internal error"；堆栈写入日志不返回客户端 |
| Proto 反序列化畸形数据导致内存异常 | Denial of Service | Protobuf `CodedInputStream` 内置限制（最大递归深度、最大 bytes 长度）；4MB `maxInboundMessageSize` 已在 ChatServer.kt 中配置 |
| Session 缓存一致性问题（多节点） | Tampering | L2 Redis 提供跨节点持久化（通过 SessionRepository.setex/TTL）；L1 本地缓存故障不影响全局 Session | 

## Sources

### Primary (HIGH confidence)
- [CONTEXT.md] `04-CONTEXT.md` — 31 个决策项 (D-01~D-31) 锁定所有架构决策
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/08-Handler层设计/8.1-接口契约.md` — Handler 接口定义（需适配为 suspend 版本）
- [设计文档 v1.2] `08-Handler层设计/8.2-Koin注册.md` — Koin 模块注册模式
- [设计文档 v1.2] `08-Handler层设计/8.3-Pipeline与拦截器.md` — Interceptor/Chain 模式
- [设计文档 v1.2] `08-Handler层设计/8.4-异常处理.md` — BizException 映射
- [设计文档 v1.2] `08-Handler层设计/8.5-分文件组织.md` — 目录结构
- [设计文档 v1.2] `04-认证与会话/4.2-Token方案.md` — Session 字段定义
- [设计文档 v1.2] `03-通信协议/3.4-心跳策略.md` — 原始心跳设计
- [search.maven.org] Koin 4.1.0 最新版本验证 — `g:io.insert-koin AND a:koin-core`
- [Context7] `/insertkoinio/koin` — Koin 4.1.0 module DSL、startKoin、KoinTestExtension API
- [Context7] `/mockk/mockk` — MockK coEvery/coVerify/coAnswers suspend 函数 Mock
- [Context7] `/kotlin/kotlinx.coroutines` — runTest 虚拟时间控制、TestScope

### Secondary (MEDIUM confidence)
- [Phase 2 code] `ChatServer.kt` — gRPC keepalive 现有配置
- [Phase 2 code] `BizCode.kt` — 业务状态码枚举（ExceptionInterceptor 直接引用）
- [Phase 2 code] `BizException.kt` — 业务异常基类
- [Phase 3 code] `SessionRepository.kt` — Redis Session 操作（SessionRegistry L2 依赖）
- [NebulaServer.kt] — 现有启动顺序，Phase 4 需在此集成 startKoin

### Tertiary (LOW confidence)
- MethodHandles 与 Protobuf 4.29.3 的完全兼容性 — 未具体验证所有 generated 类型
- RateLimitInterceptor 的 Bucket4j 与项目现有依赖的兼容性 — 可选择其他限流算法

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Koin 4.1.0、MockK、kotlinx-coroutines-test 均来自 Maven Central/Context7 验证
- Architecture: HIGH — 31 个决策项锁定所有架构，设计文档 8.1~8.5 提供具体实现参考
- Pitfalls: MEDIUM — CoroutineContext 传递失效和 SupervisorJob 配合为已知协程陷阱；MethodHandles 与 Protobuf 兼容性有待验证
- Testing: MEDIUM — D-23~D-26 定义的测试策略明确，但 kotlinx-coroutines-test 的 runTest 与 MockK coEvery 的集成测试未在项目中验证过

**Research date:** 2026-06-12
**Valid until:** 2026-07-12（30 天 — 版本号可能更新，但架构模式和设计决策保持稳定）
