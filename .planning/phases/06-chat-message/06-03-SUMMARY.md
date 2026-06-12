---
phase: 06-chat-message
plan: 03
subsystem: api
tags: [message, pull, cursor, pagination, read-receipt, redis, push]

# Dependency graph
requires:
  - phase: 06-01
    provides: PushService, UserStreamRegistry, ConversationMemberRepository
provides:
  - message/pull 入口：PullMessagesHandler（游标分页拉取 + 安全注释 + 会话存在性检查）
  - message/read 入口：ReadReportHandler（已读进度更新 + 未读计数重置 + 私聊已读回执推送）
affects: [06-04, phase-7-conversation]

# Tech tracking
tech-stack:
  added: []
  patterns: [Handler Direct Pattern, toChatMessage Entity→Proto Mapping]

key-files:
  created:
    - gateway/.../handler/message/PullMessagesHandler.kt
    - gateway/.../handler/message/ReadReportHandler.kt
    - gateway/src/test/.../message/PullMessagesHandlerTest.kt
    - gateway/src/test/.../message/ReadReportHandlerTest.kt
  modified: []

key-decisions:
  - "PullMessagesHandler cursor=0 使用 Long.MAX_VALUE 替代零值游标，避免 MySQL 查询 `id < 0` 返回空（D-18 Pitfall 2）"
  - "toChatMessage() 使用 `?: ChatContentType.UNRECOGNIZED` 处理 null messageType，符合 REVIEW-MEDIUM-11 要求"
  - "ReadReportHandler 在更新已读前验证成员身份（REVIEW-MEDIUM-10），非成员返回 NOT_MEMBER"
  - "私聊已读回执推送不阻塞主流程，未找到发送者时仅记录 debug 日志"

patterns-established:
  - "消息拉取 Handler 模式：游标分页 + existsById 存在性检查 + limit coerceIn 约束"
  - "已读回执 Handler 模式：会话类型判定 → 成员验证 → 更新已读 → 重置 Redis → 私聊推送"
  - "Entity→Proto 映射扩展函数（private fun MessageEntity.toChatMessage()）"

requirements-completed: [BIZ-MSG-01, BIZ-MSG-02]

# Metrics
duration: 18 min
completed: 2026-06-12
---

# Phase [6] Plan [03]: Message Pull & Read Summary

**PullMessagesHandler （游标分页拉取）和 ReadReportHandler（已读回执），含 REVIEW 安全变更（SECURITY FIXME 注释、会话存在性检查、成员身份检查、UNRECOGNIZED 保底）**

## Performance

- **Duration:** 18 min
- **Started:** 2026-06-12T11:40:00Z (approx)
- **Completed:** 2026-06-12T11:58:15Z
- **Tasks:** 3
- **Files modified:** 4 (2 production + 2 test)

## Accomplishments
- PullMessagesHandler：基于 MySQL 游标分页的消息历史拉取，cursor=0 → Long.MAX_VALUE 查最新，limit coerceIn(1, 100) 防滥用
- PullMessagesHandler：文件开头添加 `// SECURITY(FIXME Phase 7)` 安全注释（REVIEW-HIGH-3），会话存在性 existsById 检查（REVIEW-MEDIUM-9）
- ReadReportHandler：会话类型判定（私聊/群聊）→ 成员身份验证（REVIEW-MEDIUM-10）→ updateReadReceipt 更新已读进度 → Redis DEL 重置未读计数（D-28）
- ReadReportHandler：私聊场景构建 ReadReceiptPayload 通过 PushService.pushReadReceipt 推送（D-23），群聊不推送
- toChatMessage() 扩展函数使用 `?: ChatContentType.UNRECOGNIZED` 处理 null messageType（REVIEW-MEDIUM-11）
- 13 个单元测试全部通过（PullMessagesHandlerTest 8 个 + ReadReportHandlerTest 5 个）

## Task Commits

Each task was committed atomically:

1. **Task 1: 创建 PullMessagesHandler** - `28f1c6a` (feat)
2. **Task 2: 创建 ReadReportHandler** - `81b1e3a` (feat)
3. **Task 3: 单元测试** - `c090dde` (test)

**Plan metadata:** `pending` (SUMMARY commit)

## Files Created/Modified
- `gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt` — 游标分页消息拉取 Handler
- `gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt` — 已读报告 Handler
- `gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt` — 8 个单元测试
- `gateway/src/test/kotlin/com/nebula/gateway/handler/message/ReadReportHandlerTest.kt` — 5 个单元测试

## Decisions Made
- PullMessagesHandler 不验证请求者是否为会话成员（接受 T-06-10 风险，标记 FIXME Phase 7）
- toChatMessage() 中 receiver_uid 统一填 0（MessageEntity 未持久化该字段）
- ReadReportHandler 的单 Handler 模式（D-26：不拆 Step 链）
- Redis DEL 未读计数键接受极低概率竞态（D-28 文档化）

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- **MockK Pageable 匹配失败：** `Pageable.ofSize(N)` 对象无法通过 `equals()` 匹配，改用 `any()` 通配匹配参数。已修复，所有测试通过。
- **Lettuce connection.reactive() 注入问题：** ReadReportHandler 的 redis 字段在构造时由 `connection.reactive()` 初始化，测试中通过反射替换为 mock 实例。使用 `mockk(relaxed=true)` 避免构造时的错误。

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- 两个 Handler 已实现并通过编译和单元测试
- 待 06-04 DI Wiring 将 Handler 注册到 Koin 容器和 HandlerRegistry
- Phase 7 需在 PullMessagesHandler 中补充会话成员检查（SECURITY FIXME）
- 游标分页、已读回执推送的核心逻辑已覆盖，ready for next plan

---
*Phase: 06-chat-message*
*Completed: 2026-06-12*
