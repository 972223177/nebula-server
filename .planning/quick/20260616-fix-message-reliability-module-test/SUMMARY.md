---
slug: fix-message-reliability-module-test
description: 修复 MessageReliabilityModuleTest Koin 装配测试中 DeadLetterService 和 SeqService 未注册的问题
created: 2026-06-16
status: completed
tasks_total: 1
tasks_completed: 1
completed_at: 2026-06-16T23:26+08:00
expert: debugger
---

# Quick Summary: fix-message-reliability-module-test

## 执行结果

所有任务已完成，测试通过。

## 变更文件

- `gateway/src/test/kotlin/com/nebula/gateway/di/MessageReliabilityModuleTest.kt`：
  - `buildExternalModule()` 新增 `deadLetterService` 和 `seqService` mock 注册
  - 新增两个 mock 字段声明

## 验证

- `./gradlew :gateway:test --tests "com.nebula.gateway.di.MessageReliabilityModuleTest"` — BUILD SUCCESSFUL

## 根因分析

`DeadLetterService` 和 `SeqService` 定义在 service 模块的 `serviceKoinModule` 中，但 gateway 模块的测试仅加载了 `messageReliabilityModule`，未包含 service 模块的 Koin 模块。由于测试目的是验证 gateway 层 DI 装配，采用 mock 实例而非引入 service 模块的完整 DI 配置，避免级联依赖问题。
