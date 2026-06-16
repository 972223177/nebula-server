---
slug: fix-ratelimit-test
description: 修复 RateLimitInterceptorTest 断言错误
created: 2026-06-16
completed: 2026-06-16
expert: debugger
mode: quick
status: complete
tasks: 1
completed_tasks:
  - 1
---

# Summary: fix-ratelimit-test

## 问题

`RateLimitInterceptorTest.shouldReturn429OnSixthRegisterRequest` 和 `shouldCountRegisterLimitsIndependentlyPerIp` 断言硬编码的 429，但 `BizCode.RATE_LIMITED.code` 实际为 1004。

## 修改

`gateway/src/test/kotlin/com/nebula/gateway/interceptor/RateLimitInterceptorTest.kt`:
- 第 5 行：添加 `import com.nebula.common.BizCode`
- 第 284 行：`assertEquals(429, ...)` → `assertEquals(BizCode.RATE_LIMITED.code, ...)`
- 第 313 行：`assertEquals(429, ...)` → `assertEquals(BizCode.RATE_LIMITED.code, ...)`

## 验证

`./gradlew :gateway:test --tests "com.nebula.gateway.interceptor.RateLimitInterceptorTest"` → BUILD SUCCESSFUL
