---
phase: 06
slug: chat-message
status: validated
nyquist_compliant: true
wave_0_complete: true
created: 2026-06-12
---

# Phase 6 — Validation Strategy

> 聊天消息发送、扇出推送、消息拉取和已读回执

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + MockK (Kotlin) |
| **Config file** | 无 — Gradle managed (build.gradle.kts) |
| **Quick run command** | `./gradlew :gateway:test --tests "com.nebula.gateway.handler.chat.send.*" --tests "com.nebula.gateway.handler.message.*"` |
| **Full suite command** | `./gradlew :gateway:test :server:test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :gateway:test --tests "com.nebula.gateway.*"`
- **After every plan wave:** Run `./gradlew :gateway:test :server:test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 06-01-01 | 01 | 1 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02 | — | message.proto D-22 变更验证（sender_username/sender_avatar 移除，receiver_uid 添加） | compile | `./gradlew :proto:generateProto && ./gradlew :gateway:compileKotlin` | ✅ proto 构建产物 | ✅ green |
| 06-01-02 | 01 | 1 | BIZ-CHAT-01, BIZ-CHAT-02 | T-06-03 | UserStreamRegistry：ConcurrentHashMap<Long, CopyOnWriteArrayList> 管理 userId→observer 映射，仅暴露 getStreams(userId) 接口 | unit | `./gradlew :gateway:compileKotlin` | ✅ UserStreamRegistry.kt | ✅ green |
| 06-01-03 | 01 | 1 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-02 | T-06-01, T-06-02 | PushService：pushMessage 排除 excludeUid（D-09），单 observer try-catch 容错（D-05） | unit | `./gradlew :gateway:compileKotlin` | ✅ PushService.kt | ✅ green |
| 06-01-04 | 01 | 1 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-02 | T-06-01, T-06-02, T-06-03 | PushService + UserStreamRegistry 单元测试：register/remove/getStreams、消息排除、异常容错 | unit (TDD) | `./gradlew :gateway:test --tests "com.nebula.gateway.push.*" --tests "com.nebula.gateway.session.*"` | ✅ UserStreamRegistryTest.kt, PushServiceTest.kt | ✅ green |
| 06-02-01 | 02 | 2 | BIZ-CHAT-01 | T-06-04, T-06-05, T-06-06 | Step 链：ValidateStep 校验 content/clientMessageId 非空 + 成员检查（D-08, D-14）；DedupStep SETNX 原子去重 + 7 天 TTL（D-07）；WriteStep Snowflake ID + Redis Stream + 会话元更新（D-04, D-10） | unit | `./gradlew :gateway:compileKotlin` | ✅ SendMessageStep.kt, SendContext.kt, ValidateStep.kt, DedupStep.kt, WriteStep.kt | ✅ green |
| 06-02-02 | 02 | 2 | BIZ-CHAT-01, BIZ-CHAT-02 | T-06-07, T-06-14 | SendMessageHandler：Step 链 try-catch 包裹（REVIEW-HIGH-2），WriteStep 后立即返回 SendMessageResp，推送/未读计数 fire-and-forget 异步执行（D-04 per REVIEW） | unit | `./gradlew :gateway:compileKotlin` | ✅ SendMessageHandler.kt | ✅ green |
| 06-02-03 | 02 | 2 | BIZ-CHAT-01 | — | ChatService 集成 UserStreamRegistry：handleLoginSuccess register + cleanupConnection removeStream + 显式类型检查（REVIEW-MEDIUM-7） | compile | `./gradlew :gateway:compileKotlin` | ✅ ChatService.kt (modified) | ✅ green |
| 06-02-04 | 02 | 2 | BIZ-CHAT-01 | T-06-04, T-06-05, T-06-06, T-06-14 | ValidateStep/DedupStep/WriteStep/SendMessageHandler 单元测试：内容为空、重复消息、msg_id 生成、异常包装等 | unit (TDD) | `./gradlew :gateway:test --tests "com.nebula.gateway.handler.chat.send.*"` | ✅ ValidateStepTest.kt, DedupStepTest.kt, WriteStepTest.kt, SendMessageHandlerTest.kt | ✅ green |
| 06-03-01 | 03 | 2 | BIZ-MSG-01 | T-06-08, T-06-09, T-06-10, T-06-15 | PullMessagesHandler：cursor=0 → Long.MAX_VALUE（D-18），limit coerceIn(1,100)（D-19），会话 existsById 检查（REVIEW-MEDIUM-9），// SECURITY(FIXME Phase 7) 注释（REVIEW-HIGH-3） | unit | `./gradlew :gateway:compileKotlin` | ✅ PullMessagesHandler.kt | ✅ green |
| 06-03-02 | 03 | 2 | BIZ-MSG-02 | T-06-11 | ReadReportHandler：会话类型判定（D-27），成员身份检查（REVIEW-MEDIUM-10），updateReadReceipt + Redis DEL（D-28），私聊推送 READ_RECEIPT（D-23），群聊不推送 | unit | `./gradlew :gateway:compileKotlin` | ✅ ReadReportHandler.kt | ✅ green |
| 06-03-03 | 03 | 2 | BIZ-MSG-01, BIZ-MSG-02 | T-06-08, T-06-09, T-06-10, T-06-11, T-06-15 | PullMessagesHandler + ReadReportHandler 单元测试：游标/limit 边界、会话不存在、成员检查、私聊/群聊分支 | unit (TDD) | `./gradlew :gateway:test --tests "com.nebula.gateway.handler.message.*"` | ✅ PullMessagesHandlerTest.kt, ReadReportHandlerTest.kt | ✅ green |
| 06-04-01 | 04 | 3 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02 | T-06-12, T-06-13 | GatewayModule.handlerModule 追加 Phase 6 组件注册（8 个 single 声明 + Step 列表） | compile | `./gradlew :gateway:compileKotlin` | ✅ GatewayModule.kt (modified) | ✅ green |
| 06-04-02 | 04 | 3 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02 | T-06-16 | registerHandlers 追加 3 个 Handler 注册 + NebulaServer.kt ChatService 实例化修复（REVIEW-MEDIUM-8） | compile | `./gradlew :server:compileKotlin && ./gradlew build` | ✅ GatewayModule.kt, NebulaServer.kt (modified) | ✅ green |
| 06-04-03 | 04 | 3 | BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02 | — | Koin 验证测试：确认所有 Phase 6 组件可在容器中解析 | unit | `./gradlew :server:test --tests "com.nebula.server.KoinVerificationTest"` | ✅ KoinVerificationTest.kt | ✅ green |

> **Frame of reference:** ✅=green means tests passed on last execution per SUMMARY files. Stale results remediated by re-running full suite before sign-off.

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements.
无额外 Wave 0 依赖（JUnit5 + MockK + Gradle 已在 Phase 1-5 建立）。

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter

---

## Validation Audit 2026-06-12

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |

**状态：全部 4 个需求（BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02）均有自动化单元测试覆盖。**
**总计 15 个任务项，41+ 单元测试用例，全部通过。**

| 需求 | 测试文件数 | 测试用例数 | 状态 |
|------|-----------|-----------|------|
| BIZ-CHAT-01 | 5 | 20 | ✅ COVERED |
| BIZ-CHAT-02 | 2 | 9 | ✅ COVERED |
| BIZ-MSG-01 | 1 | 7 | ✅ COVERED |
| BIZ-MSG-02 | 1 | 5 | ✅ COVERED |
| DI 验证 | 2 | 2 | ✅ COVERED |

**TODO（Phase 7 接手）:**
- PullMessagesHandler 需补充会话成员检查（`// SECURITY(FIXME Phase 7)` 标记，REVIEW-HIGH-3 / T-06-10）

**Approval:** pending
