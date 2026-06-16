---
slug: fix-server-gateway-test-compile
description: 修复 server 和 Gateway 模块的测试编译错误（缺失依赖 + 引用更新）
created: 2026-06-16
expert: debugger
mode: quick
team: true
tasks: 2
---

# Quick Plan: fix-server-gateway-test-compile

## 任务描述

重构后（DRY refactoring，commit `6dbdd21`），server 和 gateway 模块的测试出现编译错误：

- **Gateway**: `TestHelper.kt` 使用了 `repository.entity.*` 类（`ConversationMemberEntity`、`FriendRequestEntity`、`FriendshipEntity`），但 gateway 模块的 test scope 缺少 `:repository` 依赖（service 模块使用 `implementation` 而非 `api` 导出 repository，不传递）
- **Server**: `KoinVerificationTest.kt` 引用已重命名的 `serviceModule`（实际已变为 `serviceKoinModule` 并移至 `com.nebula.service.init` 包）

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | fix | gateway/build.gradle.kts | 添加 `testImplementation(project(":repository"))` | `./gradlew :gateway:compileTestKotlin` 通过 |
| 2 | fix | server/src/test/kotlin/.../KoinVerificationTest.kt | 用 `gatewayModules` 替换已失效的 `serviceModule` 引用 | `./gradlew :server:compileTestKotlin` 通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
- 涉及 commit: `6dbdd21` feat(phase-11): plan 11-04 stage C -- DRY refactoring
- 前序 quick task: `.planning/quick/20260616-fix-repo-gateway-test-imports/` (仅修复了 Handler 测试文件的构造参数)
