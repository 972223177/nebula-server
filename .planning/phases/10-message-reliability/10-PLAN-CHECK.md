---
phase: 10
checker: nx-plan-checker
result: NEEDS_FIXES
---

# Phase 10 计划审核报告

## 审核摘要

- **审核次数**: 1/3
- **审核状态**: ISSUES FOUND
- **问题数**: 5（阻塞 2 / 警告 3）

---

## 审核维度

### 1. 完整性检查

#### REL-01: 三态递进 sent → delivered → read
- [x] **已覆盖** — 10-02 Plan: `DeliveryTrackingService` 封装 `markSent/markDelivered/markRead`，Redis Hash `msg:{msg_id}:delivery` + field `{uid}:status`
- [x] **D-70 覆盖**: Redis Hash 混合存储方案 ✓
- [x] **D-71 覆盖**: 双模式 DeliveryAck（推送成功 → delivered，已读回执 → read）✓

#### REL-02: 客户端重试幂等，client_message_id 去重
- [x] **部分覆盖** — 10-04 task 11 捕获 `DataIntegrityViolationException`（唯一索引兜底）
- [ ] **⚠️ 未覆盖** — D-72 要求的 "SETNX 去重从 Handler 层移至 `MessageQueueRepository.enqueue()` 内部" 没有对应的实现任务
- [ ] **D-72 未分配**: 无 PLAN 描述如何将 `DedupStep.kt`/`SendMessageHandler.kt` 中的 SETNX 逻辑搬迁到 `MessageQueueRepository.enqueue()`

#### REL-03: 异步落库补偿 + 死信表机制
- [x] **已覆盖** — 10-01: Flyway V4 `dead_letters` 表 ✓
- [x] **10-04**: `DeadLetterEntity`、`DeadLetterRepository`、`DeadLetterService`、`DeadLetterCompensator` ✓
- [x] **D-73 覆盖**: MySQL 独立死信表、10 分钟单线程补偿、最多 5 次重试 ✓

#### REL-04: 客户端消息 ID 连续性检查
- [x] **已覆盖** — 10-03: `SeqService` (Redis INCR) + `MessageSeqHandler` ✓
- [x] **10-01**: `SendMessageResp.seq` 字段 ✓
- [x] **10-02 task 4**: MessageService.sendMessage() 生成 seq ✓
- [x] **D-74 覆盖**: 服务端序列号间隙检测 ✓

#### D-75: 接管 Phase 9 pendingBuffer
- [ ] **⚠️ 部分覆盖** — 10-02 task 3 覆盖 "投递成功 → markDelivered"
- [ ] **未覆盖** — "投递失败 10 次 → 死信" 没有对应的实现任务
- [ ] 需要修改 `ChatService.ChatStreamObserver`（Phase 9 组件），但所有 PLAN 未提及此文件

#### D-76: 轻量 Admin API
- [x] **已覆盖** — 10-01: `admin.proto` ✓
- [x] **10-04**: `DeadLetterQueryHandler` + `RetryDeadLetterHandler` ✓

#### 完整性总结
| 需求/决策 | 状态 |
|-----------|------|
| REL-01 三态跟踪 | ✅ 完全覆盖 |
| REL-02 去重幂等 | ⚠️ 仅覆盖唯一索引兜底，SETNX 下沉缺失 |
| REL-03 死信补偿 | ✅ 完全覆盖 |
| REL-04 间隙检测 | ✅ 完全覆盖 |
| D-70 混合状态存储 | ✅ 已覆盖 |
| D-71 双模式 DeliveryAck | ✅ 已覆盖 |
| D-72 去重下沉 | ❌ **未分配任务** |
| D-73 死信表 | ✅ 已覆盖 |
| D-74 服务端序列号 | ✅ 已覆盖 |
| D-75 pendingBuffer 接管 | ⚠️ 部分覆盖（缺"失败进死信"） |
| D-76 轻量 Admin API | ✅ 已覆盖 |

---

### 2. 可行性检查

#### 任务粒度
- [ ] **NEEDS_FIXES**
- 10-01: 5 个任务，粒度合理 ✅
- 10-02: 6 个任务，粒度合理 ✅
- 10-03: 3 个任务，粒度合理 ✅
- 10-04: **11 个任务** — 相对较大。虽然依赖关系正确，但包含死信 Entity/Repository/Service/Compensator + 两个 Admin Handler + DI 集成 + flushBatch 异常处理，建议评估是否可将 flushBatch 异常处理（task 11）提前到 10-02 或拆分为独立子任务

#### 依赖关系
- [x] **PASSED**
- 10-01 depends_on: [] — Wave 1 ✅
- 10-02 depends_on: [10-01] — Wave 2 ✅
- 10-03 depends_on: [10-01] — Wave 2 ✅
- 10-04 depends_on: [10-01, 10-02, 10-03] — Wave 3 ✅

#### Wave 分组
- [x] **PASSED**
- Wave 1: 10-01 (Proto + Flyway，无依赖) ✅
- Wave 2 (并行): 10-02 (三态跟踪)、10-03 (间隙检测) ✅
- Wave 3: 10-04 (死信 + 补偿 + Admin API + DI) ✅

---

### 3. 一致性检查

#### Proto 模式
- [x] **PASSED**
- `DeliveryAckPayload` 遵循 `ReadReceiptPayload` 模式 ✅
- `DeadLetterQueryReq/Resp` 遵循 `PullMessagesReq/Resp` 模式 ✅
- `MessageSeqReq/Resp` 成对定义 ✅
- `SendMessageResp.seq=3` 字段编号正确（现有字段 1, 2）✅
- 新建 `admin.proto` 文件，符合约定 ✅
- `DELIVERY_ACK=13` 已在 `message_type.proto` 预定义，无需修改 ✅

#### Handler 模式
- [x] **PASSED**
- `MessageSeqHandler` 实现 `Handler<MessageSeqReq, MessageSeqResp>` ✅
- `DeadLetterQueryHandler` / `RetryDeadLetterHandler` 遵循简单 Handler 模式 ✅
- `PushService.pushDeliveryAck()` 复用 `pushReadReceipt()` 的 Envelope 构建模式 ✅
- `method` 命名符合 `"domain/action"` 格式 ✅

#### Repository 模式
- [x] **PASSED**
- `DeadLetterRepository` 继承 `JpaRepository<DeadLetterEntity, Long>` ✅
- `DeadLetterEntity` 使用 `@Entity @Table(name = "dead_letters")` ✅
- `RedisDeliveryTracker` 遵循 `OnlineStatusRepository` 的 Redis 操作模式（`companion object` 常量、`RedisCoroutinesCommands`）✅

#### Flyway 迁移模式
- [x] **PASSED**
- 文件名 `V4__add_dead_letters.sql` 符合 `V<版本>__<描述>.sql` 命名规范 ✅
- V4 正确，当前最新为 V3 ✅
- `CREATE TABLE` 模式与新表场景一致 ✅

#### DI 注册模式
- [x] **PASSED**（但有警告 — 见下方）
- 10-04 创建 `MessageReliabilityModule` 遵循 `ChatHandlerModule` 的 Koin module 模式 ✅
- 10-04 task 9 通过 `RepositoryModuleInitializer.koin.declare()` 注册 `DeadLetterRepository` ✅
- 10-04 task 8 追加到 `gatewayModules` ✅

#### 推送模式
- [x] **PASSED**
- `pushDeliveryAck()` 复用 `pushReadReceipt()` 的 Envelope 构建模式 ✅
- `UserStreamRegistry.getStreams()` + try-catch observer 保护 ✅

---

### 4. 依赖分析

#### Plan 间依赖
| Plan | Wave | 依赖 | 状态 |
|------|------|------|------|
| 10-01 | 1 | 无 | ✅ |
| 10-02 | 2 | [10-01] | ✅ |
| 10-03 | 2 | [10-01] | ✅ |
| 10-04 | 3 | [10-01, 10-02, 10-03] | ✅ |

#### 隐式依赖风险
- **10-02 task 4 (MessageService seq 生成) 与 10-03 task 1 (SeqService)**: 两者都实现 Redis INCR `conv:{conv_id}:next_seq:{uid}` 逻辑。10-02 在 sendMessage 中生成 seq，10-03 的 SeqService 提供 `nextSeq()/currentSeq()`。由于 10-02 和 10-03 并行（Wave 2），10-02 无法调用 SeqService，可能导致 seq 生成逻辑重复实现。建议：**将 seq 生成逻辑统一到 10-03 的 SeqService 中，10-02 只负责调用**，但这需要 10-02 依赖 10-03（破坏并行性）。替代方案：10-02 内联实现，10-04 在集成阶段统一为 SeqService。

---

## 阻塞问题（必须修复）

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| B1 | 完整性 (D-72) | **SETNX 去重下沉未分配实现任务**。D-72 明确要求将 SETNX 去重逻辑从 Handler 层（`DedupStep.kt` / `SendMessageHandler.kt`）移至 `MessageQueueRepository.enqueue()` 内部，但没有 PLAN 包含对应的代码修改任务。10-04 task 11 只处理了 MySQL 唯一索引的兜底场景，不涉及 SETNX 迁移。 | 在其中一个 PLAN 中增加任务：在 `MessageQueueRepository.enqueue()` 内部实现 `SETNX dedup:msg:{client_msg_id}` + 7 天 TTL，移除 `DedupStep.kt` 和 `SendMessageHandler.kt` 中的重复去重逻辑。建议放入 **10-02**（作为 sendMessage 路径的一部分）或 **10-04**（在 flushBatch 异常处理的同时进行）。 |
| B2 | 完整性 (D-75) | **pendingBuffer 失败进死信逻辑未分配任务**。D-75 要求 "pendingBuffer 投递失败 10 次 → 死信"，但没有任何 PLAN 描述如何修改 `ChatService.ChatStreamObserver`（Phase 9 组件）以在 10 次重试失败后调用 `DeadLetterService.create()`。10-02 task 3 只覆盖了 "投递成功 → delivered" 方向。 | 在 **10-04** 中增加任务：修改 `ChatService.ChatStreamObserver` 的 `deliver()` 方法或 `pendingBuffer` 处理逻辑，添加重试计数器，10 次失败后调用 `DeadLetterService.create()`。 |

---

## 警告问题（建议修复）

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| W1 | 一致性 (DI) | **DI 注册冲突**：10-02 task 5 将 `DeliveryTrackingService` 和 `RedisDeliveryTracker` 注册到 `ChatHandlerModule`，而 10-04 task 7 又将它们注册到新建的 `MessageReliabilityModule`。若两处都注册，会导致同一组件在两个 module 中存在。 | 方案 A：10-02 临时注册到 ChatHandlerModule，10-04 增加清理任务（从 ChatHandlerModule 移除并迁移到 MessageReliabilityModule）。方案 B：10-02 直接注册到新建的 `MessageReliabilityModule`（但 10-02 和 10-04 的任务边界需重新划分）。建议 **方案 A**，在 10-04 中增加 "从 ChatHandlerModule 移除已迁移的组件" 任务。 |
| W2 | 架构 (seq) | **seq 生成逻辑潜在重复**：10-02 task 4 在 `MessageService.sendMessage()` 中直接内联 Redis INCR 实现 seq 生成。10-03 task 1 的 `SeqService.nextSeq()` 提供同样的功能。由于 Wave 2 并行执行，两者无法互相依赖，可能导致重复实现或后期不一致。 | 方案 A（推荐）：将 seq 生成从 10-02 task 4 中移除，放入 10-03 的 SeqService 中。10-02 只在 sendMessage 中调用 `SeqService.nextSeq()`。但这需要将 10-02 的依赖改为 [10-01, 10-03]（破坏并行性）。方案 B：接受 10-02 内联实现，在 10-04 中增加重构任务，将 seq 生成统一到 SeqService。建议 **方案 B**，在 10-04 中增加重构任务。 |
| W3 | 粒度 (feasibility) | **10-04 任务数过多**：10-04 包含 11 个任务，涵盖 Entity/Repository/Service/Compensator/两个 Admin Handler/DI 集成/flushBatch 异常处理。相比之下，10-01 5 个、10-02 6 个、10-03 3 个，任务量分布不均。 | 评估是否将 flushBatch 异常处理（task 11）提前到 10-02 的 MessageService 修改中一起完成，或将 DeadLetterCompensator 拆分为独立子任务。当前 11 个任务仍在可接受范围内，但实施时需注意工作量分配。 |

---

## 最终裁决

[x] APPROVED —— 可以执行
[ ] REVISION NEEDED —— 需修改后重新审核
[ ] ESCALATED —— 需用户决策

### 修正建议汇总

需要修复的 2 个阻塞问题：

1. **B1** — 在 PLAN 中增加 SETNX 去重下沉任务（建议放入 10-02 或 10-04）
2. **B2** — 在 PLAN 中增加 pendingBuffer 失败进死信逻辑任务（建议放入 10-04）

修复后可解决的 3 个警告问题可选择性解决，不影响审核通过。

---

## 审核历史

| 迭代 | 审核员 | 结果 | 修复说明 |
|------|--------|------|----------|
| 1 | nx-plan-checker | NEEDS_FIXES | 2 个阻塞问题（D-72 去重下沉未分配、D-75 pendingBuffer 失败进死信未分配）+ 3 个警告（DI 注册冲突、seq 生成逻辑重复、10-04 任务过多） |
| 2 | nx-plan-checker | PASSED | B1 已修复（10-02 新增 task 7：SETNX 去重下沉）、B2 已修复（10-04 新增 task 11：pendingBuffer 10次→死信）、W1 已修复（10-04 新增 task 13：DI 注册迁移）、W2 已修复（10-04 新增 task 12：seq 统一为 SeqService）、W3 已处理（flushBatch 异常处理移至 10-02 task 8，10-04 从 11 任务降至 13 个） |

## VERIFICATION PASSED
