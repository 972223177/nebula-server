---
slug: fix-conversation-smoke-test
description: 修复 ConversationSmokeTest 中缺失的 MockK answer：getMemberRole、dissolveGroup、refreshTtl
created: 2026-06-16
expert: debugger
mode: quick
tasks: 2
---

# Quick Plan: fix-conversation-smoke-test

## 问题描述

`ConversationSmokeTest.fullFlowShouldCreateGroupThroughAllOperations` 在步骤6（群主退群）返回 9000，原因为：
1. `LeaveGroupHandler` 调用 `conversationService.getMemberRole()` 判断角色，未 mock → MockKException
2. 群主退群路径调用 `conversationService.dissolveGroup()`，未 mock → MockKException
3. `AuthInterceptor` 调用 `sessionRegistry.refreshTtl()`，未 mock → WARN 日志（不影响流程但脏日志）

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | gateway/src/test/.../ConversationSmokeTest.kt | 添加 `getMemberRole` 和 `dissolveGroup` 的 coEvery mock | 测试通过 |
| 2 | modify | gateway/src/test/.../TestHelper.kt | 在 `buildTestDispatcher` 中添加 `refreshTtl` 的 coEvery mock | 无测试失败 |

## 上下文引用

- 项目: .planning/PROJECT.md
