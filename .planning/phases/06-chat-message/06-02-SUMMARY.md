---
phase: 06-chat-message
plan: 02
subsystem: gateway
tags:
  - step-chain
  - message-sending
  - dedup
  - validate
  - async-push
  - unread-count
requires:
  - "06-01"
provides:
  - "chat/send Step 链编排"
  - "SendMessageHandler Handler"
  - "ChatService UserStreamRegistry 集成"
affects:
  - "gateway"
  - "server"
tech-stack:
  added:
    - dependency: "io.lettuce:lettuce-core (implementation)"
      reason: "DedupStep/WriteStep 需要 gateway 模块直接使用 Redis"
    - dependency: "org.jetbrains.kotlinx:kotlinx-coroutines-reactive (implementation)"
      reason: "RedisCoroutinesCommandsImpl 依赖 reactive 适配"
key-files:
  created:
    - "gateway/.../handler/chat/send/SendMessageStep.kt"
    - "gateway/.../handler/chat/send/SendContext.kt"
    - "gateway/.../handler/chat/send/ValidateStep.kt"
    - "gateway/.../handler/chat/send/DedupStep.kt"
    - "gateway/.../handler/chat/send/WriteStep.kt"
    - "gateway/.../handler/chat/send/SendMessageHandler.kt"
    - "gateway/.../handler/chat/send/ValidateStepTest.kt"
    - "gateway/.../handler/chat/send/DedupStepTest.kt"
    - "gateway/.../handler/chat/send/WriteStepTest.kt"
    - "gateway/.../handler/chat/send/SendMessageHandlerTest.kt"
  modified:
    - "gateway/.../service/ChatService.kt"
    - "server/.../NebulaServer.kt"
    - "gateway/build.gradle.kts"
decisions:
  - "Push 不作为同步 Step — 在 SendMessageHandler 中 fire-and-forget 异步执行（D-04 per REVIEW）"
  - "未读计数 INCR 从 WriteStep 移出至异步后处理阶段（REVIEW-MEDIUM-5）"
  - "去重键初始值 'pending'，WriteStep 更新为实际 msg_id（REVIEW-MEDIUM-6）"
  - "catch-all 异常处理：非预期异常包装为 SendMessageException(INTERNAL_ERROR)（REVIEW-HIGH-2）"
  - "ChatStreamObserver.userId 通过 require() 显式类型检查设置（REVIEW-MEDIUM-7）"
metrics:
  duration: "~5 分钟（编译 + 测试）"
  completed_date: "2026-06-12"
---

# Phase 6 Plan 2: SendMessage Step 链编排 — Summary

JWT 认证与消息发送的 Step 链模式实现：Validate → Dedup → Write 三个同步 Step 由 SendMessageHandler 编排，WriteStep 完成后立即返回响应，推送和未读计数在 fire-and-forget 协程中异步执行。ChatService 集成 UserStreamRegistry 实现连接生命周期管理。

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Step 链接口 + SendContext + 3 个同步 Step | `efc4bcf` | SendMessageStep.kt, SendContext.kt, ValidateStep.kt, DedupStep.kt, WriteStep.kt |
| 2 | SendMessageHandler（Step 链编排器） | `4fadf9c` | SendMessageHandler.kt |
| 3 | ChatService 集成 UserStreamRegistry | `2d05c80` | ChatService.kt, NebulaServer.kt |
| 4 | 单元测试（TDD） | `bf78f7a` | ValidateStepTest, DedupStepTest, WriteStepTest, SendMessageHandlerTest |

## Architecture

```
chat/send 请求
    │
    ├── SendMessageHandler.handle()
    │   ├── SendContext(req, senderUid)
    │   ├── [try-catch 包裹]
    │   │   ├── ValidateStep   → content 非空 / clientMessageId 非空 / 成员检查
    │   │   ├── DedupStep       → Redis SETNX 去重 + 7 天 TTL
    │   │   └── WriteStep       → Snowflake ID → ChatMessage 构建 → Redis Stream → 会话元更新 → 去重键更新
    │   ├── SendMessageResp (立即返回)
    │   └── [fire-and-forget 协程]
    │       ├── 未读计数 INCR (逐成员)
    │       └── PushService.pushMessage()
    │
    └── ChatService 生命周期
        ├── handleLoginSuccess → userStreamRegistry.register()
        └── cleanupConnection  → userStreamRegistry.removeStream()
```

## Deviations from Plan

### Rule 3 - 自动修复：gateway 模块缺少 lettuce 依赖

- **问题：** gateway/build.gradle.kts 未声明 `io.lettuce:lettuce-core` 和 `kotlinx-coroutines-reactive` 依赖，DedupStep 和 WriteStep 无法编译
- **修复：** 在 build.gradle.kts 中添加 implementation 依赖
- **文件修改：** gateway/build.gradle.kts

### Rule 1 - 自动修复：DedupStep redis.setnx 返回值可空

- **问题：** `redis.setnx()` 返回 `Boolean?`，直接用于 `!isNew` 条件编译失败
- **修复：** 添加 `?: false` 处理可空情况
- **文件修改：** DedupStep.kt

### Rule 1 - 自动修复：SendMessageException 不支持 cause 参数

- **问题：** 原 SendMessageException 无 cause 参数，catch-all 块尝试传入 e 失败
- **修复：** 去掉 cause 参数，异常消息中包含原始异常信息

## Test Results

所有 14 个测试用例通过：

- **ValidateStepTest** (4/4): 内容为空、clientMessageId 为空、非成员、合法请求
- **DedupStepTest** (2/2): 首次 SETNX 成功、重复 SETNX 失败
- **WriteStepTest** (5/5): msgId 设置、ChatMessage 构建、enqueue 调用、会话元更新、去重键更新
- **SendMessageHandlerTest** (3/3): 全部成功、异常传播、非预期异常包装

## Threat Surface

无新增威胁面 — 所有新文件均在 plan 的 `<threat_model>` 覆盖范围内：

| Threat ID | Disposition | Component |
|-----------|-------------|-----------|
| T-06-04 | mitigate | ValidateStep（内容非空、clientMessageId 非空、成员检查） |
| T-06-05 | mitigate | DedupStep（Redis SETNX 原子操作 + 7 天 TTL） |
| T-06-06 | mitigate | WriteStep（仅写入已验证成员的消息） |
| T-06-07 | mitigate | PushService async（try-catch 容错，异常不阻断主流程） |
| T-06-14 | mitigate | SendMessageHandler（catch-all 异常包装） |

## Self-Check: PASSED

所有创建和修改文件已通过编译检查 (`./gradlew :gateway:compileKotlin :server:compileKotlin`) 和单元测试 (`./gradlew :gateway:test --tests "com.nebula.gateway.handler.chat.send.*"`)。

## Known Stubs

无 — 所有文件和逻辑均完整实现。
