---
slug: fix-repo-gateway-test-imports
description: 修复 gateway 模块测试文件的导入错误和构造函数参数不匹配
created: 2026-06-16
expert: debugger
mode: quick
team: true
tasks: 3
---

# Quick Plan: fix-repo-gateway-test-imports

## 任务描述

重构后 gateway 模块的 Handler 构造函数参数发生了变化（repository 层参数被移除，改为 service 层注入），但对应的 test 文件未同步更新，导致编译错误。需要修复所有 gateway 测试文件中的导入错误和构造函数参数不匹配。

## 影响范围

仅 gateway 模块 `src/test/` 目录，repository 模块测试无需修改。

## 任务表

| # | 类型 | 涉及文件 | 操作 | 验证 |
|---|------|----------|------|------|
| 1 | fix | SendMessageHandlerTest, ReadReportHandlerTest, FriendAddHandlerTest, FriendAcceptHandlerTest, LeaveGroupHandlerTest, InviteMemberHandlerTest, KickMemberHandlerTest | 移除多余的构造参数（Repository 已从 Handler 移除），更新 mock 类型 | 编译通过 |
| 2 | fix | BatchGetStatusHandlerTest, SetPrivacyHandlerTest | 将 Repository 层依赖改为 Service 层类型，更新导入和 mock 声明 | 编译通过 |
| 3 | fix | GatewayModuleTest, HandlerRegistryTestBase | 更新 DI 注册中的构造函数参数和 bind 声明 | 编译通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
- 涉及 commit: `6dbdd21` feat(phase-11): plan 11-04 stage C -- DRY refactoring
