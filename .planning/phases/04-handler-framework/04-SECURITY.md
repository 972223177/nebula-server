---
phase: 04
slug: handler-framework
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-12
---

# Phase 04 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Dispatcher.dispatch() 入口 | 所有外部请求经过此入口进入 Handler 框架 | Request/Response proto 消息 |
| AuthInterceptor 入口 | 未认证请求在此边界被拦截 | Session token、Session 数据 |
| ExceptionInterceptor 出口 | 异常信息在此边界被脱敏 | 异常堆栈（仅日志），脱敏后的错误码/消息 |
| SessionRegistry L1 缓存 | Session 本地内存缓存一致性 | Session userId/token/deviceType/deviceId/connectionId |
| SessionRegistry L2 到 Redis | 跨节点 Session 持久化 | 同上，经网络传输 |
| Koin module 加载 | 组件注册和依赖解析入口 | 组件引用 |
| NebulaServer.kt Koin 初始化 | 应用级启动流程 | Koin 容器状态 |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-04-01 | Tampering | HandlerRegistry.register() 重复注册 | mitigate | putIfAbsent 替代 put，重复注册抛 IllegalStateException（D-11） | closed |
| T-04-02 | Denial of Service | ProtoCodec.buildCodec() MethodHandle | mitigate | MethodHandle 查找在注册时执行（启动期），运行时零反射开销；空载荷 guard 防止 parseFrom 异常 | closed |
| T-04-03 | Denial of Service | Dispatcher CoroutineScope | mitigate | SupervisorJob 隔离单个 Handler 异常，CoroutineExceptionHandler 兜底防止 JVM 崩溃（D-02/D-04） | closed |
| T-04-04 | Information Disclosure | Dispatcher 错误响应 | mitigate | method 不存在时返回 1003 NOT_FOUND，不暴露注册表内容 | closed |
| T-04-05 | Spoofing | AuthInterceptor 认证绕过 | mitigate | 每个请求验证 Session token，skipMethods 严格控制白名单仅 system/ping（D-09） | closed |
| T-04-06 | Information Disclosure | ExceptionInterceptor 异常泄漏 | mitigate | 未预期异常仅返回 9000 + "internal error"，堆栈写入日志不返回客户端（D-10） | closed |
| T-04-07 | Tampering | AuthInterceptor.skipMethods 篡改 | mitigate | skipMethods 通过 Koin 注入的构造函数参数配置，运行时不可修改；当前仅包含 system/ping（D-07） | closed |
| T-04-08 | Spoofing | AuthInterceptor CoroutineContext 注入伪造 | mitigate | AuthInterceptor 通过 withContext(SessionKey(session)) 注入，结构化并发保证上下文传递 | closed |
| T-04-09 | Denial of Service | RateLimitInterceptor 资源耗尽 | mitigate | 基于 Semaphore 的每用户并发限流（并发上限 20/用户），超限返回 429。未认证用户按 IP 限流 | closed |
| T-04-10 | Tampering | SessionRegistry L1/L2 缓存一致性 | mitigate | L1 只读缓存（validate 写入），L2 持久化（Redis TTL）。L1 故障不影响全局 Session（L2 兜底） | closed |
| T-04-10b | Denial of Service | SessionRegistry L2 Redis 超时 | mitigate | queryFromRedis() 和 saveToRedis() 使用 withTimeout(500ms) 保护，Redis 不可用时降级为纯 L1 缓存 | closed |
| T-04-11 | Elevation of Privilege | PingHandler 越权访问 | mitigate | D-30: AuthInterceptor 跳过 system/ping，但 PingHandler 只返回固定 pong 响应，无业务逻辑暴露 | closed |
| T-04-12 | Denial of Service | PingHandler 心跳洪泛 | mitigate | 客户端 PING 间隔 30~60s + 随机 Jitter（D-32），服务端 EnforcementPolicy minTime=10~20s 拒绝更频繁的 PING | closed |
| T-04-13 | Elevation of Privilege | SessionRegistry.onEviction 回调越权 | mitigate | 回调仅通知关闭 StreamObserver，不涉及认证或授权操作（D-20） | closed |
| T-04-14 | Denial of Service | keepalive Jitter 缺失导致惊群 | mitigate | D-32 强制所有时间参数随机化。ChatServer.kt 对 keepaliveTime、maxConnectionIdle 做 per-connection 随机化 | closed |
| T-04-15 | Denial of Service | NebulaServer Koin init 失败导致半初始化 | mitigate | startKoin 在 gRPC 启动前执行，若 DI 装配失败则应用不启动，避免运行在半初始化状态 | closed |
| T-04-16 | Tampering | registerHandlers() 注册顺序 | mitigate | registerHandlers() 在 Koin init 后、gRPC 启动前执行，确保 method→Handler 映射在请求到达前完整建立 | closed |
| T-04-17 | Tampering | registerHandlers() 遗漏 Handler 注册 | accept | 每次新增 Handler 时需在 registerHandlers() 中添加注册代码。Phase 5+ 将在新增 Handler 时同步更新 | closed |
| T-04-SC-1 | Tampering | Maven Central 依赖完整性 | mitigate | 所有包（koin-core 4.1.0, mockk 1.13.14, kotlinx-coroutines 1.9.0）在 RESEARCH.md Package Legitimacy Audit 中标记为 [VERIFIED] | closed |
| T-04-SC-2 | Tampering | npm/pip/cargo 包管理器安装 | accept | 本计划无包管理器安装操作。所有依赖通过 Gradle Maven 解析 | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| R-04-01 | T-04-17 | registerHandlers() 手动模式：每次新增 Handler 需手动在注册函数中添加。这是有意的显式设计，避免隐式注册带来的不可控行为。Phase 5+ 将在新增 Handler 时同步更新注册表 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-04-02 | T-04-SC-2 | 无第三方包管理器安装操作（npm/pip/cargo），所有依赖通过 Gradle Maven 解析并在 RESEARCH.md 中审计 | plan-audit (gsd-secure-phase) | 2026-06-12 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-06-12 | 20 | 20 | 0 | gsd-secure-phase (auto-verified) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-06-12
