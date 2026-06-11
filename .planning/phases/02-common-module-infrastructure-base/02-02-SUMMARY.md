---
phase: 02-common-module-infrastructure-base
plan: 02
subsystem: infra
tags: [kotlin, config, exception, snowflake, idgen]
requires:
  - phase: 02-01
    provides: Gradle dependencies (typesafe-config, hikaricp, grpc-netty-shaded)
provides:
  - 5 config data classes (ApplicationConfig + 4 sub-sections)
  - BizException exception hierarchy (1 base + 5 domain + 2 system exceptions)
  - SnowflakeIdGenerator (64-bit unique ID generator)
affects: [02-03, 03, 04, 05, 06, 07, 08, 09, 10]
tech-stack:
  added: [kotlin-synchronized]
  patterns: [data class config hierarchy, domain-specific exception subclasses, Snowflake ID generation]
key-files:
  created:
    - common/src/main/kotlin/com/nebula/common/config/ApplicationConfig.kt
    - common/src/main/kotlin/com/nebula/common/config/ServerConfig.kt
    - common/src/main/kotlin/com/nebula/common/config/DatabaseConfig.kt
    - common/src/main/kotlin/com/nebula/common/config/SnowflakeConfig.kt
    - common/src/main/kotlin/com/nebula/common/config/SslConfig.kt
    - common/src/main/kotlin/com/nebula/common/exception/BizException.kt
    - common/src/main/kotlin/com/nebula/common/exception/UserException.kt
    - common/src/main/kotlin/com/nebula/common/exception/ChatException.kt
    - common/src/main/kotlin/com/nebula/common/exception/ConversationException.kt
    - common/src/main/kotlin/com/nebula/common/exception/FriendException.kt
    - common/src/main/kotlin/com/nebula/common/exception/MessageException.kt
    - common/src/main/kotlin/com/nebula/common/exception/ClockBackwardsException.kt
    - common/src/main/kotlin/com/nebula/common/exception/SequenceOverflowException.kt
    - common/src/main/kotlin/com/nebula/common/idgen/SnowflakeIdGenerator.kt
key-decisions:
  - "SslConfig contains only data class — buildSslContext extension function belongs to Plan 03 (needs gRPC imports)"
  - "ClockBackwardsException extends RuntimeException (system-level), not BizException"
  - "SequenceOverflowException extends RuntimeException (internal use), never thrown to callers"
  - "BizException.bizCode exposed as val for Phase 4 ExceptionInterceptor consumption"
requirements-completed: [INFRA-03]
duration: 8min
completed: 2026-06-11
---

# Phase 02-02: Common Module Core Infrastructure Summary

**Config data classes with ApplicationConfig aggregation, BizException domain hierarchy with 5 subclasses, Snowflake 64-bit ID generator with clock-backwards detection**

## Performance

- **Duration:** 8 min
- **Completed:** 2026-06-11
- **Tasks:** 3
- **Files created:** 14

## Accomplishments
- 5 config data classes: ApplicationConfig (aggregator) + ServerConfig, DatabaseConfig, SnowflakeConfig, SslConfig
- 8 exception classes: BizException (open base) + 5 domain subclasses (User/Chat/Conversation/Friend/Message) + 2 system exceptions (ClockBackwards/SequenceOverflow)
- SnowflakeIdGenerator with 41|10|12 bit allocation, @Synchronized, clock-backwards detection, waitNextMillis self-healing

## Task Commits

1. **Task 1: 创建配置数据类** - `7f4594f`
2. **Task 2: 创建 BizException 异常体系** - `e243e93`
3. **Task 3: 实现 SnowflakeIdGenerator** - `e5ae642`

## Files Created
- `common/.../config/ApplicationConfig.kt` — Unified config entry with 4 sub-sections
- `common/.../config/ServerConfig.kt` — Server port config
- `common/.../config/DatabaseConfig.kt` — Database connection pool config
- `common/.../config/SnowflakeConfig.kt` — Snowflake worker ID and epoch
- `common/.../config/SslConfig.kt` — SSL cert paths (data class only)
- `common/.../exception/BizException.kt` — Base exception with BizCode
- `common/.../exception/{User,Chat,Conversation,Friend,Message}Exception.kt` — Domain exceptions
- `common/.../exception/ClockBackwardsException.kt` — Clock rollback detection
- `common/.../exception/SequenceOverflowException.kt` — Internal overflow marker
- `common/.../idgen/SnowflakeIdGenerator.kt` — Thread-safe 64-bit ID generator

## Decisions Made
- SslConfig is data class only — buildSslContext() extension deferred to Plan 03 (needs GrpcSslContexts)
- ClockBackwardsException and SequenceOverflowException extend RuntimeException directly (not BizException), per D-13/D-14
- Domain exceptions use direct throw syntax (throw UserException(BizCode.USER_NOT_FOUND)), per D-07

## Deviations from Plan
None — plan executed exactly as written.

## Issues Encountered
None

## Next Phase Readiness
- Config data classes ready for Plan 02-03 ConfigLoader parsing
- Exception hierarchy ready for Phase 4 ExceptionInterceptor
- SnowflakeIdGenerator ready for Phase 5+ user/message ID generation

---
*Phase: 02-common-module-infrastructure-base*
*Completed: 2026-06-11*
