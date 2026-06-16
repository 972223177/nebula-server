## 审核结果：Plan 14-01

### 审核摘要
- 审核次数：1/3（max 3）
- 审核状态：ISSUES FOUND
- 问题数：5（阻塞 3 / 警告 2）

---

### 阻塞问题（必须修复）

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| **B1** | 可行性 | **任务 #5 (T05): compensate 调用签名错误** — `DeadLetterService.compensate()` 无参数（`suspend fun compensate(): Int`），计划描述中"创建带 payload 的死信 → 调用 compensate()"的流程无法在 MockK 方案中实现。计划未说明如何让 `compensate()` 内部获取到带 payload 的 `DeadLetterEntity`。正确做法是 mock `deadLetterRepository.findByStatusAndFailCountLessThan()` 返回 `payload` 非空的实体，再验证 `enqueue()` 被调用。参见 `DeadLetterService.kt:149` 确认签名。 | 修正任务 #5 测试方案为：① Mock `deadLetterRepository.findByStatusAndFailCountLessThan()` 返回 `payload 非 null` 的 `DeadLetterEntity` 列表；② 使用 `slot<Map<String, String>>` 捕获 `messageQueueRepository.enqueue()` 的参数；③ 调用 `deadLetterService.compensate()`（无参）；④ 验证 `slot.captured["payload"]` 为 Base64 可解码字符串。 |
| **B2** | 可行性 | **任务 #6 (T06): 方法名 `recoverFromDatabase()` 不存在** — `SeqService` 中无 `recoverFromDatabase()` 方法。正确的方法是 `tryRestoreSeq(convId, uid, nextSeq)`（单 Key 恢复，`SeqService.kt:104`）或 `recoverSequences(...)`（批量恢复，`SeqService.kt:121`）。 | 将计划中 `seqService.recoverFromDatabase()` 改为 `seqService.tryRestoreSeq(convId, uid, 6L)`，与 RESEARCH.md 第 362-363 行的设计保持一致。 |
| **B3** | 一致性 | **任务 #6 (T06): `nextSeq(convId)` 参数不完整** — 验收标准写 `seqService.nextSeq(convId)`，但 `SeqService.nextSeq()` 需要两个参数 `(convId: String, uid: Long)`（参见 `SeqService.kt:61`）。仅传一个 `convId` 会导致编译错误。 | 将验收标准修正为 `seqService.nextSeq(convId, uid)`，并在测试代码中定义 `uid` 常量（如 `val uid = 1001L`）。RESEARCH.md 第 355/365 行已正确使用双参数版本。 |

---

### 警告问题（建议修复）

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| **W1** | 一致性 | **任务 #1 辅助方法名与 RESEARCH.md 不一致** — 计划说暴露 `getRedisURI()` / `createRedisClient()` / `createConnection()`，但 RESEARCH.md（第 113-115 行）设计为 `getConnection()` / `getCommands()` / `getRedisPort()` / `getRedisHost()`。名称不一致可能导致实现时的混淆。 | 统一使用 RESEARCH.md 中定义的方法签名，或更新 PLAN 中的方法名与 RESEARCH 对齐。建议遵循 `DatabaseTestBase` 的命名风格（`getDataSource()` / `getJdbcUrl()`），即命名模式为 `getXxx()`。 |
| **W2** | 风险 | **任务 #4 (T04): MockK 方案无法验证真正的并发竞争条件** — 计划已诚实标注此方案"仅覆盖协程并发调度逻辑"，但需明确指出：所有 Repository 调用被 MockK 替换后，`coroutineScope { launch { ... } }` 下的调用实际上是顺序执行 MockK 预配置行为，无法验证 MySQL JPA 层的 `member_count` 原子性。测试价值有限。 | ① 在计划中增加"局限性"小节，明确标注此测试**不替代** MySQL 级并发测试；② 增加 TODO 注释"需要 MySQL Testcontainers 环境实现真实并发一致性测试"；③ 考虑将该测试标记为 `@Tag("mock-concurrency")` 以便与真实集成测试区分。 |

---

### 维度评分

| 维度 | 评分 | 说明 |
|------|------|------|
| 完整性 | PASS | 覆盖了 CONTEXT.md 中 T04/T05/T06 和 RedisTestBase 基础设施的要求 |
| 可行性 | **BLOCK** (B1, B2, B3) | T05 的 compensate 调用方案不可行；T06 的方法名和参数均有错误，编译无法通过 |
| 一致性 | PASS | 两个 PLAN 之间无冲突，依赖关系合理（Wave 2 依赖 Wave 1-A） |
| 安全性 | PASS | 无凭据硬编码、无 Token 泄露风险；全部使用容器化 Redis |
| 可测试性 | **FLAG** (W1, W2) | T04 MockK 方案验证能力有限，需明确标注局限性 |

---

### 最终裁决

**REVISION NEEDED** — 需修改后重新审核

~~必须修复 **B1**（T05 compensate 签名）、**B2**（T06 方法名）、**B3**（T06 参数缺失）三个阻塞问题。建议也处理 **W1**（方法名对齐）和 **W2**（标注 T04 局限性）。~~

---

### 修复确认（2026-06-16 重新审核）

所有 3 个阻塞问题和 2 个警告问题已修复：

| # | 修复状态 | 修复内容 |
|---|---------|---------|
| **B1** | ✅ 已修复 | T05 改为 Mock Repository 方案：`mockk<DeadLetterRepository>` → 返回 payload 非 null 实体 → 调用 `compensate()` 无参 → 验证 `enqueue()` 参数 |
| **B2** | ✅ 已修复 | T06 方法名从 `recoverFromDatabase()` 修正为 `tryRestoreSeq(convId, uid, 6L)` |
| **B3** | ✅ 已修复 | T06 验收标准中 `nextSeq(convId)` 修正为 `nextSeq(convId, uid)`，同时传 convId 和 uid |
| **W1** | ✅ 已修复 | RedisTestBase 辅助方法统一为 `getConnection()` / `getCommands()` / `getRedisHost()` / `getRedisPort()`，与 RESEARCH.md 保持一致 |
| **W2** | ✅ 已修复 | T04 部分增加"局限性"小节，明确标注 MockK 无法验证 MySQL 层原子性 |

**新审核状态: APPROVED** — 可以执行

