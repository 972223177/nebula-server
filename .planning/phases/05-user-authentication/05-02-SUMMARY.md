---
phase: 05-user-authentication
plan: 02
subsystem: auth
tags: [gRPC, bidirectional-streaming, chat-service, login, register, search-user, bcrypt, session-binding, device-kick]

requires:
  - phase: 05-01
    provides: Proto 契约扩展（metadata/LoginResp device 字段）、SessionRegistry registerWithDeviceType()、AuthInterceptor Token 提取、RegisterRateLimiter、spring-security-crypto 依赖
provides:
  - ChatService: gRPC BindableService 双向流实现，Envelope 协议分发、LoginResp 拦截/Session 绑定、tokenToObserver 生命周期管理
  - LoginHandler: 密码验证（bcrypt cost 12）+ Token 重连复用现有 Token（Review 修复）
  - RegisterHandler: 唯一性校验 + 密码强度 + bcrypt 哈希
  - SearchUserHandler: LIKE 模糊搜索 + 游标分页
  - UserRepository: JPA @Query 参数绑定模糊搜索方法
  - V1_2__seed_users.sql: 3 个预置账号（admin/testuser1/testuser2）
  - ChatServer: addService(ChatService) 服务注册（Review 修复）
affects: [05-user-authentication, 08-friend-online-status, 09-chat-send]

tech-stack:
  added: []
  patterns: [gRPC 自定义 BindableService + ServerCalls.asyncBidiStreamingCall、coroutine scope 桥接非 suspend 回调、open class + open fun 测试覆写模式]

key-files:
  created:
    - gateway/.../service/ChatService.kt
    - gateway/.../handler/user/LoginHandler.kt
    - gateway/.../handler/user/RegisterHandler.kt
    - gateway/.../handler/user/SearchUserHandler.kt
    - repository/.../db/migration/V1_2__seed_users.sql
    - gateway/.../handler/user/LoginHandlerTest.kt
    - gateway/.../handler/user/RegisterHandlerTest.kt
    - gateway/.../handler/user/SearchUserHandlerTest.kt
  modified:
    - server/.../server/ChatServer.kt
    - server/.../NebulaServer.kt
    - repository/.../repository/UserRepository.kt
    - gateway/build.gradle.kts

key-decisions:
  - "ChatService 使用 ServerCalls.asyncBidiStreamingCall 注册自定义双向流方法，非 gRPC 生成的 stub"
  - "ChatService 通过 CoroutineScope(Dispatchers.IO + SupervisorJob) 桥接 gRPC 非 suspend 回调与协程 Dispatcher"
  - "LoginHandler 改为 open class，verifyPassword 提取为 open 方法便于测试覆写 BCryptPasswordEncoder（final class 不可 mock）"
  - "LoginHandler 从 LoginReq 直接复制 deviceType/deviceId 到 LoginResp，ChatService 从 LoginResp 读取避免重复解析 params"

requirements-completed: [AUTH-01, AUTH-02, AUTH-05, AUTH-06, BIZ-USER-01]

duration: 32min
completed: 2026-06-12
---

# Phase 05 Plan 02: 核心认证 Handler — ChatService 双向流 + Login/Register/Search Handler + 单元测试

**实现 gRPC 双向流服务 + 核心认证 Handler：ChatService（含 LoginResp 拦截/Session 绑定 + addService 注册）、LoginHandler（Token 复用修复）、RegisterHandler、SearchUserHandler、预置账号、14 个单元测试**

## Performance

- **Duration:** 32 min
- **Started:** 2026-06-12
- **Completed:** 2026-06-12
- **Tasks:** 3
- **Files created:** 8
- **Files modified:** 4
- **Tests:** 14 (all passing)

## Accomplishments

### Task 1: ChatService gRPC 双向流服务 + ChatServer addService 注册

- **ChatService.kt** — 创建 gRPC `BindableService` 实现，使用 `ServerServiceDefinition.builder("nebula.chat.ChatService")` + `MethodDescriptor` + `ServerCalls.asyncBidiStreamingCall` 注册自定义 BIDI_STREAMING 方法
  - Envelope 协议分发：`Direction.REQUEST` → `handleRequest()`（dispatcher.dispatch），`Direction.PING` → PONG 回复
  - LoginResp 拦截（D-05 绑定流程）：检测 `user/login` 200 响应 → `LoginResp.parseFrom()` 直接读取 `deviceType/deviceId`（Review 修复#3：无需重复解析 `Request.params`）→ 构建 Session → `registerWithDeviceType()` → 更新 `tokenToObserver`
  - `tokenToObserver: ConcurrentHashMap` 维护 token↔StreamObserver 映射；onCompleted/onError 清理（Review 修复#6）
  - eviction callback 注册：Session 被驱逐时推送 PUSH `PushEventType.LOGOUT` Envelope 并关闭连接
  - 使用 `CoroutineScope(Dispatchers.IO + SupervisorJob())` 桥接 gRPC 非 suspend 回调与协程 Dispatcher
- **ChatServer.kt** — `start()` 签名改为 `fun start(chatService: ChatService)`，调用 `builder.addService(chatService)`（Review 修复#1：Phase 4 遗漏的服务注册）
- **NebulaServer.kt** — 新增 ChatService/Dispatcher 依赖构造流程，从 Koin 获取 SessionRegistry + 拦截器列表，创建 Dispatcher 和 ChatService 实例
- **gateway/build.gradle.kts** — 新增 `grpc-api`、`grpc-protobuf`、`grpc-stub` 依赖

### Task 2: LoginHandler（Review 修复：Token 复用 + 移除 SnowflakeIdGenerator）+ RegisterHandler

- **LoginHandler.kt** — 实现 `Handler<LoginReq, LoginResp>`
  - Token 重连路径：`sessionRegistry.validate(token)` → 有效则复用 `existingSession.token`（Review 修复#2：避免 Session 孤儿化）
  - 密码登录路径：`findByUsername()` → `BCryptPasswordEncoder(12).matches()` 验证
  - deviceType/deviceId 从 LoginReq 复制到 LoginResp（Review 修复#3）
  - `open fun verifyPassword()` 提取为独立方法（因 BCryptPasswordEncoder 为 final class）
  - 无 SnowflakeIdGenerator 依赖（Review 修复#4）
- **RegisterHandler.kt** — 实现 `Handler<RegisterReq, RegisterResp>`
  - 密码长度校验（>=6 位）、用户名唯一性校验、BCrypt cost 12 哈希
  - 返回 RegisterResp（仅 uid，不含 token — 注册后需通过 `/login` 获取 Token）

### Task 3: SearchUserHandler + UserRepository 增强 + 预置账号 SQL + 14 个单元测试

- **SearchUserHandler.kt** — LIKE `%keyword%` 模糊搜索（D-07），游标分页最多 20 条按 createdAt 倒序（D-08）
- **UserRepository.kt** — 新增 `@Query("SELECT u FROM UserEntity u WHERE u.username LIKE %:keyword% AND (:cursor = 0L OR u.createdAt < :cursor) ORDER BY u.createdAt DESC") findByUsernameContaining()`，参数绑定防 SQL 注入
- **V1_2__seed_users.sql** — 3 个预置账号（admin/admin123, testuser1/test1234, testuser2/test1234），uid 1000000+，bcrypt 预哈希
- **LoginHandlerTest.kt** — 5 个测试（密码登录成功/密码错误/用户名不存在/Token 复用/Token 过期）
- **RegisterHandlerTest.kt** — 4 个测试（注册成功/用户名已存在/密码太短/用户名为空）
- **SearchUserHandlerTest.kt** — 5 个测试（搜索成功/无结果/分页/游标/空关键词）

## Task Commits

Each task was committed atomically:

1. **Task 1: ChatService + ChatServer addService + 依赖** — `a043c57` (feat)
2. **Task 2: LoginHandler + RegisterHandler** — `9d9b885` (feat)
3. **Task 3: SearchUserHandler + UserRepository + SQL + 测试** — (latest commit) (feat)

## Files Created/Modified

### Created
- `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` — gRPC BindableService 双向流实现
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/LoginHandler.kt` — 用户登录 Handler
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/RegisterHandler.kt` — 用户注册 Handler
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/SearchUserHandler.kt` — 用户搜索 Handler
- `repository/src/main/resources/db/migration/V1_2__seed_users.sql` — 预置账号 Flyway 迁移
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/LoginHandlerTest.kt` — 5 个测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/RegisterHandlerTest.kt` — 4 个测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/SearchUserHandlerTest.kt` — 5 个测试

### Modified
- `server/src/main/kotlin/com/nebula/server/server/ChatServer.kt` — start() 接受 ChatService 参数，调用 addService
- `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` — 新增 ChatService/Dispatcher 构造启动流程
- `repository/src/main/kotlin/com/nebula/repository/repository/UserRepository.kt` — 新增 @Query 模糊搜索方法
- `gateway/build.gradle.kts` — 新增 grpc-api/grpc-protobuf/grpc-stub 依赖

## Deviations from Plan

### Rule 2 - Auto-add missing critical functionality

**1. [Rule 2] LoginHandler.buildLoginResp() 需要从 LoginReq 复制 deviceType/deviceId**
- **Found during:** Task 2
- **Issue:** 计划中 `buildLoginResp()` 代码片段没有设置 deviceType/deviceId，但 ChatService 需要从 LoginResp 读取这些字段
- **Fix:** `buildLoginResp()` 新增 `req: LoginReq` 参数，调用 `.setDeviceType(req.deviceType).setDeviceId(req.deviceId)`
- **Files modified:** `LoginHandler.kt`
- **Commit:** `9d9b885`

**2. [Rule 2] SearchUserHandler 空关键词应返回空结果而非报错**
- **Found during:** Task 3
- **Issue:** 空关键词传 NULL 会导致 SQL 异常
- **Fix:** 增加 `if (keyword.isBlank()) return SearchUserResp.getDefaultInstance()` 保护
- **Files modified:** `SearchUserHandler.kt`
- **Commit:** (latest)

### Rule 3 - Auto-fix blocking issues

**3. [Rule 3] gateway 模块缺少 gRPC 依赖**
- **Found during:** Task 1 编译
- **Issue:** ChatService 使用 `io.grpc.*` 类型但 gateway 模块没有 gRPC 依赖
- **Fix:** gateway/build.gradle.kts 添加 `grpc-api`、`grpc-protobuf`、`grpc-stub`
- **Files modified:** `gateway/build.gradle.kts`
- **Commit:** `a043c57`

**4. [Rule 3] GitHub Copilot 类型推断问题 — ChatService 编译错误**
- **Found during:** Task 1 编译
- **Issue:** `asyncBidirectionalStreamingCall` 在 gRPC 1.81.0 中不存在（应使用 `asyncBidiStreamingCall`），`import io.grpc.ServerCalls` 应改为 `import io.grpc.stub.ServerCalls`
- **Fix:** 修正方法名和导入路径
- **Commit:** `a043c57`

**5. [Rule 3] NebulaServer.kt 调用 chatServer.start() 未传参**
- **Found during:** Task 1 编译
- **Issue:** ChatServer.start() 签名变更后，调用方未更新
- **Fix:** 新增 ChatService/Dispatcher 依赖构造并传入 chatServer.start(chatService)
- **Commit:** `a043c57`

**6. [Rule 3] LoginHandlerTest 中 findById 返回类型不匹配**
- **Found during:** Task 3 测试编译
- **Issue:** `findById()` 返回 `Optional<UserEntity>` 而非 `UserEntity`
- **Fix:** 使用 `Optional.of()` 包装
- **Commit:** (latest)

**7. [Rule 3] SearchUserHandlerTest 中 toEpochMilli 不存在**
- **Found during:** Task 3 测试编译
- **Issue:** `LocalDateTime` 没有 `toEpochMilli()` 方法，需要先转 `Instant`
- **Fix:** 使用 `toInstant(ZoneOffset.UTC).toEpochMilli()`
- **Commit:** (latest)

### Changes to implement

- LoginHandler 改为 `open class` — 使测试可创建匿名子类覆写 `verifyPassword`（因 BCryptPasswordEncoder 为 final class）
- SearchUserHandlerTest 中使用 `java.time.ZoneOffset.UTC` 全限定名（避免与 proto 生成代码的 ZoneOffset 冲突）

## Review Fixes Applied

| Review Issue | Severity | Fix | Status |
|-------------|----------|-----|--------|
| Phase 4 遗漏 addService() | 高 | ChatServer.start() 调用 builder.addService(chatService) | ✓ |
| Token 重连时复用现有 Token | 高 | LoginHandler 使用 existingSession.token 而非 UUID.randomUUID() | ✓ |
| 重复解析 Request.params | 高 | ChatService 从 LoginResp.deviceType/deviceId 直接读取 | ✓ |
| LoginHandler 移除 SnowflakeIdGenerator | 中 | LoginHandler 构造函数只有 UserRepository + SessionRegistry | ✓ |
| tokenToObserver 生命周期清理 | 中 | onCompleted/onError 通过 cleanupConnection() 清理映射 | ✓ |

## Issues Encountered

- **gRPC API 版本差异**：`ServerCalls.asyncBidirectionalStreamingCall` 在 gRPC 1.81.0 中已不存在，需要使用 `asyncBidiStreamingCall`。
- **gateway 模块缺少 gRPC 依赖**：ChatService 创建在 gateway 模块中，但该模块原无 gRPC 依赖，需添加 `grpc-api`、`grpc-protobuf`、`grpc-stub`。
- **BCryptPasswordEncoder final class 不可 Mock**：采用 `open fun verifyPassword()` + `open class LoginHandler` 模式解决，测试中通过匿名子类覆写该方法。
- **Spring Data JPA findById 返回 Optional**：MockK 需要 `Optional.of()` 包装返回值。

## Threat Surface Scan

No additional threat surface introduced beyond the plan's `<threat_model>`. All mitigations are implemented:
- T-05-04 (Spoofing): bcrypt cost 12 + Token 重连验证
- T-05-05 (Tampering): 用户名唯一性校验 + 密码强度校验
- T-05-06 (Information Disclosure): USER_NOT_FOUND 和 AUTH_FAILED 分别返回
- T-05-07 (Tampering): JPA @Query 参数绑定防 SQL 注入
- T-05-08 (DoS): RegisterRateLimiter IP 限流（Plan 01 已实现）
- T-05-14 (Tampering): ChatService 编译期绑定，无运行时热加载

## Self-Check: PASSED

- `./gradlew :gateway:compileKotlin :server:compileKotlin :repository:compileKotlin` → BUILD SUCCESSFUL
- `./gradlew :gateway:test --tests "com.nebula.gateway.handler.user.*"` → BUILD SUCCESSFUL (14 tests)
- `grep "addService" ChatServer.kt` → `builder.addService(chatService)` found
- `grep "existingSession.token" LoginHandler.kt` → Token 重连复用现有 Token
- `grep "LoginResp" ChatService.kt` → LoginResp.parseFrom() + deviceType/deviceId 读取
- `grep "tokenToObserver" ChatService.kt` → ConcurrentHashMap + cleanupConnection
- `grep "cleanupConnection\|onCompleted\|onError" ChatService.kt` → 生命周期管理完善
- `grep "findByUsernameContaining\|@Query" UserRepository.kt` → JPA 参数绑定模糊搜索
- SQL 预置账号文件存在，bcrypt 预哈希

---
*Phase: 05-user-authentication*
*Completed: 2026-06-12*
