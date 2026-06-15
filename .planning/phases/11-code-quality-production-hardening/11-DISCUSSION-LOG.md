---
phase: 11
discussion_status: completed
started: 2026-06-15
updated: 2026-06-15
---

# Phase 11: Code Quality & Production Hardening — 讨论日志

## 灰区 1：执行策略与回归测试

**决策**：逐个修复 + 独立 commit + 波级全量测试，每个修复补充对应的专项测试。

- 每个修复一个独立 commit，方便回滚
- 修复后执行涉及模块的测试（模块级验证）
- 每波 Wave 完成后执行全量测试（257+ 测试）
- 维持 ROADMAP.md 预设的 3 Wave 分组

## 灰区 2：安全性修复边界

**决策**：
- Redis 认证：开发环境免密码 + 生产要求密码（无默认值），分环境控制
- SSL sslMode：`application.conf` 新增 `ssl.mode` 字段，dev/prod 区分
- 密钥注入方式：HOCON `${?VAR}` 语法读取环境变量

## 灰区 3：事务保护策略

**决策**：沿用 Phase 7 引入的 `TransactionTemplate`，避免 Spring `@Transactional` AOP 复杂性。

- ConversationService、FriendService 跨 Repository 写入用 TransactionTemplate 包裹
- 保证 createGroup/inviteMember/kickMember/leaveGroup + addFriend/acceptFriend 的原子性

## 灰区 4：N+1 修复与查询改写

**决策**：改 JOIN + 补充单元测试验证结果正确性 + 性能对比。

- `ConversationService.inviteMember/createGroup` 的 N+1 查询改为 JPQL JOIN 一次加载
- 补充单元测试对比修复前后的查询结果一致性
- 记录性能对比（查询次数/时间）

## 灰区 5：协程/线程安全修复

**决策**：
- PushService JPA 阻塞修复：`withContext(Dispatchers.IO)`（与 Phase 5/6 一致）
- 非结构化 `launch` 修复：使用现有 `ApplicationScope + SupervisorJob()`
- ChatServer `@Volatile server` 修复：按 CQ-11 审查结论处理

## 灰区 6：空断言替换策略

**决策**：按上下文选择替换方式。
- 参数校验 → `requireNotNull(x)` (IllegalArgumentException)
- 内部逻辑 → `?: error("message")` (IllegalStateException)
- 可选/降级 → `?: return` 或 `?: throw BizException`

## 灰区 7：修复优先级与分批策略

**决策**：维持 ROADMAP.md 预设的 3 Wave 分组。

- Wave 1 (P0 HIGH): 11-01 安全加固 + 11-02 数据一致性/竞态
- Wave 2 (P1 MEDIUM): 11-03 数据完整性与错误处理
- Wave 3 (P2 LOW): 11-04 代码质量与测试加固

## 灰区 8：项目完成标准

**决策**：
- 项目状态：Phase 11 完成后追溯修复未完成阶段（Phase 1/2/3 的 STATE 标记调整）
- 验证标准：所有 257+ 测试通过 + 85 个问题全部关闭 + 无新增 HIGH/MEDIUM 警告

## 额外灰区：竞态修复方案（CQ-04）

### FriendService 双向竞赛
**决策**：DB 唯一约束 + 幂等 catch

- `friendships` 表加 `UNIQUE(user_smaller, user_larger)`
- `addFriend()` catch `DuplicateKeyException` 后幂等返回
- 改动量最小：1 行 DDL + 1 个 try-catch

### SeqService 序列号
**决策**：启动时从 MySQL 消息表恢复

- 不修改 SeqService 运行时逻辑（保持 Redis INCR 性能）
- `NebulaServer.init()` 启动时遍历活跃会话，`MAX(seq) + 1` 初始化 Redis
- 运行时零性能损失

### Conversation memberCount
**决策**：JPQL 原子更新

- `ConversationRepository` 新增 `@Modifying @Query("UPDATE ... SET memberCount = memberCount + :delta")`
- 替换 `inviteMember()/leaveGroup()/kickMember()` 中 `loadCount → set → save` 逻辑
- 单条原子 UPDATE，无锁

## 额外灰区：Verify 阶段测试代码审查

**决策**（2026-06-15 补充）：

在 `/nx-verify 11` 阶段，除验证修复后的生产代码外，还需对修复涉及的现有测试代码进行审查：
- 死代码 Step 链对应的 `*Step*Test.kt` 需要同步清理
- FriendService 竞态修复后需补充双向竞赛并发测试
- ConversationService memberCount 修复后需补充并发更新测试
- DeadLetterService payload 修复后需补充补偿路径测试
- SeqService 启动恢复逻辑需补充恢复测试
- LoginHandler 审计日志需补充日志验证测试

**理由**：修复代码可能改变接口签名或行为语义，现有测试若未同步更新则产生假阴性。

## 审查清单落地

**完成**（2026-06-15）：通过 3 个并行 agent 重新审查 5 个模块，生成 `11-REVIEW.md`：
- 81 个问题（24 HIGH / 30 MEDIUM / 27 LOW）
- 按 4 个 Plan / 3 个 Wave 分组
- 包含测试代码专项审查（7 项）
