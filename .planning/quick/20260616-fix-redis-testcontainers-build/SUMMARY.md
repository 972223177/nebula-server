---
slug: fix-redis-testcontainers-build
status: complete
completed_at: 2026-06-16T15:16
completed_tasks:
  - task1: service/build.gradle.kts 添加 Docker API 版本配置
  - task2: SeqService.tryRestoreSeq 修复 INCR 语义不匹配 Bug
  - task3: 验证 RedisTestBaseTest 同时通过
files_changed:
  - service/build.gradle.kts
  - service/src/main/kotlin/com/nebula/service/sequence/SeqService.kt
---

# Summary: fix-redis-testcontainers-build

## 修复内容

### 问题 1：Testcontainers Docker 连接失败

**根因**: service/build.gradle.kts 的 tasks.test 缺少 systemProperty("api.version", "1.44")。Docker Engine v29.5.3 要求 API >= 1.44，但 docker-java 默认值过低。repository 模块已有此配置，service 模块缺失。

**影响测试**: `SeqServiceRedisRecoveryTest`、`RedisTestBaseTest` 及 service 模块所有使用 Testcontainers 的测试。

### 问题 2：序列号恢复偏移 +1

**根因**: `SeqService.tryRestoreSeq` 直接用 `SETNX` 存储传入的 `nextSeq` 值（如 6），但 `nextSeq()` 使用 `INCR`（加 1 后返回），导致恢复后首次调用返回 7 而非预期的 6。

**修复**: tryRestoreSeq 内部存储 `nextSeq - 1`，补偿 INCR 的 +1 行为。

**生产影响**: `recoverSequences` 方法中 `nextSeq = msgCount + 1L`，原代码会导致所有会话在 Redis 重启恢复后序列号整体偏移 +1。此修复一次性根除。

## 测试结果

- `SeqServiceRedisRecoveryTest` — ✅ 2 tests passed
- `RedisTestBaseTest` — ✅ 1 test passed
## Commit

`396d128` — fix: 修复 SeqServiceRedisRecoveryTest 失败问题
