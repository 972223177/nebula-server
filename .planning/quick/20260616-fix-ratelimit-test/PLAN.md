---
slug: fix-ratelimit-test
description: 修复 RateLimitInterceptorTest 断言错误 — 将硬编码 429 改为 BizCode.RATE_LIMITED.code（1004）
created: 2026-06-16
expert: debugger
mode: quick
tasks: 1
---

# Quick Plan: fix-ratelimit-test

## 任务描述

修复 `RateLimitInterceptorTest` 中两个测试方法断言了硬编码的 429 状态码，但 `BizCode.RATE_LIMITED.code` 实际为 1004，导致测试失败。

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | gateway/src/test/kotlin/com/nebula/gateway/interceptor/RateLimitInterceptorTest.kt | 将 `assertEquals(429, ...)` 改为 `assertEquals(BizCode.RATE_LIMITED.code, ...)` | 测试通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
