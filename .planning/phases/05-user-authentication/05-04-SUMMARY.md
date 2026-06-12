---
phase: 05-user-authentication
plan: 04
subsystem: di-integration
tags: [koin, di, handler-registration, integration-test, chat-service, external-module, review-fix]

requires:
  - phase: 05-02
    provides: LoginHandler、RegisterHandler、SearchUserHandler、ChatService、ChatServer.addService
  - phase: 05-03
    provides: GetProfileHandler、BatchGetUserHandler、BatchGetStatusHandler、SetPrivacyHandler、GetPrivacyHandler
provides:
  - GatewayModule: 所有 9 个 Handler 的 Koin 注册 + AuthInterceptor skipMethods 更新 + registerHandlers inline 辅助（Review 修复）
  - NebulaServer: externalModule（外部 Repository 注入）+ startKoin 完整 + ChatService 注册 gRPC 启动
  - GatewayModuleTest: Koin 清理 + 所有 Handler 解析测试
  - PipelineIntegrationTest: user/login 端到端分发测试（含 Review 修复）
affects: [05-user-authentication]

tech-stack:
  added: []
  patterns: [HandlerRegistry.register() inline reified 扩展消除模板代码、@AfterEach + stopKoin() 显式清理模式]

key-files:
  created: []
  modified:
    - gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt
    - server/src/main/kotlin/com/nebula/server/NebulaServer.kt
    - gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt
    - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/PipelineIntegrationTest.kt
    - gateway/build.gradle.kts

key-decisions:
  - "registerHandlers() 采用 inline 扩展 + 显式参数方案（Option 2）—— 在 HandlerRegistry 上添加 register<ReqT, RespT>() inline 扩展函数，利用 reified 泛型在编译期获取 Req/Resp KClass，消除每个 Handler 的 HandlerEntry 构建模板代码。虽未消除 10 个参数，但函数体从 50+ 行模板代码降为 9 行单行调用。"
  - "GatewayModuleTest 不使用 KoinTestExtension 自动管理，改用 @AfterEach + stopKoin() 显式清理（Review 修复）。测试模块使用 mock PrivacyRepository 替代 handlerModule 中的 PrivacyRepository(get(),get()) 以避免泛型类型擦除导致的 Koin 解析失败。"

requirements-completed: [AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06, BIZ-USER-01, BIZ-USER-02, BIZ-USER-03, BIZ-USER-04, BIZ-USER-05, BIZ-USER-06]

duration: ~10min
completed: 2026-06-12
---

# Phase 05 Plan 04: 集成所有 Phase 5 组件 — Koin 模块注册 + NebulaServer 启动 + 集成测试

**集成 Phase 5 全部组件：GatewayModule（8 个 Phase 5 Handler Koin 注册 + registerHandlers inline 辅助修复）+ NebulaServer（externalModule 注入 + full startKoin + gRPC 启动）+ 集成测试（含登录流程 Review 修复）**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-06-12T16:54:00+08:00
- **Completed:** 2026-06-12T17:01:00+08:00
- **Tasks:** 3
- **Files modified:** 5
- **Tests:** 70+ (all passing)

## Accomplishments

### Task 1: GatewayModule 更新 — Koin 注册 + registerHandlers inline 修复 + AuthInterceptor skipMethods

- **handlerModule** — 新增 8 个 Phase 5 Handler 的 Koin `single { }` 声明：
  - `LoginHandler(get(), get())` — UserRepository + SessionRegistry
  - `RegisterHandler(get(), get())` — UserRepository + SnowflakeIdGenerator
  - `SearchUserHandler(get())` — UserRepository
  - `GetProfileHandler(get())` — UserRepository
  - `BatchGetUserHandler(get())` — UserRepository
  - `BatchGetStatusHandler(get(), get())` — OnlineStatusRepository + PrivacyRepository
  - `SetPrivacyHandler(get())` — PrivacyRepository
  - `GetPrivacyHandler(get())` — PrivacyRepository
- **AuthInterceptor skipMethods** — 扩展为 `setOf("system/ping", "user/login", "user/register")`（D-30: login/register 为公共方法，无需认证）
- **registerHandlers()** — Review 修复：新增 `HandlerRegistry.register()` inline reified 扩展函数，利用编译期泛型消除每个 Handler 的 HandlerEntry 模板代码。函数体从 50+ 行的重复 HandlerEntry 构建降为 9 行单行调用。

### Task 2: NebulaServer 启动流程更新 — externalModule + 全 Handler 注册 + ChatService gRPC 启动

- **externalModule** — 在 startKoin 前定义，将 HikariCP/Flyway/Redis 初始化的 Repository 实例注入 Koin：
  - `UserRepository`、`SessionRepository`、`OnlineStatusRepository`、`SnowflakeIdGenerator`、`PrivacyRepository(redisConnection, userRepo)`
- **startKoin** — 更新为 `modules(frameworkModule, handlerModule, externalModule)`
- **registerHandlers()** — 从 Koin 获取所有 9 个 Handler 实例，调用新签名 registerHandlers() 注册到 HandlerRegistry
- **初始化顺序** — Repository 先初始化 → Koin start → Handler 注册 → ChatService → gRPC start

### Task 3: 集成测试 — GatewayModuleTest（Koin 清理修复）+ PipelineIntegrationTest（含登录流程）

- **GatewayModuleTest.kt** — Review 修复：
  - 使用 `@AfterEach` + `stopKoin()` 显式清理 Koin 容器（替代 KoinTestExtension 自动管理）
  - 使用 `@TestInstance(Lifecycle.PER_METHOD)` 防止测试间污染
  - 验证所有 8 个 Phase 5 Handler 可被 Koin 正确解析
  - 验证 `registerHandlers()` 注册了全部 9 个 method 路由
- **PipelineIntegrationTest.kt** — 新增 6 个端到端分发测试：
  - `login dispatch test - correct password returns token` — 密码登录成功，验证 LoginResp 包含 token
  - `login dispatch test - wrong password returns non 200` — 密码错误，验证返回非 200 错误码
  - `register dispatch test - success returns uid` — 注册成功，验证 RegisterResp.uid
  - `search dispatch test - keyword returns user list` — 关键词搜索，验证 SearchUserResp 用户列表
  - `getProfile dispatch test - existing user returns profile` — 用户资料查询，验证 GetProfileResp
  - `getProfile dispatch test - non existent user returns error` — 用户不存在，验证返回非 200
- **gateway/build.gradle.kts** — 添加 `testImplementation(libs.lettuce.core)` 测试依赖

## Task Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | GatewayModule — Koin 注册 + registerHandlers inline 修复 | `1df1c1f` | GatewayModule.kt |
| 2 | NebulaServer — externalModule + 全 Handler 注册 | `2fe0d3d` | NebulaServer.kt |
| 3 | 集成测试 — GatewayModuleTest + PipelineIntegrationTest | `2341f28` | 测试文件 + build.gradle.kts |

## Files Modified

- `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` — handlerModule 扩展 + AuthInterceptor skipMethods + inline registerHandlers（+83/-22）
- `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` — externalModule + startKoin 更新 + 全 Handler 注册（+39/-5）
- `gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt` — Koin 清理修复 + 全 Handler 解析测试（重写）
- `gateway/src/test/kotlin/com/nebula/gateway/dispatcher/PipelineIntegrationTest.kt` — 6 个 Phase 5 集成测试（扩展）
- `gateway/build.gradle.kts` — 添加 lettuce-core 测试依赖

## Deviations from Plan

### Rule 1 - Auto-fix bugs

**1. [Rule 1] PipelineIntegrationTest 中 user/search 和 user/getProfile 需要认证 Token**
- **Found during:** Task 3 测试运行
- **Issue:** AuthInterceptor 拦截了 user/search 和 user/getProfile 请求（skipMethods 不含这两个方法），测试未提供认证 Token
- **Fix:** mock SessionRegistry + 自定义 AuthInterceptor.extractToken() 提供固定 Token
- **Files modified:** `PipelineIntegrationTest.kt`

**2. [Rule 1] coEvery 不匹配非 suspend 的 JPA Repository 方法**
- **Found during:** Task 3 测试运行
- **Issue:** `findById()` 和 `findByUsernameContaining()` 不是 suspend 函数，使用 `coEvery` 会导致 MockK 不匹配
- **Fix:** 改为使用 `every`（非 suspend mock）
- **Files modified:** `PipelineIntegrationTest.kt`

### Rule 3 - Auto-fix blocking issues

**3. [Rule 3] GatewayModuleTest 无法解析 PrivacyRepository 的 StatefulRedisConnection 泛型依赖**
- **Found during:** Task 3 测试编译
- **Issue:** handlerModule 中 `single { PrivacyRepository(get(), get()) }` 需要 `StatefulRedisConnection<String, String>`，Koin 因泛型类型擦除无法匹配 mock
- **Fix:** 测试中使用 mock `PrivacyRepository` 替代 handlerModule，避免 Koin 解析 Redis 连接
- **Files modified:** `GatewayModuleTest.kt`

**4. [Rule 3] 测试方法名包含 '/' 导致 Kotlin 编译错误**
- **Found during:** Task 3 测试编译
- **Issue:** 即使使用反引号，`/` 字符在 Kotlin 标识符中不合法
- **Fix:** 将 `user/login dispatch test` 等名称中的 `/` 替换为 `login dispatch test`
- **Files modified:** `PipelineIntegrationTest.kt`

**5. [Rule 3] gateway 测试模块缺少 lettuce-core 依赖**
- **Found during:** Task 3 测试编译
- **Issue:** GatewayModuleTest 使用 `StatefulRedisConnection` 类型但 lettuce-core 不在 test 作用域
- **Fix:** 添加 `testImplementation(libs.lettuce.core)` 到 gateway/build.gradle.kts
- **Files modified:** `gateway/build.gradle.kts`

## Review Fixes Applied

| Review Issue | Severity | Fix | Status |
|-------------|----------|-----|--------|
| registerHandlers() 参数列表过于冗长（10 个参数）| 低 | 新增 inline reified 扩展辅助函数 HandlerRegistry.register()，消除 HandlerEntry 模板重复代码 | ✓ |
| 测试中 Koin 清理（stopKoin + @AfterEach）| 低 | GatewayModuleTest 改用 @AfterEach + stopKoin() 显式清理 | ✓ |
| 集成测试缺少登录流程 | 低 | PipelineIntegrationTest 新增 user/login 密码正确/错误两个测试 | ✓ |

## Verification

- `./gradlew :gateway:compileKotlin` → BUILD SUCCESSFUL
- `./gradlew :server:compileKotlin` → BUILD SUCCESSFUL
- `./gradlew :gateway:test` → BUILD SUCCESSFUL (70+ tests, all passing)
- `./gradlew compileKotlin` → BUILD SUCCESSFUL
- `grep "LoginHandler\|RegisterHandler\|SearchUserHandler" GatewayModule.kt` → 所有 Handler 在 handlerModule 中注册 ✓
- `grep "user/login\|user/register" AuthInterceptor.kt` → skipMethods 包含这两个方法 ✓
- `grep "ChatService" NebulaServer.kt` → ChatService 创建和注册 ✓
- `grep "stopKoin" GatewayModuleTest.kt` → 每个测试类包含 stopKoin() 清理 ✓

## Threat Surface Scan

- **T-05-12 (Spoofing, accepted)** — Koin DI 容器仅内部注册，无外部输入 ✓
- **T-05-13 (Elevation of Privilege)** — registerHandlers() 在 startKoin 后执行，AuthInterceptor 在 Pipeline 中保证认证 ✓
- **T-05-14 (Tampering)** — ChatService gRPC 服务仅编译期绑定，无运行时热加载 ✓

No additional threat surface introduced beyond the plan's `<threat_model>`.

## Self-Check: PASSED

- [x] `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` — handlerModule 包含 9 个 Handler 声明
- [x] `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` — externalModule + startKoin 更新
- [x] `gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt` — @AfterEach + stopKoin() 清理
- [x] `./gradlew :gateway:test BUILD SUCCESSFUL`
- [x] `./gradlew compileKotlin BUILD SUCCESSFUL`

---
*Phase: 05-user-authentication*
*Completed: 2026-06-12*
