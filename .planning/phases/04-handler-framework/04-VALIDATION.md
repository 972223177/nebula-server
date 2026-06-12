---
phase: 04
slug: handler-framework
status: compliant
nyquist_compliant: true
wave_0_complete: true
created: 2026-06-12
---

# Phase 04 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit5 + MockK + kotlinx-coroutines-test |
| **Config file** | gateway/build.gradle.kts (useJUnitPlatform, koin-test-junit5, mockk) |
| **Quick run command** | `./gradlew :gateway:test --tests "*${CLASS}*"` |
| **Full suite command** | `./gradlew :gateway:test` |
| **Estimated runtime** | ~4 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :gateway:compileKotlin`
- **After every plan wave:** Run `./gradlew :gateway:test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~4 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | HNDL-01 | T-04-01 | putIfAbsent 防重复注册 | unit | `:gateway:test --tests "HandlerRegistryTest"` | ✅ | ✅ green |
| 04-01-02 | 01 | 1 | HNDL-06 | T-04-02 | MethodHandle 启动期查找，空载荷 guard | unit | `:gateway:test --tests "ProtoCodecTest"` | ✅ | ✅ green |
| 04-01-03 | 01 | 1 | HNDL-02 | T-04-03/T-04-04 | SupervisorJob 隔离异常，NOT_FOUND 不暴露注册表 | unit | `:gateway:test --tests "DispatcherTest"` | ✅ | ✅ green |
| 04-02-01 | 02 | 3 | HNDL-04 | T-04-05/T-04-07/T-04-08 | Session token 验证，skipMethods 白名单 | unit | `:gateway:test --tests "AuthInterceptorTest"` | ✅ | ✅ green |
| 04-02-02 | 02 | 3 | HNDL-04 | — | LogInterceptor method + 耗时日志 | unit | `:gateway:test --tests "LogInterceptorTest"` | ✅ | ✅ green |
| 04-02-03 | 02 | 3 | HNDL-04 | T-04-09 | Semaphore 每用户限流 20 并发 | unit | `:gateway:test --tests "RateLimitInterceptorTest"` | ✅ | ✅ green |
| 04-02-04 | 02 | 3 | HNDL-04 | T-04-06 | 三态异常映射：BizException→业务码、IAE→1000、其他→9000 | unit | `:gateway:test --tests "ExceptionInterceptorTest"` | ✅ | ✅ green |
| 04-03-01 | 03 | 4 | HNDL-05 | T-04-10/T-04-10b | L1/L2 双级缓存，Redis 超时降级 L1 只读 | unit | `:gateway:test --tests "SessionRegistryTest"` | ✅ | ✅ green |
| 04-03-02 | 03 | 4 | HNDL-01 | T-04-11/T-04-12 | PingHandler 固定 pong 响应，心跳间隔限制 | unit | `:gateway:test --tests "PingHandlerTest"` | ✅ | ✅ green |
| 04-04-01 | 04 | 4 | HNDL-03 | T-04-15 | Koin module 组件解析（HandlerRegistry、PingHandler 等） | unit | `:gateway:test --tests "GatewayModuleTest"` | ✅ | ✅ green |
| 04-04-02 | 04 | 4 | HNDL-03 | T-04-16/T-04-17 | 全流水线集成：ping（无认证）+ 认证路径 | integration | `:gateway:test --tests "PipelineIntegrationTest"` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements:
- JUnit5 platform configured in `gateway/build.gradle.kts`
- MockK 1.13.14 for suspend function mocking
- kotlinx-coroutines-test v1.9.0 for `runTest` support
- protobuf-java test dependency for Proto codec tests

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 4s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-12
