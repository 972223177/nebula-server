---
phase: 06-chat-message
plan: 04
subsystem: gateway, server
tags:
  - di-wiring
  - koin
  - handler-registration
  - nebula-server
requires:
  - "06-02"
  - "06-03"
provides:
  - "Phase 6 组件 Koin 注册"
  - "Handler 路由注册"
  - "Koin 容器验证测试"
affects:
  - "gateway"
  - "server"
tech-stack:
  added:
    - dependency: "io.insert-koin:koin-test (testImplementation for :server)"
      reason: "Phase 6 Koin 容器验证测试"
    - dependency: "io.mockk:mockk (testImplementation for :server)"
      reason: "Koin 验证测试的 mock 依赖"
    - dependency: "org.junit.jupiter:junit-jupiter-api (testImplementation for :server)"
      reason: "JUnit5 测试框架"
    - dependency: "org.junit.jupiter:junit-jupiter-engine (testRuntimeOnly for :server)"
      reason: "JUnit5 测试引擎"
    - dependency: "org.junit.platform:junit-platform-launcher (testRuntimeOnly for :server)"
      reason: "JUnit5 Platform Launcher"
key-files:
  created:
    - "server/.../test/.../KoinVerificationTest.kt"
  modified:
    - "gateway/.../di/GatewayModule.kt"
    - "server/.../NebulaServer.kt"
    - "gateway/.../di/GatewayModuleTest.kt"
    - "server/build.gradle.kts"
decisions:
  - "Step 链通过显式 listOf() 注册而非 Koin getAll()，避免 Koin 4.x List<T> 泛型解析兼容性问题"
  - "redisConfig.connection 注册到 Koin 的 externalModule，供 DedupStep/WriteStep 的 StatefulRedisConnection 依赖解析"
  - "CoroutineScope 以 named('sendHandlerScope') 注册，避免与 ChatService 内部 scope 冲突"
  - "Koin 验证测试放在 server 模块而非 gateway 模块，更贴近实际启动路径"
metrics:
  duration: "~5 分钟（编译 + 全量测试）"
  completed_date: "2026-06-12"
---

# Phase 6 Plan 4: DI Wiring 与 Handler 注册 — Summary

将 Phase 6 所有组件注册到 Koin 依赖注入容器，更新 HandlerRegistry 注册入口，修复 ChatService 构造函数变更（REVIEW-MEDIUM-8），添加 Koin 容器验证测试。

## Tasks Completed

| 任务 | 名称 | 提交 | 关键文件 |
|------|------|------|----------|
| 1 | GatewayModule.kt handlerModule 追加 Phase 6 组件 | (Task 1+2 合并提交) | GatewayModule.kt |
| 2 | registerHandlers + NebulaServer.kt 更新 | (Task 1+2 合并提交) | GatewayModule.kt, NebulaServer.kt |
| 3 | Koin 验证测试 | (Task 3 单独提交) | KoinVerificationTest.kt, server/build.gradle.kts |

## Architecture

```
GatewayModule.kt handlerModule 新增注册:
├── named("sendHandlerScope") → CoroutineScope(IO + SupervisorJob)
├── UserStreamRegistry()                            (D-01)
├── PushService(UserStreamRegistry, ConvMemberRepo)
├── listOf<SendMessageStep>(
│   ├── ValidateStep(ConvMemberRepo)
│   ├── DedupStep(RedisConnection)
│   └── WriteStep(SnowflakeIdGen, MsgQueueRepo, RedisConn)
│   )
├── SendMessageHandler(steps, pushSvc, convMemberRepo, redisConn, scope)
├── PullMessagesHandler(MessageRepo, ConversationRepo)
└── ReadReportHandler(ConvRepo, ConvMemberRepo, PushSvc, RedisConn)

registerHandlers 新增:
├── registry.register(sendMessageHandler)   // chat/send
├── registry.register(pullMessagesHandler)  // message/pull
└── registry.register(readReportHandler)    // message/read

NebulaServer.kt 更新:
├── externalModule 追加: redisConfig.connection → StatefulRedisConnection<String,String>
├── Koin get: sendMessageHandler, pullMessagesHandler, readReportHandler
└── registerHandlers 追加 3 个实参
```

## Deviations from Plan

### Rule 1 — 修复 StatefulRedisConnection 注册到 externalModule

- **问题：** 生产 GatewayModule.kt 的 DedupStep/WriteStep 需要 `StatefulRedisConnection<String, String>` 的 Koin 定义，但该 Bean 未在任何模块中注册。
- **修复：** 在 NebulaServer.kt 的 `externalModule` 中追加 `single { redisConfig.connection as StatefulRedisConnection<String, String> }`
- **文件修改：** server/.../NebulaServer.kt (externalModule)

### Rule 2 — Step 链注册方式调整

- **问题：** Koin 4.1.0 不支持通过 `single<SendMessageStep> { ... }` + `get()` (for `List<SendMessageStep>`) 自动聚合所有子类型。
- **修复：** 改用显式 `single { listOf<SendMessageStep>(ValidateStep(get()), DedupStep(get()), WriteStep(get(), get(), get())) }` 构建列表。
- **影响：** 移除了 `single<SendMessageStep>` 的单独注册，改为直接注册 `List<SendMessageStep>` Bean。

### Rule 3 — GatewayModuleTest 需要同步更新

- **问题：** registerHandlers 新增 3 个参数后，现有 GatewayModuleTest 无法编译。
- **修复：** 在 GatewayModuleTest 中添加 Phase 6 mock 依赖 + 3 个新 Handler 获取 + 注册 + 验证断言。
- **文件修改：** gateway/.../di/GatewayModuleTest.kt

## Test Results

- **gateway:compileKotlin** — 通过
- **server:compileKotlin** — 通过
- **gateway:test** — 111 tests, all passing (including GatewayModuleTest)
- **server:test** — 1 test passing (KoinVerificationTest)
- **Full build (./gradlew build)** — 通过

## Threat Surface

无新增威胁面 — DI 注册不引入新的信任边界：

| Threat ID | Disposition | Component |
|-----------|-------------|-----------|
| T-06-12 | mitigate | Koin module registration (编译期类型安全) |
| T-06-13 | mitigate | HandlerRegistry.register (inline reified 泛型，零反射) |
| T-06-16 | mitigate | ChatService instantiation (REVIEW-MEDIUM-8，编译期强制检查) |

## Self-Check: PASSED

所有修改已通过全量编译和测试验证。
