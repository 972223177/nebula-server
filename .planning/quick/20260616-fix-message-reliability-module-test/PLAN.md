---
slug: fix-message-reliability-module-test
description: 修复 MessageReliabilityModuleTest Koin 装配测试中 DeadLetterService 和 SeqService 未注册的问题
created: 2026-06-16
expert: debugger
mode: quick
tasks: 1
---

# Quick Plan: fix-message-reliability-module-test

## 问题描述

`MessageReliabilityModuleTest` 中两个测试方法失败：
1. `messageReliabilityModuleShouldResolveAllComponents` — 尝试 `get<SeqService>()` 失败
2. `messageReliabilityModuleShouldConstructAdminHandlerCollector` — `DeadLetterQueryHandler` 构造时依赖 `DeadLetterService`，但未注册

根因：`DeadLetterService` 和 `SeqService` 注册在 `serviceKoinModule`（service 模块），但测试仅加载了 `messageReliabilityModule`（gateway 模块），未提供这两者的 mock 实例。

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | gateway/src/test/.../MessageReliabilityModuleTest.kt | 在 `buildExternalModule()` 中添加 `DeadLetterService` 和 `SeqService` 的 mockk 实例注册 | 测试通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
