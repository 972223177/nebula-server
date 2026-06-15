---
phase: 11
status: contexted
---

# Phase 11: Code Quality & Production Hardening — 上下文

## 阶段目标

基于 2026-06-15 全量代码审查结果，修复所有 81 个问题（24 HIGH / 30 MEDIUM / 27 LOW），使项目达到 v1 生产就绪状态。

## 关联需求

全部 15 个 CQ 需求：CQ-01 ~ CQ-15

| 需求 | 内容 | 严重性 | Plan |
|------|------|--------|------|
| CQ-01 | 跨模块死代码清理（SendMessageStep 链 7 文件 + SequenceOverflowException + Dispatcher.scope）[已清除：Dispatcher.scope 字段在 Phase 11 执行后被删除，协程生命周期管理由 ChatService.scope 承载] | HIGH | 11-03 |
| CQ-02 | 生产安全加固（Redis 认证 + SSL + DatabaseConfig 密码泄露 + 环境变量注入） | HIGH | 11-01 |
| CQ-03 | 服务层事务保护（ConversationService/FriendService 跨 Repository 写入） | HIGH | 11-02 |
| CQ-04 | 数据一致性与竞态修复（好友双向、SeqService、memberCount 原子） | HIGH | 11-02 |
| CQ-05 | 可靠性基础设施（Shutdown Hook + 全局异常处理 + ConnectionId 刷新） | HIGH | 11-01 |
| CQ-06 | 数据丢失修复（repository 唯一索引冲突 → 死信 + compensate payload + 异步序列化） | HIGH | 11-03 |
| CQ-07 | N+1 查询消除（ConversationService inviteMember/createGroup + repository JOIN） | MEDIUM | 11-03 |
| CQ-08 | 业务逻辑修复（DeadLetterService 分页总数 + markPermanentFailed + MessageService 未读计数） | MEDIUM | 11-03 |
| CQ-09 | 启动健壮性（SSL 预校验 + init 链回滚 + ConfigLoader 校验） | MEDIUM | 11-01 |
| CQ-10 | 日志与可观测性（println → Logger + 审计日志 + 配置提示） | LOW | 11-01 + 11-04 |
| CQ-11 | 协程与线程安全（PushService blocking JPA + ChatServer @Volatile + launch + RateLimiter 泄漏） | MEDIUM | 11-03 |
| CQ-12 | 状态管理一致性（conversations.type 矛盾 + 好友状态魔法数字 + 会话类型枚举） | LOW | 11-04 |
| CQ-13 | 消息可靠性增强（clientMessageId 去重下沉 + Redis/MySQL 原子性 + unique constraint） | HIGH | 11-03 |
| CQ-14 | 测试健壮性（Clock 接口抽取 + mock 改进 + SnowflakeIdGenerator 反射替代） | LOW | 11-04 |
| CQ-15 | 代码质量清理（!! 空断言替换 + 死代码移除 + DRY + 异常类型精简） | LOW | 11-04 |

## 技术决策

### D-76：执行策略 — 逐个修复 + 波级全量测试

**决策**：每个修复独立 commit + 模块级测试 → 每波完成后全量回归测试。

**理由**：细粒度回滚 + 波级验证平衡效率与安全。维持 ROADMAP.md 预设的 3 Wave 分组（Wave 1: HIGH → Wave 2: MEDIUM → Wave 3: LOW）。

### D-77：Redis 认证 — 分环境控制

**决策**：开发环境免密码 + 生产要求密码（无默认值）。密码通过 HOCON `${?REDIS_PASSWORD}` 语法注入。

**理由**：本地开发便利 + 生产强制安全，无中间状态。

### D-78：密钥注入 — HOCON `${?VAR}` 语法

**决策**：所有密码/密钥统一使用 HOCON `${?VAR}` 语法从环境变量读取，不引入额外库（dotenv 等）。

**理由**：TypeSafe Config 原生支持，不需要新增依赖。

### D-79：事务保护 — TransactionTemplate（沿用 Phase 7）

**决策**：ConversationService 和 FriendService 跨 Repository 写入使用 `TransactionTemplate` 包裹，与 Phase 7 引入的 `ConversationLockManager` 风格一致。

**理由**：避免 Spring `@Transactional` AOP 复杂性，保持项目事务管理方式统一。

### D-80：好友双向竞赛 — DB 唯一约束 + 幂等 catch

**决策**：`friendships` 表加 `UNIQUE(user_smaller, user_larger)`。`addFriend()` 中 catch `DuplicateKeyException` 后幂等返回。

**理由**：改动最小（1 行 DDL + 1 个 try-catch），数据库自身保证唯一性，无需应用层锁。

### D-81：SeqService 序列号持久化 — 启动时从消息表恢复

**决策**：不修改 SeqService 运行时逻辑。`NebulaServer.init()` 启动时遍历活跃会话，从 MySQL `messages` 表取 `MAX(seq) + 1` 初始化 Redis 序列号。

**理由**：运行时零性能损失（Redis INCR 不变），改动集中在启动逻辑。重启前所有消息已在 MySQL，序列号起点可靠。

### D-82：memberCount — JPQL 原子更新

**决策**：`ConversationRepository` 新增 `@Modifying @Query("UPDATE ... SET memberCount = memberCount + :delta")`。替换 `inviteMember()/leaveGroup()/kickMember()` 中 `loadCount → set → save` 为原子更新。

**理由**：单条原子 UPDATE 语句，数据库侧保证一致性，无需应用层锁。改动最小（1 个 Repository 方法 + 3 个 Service 调用点替换）。

### D-83：N+1 查询修复 — JOIN + 正确性+性能双重验证

**决策**：`ConversationService.inviteMember/createGroup` 的 N+1 查询改为 JPQL JOIN 一次加载。补充单元测试对比修复前后结果一致性 + 记录查询次数/时间对比。

**理由**：不仅消除性能问题，也验证逻辑等价性。

### D-84：JPA 阻塞修复 — withContext(Dispatchers.IO)

**决策**：PushService 中 JPA 操作包装 `withContext(Dispatchers.IO)`，与 Phase 5/6 修复一致。

**理由**：保持项目统一的 JPA 阻塞处理策略。

### D-85：协程管理 — ApplicationScope + SupervisorJob()

**决策**：非结构化 `launch` 改为使用现有 `ApplicationScope` + `SupervisorJob()` 管理协程生命周期。

**理由**：避免协程泄漏，统一生命周期管理。

### D-86：空断言替换 — 按上下文选择

**决策**：参数校验 → `requireNotNull()`，内部逻辑 → `?: error("message")`，可选/降级 → `?: return` / `?: throw BizException`。

**理由**：不同场景需要不同的异常语义和降级策略，一刀切不合理。

### D-87：项目完成标准

**决策**：所有 257+ 测试通过 + 81 个问题全部关闭 + 无新增 HIGH/MEDIUM 警告。Phase 11 完成后追溯修复 Phase 1/2/3 的 STATE 标记状态。

### D-88：Verify 阶段测试代码审查

**决策**：`/nx-verify 11` 阶段除验证生产代码修复外，还需审查修复涉及的现有测试代码：
- 死代码清理后同步清理对应测试文件
- 竞态修复后补充并发场景测试
- payload 修复后补充补偿路径测试
- SeqService 恢复逻辑补充恢复测试
- LoginHandler 审计日志补充日志验证测试

**理由**：修复可能改变接口签名或行为语义，现有测试若未同步更新则产生假阴性。避免"修复通过但测试未覆盖"的盲区。

## 审查清单

详见 `11-REVIEW.md`：81 个具体问题（24 HIGH / 30 MEDIUM / 27 LOW），按 4 个 Plan / 3 个 Wave 分组，包含测试代码专项审查 7 项。

## 实现约束

- **语言**：Kotlin，不使用 Java
- **事务**：TransactionTemplate（非 @Transactional）
- **协程**：withContext(Dispatchers.IO) 处理 JPA 阻塞
- **配置**：HOCON `${?VAR}` 注入密钥
- **数据库**：DDL 修改需 Flyway migration，不手动改表
- **测试**：每个修复均有专项单元测试，波级全量回归

## 灰区已解决

1. 执行策略与回归测试（逐个修复 + 波级全量测试 + 专项测试覆盖）
2. 安全性修复边界（Redis 分环境 + HOCON 注入 + SSL sslMode）
3. 事务保护策略（TransactionTemplate 沿用）
4. N+1 修复验证深度（正确性 + 性能双重验证）
5. 协程/线程安全修复（withContext(IO) + ApplicationScope）
6. 空断言替换策略（按上下文选择）
7. 修复优先级与分批策略（维持 3 Wave 分组）
8. 项目完成标准（全测试通过 + 81 问题关闭 + STATE 追溯更新）
9. 竞态修复方案（DB 唯一约束 + 启动恢复 + JPQL 原子更新）
10. Verify 阶段测试代码审查（修复后同步审查测试代码，补缺测试项）
11. 审查清单落地（3 agent 并行 → 81 问题清单 <11-REVIEW.md>）

## 灰区遗留

无 — 所有灰区已充分讨论并决策。
