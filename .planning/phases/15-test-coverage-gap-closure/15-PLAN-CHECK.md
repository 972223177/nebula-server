---
phase: 15
checker: nx-plan-checker
iteration: 2
result: VERIFICATION_PASSED
---

# Phase 15 计划审核报告

## 审核摘要

- **审核次数**: 1/3
- **审核状态**: ISSUES FOUND
- **问题数**: 6（阻塞 2 / 警告 4）

---

## 完整性检查

### P0 问题覆盖核对（必须全部覆盖）

| 编号 | 问题 | 覆盖计划 | 状态 |
|------|------|---------|------|
| P0-01 | SessionRepository 7/8 方法无测试 | 15-3 Task 1 | ✅ 覆盖 |
| P0-02 | MessageRepository 完全无测试 | 15-3 Task 4 | ✅ 覆盖 |
| P0-03 | DeadLetterRepository 完全无测试 | 15-3 Task 5 | ✅ 覆盖 |
| P0-04 | PrivacyRepository 完全无测试 | 15-3 Task 2 | ✅ 覆盖 |
| P0-05 | MessageQueueRepository 完全无测试 | 15-3 Task 3 | ✅ 覆盖 |
| P0-06 | ConversationService.dissolveGroup 完全未测试 | 15-2 Task 1-2 | ✅ 覆盖 |

**P0 结论**: 6/6 全部覆盖 ✅

### P1 问题覆盖核对（需 ≥ 80% = 14/17）

| 编号 | 问题 | 覆盖计划 | 状态 |
|------|------|---------|------|
| P1-01 | SeqService.recoverSequences | 15-4 Tasks 1-2 | ✅ 覆盖 |
| P1-02 | UserRepository.findByUsernameContaining 游标分页 | 15-5 Task 2 | ✅ 覆盖 |
| P1-03 | ConversationRepository.findConversationsByUserId 游标分页 | 15-5 Task 3 | ✅ 覆盖 |
| P1-04 | FriendshipRepository.findFriendsByUserId 游标分页 | 15-5 Task 5 | ✅ 覆盖 |
| P1-05 | ConversationMemberRepository.incrementUnreadCount | 15-5 Task 3 | ✅ 覆盖 |
| P1-06 | ConversationMemberRepository 4 个批量查询方法 | 15-5 Task 4 | ✅ 覆盖 |
| P1-07 | FriendRequestRepository 2 个方法 | 15-5 Task 5 | ✅ 覆盖 |
| P1-08 | ConversationService 3 个辅助方法 | 15-2 Task 3 | ✅ 覆盖 |
| P1-09 | UserPrivacyService.batchGetHideOnlineStatus | 15-4 Task 3 | ✅ 覆盖 |
| P1-10 | MessageService 3 个方法 | 15-4 Tasks 4-6 | ✅ 覆盖 |
| P1-11 | ConversationService.leaveGroup memberCount==1 分支 | 15-2 Tasks 4-5 | ✅ 覆盖 |
| P1-12 | UserService.register 异常兜底 | 15-4 Task 8 | ✅ 覆盖 |
| P1-13 | MessageService.pullMessages limit 边界 | 15-4 Task 7 | ✅ 覆盖 |
| P1-14 | FriendService.listFriends nextCursor | 15-4 Task 10 | ✅ 覆盖 |
| P1-15 | Handler 层无 Session 异常路径缺失 | 15-5 Task 6 | ✅ 覆盖 |
| P1-16 | ReadReportHandlerTest 反射注入 | 15-1 Tasks 1,3 | ✅ 覆盖 |
| P1-17 | RedisDeliveryTrackerTest 反射注入 | 15-1 Tasks 2,4 | ✅ 覆盖 |

**P1 结论**: 17/17 全部覆盖（100%）✅ — 远超 80% 要求

### P2 问题覆盖核对（选定 5 项）

| 编号 | 问题 | 覆盖计划 | 状态 |
|------|------|---------|------|
| P2-01 | Flyway V4/V5 | 15-5 Task 1 | ✅ 覆盖 |
| P2-02 | runBlocking 反模式 | 15-4 Tasks 9,11 | ✅ 覆盖 |
| P2-06 | ProtoCodec 字段验证 | 15-1 Task 7 | ✅ 覆盖 |
| P2-07 | cursor 验证 | 15-1 Task 8 | ✅ 覆盖 |
| P2-09 | 异常类型 | 15-1 Tasks 5-6 | ✅ 覆盖 |

**P2 结论**: 5/5 选定的 P2 项全部覆盖 ✅

### 技术决策覆盖核对

| 决策 | 内容 | 覆盖计划 | 状态 |
|------|------|---------|------|
| D-15-01 | Redis 测试策略（选项 C: MockK + Testcontainers） | 15-3 Tasks 1-3 | ✅ 覆盖 |
| D-15-02 | JPA 集成测试策略（选项 C: JpaRepositoryFactory） | 15-3 Tasks 4-5 | ✅ 覆盖 |
| D-15-03 | 反射注入修复（选项 A: 构造函数注入+默认参数） | 15-1 Tasks 1-4 | ✅ 覆盖 |
| D-15-04 | P2 范围选择（选项 B: 仅纳入 5 项） | 15-1/4/5 分散覆盖 | ✅ 覆盖 |
| D-15-05 | seqService mock 策略（选项 B: 全局+添加验证） | 15-2 Task 6 | ✅ 覆盖 |

---

## 可行性检查

### 优势

- ✅ 每个 Plan 有明确的 objectve、files_modified、依赖声明
- ✅ Wave 分组合理：无依赖的 Wave 1 可并行执行，Wave 2 依赖 Wave 1，Wave 3 依赖 Wave 2
- ✅ 每个任务有 type/files/action/verify/acceptance_criteria 完整五要素
- ✅ 任务粒度基本合理（5-11 个任务/Plan）

### 问题

#### 🔴 阻塞-1: 成功标准中 P1 计数与实际情况不符

**位置**: PLAN.md 第 356 行
**描述**: 成功标准中写 `P1 闭合 ≥ 80%（13/16）：除 P2-03/P2-04/P2-11 延期项外全部覆盖`。问题有三：
1. 实际 P1 项目数为 17 项（P1-01 ~ P1-17），不是 16
2. 实际覆盖为 17/17（100%），不是 13/16
3. 提到的"P2-03/P2-04/P2-11"属于 P2 分类，不是 P1

**影响**: 成功标准数据错误可能导致阶段验收时标准混乱。
**建议修复**: 改为 `P1 闭合 100%（17/17）：全部覆盖`。

#### 🔴 阻塞-2: CONTEXT.md 与 PLAN.md 的 P1 计数不一致

**位置**: CONTEXT.md 第 99 行 vs PLAN.md 第 357 行
**描述**: CONTEXT.md 中成功标准要求"至少 80% P1 问题修复（预计 16/17 项）"——此处 P1 总数为 17，但 PLAN.md 中写"13/16"，数值不一致且 PLAN 侧数据错误。
**影响**: 两份文档的对应数据不一致，影响追踪。
**建议修复**: 统一为 17 项，PLAN.md 成功标准改为 `P1 闭合 100%（17/17）：全部覆盖`。

#### 🟡 警告-1: Plan 15-5 Task 6 涉及 18 个 Handler 测试文件，任务粒度过大

**位置**: PLAN.md 第 314-316 行
**描述**: 一个任务要求修改 18 个 gateway handler 测试文件，每个文件添加 1 个无 Session 测试方法。虽然每个修改很小（1 个测试方法），但文件数过多，建议拆分以降低风险。
**影响**: 如果中间某个 Handler 的构造方式不同或存在特殊情况，整个任务会被阻塞。且 18 个文件的并行度受限。
**建议修复**: 按 Handler 领域拆分为 3-4 个子任务（如：Conversation 6 个、Friend 6 个、User 6 个），或至少在任务描述中明确**按文件逐个独立执行**。

#### 🟡 警告-2: Plan 15-4 任务数最多（11 个），风险集中

**位置**: PLAN.md 第 231-243 行
**描述**: Plan 15-4 包含 11 个任务，覆盖 5 个测试文件，涉及 runBlocking 修复（22 处）、新测试方法（约 10 个）、异常断言修改等。是 5 个 Plan 中任务量最大的。
**影响**: 单 Plan 工作量大，如果执行中出现问题会影响 Wave 3 启动（波依赖）。
**建议修复**: 当前结构可接受，但建议规划执行时优先完成 Tasks 9/11（runBlocking 修复）——因其涉及 22 处修改，尽早验证编译通过。

#### 🟡 警告-3: "零生产逻辑变更"表述可能产生误解

**位置**: PLAN.md 第 361 行
**描述**: 成功标准中列了"零生产逻辑变更：仅 2 个生产文件添加构造参数默认值，无业务逻辑修改"。但 Plan 15-1 Tasks 1-2 修改的正是生产文件，虽然只是添加默认参数，但严格来说不属于"零生产逻辑变更"。
**影响**: 如果审核者严格按字面检查"零生产逻辑变更"可能会产生误解。
**建议修复**: 改为更精确的表述，如"生产代码仅限添加构造参数默认值，无业务逻辑修改"。

---

## 一致性检查

### 与 RESEARCH.md 一致性

| 决策 | RESEARCH.md 推荐 | PLAN 实现 | 状态 |
|------|-----------------|-----------|------|
| D-15-01 | 选项 C（MockK + Testcontainers） | 15-3 Tasks 1-3 采用混合策略 | ✅ 一致 |
| D-15-02 | 选项 C（JpaRepositoryFactory） | 15-3 Tasks 4-5 使用 JpaRepositoryFactory | ✅ 一致 |
| D-15-03 | 选项 A（构造函数注入+默认参数） | 15-1 Tasks 1-4 采用此方案 | ✅ 一致 |
| D-15-04 | 选项 B（仅纳入 P2-01/02/06/07/09） | 5 项分散在各 Plan | ✅ 一致 |
| D-15-05 | 选项 B（保持全局+coVerify 验证） | 15-2 Task 6 添加 coVerify | ✅ 一致 |

### 与 PATTERNS.md 一致性

| 模式参考 | PATTERNS.md 建议 | PLAN 引用 | 状态 |
|---------|-----------------|----------|------|
| Redis Mock 模式 | SessionRepositoryBatchDeleteTest + OnlineStatusRepositoryTest | 15-3 Task 1 明确引用这两个模式 | ✅ 一致 |
| JPA 集成测试模式 | JpaRepositoryFactory（符合 D-15-02） | 15-3 Tasks 4-5 | ✅ 一致 |
| Handler 无Session 模式 | MessageSeqHandlerTest.handleShouldRequireSession | 15-5 Task 6 明确引用此模式 | ✅ 一致 |
| Flyway 迁移验证模式 | 现有 FlywayMigrationTest 模式 | 15-5 Task 1 模式一致 | ✅ 一致 |

### 依赖关系自洽性

```
Wave 1 (并行)          Wave 2 (依赖 Wave 1)     Wave 3 (依赖 Wave 2)
15-1 (基础修复) ──────→ 15-3 (Repository P0) ──┐
                                                 ├──→ 15-5 (尾部改进)
15-2 (Service 核心) ──→ 15-4 (Service 剩余) ───┘
```

- 依赖关系清晰、无循环依赖 ✅
- 并行 Plan 无文件重叠 ✅（15-1 涉及 gateway+repository，15-2 仅 service）
- Wave 2 的 15-3 依赖 15-1、15-4 依赖 15-2 — 符合实际逻辑 ✅
- Wave 3 的 15-5 依赖 15-3+15-4 — 合理，因为需在 Repository 和 Service 全部测试就绪后完成 ✅

---

## 风险与建议

### 风险矩阵

| 风险 | 等级 | 描述 | 缓解措施 |
|------|------|------|---------|
| R1 | 🟡 中 | PrivacyRepositoryTest 需同时启动 MySQL+Redis Testcontainers 容器，可能存在端口/网络冲突 | 隔离容器网络（使用 `withNetworkMode` 或独立 compose）；Plan 已识别此风险并写入文档 |
| R2 | 🟡 中 | runBlocking 修复（22 处）涉及 UserServiceTest 和 FriendServiceTest，`assertThrows` 改写为 `assertFailsWith` 模式容易出错 | 为每个修改添加独立的编译验证步骤；先改一个文件验证通过再批量操作 |
| R3 | 🟡 中 | Plan 15-5 Task 6 涉及 18 个 handler 测试文件，部分 Handler 构造复杂或有特殊 setup | 建议拆分为子任务；先确认每个 Handler 的构造参数列表一致性 |
| R4 | 🟢 低 | 新增 Repository 测试文件（15-3 Tasks 4-5）需要 `spring-data-jpa` 在 test scope 可用 | 实施前验证 `repository/build.gradle.kts` 中依赖情况 |
| R5 | 🟢 低 | 游标分页测试需手动赋值 Long ID 以控制 Snowflake ID 排序 | 所有相关任务已注明此注意事项 ✅ |
| R6 | 🟢 低 | SeqServiceTest.recoverSequences 需反射注入 Redis mock — 与 D-15-03 的构造函数方案不同，需注意两个模式共存 | 15-4 风险部分已说明此差异 ✅ |

### 整体可执行性评估

**可执行**: ✅

尽管存在上述风险和问题（主要是文档数字错误），PLAN 的整体结构合理、任务定义清晰、依赖关系正确、技术决策与研究成果一致。修复文档中的数字错误后即可执行。

**执行建议**:
1. 优先验证 `spring-data-jpa` 依赖在 repository 模块 test scope 中的可用性（影响 15-3）
2. 15-4 的 runBlocking 修复建议先做 UserServiceTest（8 处），验证模式正确后再处理 FriendServiceTest（14 处）
3. 15-5 Task 6（18 个 handler 无 session 测试）建议按 Conversation/Friend/User 领域分批提交

---

## 结论

### 第一轮阻塞问题修复验证

| 问题 | 原描述 | 修复后 | 状态 |
|------|--------|--------|------|
| P1 计数错误 | P1 闭合 ≥ 80%（13/16） | P1 全部闭合（17/17） | ✅ 已修复 |
| P2 项误引 | "除 P2-03/P2-04/P2-11 延期项外全部覆盖" | "所有 P1 项均在对应 Plan 中覆盖" | ✅ 已修复 |
| "零生产逻辑变更"表述 | 可能被误解为无生产文件修改 | "零业务逻辑变更（仅 2 文件添加构造参数默认值）" | ✅ 已修复 |

### 最终裁决

> **APPROVED** ✅ — 可以执行

所有阻塞问题已修复，剩余 4 个警告问题不构成执行障碍（已在风险分析中记录缓解措施）。PLAN.md 覆盖完整、依赖关系清晰、技术决策与研究成果一致，可以安全执行。
