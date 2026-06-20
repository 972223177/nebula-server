---
description: Plan A 提交后审查发现的 14 个未解决问题，分 6 个 Phase 按优先级修复
status: in-progress
created: 2026-06-20
research: 联网调研了 JPA persist vs merge、withContext 性能、Outbox Pattern
---

# Plan A 修复 Quick Task

## 上下文

Plan A（移除 Spring Data JPA，替换为纯 Hibernate + DAO）已在 commit `2f9e74e` 提交。
本任务针对审查发现的 14 个未解决问题，按严重度分 6 个 Phase 修复。

## 调研结论（业内做法）

### 1. JPA persist vs merge（影响 Phase 1）

**业界共识**（Baeldung, 阿里云, 多课网）：
- **托管实体（managed）直接改属性即可**，commit 时 Hibernate 脏检查自动生成 UPDATE
- 对**已托管实体**调 `em.merge()` 是反模式：
  - 多一次 SELECT（验证存在性）
  - 复制对象状态
  - 修改原引用可能丢失（merge 返回新托管实例）
- `em.merge()` 正确用法：
  - 游离态（detached）实体重新纳入
  - 不确定状态时的兜底
  - **必须使用返回值**

**Nebula 现状问题**（`FriendService.kt:96-110, 196-207`）：
```kotlin
val request = friendRequestDao.findById(em, requestId)  // request 现在是托管实体
request.status = 1                                        // 直接改属性即可
em.flush()                                                // 强制刷盘
friendRequestDao.update(em, request)                      // 错误：对托管实体 merge
```
正确写法：直接 set 字段，让 commit 时脏检查自动 flush。

### 2. withContext 性能开销（影响 Phase 2）

**调研结论**（掘金 · 调度器的艺术）：
- 每次 `withContext` 涉及 3 步：挂起当前协程 → 调度器切换 → 恢复
- 嵌套 `withContext` 有**叠加**开销（N 次挂起 + N 个 DispatchedTask + N 次队列操作）
- 相同 Dispatcher 默认仍有调度开销（除非重写 `isDispatchNeeded` 返回 false）

**Nebula 现状问题**（`EntityDao.kt:214-215`）：
```kotlin
protected suspend inline fun <R> io(crossinline block: () -> R): R =
    withContext(Dispatchers.IO) { block() }
```
而 `JpaTxRunner.execute` 已经在 `withContext(Dispatchers.IO)` 块内。
**每次 DAO 调用多一次 withContext 调度**。

**修复方向**（按推荐顺序）：
- 方案 A（首选）：`EntityDao.io` 移除，文档说明"调用方负责在 IO 线程上调用"
- 方案 B：TxRunner 暴露内部 dispatcher，DAO 用它而不 wrap

### 3. Transactional Outbox Pattern（影响 Phase 3）

**业界共识**（AWS Prescriptive Guidance, CSDN 调研）：
- 解决"DB 写 + 消息队列"双写一致性问题
- 核心：业务表 + outbox 表**同事务写入**
- Relay 异步投递，状态机管理（Pending → Sent / Failed）
- 消费方必须**幂等**（msg_id 业务主键 + 去重表）

**IM 系统典型架构**（AWS + CSDN 综合）：
```
事务内：写 message 表 + 写 outbox 表
↓
@AfterCommit 触发 → 或定时 Relay 扫描
↓
Relay: XADD 到 Redis Stream → 标记 status='SENT'
↓
Push Worker: XREADGROUP → 推送 → XACK
                  ↓ 失败
              PEL (Pending Entries List) → XCLAIM 接管
```

**Nebula 现状问题**（`MessageService.sendMessage`）：
- 跨 3 个独立事务（DB 验证 → Redis enqueue → DB 更新元信息）
- enqueue 在事务外，失败不回滚
- enqueue 成功但元信息更新失败 → UI 不一致

**修复方案**：
- 方案 A（完整 outbox）：加 `outbox` 表 + Relay 进程，复杂但严格一致
- 方案 B（轻量兜底）：保持现状，加补偿机制：enqueue 成功后在 DB 写"enqueued"标记，独立 worker 扫描
- 方案 C（顺序调整）：先 enqueue Redis → 失败抛错 → 然后**同一个事务**写 DB 元信息；如果元信息失败，标记 Redis Stream 条目为"撤回"

考虑到 Nebula 是中小型项目（作者自己也说"中小型不必引入 outbox"），**推荐方案 C + 客户端幂等**。

## 任务列表（按优先级）

### Phase 1: P0 Bug 修复（必须修）

#### Task 1.1: 修复 FriendService 中 em.merge on 托管实体的反模式
- 位置：`FriendService.kt:96-110, 196-207`
- 改动：
  - 删除 `em.flush()` 显式调用（commit 时自动）
  - 删除 `dao.update(em, request)` 对托管实体的 merge 调用
  - 改用：直接改属性，让脏检查处理
- 验证：双向竞赛场景集成测试
- 预计改动：~10 行

#### Task 1.2: 修复 listFriends / getGroupMembers 拆事务问题
- 位置：`FriendService.kt:299-323`、`ConversationService.kt:438-453`
- 改动：合并为单事务
- 验证：现有 handler 测试 + 新增并发场景
- 预计改动：~20 行

### Phase 2: P1 性能优化

#### Task 2.1: EntityDao 移除嵌套 withContext(IO)
- 位置：`EntityDao.kt:214-215`
- 改动：删除 `io { }` 包装，文档说明"调用方在 IO 线程调用"
- 验证：所有 dao 集成测试 + 单元测试通过
- 预计改动：~5 行

#### Task 2.2: JpaTxRunner 加 metrics 埋点
- 位置：`JpaTxRunner.kt:83-105`
- 改动：使用 Micrometer Timer 记录 block 耗时
- 验证：测试中可注入 fake metrics
- 预计改动：~30 行

### Phase 3: P1 sendMessage 双写一致性

#### Task 3.1: 顺序调整 sendMessage（方案 C：enqueue 先于 DB）
- 位置：`MessageService.kt:69-146`
- 改动：
  - Step 2-3（成员验证）单事务
  - Step 4（enqueue Redis）— **不在事务中**，失败抛错
  - Step 5（更新元信息）单事务，enqueue 失败时跳过此步
  - **关键**：客户端 `clientMessageId` 已做去重，重试安全
- 验证：集成测试模拟 enqueue 失败
- 预计改动：~30 行

### Phase 4: P2 文档/可读性

#### Task 4.1: JpaTxRunner KDoc 强化设计契约
- 位置：`JpaTxRunner.kt:12-64`
- 改动：明确禁止 `withContext(其他Dispatcher)` 在 block 内，加 WARN 注释
- 预计改动：~20 行

#### Task 4.2: 补齐 DeadLetterDao.findAllOrderByCreatedAt
- 位置：`DeadLetterService.kt:248-265` 引用的 DAO 方法
- 改动：DAO 加 `findAllOrderByCreatedAt(em, offset, limit)`
- 预计改动：~20 行

#### Task 4.3: UserService.register 中 user.id 校验提前
- 位置：`UserService.kt:98-105`
- 改动：把 `requireNotNull(user.id)` 移到事务内
- 预计改动：~5 行

#### Task 4.4: markPermanentFailed 改批量 update
- 位置：`DeadLetterService.kt:301-318`
- 改动：用 `executeUpdate` 一次更新所有超限记录
- 预计改动：~20 行

### Phase 5: P3 边缘 case

#### Task 5.1: searchUsers 游标改用 id 排序
- 位置：`UserService.kt:157-189` 附近
- 改动：游标从毫秒时间戳改为 id，分页用 `WHERE id < cursor` 替代毫秒比较
- 预计改动：~30 行

#### Task 5.2: listConversations 同样改 id 游标
- 位置：`ConversationService.kt:148-183`
- 改动：同 5.1
- 预计改动：~30 行

### Phase 6: 全量回归 + 提交

#### Task 6.1: 跑全量测试（集成 + 单元）
#### Task 6.2: 原子提交

## 验收标准

- ✅ 所有现有测试通过（491 → ≥491）
- ✅ 新增的修复点测试覆盖
- ✅ git log 包含所有 phase 的独立提交

## 风险

- **Phase 1 风险低**：纯 bug 修复，已有测试覆盖
- **Phase 2 风险低**：性能优化
- **Phase 3 风险中**：sendMessage 是核心路径，需要仔细回归
- **Phase 4-5 风险低**：文档/边缘 case

## 不在本次范围

- 完整的 Transactional Outbox 改造（Phase 3 只做最小改动）
- Saga 模式（AWS 推荐的跨服务事务方案，Nebula 暂不需要）
- Debezium CDC 集成（运维成本高，目前不需要）
