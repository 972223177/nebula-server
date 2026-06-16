---
slug: fix-conversation-smoke-test
description: 修复 ConversationSmokeTest 中缺失的 MockK answer
created: 2026-06-16
status: completed
tasks_total: 2
tasks_completed: 2
completed_at: 2026-06-16T23:29+08:00
expert: debugger
---

# Quick Summary: fix-conversation-smoke-test

## 执行结果

所有任务已完成，测试通过。

## 变更文件

- `gateway/src/test/kotlin/com/nebula/gateway/dispatcher/ConversationSmokeTest.kt`：
  - 新增 `getMemberRole` mock（返回 `ConversationMemberInfo(userId=1001, role="owner")`）
  - 新增 `dissolveGroup` mock（返回 `Unit`）
  - 新增 `ConversationMemberInfo` import

- `gateway/src/test/kotlin/com/nebula/gateway/testutil/TestHelper.kt`：
  - `buildTestDispatcher` 新增 `refreshTtl` mock，消除 AuthInterceptor WARN 日志

## 验证

- `ConversationSmokeTest.fullFlowShouldCreateGroupThroughAllOperations` — 通过

## 根因分析

`LeaveGroupHandler.handle()` 在群主退群路径中依次调用：
1. `getMemberRole(convId, userId)` → 判断是否为群主，缺少 mock 导致 MockKException → 9000
2. `dissolveGroup(convId)` → 解散群，同样缺少 mock 导致 MockKException → 9000

`PipelineIntegrationTest`、`FriendSmokeTest`、`PrivacySmokeTest` 的失败是预先存在的，与本次修改无关。
