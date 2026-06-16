---
slug: fix-leave-group-handler-test
description: 修复 LeaveGroupHandlerTest ownerLeaveShouldDissolveAndPushGroupDissolved 缺少 dissolveGroup mock 的问题
created: 2026-06-16
expert: debugger
mode: quick
tasks: 1
---

# Quick Plan: fix-leave-group-handler-test

## 任务描述

`LeaveGroupHandlerTest.ownerLeaveShouldDissolveAndPushGroupDissolved` 测试失败，原因是 Handler 在群主退群时调用 `conversationService.dissolveGroup(convId)`，但测试仅 mock 了 `leaveGroup(any(), any())`，未配置 `dissolveGroup` 的 mock 返回值。

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandlerTest.kt | 添加 `dissolveGroup` mock 配置；可选补充 `dissolveGroup` 调用验证 | 编译通过 + 单元测试通过 |

## 上下文引用

- 错误日志: 用户提供的 test run 日志（MockKException: no answer found for dissolveGroup）
- Handler 源码: gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandler.kt
