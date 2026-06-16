---
slug: fix-gateway-server-tests
description: 修复 server 和 gateway 模块测试代码编译失败，适配 Phase 12 模块依赖隔离（api→implementation）后的接口变更
created: 2026-06-16
expert: java-developer
mode: quick
tasks: 8
team: true
---

# Quick Plan: 修复 server/gateway 测试代码编译

## 任务描述

Phase 12 将模块间依赖从 `api` 改为 `implementation`，导致 gateway 测试代码无法访问 repository 实体/Repository 类型。server 模块测试也有一个 import 变更。需修复全部 8 个文件使 `./gradlew compileTestKotlin` 通过。

## 上下文引用

- Phase 12 计划: .planning/phases/12-module-dependency-isolation/12-01-PLAN.md
- Phase 12 摘要: .planning/phases/12-module-dependency-isolation/12-01-SUMMARY.md
- 项目代码注释规范: CODEBUDDY.md

## 根本原因

gateway:repository 依赖从 `api` → `implementation`，gateway 测试代码无法再 import 任何 `com.nebula.repository.*` 类型。

## 关键类型映射

| 旧 (Repository) | 新 (Service) |
|---|---|
| `OnlineStatusRepository` | `OnlineStatusService` |
| `FriendshipRepository` | `FriendService` |
| `PrivacyRepository` | `UserPrivacyService` |
| `ConversationMemberRepository` | `ConversationService` |
| `SessionRepository` | `SessionStore` (common 模块) |
| `DeadLetterEntity` | `DeadLetterDTO` |
| `ConversationEntity` | `ConversationInfo` |
| `ConversationMemberEntity` | `ConversationMemberInfo` |
| `FriendshipEntity` | `FriendshipInfo` |
| `com.nebula.gateway.di.serviceModule` | `com.nebula.service.init.serviceKoinModule` |

## 任务表

| # | 类型 | 文件 | 问题 | 修复策略 |
|---|------|------|------|----------|
| 1 | modify | server/.../KoinVerificationTest.kt | `serviceModule` 未解析 (2 errors) | 导入改为 `com.nebula.service.init.serviceKoinModule` |
| 2 | modify | gateway/.../TestHelper.kt | 实体工厂函数引用不可访问的 Entity 类型 (~30 errors) | 替换为 DTO 类型工厂函数，移除不可转换的工厂 |
| 3 | modify | gateway/.../DeadLetterQueryHandlerTest.kt | `DeadLetterEntity` 不可访问 (~16 errors) | 替换为 `DeadLetterDTO`，适配字段类型 |
| 4 | modify | gateway/.../SendMessageHandlerTest.kt | `ConversationEntity` 不可访问 (~7 errors) | 使用 `ConversationInfo` DTO 替代 |
| 5 | modify | gateway/.../PushServiceTest.kt | `ConversationMemberRepository`+Entity 不可访问 (~20 errors) | mock `ConversationService.getConversationMembers()` 替代 |
| 6 | modify | gateway/.../ChatServiceReconnectTest.kt | 构造函数参数名变更，Repository 类型不可访问 (~11 errors) | `ChatService()` 参数改为 service 类型 |
| 7 | modify | gateway/.../ChatServiceReconnectIntegrationTest.kt | 同样模式变更 (~24 errors) | 注入 mock 改为 service 类型 |
| 8 | modify | gateway/.../SessionRegistryTest.kt | `SessionRepository` 不可访问 (~10 errors) | mock `SessionStore` 替代，适配方法名 |

## 团队分工（--team 模式）

- **server-expert**: 负责任务 #1（Server 模块）
- **gateway-service-expert**: 负责任务 #2, #3, #4（TestHelper + Handler 测试 + SendMessage 测试）
- **gateway-infra-expert**: 负责任务 #5, #6, #7, #8（PushService + ChatService + Session 测试）

## 验证标准

- `./gradlew :server:compileTestKotlin` 通过
- `./gradlew :gateway:compileTestKotlin` 通过
- 测试语义不变（仅适配接口，不改测试覆盖的场景）
- 不新增 `api` 依赖，不破坏 Phase 12 依赖隔离
