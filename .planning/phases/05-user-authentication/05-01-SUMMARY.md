---
phase: 05-user-authentication
plan: 01
subsystem: auth
tags: [protobuf, proto, redis, session, auth, device-kick, rate-limit, privacy, bcrypt]

requires:
  - phase: 04-handler-framework
    provides: SessionRegistry L1/L2 缓存、AuthInterceptor 骨架、Interceptor pipeline、Session 数据模型
provides:
  - Proto: Request.metadata 字段（Token/IP 传递）、注册/搜索游标分页/LoginResp device 字段/GetPrivacyReq
  - SessionRepository: saveRaw/findRaw/deleteKey 通用 Redis 操作
  - SessionRegistry: 设备类型索引 + registerWithDeviceType() 同类型设备互踢
  - Device type mapping: Redis 持久化（session:{userId}:{deviceType} → token），重启后恢复
  - AuthInterceptor: 从 Request.metadata["authorization"] 提取 Token
  - RegisterRateLimiter: 每小时每 IP 5 次注册限流，内存泄漏已修复
  - PrivacyRepository: Redis 隐私 key 操作 + MySQL 异步刷写 + batchGetHideOnlineStatus（MGET 批量查询）
  - spring-security-crypto: BCrypt 密码哈希依赖就绪
affects: [05-user-authentication, 08-friend-online-status]

tech-stack:
  added: [spring-security-crypto 6.4.5]
  patterns: [Protobuf metadata 传递 Token、设备类型 Redis 持久化、best-effort 异步 MySQL 刷写]

key-files:
  created:
    - repository/.../redis/PrivacyRepository.kt
  modified:
    - proto/.../envelope.proto
    - proto/.../user/user.proto
    - repository/.../redis/SessionRepository.kt
    - gateway/.../session/SessionRegistry.kt
    - gateway/.../interceptor/AuthInterceptor.kt
    - gateway/.../interceptor/RateLimitInterceptor.kt
    - gradle/libs.versions.toml
    - gateway/build.gradle.kts
    - repository/build.gradle.kts

key-decisions:
  - "Token 通过 Request.metadata['authorization'] 传递（D-04 推荐方式），由 AuthInterceptor.extractToken() 提取"
  - "设备类型映射 Redis key 格式 session:{userId}:{deviceType} → token，确保重启后设备互踢仍正常工作"
  - "PrivacyRepository 异步 MySQL 刷写采用 best-effort 模式，crash 时最后一次隐私设置可能丢失，重启后从 MySQL 恢复"
  - "RegisterRateLimiter 使用 ConcurrentHashMap + synchronized 窗口实现，移除空 IP 条目防止内存泄漏"

patterns-established:
  - "Protobuf Request.metadata map 传递认证和元数据（Token、客户端 IP）"
  - "SessionRegistry.registerWithDeviceType() 统一管理同类型设备互踢"
  - "deviceTypeIndex 内存索引 + Redis 持久化双保险，重启后可恢复设备类型映射"
  - "PrivacyRepository 使用 MGET 批量查询避免 N+1 问题"

requirements-completed: [AUTH-03, AUTH-04, AUTH-05, BIZ-USER-04, BIZ-USER-05, BIZ-USER-06]

duration: 18min
completed: 2026-06-12
---

# Phase 05 Plan 01: 认证基础设施 — Proto 契约扩展 + SessionRegistry 设备互踢 + Token 提取 + 隐私存储

**Proto 扩展 Request.metadata 传递 Token、设备类型索引与 Redis 持久化实现同类型设备互踢、AuthInterceptor 从 metadata 提取 Token、RegisterRateLimiter IP 限流（含内存泄漏修复）、PrivacyRepository 隐私读写与批量查询**

## Performance

- **Duration:** 18 min
- **Started:** 2026-06-12T08:40:00Z (approx)
- **Completed:** 2026-06-12T08:58:00Z (approx)
- **Tasks:** 3
- **Files modified:** 7 (created 1, modified 6)

## Accomplishments

- **Proto 契约扩展完成** — Request 增加 metadata map 字段传递 Token/IP；user.proto 新增 RegisterReq/Resp、GetPrivacyReq、游标分页字段、LoginResp device 字段
- **设备类型互踢基础设施** — SessionRegistry 新增 registerWithDeviceType() 方法和 deviceTypeIndex；设备类型映射持久化到 Redis，重启后可恢复
- **Token 提取机制就绪** — AuthInterceptor.extractToken() 从 Request.metadata["authorization"] 提取 Token，替代原 null 存根
- **注册 IP 限流** — RateLimitInterceptor 新增 RegisterRateLimiter（每 IP 每小时 5 次），含空 IP 条目清理防止内存泄漏
- **隐私存储层** — PrivacyRepository 支持 get/set hideOnlineStatus，使用 MGET 批量查询避免 N+1；Redis 立即生效 + MySQL 异步刷写（best-effort）
- **spring-security-crypto 依赖就绪** — 版本 6.4.5，后续 Handler 可直接使用 BCryptPasswordEncoder

## Task Commits

Each task was committed atomically:

1. **Task 1: 扩展 Proto 定义** — `96cae02` (feat)
2. **Task 2: SessionRepository 增强 + SessionRegistry 设备类型索引 + registerWithDeviceType()** — `a2ce072` (feat)
3. **Task 3: AuthInterceptor Token 提取 + RegisterRateLimiter + PrivacyRepository + 依赖** — `387fcaa` (feat)

## Files Created/Modified

- `proto/src/main/proto/nebula/envelope.proto` — Request 增加 `map<string, string> metadata = 3` 字段
- `proto/src/main/proto/nebula/user/user.proto` — 新增 RegisterReq/RegisterResp、SearchUserReq cursor/limit、SearchUserResp next_cursor/has_more、LoginResp device_type/device_id、LoginReq device_id、UserBrief created_at、GetPrivacyReq
- `repository/src/main/kotlin/com/nebula/repository/redis/SessionRepository.kt` — 新增 saveRaw/findRaw/deleteKey 通用方法
- `gateway/src/main/kotlin/com/nebula/gateway/session/SessionRegistry.kt` — 新增 deviceTypeIndex、registerWithDeviceType()、saveDeviceTypeMapping/deleteDeviceTypeMapping/findDeviceTokenFromRedis 辅助方法；增强 removeFromLocalCache()/unregister()
- `gateway/src/main/kotlin/com/nebula/gateway/interceptor/AuthInterceptor.kt` — extractToken() 实现：从 Request.metadata["authorization"] 提取
- `gateway/src/main/kotlin/com/nebula/gateway/interceptor/RateLimitInterceptor.kt` — 新增 RegisterRateLimiter 内部类、注册请求 IP 限流、extractClientIp() 实现
- `repository/src/main/kotlin/com/nebula/repository/redis/PrivacyRepository.kt` — **新建**，含 getHideOnlineStatus/setHideOnlineStatus/batchGetHideOnlineStatus
- `gradle/libs.versions.toml` — 新增 spring-security-crypto 6.4.5 版本和库声明
- `gateway/build.gradle.kts` — 新增 spring-security-crypto 依赖
- `repository/build.gradle.kts` — 新增 kotlinx-serialization 插件和依赖

## Decisions Made

- **Token 传递方式**（D-04）：Token 通过 Request.metadata["authorization"] 传递，由 AuthInterceptor.extractToken() 提取 — 与原有 Protobuf 兼容，无需修改 proto 主消息结构
- **设备类型 Redis key 格式**：`session:{userId}:{deviceType}` → token，TTL 7 天。重启后 ChatService 可通过 findDeviceTokenFromRedis() 恢复设备类型映射
- **PrivacyRepository 异步策略**：setHideOnlineStatus() 先写 Redis（实时生效），异步刷 MySQL。接受服务器 crash 导致最后一次隐私设置丢失的风险（重启后从 MySQL 恢复）
- **RegisterRateLimiter 实现**：使用 ConcurrentHashMap + synchronized 滑动窗口，并内置空 IP 条目自动清理机制防止内存泄漏（Review 反馈修复）

## Deviations from Plan

None - plan executed exactly as written.

### Deviation Note: `extractToken` signature

The plan's action section showed `override fun extractToken(request: Request)` but `AuthInterceptor` defines `extractToken` as its own `open fun` method (not overriding anything from a parent interface). Kept as `open fun` to maintain compilation compatibility.

## Issues Encountered

- **Lettuce `mget` coroutines API type resolution**: The `redis.mget()` in Lettuce coroutines API had Kotlin type inference issues with `vararg` overloads (two overloads: one returning `Flux<KeyValue>`, another returning `Mono<Long>`). Fixed by using the `redis.mget().awaitFirst()` pattern with explicit `as List<KeyValue<String, String>>` cast.
- **repository 模块 kotlinx-serialization 缺失**: PrivacyRepository 在 repository 模块中需要 kotlinx.serialization，该模块原未声明相关插件和依赖。已添加 `kotlin.plugin.serialization` 插件和 `kotlinx-serialization-json` 依赖。

## Threat Surface Scan

No additional threat surface introduced beyond the plan's `<threat_model>` — all new surfaces (Request.metadata authorization, PrivacyRepository Redis writes, spring-security-crypto install) are documented in T-05-01 through T-05-SC.

## Self-Check: PASSED

- `./gradlew :proto:generateProto` → BUILD SUCCESSFUL
- `./gradlew compileKotlin` → BUILD SUCCESSFUL
- `grep "metadata" envelope.proto` → `map<string, string> metadata = 3` found
- `grep "RegisterReq" user.proto` → defined, no device_type field
- `grep "device_type" user.proto` → LoginResp contains device_type=7
- `grep "registerWithDeviceType" SessionRegistry.kt` → method exists
- `grep "extractToken" AuthInterceptor.kt` → returns metadata["authorization"]
- `grep "batchGetHideOnlineStatus" PrivacyRepository.kt` → method exists
- `grep "saveRaw\|findRaw\|deleteKey" SessionRepository.kt` → all three methods exist

## Next Phase Readiness

- Proto 定义基础完成，后续 Handler（login/register/search/setPrivacy/getPrivacy）可直接使用
- SessionRegistry 设备类型互踢就绪，LoginHandler 可调用 registerWithDeviceType()
- AuthInterceptor 可正常提取 Token，login/register 走 skipMethods
- RegisterRateLimiter 就绪，register Handler 无需自行实现 IP 限流
- PrivacyRepository 就绪，setPrivacy/getPrivacy Handler 可直接调用
- spring-security-crypto 可注入 BCryptPasswordEncoder 用于密码哈希

---
*Phase: 05-user-authentication*
*Completed: 2026-06-12*
