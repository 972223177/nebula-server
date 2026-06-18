---
phase: quick
plan: 260618-w9m-hibernate-dialect-hhh90000025-6
subsystem: repository
tags: [hibernate, dialect, cleanup, warning]
requires: []
provides: []
affects:
  - repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/ConversationRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/DeadLetterRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/MessageRepositoryIntegrationTest.kt
tech-stack:
  added: []
  patterns: [remove-deprecated-hibernate-dialect]
key-files:
  created: []
  modified:
    - repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt
    - repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt
    - repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt
    - repository/src/test/kotlin/com/nebula/repository/repository/ConversationRepositoryIntegrationTest.kt
    - repository/src/test/kotlin/com/nebula/repository/repository/DeadLetterRepositoryIntegrationTest.kt
    - repository/src/test/kotlin/com/nebula/repository/repository/MessageRepositoryIntegrationTest.kt
decisions:
  - 移除所有显式 hibernate.dialect 设置，依赖 Hibernate 6 的自动方言检测
metrics:
  duration: "~0m"
  completed-date: "2026-06-18"
---

# Quick Task 260618-w9m: 移除显式 Hibernate Dialect 设置 (HHH90000025)

从 repository 模块的 6 个文件中移除所有显式 `hibernate.dialect` 设置，消除 Hibernate 6.x 的 HHH90000025 废弃警告。

## Summary

Hibernate 6.x 自动根据 JDBC 驱动检测方言（`MySQLDialect`），显式 `setDatabasePlatform(...)` 和 `config.setProperty("hibernate.dialect", ...)` 触发 HHH90000025 废弃警告。本次移除 6 处显式设置：

| # | 文件 | 移除行 |
|---|------|--------|
| 1 | `JpaConfig.kt` | `setDatabasePlatform("org.hibernate.dialect.MySQLDialect")` |
| 2 | `FriendshipRepositoryIntegrationTest.kt` | `config.setProperty("hibernate.dialect", ...)` |
| 3 | `UserRepositoryIntegrationTest.kt` | `config.setProperty("hibernate.dialect", ...)` |
| 4 | `ConversationRepositoryIntegrationTest.kt` | `config.setProperty("hibernate.dialect", ...)` |
| 5 | `DeadLetterRepositoryIntegrationTest.kt` | `config.setProperty("hibernate.dialect", ...)` |
| 6 | `MessageRepositoryIntegrationTest.kt` | `config.setProperty("hibernate.dialect", ...)` |

编译验证通过（`compileKotlin + compileTestKotlin`），无 HHH90000025 警告。

## Tasks

### Task 1: 移除 6 处显式 hibernate.dialect 设置

- **Status**: Complete
- **Commit**: `ee677f6`

Verified:
- `grep -rn "hibernate.dialect" repository/` -- 返回 0 行
- `./gradlew :repository:compileKotlin :repository:compileTestKotlin` -- BUILD SUCCESSFUL

## Verification

| Check | Status |
|-------|--------|
| `grep -rn "hibernate.dialect" repository/` = 0 matches | PASSED (0 matches) |
| `./gradlew :repository:compileKotlin :repository:compileTestKotlin` | PASSED (BUILD SUCCESSFUL) |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

- 6 files modified, exactly 1 line removed each (verified via `git diff --stat`)
- Commit `ee677f6` exists with correct message format
- No unintended deletions detected
- No untracked files from build
