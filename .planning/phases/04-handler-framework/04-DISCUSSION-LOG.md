# Phase 4: Handler Framework - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 4-Handler Framework
**Areas discussed:** 协程支持策略, 拦截器 Pipeline 架构, Dispatcher 路由与序列化, Session 数据结构与跨模块依赖, 包结构与目录组织, 心跳处理策略, Handler 测试策略

---

## 协程支持策略

| Option | Description | Selected |
|--------|-------------|----------|
| 同步 Handler + runBlocking 桥接 | Handler 是纯同步函数，需要协程时内部 runBlocking | |
| suspend Handler (推荐) | Handler 接口定义为 suspend fun，与协程生态一致 | ✓ |
| 你来决定 | — | |

| Option | Description | Selected |
|--------|-------------|----------|
| CoroutineScope(Dispatchers.IO) | 使用 Netty 标准 worker 线程池 + Dispatchers.IO | ✓ |
| 自定义协程调度器 | 引入自定义调度器或 virtual threads | |

| Option | Description | Selected |
|--------|-------------|----------|
| Dispatcher 内部 CoroutineScope (推荐) | 统一管理生命周期和异常 | ✓ |
| 无统一作用域 | 简单但异常传播路径不清晰 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Session 通过 CoroutineContext 传递 (推荐) | 接口简洁，协程安全 | ✓ |
| Handler 参数显式传 Session | 显式但参数膨胀 | |

**自由文本补充:** Session通过CoroutineContext传递，且需要注释说明来源和用法

| Option | Description | Selected |
|--------|-------------|----------|
| CoroutineExceptionHandler 统一捕获 (推荐) | 统一异常入口 | ✓ |
| 逐层 try/catch | 重复代码，容易遗漏 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 全局单作用域 (推荐) | ChatServer 级别全局，SupervisorJob 隔离 | ✓ |
| 每条连接一个 CoroutineScope | 连接级取消，复杂度增加 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 所有拦截器都是 suspend (推荐) | Pipeline 统一为协程链 | ✓ |
| 拦截器保持同步 | AuthInterceptor 中的 Redis 调用会阻塞 | |

**User's choice:** suspend Handler + Dispatchers.IO + 全局单 CoroutineScope + CoroutineContext 传递 Session + CoroutineExceptionHandler 统一捕获 + 所有 Interceptor 为 suspend
**Notes:** 用户强调 Session 通过 CoroutineContext 传递时需要在 KDoc 中注明来源和用法。

---

## 拦截器 Pipeline 架构

| Option | Description | Selected |
|--------|-------------|----------|
| 拦截器 Chain + 注解控制 (推荐) | 设计文档 8.3 的 Interceptor/Chain 接口，suspend 适配 | ✓ |
| 高阶函数组合链 | Kotlin 函数式组合风格 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Auth → Log → Exception (推荐) | Auth 在前尽早拒绝未认证请求 | ✓ |
| Log → Auth → Exception | 先记录再认证 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 3 个拦截器 (推荐) | Auth、Log、Exception | |
| 4 个拦截器（含限流） | Auth、Log、RateLimit、Exception | ✓ |

| Option | Description | Selected |
|--------|-------------|----------|
| AuthInterceptor 内部白名单 (推荐) | skipMethods: Set<String> | ✓ |
| 注册时标注 authRequired | 声明式但复杂度分布 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 三态异常处理 + 防泄漏 (推荐) | BizException→业务码, IllegalArgumentException→BAD_REQUEST, 其他→9000 | ✓ |
| 统一兜底 | 所有异常统一 GENERIC_ERROR | |

**User's choice:** Chain 接口 + 4 拦截器 (Auth→Log→RateLimit→Exception) + AuthInterceptor 白名单 + 三态异常防泄漏
**Notes:** RateLimitInterceptor 被用户要求一并实现，虽原设计属于性能优化范畴。

---

## Dispatcher 路由与序列化

| Option | Description | Selected |
|--------|-------------|----------|
| 运行时反射查询 (推荐) | ConcurrentHashMap + KClass 反射调用 parseFrom() | ✓ |
| Kotlin reified 编译期校验 | inline + reified 但限制 Koin 灵活性 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 预编译方法引用缓存 (推荐) | 注册时缓存 parseFrom/serialize 方法引用 | ✓ |
| 运行时全反射 | 每次调用都有反射开销 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Registry 持有类型信息 (推荐) | HandlerEntry 包含 Handler + 序列化方法引用 | ✓ |
| ProtoCodec 独立维护 | 需要查两次表 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Dispatcher 返回完整 Response (推荐) | 不解耦 gRPC StreamObserver | ✓ |
| Dispatcher 直接操作 StreamObserver | 耦合 gRPC 细节 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 接收 Request proto，返回 Response proto (推荐) | suspend fun dispatch(request: Request): Response | ✓ |
| 接收 method + bytes，返回 bytes | 更底层，Gateway 需额外组装 | |

**User's choice:** 运行时反射查表 + 预编译方法引用缓存 + Registry 持有类型信息 + 返回完整 Response Proto
**Notes:** 无额外说明。

---

## Session 数据结构与跨模块依赖

| Option | Description | Selected |
|--------|-------------|----------|
| 完整 Session (推荐) | userId, token, deviceType, deviceId, connectionId | ✓ |
| 最小 Session | 简化版后续按需扩展 | |

| Option | Description | Selected |
|--------|-------------|----------|
| SessionRegistry 在 :gateway 模块 (推荐) | 依赖 :repository SessionRepository | ✓ |
| SessionRegistry 在 :repository 模块 | 耦合 Gateway 概念 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 本地内存 + Redis 二级缓存 (推荐) | ConcurrentHashMap L1 + Redis L2 | ✓ |
| 纯 Redis 查询 | 每次请求都要 Redis 查询 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 注册回调通知 + 本地驱逐 (推荐) | (token) -> Unit 回调，通知关闭 StreamObserver | ✓ |
| 被动 TTL 到期扫描 | 定期全量扫描，不够及时 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 统一入口 + 细粒度方法 (推荐) | 组合方法 (validate/register/unregister) + 底层操作 | ✓ |
| 纯底层操作 API | 调用方自行编排 | |

**User's choice:** 细粒度子操作 API → 统一入口 + 细粒度方法
**Notes:** 用户最初选择了"细粒度子操作 API"，经进一步确认后采用"统一入口 + 细粒度方法"方案。

---

## 包结构与目录组织

| Option | Description | Selected |
|--------|-------------|----------|
| 设计文档标准结构 (推荐) | com.nebula.gateway.{dispatcher|interceptor|codec|session|handler.{domain}} | ✓ |
| 扁平结构 | 不按 domain 分包 | |

**User's choice:** 标准结构
**Notes:** 无额外说明。

---

## 心跳处理策略

| Option | Description | Selected |
|--------|-------------|----------|
| 纯 gRPC keepalive | 框架自动处理，零应用逻辑 | ✓ |
| 应用层自定义心跳 | 增加 Dispatcher 请求量 | |

**User's choice:** 纯 gRPC keepalive，已从 envelope.proto 中移除 PING/PONG 枚举值
**Notes:** 用户提到 Envelope 的 Direction 枚举已定义 PING/PONG，经分析后确认 gRPC keepalive 在传输层处理，无需应用层心跳。PING(4)/PONG(5) 已标记为 reserved 移除。

---

## Handler 测试策略

| Option | Description | Selected |
|--------|-------------|----------|
| JUnit5 + MockK (推荐) | 与项目现有配置一致，coEvery{} 原生支持 suspend | ✓ |
| Kotest | 需要引入新依赖 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 单元测试覆盖框架组件 (推荐) | HandlerRegistry, Dispatcher, Interceptor, ProtoCodec, SessionRegistry | ✓ |
| 仅集成测试 | 单元级问题发现晚 | |

| Option | Description | Selected |
|--------|-------------|----------|
| runTest + MockK coEvery (推荐) | koltinx-coroutines-test 虚拟时间 | ✓ |
| runBlocking | 不提供协程测试能力 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Mock 全链路测试 (推荐) | MockHandlerRegistry + MockInterceptorChain | ✓ |
| 仅独立组件测试 | 不做集成验证 | |

**User's choice:** JUnit5 + MockK + 单元覆盖各组件 + runTest + Mock 全链路测试
**Notes:** 无额外说明。

---

## Claude's Discretion

无 — 所有灰色区域均已做出明确决策。

## Deferred Ideas

无 — 讨论严格保持在 Phase 4 范围内。
