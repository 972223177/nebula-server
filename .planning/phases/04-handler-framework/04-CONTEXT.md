# Phase 4: Handler Framework - Context

**Gathered:** 2026-06-12 (updated)
**Status:** Ready for planning

<domain>
## Phase Boundary

构建通用的请求处理框架 — Handler 接口、Dispatcher 路由分发、Koin 依赖注入、Interceptor Pipeline 及异常处理。提供 :gateway 模块的核心骨架，供 Phase 5+ 业务 Handler 继承使用。

**共 6 个需求 (HNDL-01~06):** Handler 接口契约、Dispatcher 路由、Koin 注册、认证拦截器、日志拦截器、异常处理拦截器。

</domain>

<decisions>
## Implementation Decisions

### 协程支持策略
- **D-01:** Handler 接口定义为 `suspend fun handle(req: Req): Resp` — 所有 Handler 和 Interceptor 统一为 suspend 函数，与全局协程生态一致。
- **D-02:** Dispatcher 使用 `CoroutineScope(Dispatchers.IO + SupervisorJob)` — 全局单作用域，ChatServer 级别生命周期管理，SupervisorJob 隔离单个 Handler 异常。
- **D-03:** Session 通过 CoroutineContext 隐式传递 — Handler 接口不显式携带 Session 参数，由 AuthInterceptor 注入协程上下文，Handler KDoc 中注明获取方式。
- **D-04:** 协程异常通过 `CoroutineExceptionHandler` 统一捕获，送入 ExceptionInterceptor 处理。
- **D-05:** Interceptor 接口全部定义为 suspend，Pipeline 统一为协程链。

### 拦截器 Pipeline 架构
- **D-06:** 采用设计文档 8.3 的 Interceptor/Chain 接口模式（GoF Chain of Responsibility），适配为 suspend 版本。拦截器通过 Koin `List<Interceptor>` 注入。
- **D-07:** 拦截器执行顺序：AuthInterceptor → LogInterceptor → RateLimitInterceptor → ExceptionInterceptor（ExceptionInterceptor 作为链尾包裹 Handler）。
- **D-08:** 实现 4 个拦截器：AuthInterceptor、LogInterceptor、RateLimitInterceptor、ExceptionInterceptor。
- **D-09:** AuthInterceptor 内部维护 `skipMethods: Set<String>` 白名单控制跳过认证的方法（如 "system/ping"）。
- **D-10:** ExceptionInterceptor 三态异常处理：BizException→业务状态码、IllegalArgumentException→BAD_REQUEST、未预期异常→INTERNAL_ERROR(9000) 不暴露堆栈细节。

### Dispatcher 路由与序列化
- **D-11:** HandlerRegistry 持有 `ConcurrentHashMap<String, HandlerEntry>`，Entry 包含 Handler 实例 + Req/Resp 的序列化方法引用。运行时通过 method 查表。
- **D-12:** ProtoCodec 采用预编译方法引用缓存策略 — 注册时缓存 `parseFrom()`/`toByteArray()` 的方法引用 (MethodHandles)，运行时零反射开销。
- **D-13:** HandlerRegistry 自身持有类型信息 — HandlerEntry 包含 Handler + Req KClass + Resp KClass + 序列化/反序列化方法引用。
- **D-14:** Dispatcher 返回完整 `Response` proto 对象，不直接操作 StreamObserver，保持与 gRPC 的解耦。
- **D-15:** `Dispatcher.dispatch()` 签名：`suspend fun dispatch(envelopeRequest: Request): Response`。

### Session 数据结构与跨模块依赖
- **D-16:** Session 数据模型字段：`userId`, `token`, `deviceType`, `deviceId`, `connectionId`。
- **D-17:** SessionRegistry 放在 :gateway 模块，依赖 :repository 模块的 SessionRepository（Redis 操作）。
- **D-18:** SessionRegistry 采用本地内存 (ConcurrentHashMap) + Redis 二级缓存策略。本地作为 L1 缓存，Redis 作为持久化存储。
- **D-19:** SessionRegistry API 采用统一入口 + 细粒度方法：`addToLocalCache()`/`removeFromLocalCache()`/`getFromLocalCache()`、`saveToRedis()`/`removeFromRedis()`/`queryFromRedis()`、以及组合方法 `validate()`/`register()`/`unregister()`。
- **D-20:** Session 缓存一致性采用注册回调机制 — SessionRegistry 提供 `(token) -> Unit` 回调注册点，ChatGatewayImpl 在创建 Session 时注册。本地驱逐时通知关闭对应 StreamObserver。

### 包结构与目录组织
- **D-21:** 沿用设计文档 8.5 的标准包结构：
  - `com.nebula.gateway.dispatcher` — HandlerRegistry, Dispatcher
  - `com.nebula.gateway.interceptor` — Interceptor, AuthInterceptor, LogInterceptor, ExceptionInterceptor, RateLimitInterceptor
  - `com.nebula.gateway.codec` — ProtoCodec
  - `com.nebula.gateway.session` — Session, SessionRegistry
  - `com.nebula.gateway.handler.{domain}` — 按业务域分包

### 心跳处理策略（2026-06-12 更新）
- **D-22（已废弃）：** ~~心跳完全由 gRPC 内置 keepalive 机制处理，不实现应用层 PING/PONG。~~ → 废弃原因：移动端网络环境下 gRPC keepalive 无法可靠检测半开连接（NAT/代理透传 HTTP/2 PING 帧但不保证数据通道畅通）。
- **D-27:** 采用纯应用层 PING/PONG 心跳，替代 gRPC keepalive 作为主要心跳检测机制。客户端定时发送 PING 请求，服务端返回 PONG 响应。gRPC keepalive 参数保持现有配置作为传输层兜底（`keepAliveTime=30s`, `keepAliveTimeout=10s`, `permitKeepAliveWithoutCalls=false`）。
- **D-28:** 应用层心跳通过普通 Handler `method = "system/ping"` 实现，走标准 Dispatcher + Pipeline 路由。PING 请求不携带业务 payload，服务端直接返回 PONG Response。
- **D-29:** 心跳超时采用优雅降级策略：
  - T1（60s 无 PING）：将连接标记为"可疑"状态，停止向该连接推送消息
  - T2（150s 仍无 PING）：强制断开连接，清理 Session，触发客户端重连
  - 若客户端在 T1-T2 窗口内恢复 PING 发送，标记恢复正常，恢复推送
- **D-30:** AuthInterceptor 和 LogInterceptor 跳过 `"system/ping"` 方法，心跳 Handler 直接返回 PONG 不经过认证/日志拦截。
- **D-31:** Proto `envelope.proto` Direction 枚举还原 PING(4)/PONG(5) 为有效值（从 `reserved` 移除）。更新 proto 注释说明应用层 PING/PONG 用途。

### Handler 测试策略
- **D-23:** 测试框架使用 JUnit5 + MockK，与项目现有配置一致。
- **D-24:** 单元测试覆盖框架各组件：HandlerRegistry、Dispatcher、各 Interceptor、ProtoCodec、SessionRegistry。
- **D-25:** suspend 函数测试使用 `runTest { }` (kotlinx-coroutines-test) + MockK `coEvery{}`/`coVerify{}`。
- **D-26:** Dispatcher + Pipeline 采取 Mock 全链路测试 — MockHandlerRegistry + MockInterceptorChain 验证编排顺序。

### Claude's Discretion
- 心跳 Handler 的 `system/ping` 方法名可协商，下游计划者可根据编码惯例调整
- 优雅降级具体时间窗口 T1/T2 数值可在实现阶段根据实际测试调整（参考：T1=60s, T2=150s）
- 心跳 Handler 是否需要独立单元测试由实现者决定（建议与 PingHandler Proto 定义一起测试）

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Proto 定义
- `proto/src/main/proto/nebula/envelope.proto` — Envelope、Direction（含 PING/PONG）、Request/Response 消息定义
- `proto/src/main/proto/nebula/message_type.proto` — MessageType 枚举（23 个方法）
- `proto/src/main/proto/nebula/common.proto` — 公共消息类型定义

### 设计文档 — Handler 层
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.1-接口契约.md` — Handler<ReqT, RespT> 接口定义
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.2-Koin注册.md` — Koin 模块注册方式
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.3-Pipeline与拦截器.md` — Interceptor 链和 ProtoCodec
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.4-异常处理.md` — BizException 映射 gRPC 状态码
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.5-分文件组织.md` — 模块目录和包结构

### 设计文档 — Session 与会话
- `设计文档/后端架构设计v1.2/04-认证与会话/4.1-登录流程.md` — Token 生成和 Session 创建
- `设计文档/后端架构设计v1.2/04-认证与会话/4.2-Session数据结构.md` — Session 字段定义

### 项目配置
- `server/src/main/kotlin/com/nebula/server/server/ChatServer.kt` — gRPC keepalive 配置（Phase 2 实现）
- `common/src/main/kotlin/com/nebula/common/exception/BizException.kt` — 业务异常定义

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `common` 模块的 `BizException` — 异常体系可直接复用，ExceptionInterceptor 按其 BizCode 映射 gRPC 状态码
- `repository` 模块的 `SessionRepository` — Phase 3 定义的 Redis Session 操作，SessionRegistry 直接依赖

### Established Patterns
- 协程已由 Phase 2 `CoroutineScope(Dispatchers.IO)` 模式确立为全局基础设施
- Koin 注入模式在各模块中统一（`nebulaModule` + `loadKoinModules`）
- gRPC keepalive 配置已在 ChatServer.kt 确立（Phase 2），心跳策略变更后需调整

### Integration Points
- ChatGatewayImpl（:gateway 模块） — gRPC 双向流 service 实现入口，调用 Dispatcher.dispatch()
- SessionRegistry 需要依赖 `:repository` 模块的 SessionRepository（Redis 操作）
- Handler 注册点在 Koin 模块中统一管理，Phase 5+ 的 Handler 通过 Koin 注入
- 心跳 Handler `system/ping` 需要注册到 Koin，添加到 AuthInterceptor.skipMethods

</code_context>

<specifics>
## Specific Ideas

No specific requirements beyond design docs — open to standard Kotlin/协程/Protobuf 最佳实践。

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 4-Handler Framework*
*Context gathered: 2026-06-12 (updated)*
