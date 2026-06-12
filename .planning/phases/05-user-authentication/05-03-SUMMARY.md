---
phase: 05-user-authentication
plan: 03
subsystem: user-api
tags: [handler, user-profile, batch-query, online-status, privacy, mget, review-fix]

requires:
  - phase: 05-user-authentication
    plan: 01
    provides: PrivacyRepository（含 batchGetHideOnlineStatus MGET）、SessionKey、OnlineStatusRepository、UserRepository
provides:
  - GetProfileHandler: user/getProfile 用户详细资料查询
  - BatchGetUserHandler: user/batchGet 批量用户摘要（缺失 ID 文档化）
  - BatchGetStatusHandler: user/batchGetStatus 批量在线状态（MGET 隐私过滤，Review 修复 N+1）
  - SetPrivacyHandler: user/setPrivacy 隐私设置（Redis 写 + 异步 MySQL，Review 修复 best-effort 文档化）
  - GetPrivacyHandler: user/getPrivacy 隐私读取（Redis 优先 + MySQL 回退）
affects: [08-friend-online-status]

tech-stack:
  added: []
  patterns: [Handler Session 注入 withContext, PrivacyRepository MGET 批量过滤, best-effort 异步 MySQL 文档化]

key-files:
  created:
    - gateway/.../handler/user/GetProfileHandler.kt
    - gateway/.../handler/user/BatchGetUserHandler.kt
    - gateway/.../handler/user/BatchGetStatusHandler.kt
    - gateway/.../handler/user/SetPrivacyHandler.kt
    - gateway/.../handler/user/GetPrivacyHandler.kt
    - gateway/.../handler/user/GetProfileHandlerTest.kt
    - gateway/.../handler/user/BatchGetUserHandlerTest.kt
    - gateway/.../handler/user/BatchGetStatusHandlerTest.kt
    - gateway/.../handler/user/SetPrivacyHandlerTest.kt
    - gateway/.../handler/user/GetPrivacyHandlerTest.kt
  modified:
    - gateway/build.gradle.kts

key-decisions:
  - "GetProfileResp.gender 和 bio 在 v1 中暂不填充（UserEntity 尚无对应字段）"
  - "BatchGetUserHandler 缺失 ID 静默跳过 — JPA findAllById 的预期行为，客户端通过结果判断"
  - "BatchGetStatusHandler 使用 batchGetHideOnlineStatus() MGET 批量隐私过滤，非逐用户查询（Review 修复 N+1）"
  - "SetPrivacyHandler 只允许修改当前用户隐私设置（userId 从 Session 获取，非请求参数）"
  - "GetPrivacyHandler 使用 GetPrivacyReq 空消息，userId 从 Session 获取"

patterns-established:
  - "需要 Session 的 Handler 测试：withContext(SessionKey(session)) 注入"
  - "findById 返回 Optional<UserEntity>，使用 .orElse(null) 转为可空类型"
  - "LocalDateTime 转 epoch mills：atZone(ZoneOffset.UTC).toInstant().toEpochMilli()"

requirements-completed: [BIZ-USER-02, BIZ-USER-03, BIZ-USER-04, BIZ-USER-05, BIZ-USER-06]

duration: 25min
completed: 2026-06-12
---

# Phase 05 Plan 03: 用户业务 API Handler — 资料查询、批量查询、在线状态（MGET 隐私过滤）、隐私读写

**5 个 Handler 实现：user/getProfile、user/batchGet、user/batchGetStatus（MGET 批量隐私过滤修复 N+1）、user/setPrivacy（异步 MySQL best-effort 文档化）、user/getPrivacy，含全部单元测试**

## Performance

- **Duration:** 25 min
- **Started:** 2026-06-12T16:25:00Z (approx)
- **Completed:** 2026-06-12T16:50:00Z (approx)
- **Tasks:** 3
- **Files modified:** 11 (created 10, modified 1)

## Accomplishments

- **GetProfileHandler** — user/getProfile，使用 UserRepository.findById() 查询用户详细资料，用户不存在抛 USER_NOT_FOUND
- **BatchGetUserHandler** — user/batchGet，使用 findAllById() 批量查询，KDoc 文档化缺失 ID 静默跳过行为（Review 修复）
- **BatchGetStatusHandler** — user/batchGetStatus，使用 PrivacyRepository.batchGetHideOnlineStatus()（Redis MGET）一次批量查询隐私设置，避免 N+1 逐用户查询（Review 修复）
- **SetPrivacyHandler** — user/setPrivacy，写 Redis 立即生效 + 异步 MySQL best-effort，KDoc 标注 Pitfall 4（服务器 crash 丢失最后一次设置，Review 修复）
- **GetPrivacyHandler** — user/getPrivacy，使用 GetPrivacyReq 空消息，userId 从 Session 获取
- **安全约束（T-05-10）** — SetPrivacyHandler 只允许修改当前登录用户的隐私设置（userId 通过 Session 获取，非请求参数）
- **Test Session 注入模式** — 建立 withContext(SessionKey(session)) + handler.handle(req) 测试模式

## Task Commits

Each task was committed atomically:

1. **Task 1: GetProfileHandler + BatchGetUserHandler + 单元测试** — `0af5d67` (feat)
2. **Task 2: BatchGetStatusHandler + SetPrivacyHandler + GetPrivacyHandler** — `fe87f21` (feat)
3. **Task 3: Handler 单元测试 — Status + Privacy** — `1619cc4` (test)

## Files Created/Modified

- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/GetProfileHandler.kt` — user/getProfile 用户详细资料查询
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/BatchGetUserHandler.kt` — user/batchGet 批量用户摘要（缺失 ID 文档化）
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/BatchGetStatusHandler.kt` — user/batchGetStatus 批量在线状态（MGET 隐私过滤）
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandler.kt` — user/setPrivacy 隐私设置（best-effort 文档化）
- `gateway/src/main/kotlin/com/nebula/gateway/handler/user/GetPrivacyHandler.kt` — user/getPrivacy 隐私读取
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/GetProfileHandlerTest.kt` — GetProfileHandler 单元测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/BatchGetUserHandlerTest.kt` — BatchGetUserHandler 单元测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/BatchGetStatusHandlerTest.kt` — BatchGetStatusHandler 单元测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandlerTest.kt` — SetPrivacyHandler 单元测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/user/GetPrivacyHandlerTest.kt` — GetPrivacyHandler 单元测试
- `gateway/build.gradle.kts` — 添加 spring-data-jpa 依赖以编译解析 UserRepository 超类型

## Decisions Made

- **GetProfileResp gender/bio 暂不填充**（v1）：UserEntity 尚无 gender 和 bio 字段，v1 中保持默认值
- **BatchGetUserHandler 缺失 ID 行为**：JPA `findAllById()` 对不存在的 ID 静默跳过，客户端通过结果判断（Review 指出的设计权衡已文档化）
- **Session 测试注入模式**：建立 `withContext(SessionKey(session))` + `handler.handle(req)` 模式用于需要 Session 的 Handler 测试
- **JPA 类型处理**：`findById` 返回 `Optional<UserEntity>`，使用 `.orElse(null)` 结合 Elvis 操作符转为可空类型

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] 添加 spring-data-jpa 依赖至 gateway 模块**
- **Found during:** Task 1 (GetProfileHandler/BatchGetUserHandler)
- **Issue:** gateway 模块编译时无法解析 UserRepository 的 JpaRepository 超类型（repository 模块以 implementation 而非 api 导出 spring-data-jpa）
- **Fix:** 在 gateway/build.gradle.kts 添加 `implementation(libs.spring.data.jpa)` 依赖
- **Files modified:** gateway/build.gradle.kts
- **Verification:** `./gradlew :gateway:compileKotlin BUILD SUCCESSFUL`
- **Committed in:** `0af5d67` (Task 1 commit)

**2. [Rule 3 - Blocking] 预存的 SearchUserHandler toEpochMilli 编译错误修复**
- **Found during:** 全局编译验证
- **Issue:** 前一波段创建的 SearchUserHandler.kt 对 `LocalDateTime` 调用不存在的 `toEpochMilli()` 方法，阻塞整个项目编译
- **Fix:** 替换为 `atZone(ZoneOffset.UTC).toInstant().toEpochMilli()`（与 GetProfileHandler 相同的修复模式）
- **Files modified:** gateway/.../handler/user/SearchUserHandler.kt
- **Verification:** `./gradlew :gateway:compileKotlin BUILD SUCCESSFUL`
- **Committed in:** 未单独提交（将在 final metadata commit 中捕获）

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** 两个修复均为编译阻塞问题。spring-data-jpa 依赖修复是本计划 handler 直接需要的；SearchUserHandler 修复是传递性的非本任务问题。

## Issues Encountered

- **findById 返回 Optional<UserEntity>**：JPA `findById()` 返回 `Optional<T>` 而非 `T?`。MockK 测试中需使用 `java.util.Optional.of(entity)` 匹配返回类型，Handler 中使用 `.orElse(null)` + elvis 处理 null
- **coroutineContext 需显式 import**：在 Kotlin suspend handler 实现中，`coroutineContext` 需要显式 import `kotlin.coroutines.coroutineContext`（在 SetPrivacyHandler 和 GetPrivacyHandler 中添加）

## Threat Surface Scan

- **T-05-09（Information Disclosure）** — BatchGetStatusHandler 使用 batchGetHideOnlineStatus() MGET 批量过滤隐藏用户 ✓
- **T-05-10（Tampering）** — SetPrivacyHandler 从 Session 获取 userId，非请求参数 ✓
- **T-05-11（Information Disclosure, accepted）** — GetProfileHandler 当前所有用户资料公开 ✓

No additional threat surface introduced beyond the plan's `<threat_model>`.

## Self-Check: PASSED

- `./gradlew :gateway:compileKotlin` → BUILD SUCCESSFUL
- `./gradlew :gateway:test` → BUILD SUCCESSFUL（13 个测试全部通过）
- `GetProfileHandler.kt` 存在 ✓
- `BatchGetUserHandler.kt` 存在 ✓
- `BatchGetStatusHandler.kt` 存在 ✓
- `batchGetHideOnlineStatus` 在 BatchGetStatusHandler 中使用 ✓
- `SetPrivacyHandler.kt` 存在，best-effort 文档化 ✓
- `GetPrivacyHandler.kt` 存在，使用 GetPrivacyReq 空消息 ✓
- `BatchGetUserHandler` KDoc 包含"缺失 ID 静默跳过" ✓
- `SetPrivacyHandler` KDoc 包含"best-effort" ✓

## Next Phase Readiness

- 用户业务 API Handler 全部完成（profile/batchGet/batchGetStatus/setPrivacy/getPrivacy）
- 剩余端点准备就绪：user/login、user/register（基础设施已在 Plan 01 准备）
- BatchGetStatusHandler MGET 隐私过滤为 Phase 8（好友在线状态）提供批量在线状态查询能力

---
*Phase: 05-user-authentication*
*Completed: 2026-06-12*
