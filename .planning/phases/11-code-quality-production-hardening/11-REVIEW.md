---
phase: 11
review_date: 2026-06-15
modules: common, repository, service, gateway, server
summary: "全量代码审查：85 个问题（24 HIGH / 36 MEDIUM / 25 LOW）跨越 5 个模块"
---

# Phase 11: Code Quality & Production Hardening — 审查清单

## 摘要

| 严重性 | 数量 | 关键词 |
|--------|------|--------|
| **HIGH** | 24 | 无事务保护、序列号无持久化、memberCount 非原子、去重未执行、payload 全路径丢失、无 Shutdown Hook、SSL 无预校验、无登录审计、@Volatile 缺失、魔法数字 |
| **MEDIUM** | 36 | 死代码 7 文件、SSL 弱模式、N+1 查询、协程阻塞、全量扫描、死信分页 bug、!! 空断言、DRY 违反 |
| **LOW** | 25 | 死代码残留、重复查询、魔法数字补充、异常类型清理、测试改进 |

## Wave 1 (P0 HIGH)：安全加固 + 数据一致性/竞态

### 11-01: 安全加固 (CQ-02, CQ-05, CQ-09)

| # | CQ | 文件 | 行号 | 问题 | 状态 | Plan |
|---|-----|------|------|------|------|------|
| H01 | CQ-02 | `common/.../datasource/HikariDataSourceProvider.kt` | 77-78 | MySQL JDBC `sslMode=PREFERRED` 允许降级明文 | ✅ | 11-01 |
| H02 | CQ-02 | `common/.../config/DatabaseConfig.kt` | 17-18 | password 仅注释约束，无环境变量强制校验 | ✅ | 11-01 |
| H03 | CQ-02 | `repository/.../config/RedisConfig.kt` | 26 | Redis 连接未启用 TLS (`withSsl(true)` 缺失) | ✅ | 11-01 |
| H04 | CQ-05 | `server/.../NebulaServer.kt` | 98-102 | 无 JVM Shutdown Hook，ChatServer.stop() 不可达 | ✅ | 11-01 |
| H05 | CQ-05 | `gateway/.../dispatcher/Dispatcher.kt` | 68-77 | ExceptionInterceptor 不覆盖 Handler 查找/反序列化异常 | ✅ | 11-01 |
| H06 | CQ-05 | `gateway/.../service/ChatService.kt` | 460 | connectionId 不在 gRPC 重连时刷新 | ✅ | 11-01 |
| H07 | CQ-09 | `common/.../config/SslConfig.kt` | 30-44 | SSL 证书文件无 File.exists() 预校验 | ✅ | 11-01 |
| H08 | CQ-09 | `server/.../NebulaServer.kt` | 68 | init 链无 try-catch 回滚机制 | ✅ | 11-01 |
| H09 | CQ-09 | `server/.../config/ConfigLoader.kt` | 44-101 | 无配置范围校验 + 无 File.exists() 检查 | ✅ | 11-01 |
| H10 | CQ-10 | `gateway/.../handler/user/LoginHandler.kt` | 30-44 | 无登录审计日志（成功/失败） | ✅ | 11-01 |
| H11 | CQ-10 | `server/.../server/ChatServer.kt` | 75 | `println()` 替代结构化日志 | ✅ | 11-01 |
| H12 | CQ-11 | `server/.../server/ChatServer.kt` | 28 | `@Volatile` 缺失导致 JMM 不可见 | ✅ | 11-01 |
| H13 | CQ-11 | `gateway/.../interceptor/RateLimitInterceptor.kt` | 36 | `userSemaphores` 无逐出机制导致缓慢泄漏 | ✅ | 11-01 |

### 11-02: 数据一致性与竞态 (CQ-03, CQ-04)

| # | CQ | 文件 | 行号 | 问题 | 状态 | Plan |
|---|-----|------|------|------|------|------|
| H14 | CQ-03 | `service/.../conversation/ConversationService.kt` | 113-117 | createGroup 跨两表写入无事务包裹 | ✅ | 11-02 |
| H15 | CQ-03 | `service/.../friend/FriendService.kt` | 91-143 | addFriend 双向竞赛路径跨 4 Repository 写入无事务 | ✅ | 11-02 |
| H16 | CQ-03 | `service/.../friend/FriendService.kt` | 198-239 | acceptFriendRequest 跨 4 Repository 写入无事务 | ✅ | 11-02 |
| H17 | CQ-03 | `service/.../conversation/ConversationService.kt` | 199-233 | inviteMember 跨两表无事务 | ✅ | 11-02 |
| H18 | CQ-03 | `service/.../conversation/ConversationService.kt` | 262-274 | leaveGroup 跨两表无事务 | ✅ | 11-02 |
| H19 | CQ-03 | `service/.../conversation/ConversationService.kt` | 311-323 | kickMember 跨两表无事务 | ✅ | 11-02 |
| H20 | CQ-03 | `gateway/.../conversation/ConversationLockManager.kt` | 36-39 | withLock() / TransactionTemplate 声明但生产未调用 | ✅ | 11-02 |
| H21 | CQ-04 | `service/.../sequence/SeqService.kt` | 1-92 | 序列号纯 Redis INCR，无持久化，无启动恢复 | ✅ | 11-02 |
| H22 | CQ-04 | `service/.../conversation/ConversationService.kt` | 226-233 | memberCount 使用 loadCount→set→save 非原子模式 | ✅ | 11-02 |
| H23 | CQ-04 | `repository/.../migration/V1__init_schema.sql` | 63-71 | friend_requests 表无 UNIQUE 约束防重复 | ✅ | 11-02 |
| H24 | CQ-04 | `repository/.../migration/V4__add_dead_letters.sql` | 12 | dead_letters `client_msg_id` 仅普通索引非 UNIQUE | ✅ | 11-02 |

## Wave 2 (P1 MEDIUM)：数据完整性与错误处理

### 11-03: 数据完整性与错误处理 (CQ-01, CQ-06, CQ-07, CQ-08, CQ-11, CQ-13)

| # | CQ | 文件 | 行号 | 问题 | 状态 | Plan |
|---|-----|------|------|------|------|------|
| M01 | CQ-01 | `gateway/.../send/DedupStep.kt` | 全文 | 死代码 Step 链 1/7 | ✅ | 11-03 |
| M02 | CQ-01 | `gateway/.../send/ValidateStep.kt` | 全文 | 死代码 Step 链 2/7 | ✅ | 11-03 |
| M03 | CQ-01 | `gateway/.../send/WriteStep.kt` | 全文 | 死代码 Step 链 3/7 | ✅ | 11-03 |
| M04 | CQ-01 | `gateway/.../send/FriendCheckStep.kt` | 全文 | 死代码 Step 链 4/7 | ✅ | 11-03 |
| M05 | CQ-01 | `gateway/.../send/SendMessageStep.kt` | 全文 | 死代码 Step 链 5/7 | ✅ | 11-03 |
| M06 | CQ-01 | `gateway/.../send/SendContext.kt` | 全文 | 死代码 Step 链 6/7 | ✅ | 11-03 |
| M07 | CQ-01 | `gateway/.../send/SendMessageException.kt` | 全文 | 死代码 Step 链 7/7 | ✅ | 11-03 |
| M08 | CQ-01 | `common/.../exception/SequenceOverflowException.kt` | 9 | 死代码异常类 | ✅ | 11-03 |
| M09 | CQ-06 | `service/.../admin/DeadLetterService.kt` | 142,205 | compensate/retry payload 硬编码空串 | ✅ | 11-03 |
| M10 | CQ-06 | `gateway/.../service/ChatService.kt` | 187 | createDeadLetter 保存 payload 但补偿路径未使用 | ✅ | 11-03 |
| M11 | CQ-06 | `repository/.../impl/MessageRepositoryImpl.kt` | 87-91 | DataIntegrityViolationException 时批量 XACK 但无死信记录 | ✅ | 11-03 |
| M12 | CQ-06 | `service/.../chat/MessageService.kt` | 122-131 | 消息异步路径 payload 序列化丢失 | ✅ | 11-03 |
| M13 | CQ-07 | `service/.../conversation/ConversationService.kt` | 202-205 | inviteMember for 循环内逐个查询（N+1） | ✅ | 11-03 |
| M14 | CQ-07 | `repository/.../repository/ConversationRepository.kt` | 13-40 | 缺少按 ID 列表批量查询方法 | ✅ | 11-03 |
| M15 | CQ-08 | `service/.../admin/DeadLetterService.kt` | 239-244 | query() 按状态查询时 total 取自未过滤的 findAll() | ✅ | 11-03 |
| M16 | CQ-08 | `service/.../admin/DeadLetterService.kt` | 255-260 | markPermanentFailed() failCount < 0 死查询 | ✅ | 11-03 |
| M17 | CQ-08 | `service/.../chat/MessageService.kt` | 103-105 | clientMessageId 去重注释声明但无代码执行 | ✅ | 11-03 |
| M18 | CQ-11 | `gateway/.../push/PushService.kt` | 58,174 | blocking JPA 在协程上下文直接调用 + TODO 标记 | ✅ | 11-03 |
| M19 | CQ-11 | `repository/.../impl/MessageRepositoryImpl.kt` | 116 | 独立 CoroutineScope 创建但 stop() 不 cancel | ✅ | 11-03 |
| M20 | CQ-13 | `repository/.../redis/MessageQueueRepository.kt` | 112-123 | checkAndSetDedup() 声明但发送路径未调用 | ✅ | 11-03 |
| M21 | CQ-13 | `gateway/.../send/DedupStep.kt` | 23-25 | 去重 Step 退化为 no-op | ✅ | 11-03 |
| M22 | CQ-13 | `service/.../chat/MessageService.kt` | 131 | payload 在 Redis Stream 写入时为 "" | ✅ | 11-03 |
| M23 | CQ-13 | `gateway/.../send/WriteStep.kt` | 68-71 | payload 写入 Redis Stream 时缺失 | ✅ | 11-03 |
| M24 | CQ-04 | `gateway/.../send/SendMessageHandler.kt` | 88-94 | 未读计数仅存 Redis，未持久化到 DB unreadCount | ✅ | 11-03 |
| M25 | CQ-08 | `repository/.../repository/ConversationMemberRepository.kt` | 28-37 | incrementUnreadCount() 声明但未被使用（死代码） | ✅ | 11-03 |
| M26 | CQ-06 | `repository/.../redis/PrivacyRepository.kt` | 124-128 | userRepository.save() 失败静默吞掉 | ✅ | 11-03 |
| M27 | CQ-08 | `service/.../conversation/ConversationService.kt` | 199-236 | inviteMember 中已存在成员被跳过但 memberCount 正确更新 | ✅ | 11-03 |
| M28 | CQ-08 | `service/.../admin/DeadLetterService.kt` | 262-265 | 补偿全量加载 RETRYING 记录到内存扫描 | ✅ | 11-03 |
| M29 | CQ-08 | `gateway/.../send/SendMessageHandler.kt` | 58,88 | 成员列表 pushMessage 和 asyncUnread 分别查询 | ✅ | 11-03 |
| M30 | CQ-11 | `gateway/.../send/SendMessageHandler.kt` | 73 | launch{} 非结构化协程 — fire-and-forget 异步 | ✅ | 11-03 |

## Wave 3 (P2 LOW)：代码质量与测试加固

### 11-04: 代码质量与测试加固 (CQ-10, CQ-12, CQ-14, CQ-15)

| # | CQ | 文件 | 行号 | 问题 | 状态 | Plan |
|---|-----|------|------|------|------|------|
| L01 | CQ-12 | `repository/.../entity/ConversationEntity.kt` vs `V1__init_schema.sql` | 15 / 15 | Entity 注释 `0=私聊`，SQL COMMENT `1=私聊` — 值矛盾 (HIGH) | ✅ | 11-04 |
| L02 | CQ-12 | `repository/.../entity/ConversationEntity.kt` | 15,39 | type/status 用 Int 魔法数字 | ✅ | 11-04 |
| L03 | CQ-12 | `repository/.../entity/UserEntity.kt` | 34 | privacyStatus 用 Int 魔法数字 | ✅ | 11-04 |
| L04 | CQ-12 | `repository/.../entity/FriendRequestEntity.kt` | 23 | status 用 Int 魔法数字 | ✅ | 11-04 |
| L05 | CQ-12 | `repository/.../entity/FriendshipEntity.kt` | 31 | deleted 用 Int 魔法数字 | ✅ | 11-04 |
| L06 | CQ-12 | 6+ 文件 | 多处 | `member.deleted == 0/1` 无命名常量 | ✅ | 11-04 |
| L07 | CQ-14 | `common/.../idgen/SnowflakeIdGeneratorTest.kt` | 123-126 | 反射操作 private 字段模拟时钟回拨 | ✅ | 11-04 |
| L08 | CQ-15 | `service/.../chat/MessageService.kt` | 252 | !! 空断言 `id!!` | ✅ | 11-04 |
| L09 | CQ-15 | `service/.../user/UserService.kt` | 92,120,157,183,212 | !! 空断言 5 处 `user.id!!` | ✅ | 11-04 |
| L10 | CQ-15 | `service/.../conversation/ConversationService.kt` | 151,164 | !! 空断言 2 处 `id!!` | ✅ | 11-04 |
| L11 | CQ-15 | `gateway/.../service/ChatService.kt` | 253,312 | !! 空断言 `merge().!!` 高风险 NPE | ✅ | 11-04 |
| L12 | CQ-15 | `repository/.../config/JpaConfig.kt` | 47 | !! 空断言 `emfBean.getObject()!!` | ✅ | 11-04 |
| L13 | CQ-15 | 4+ 文件 | 多处 | `member == null \|\| member.deleted == 1` 重复 4+ 次 → 提取 `isActive()` 扩展 | ✅ | 11-04 |
| L14 | CQ-15 | 4+ 文件 | 多处 | `withContext(IO) { conversationRepository.findById().orElse(null) }` 重复 9 次 → 提取 `findByIdOrNull()` | ✅ | 11-04 |
| L15 | CQ-15 | 5+ 文件 | 多处 | `friendship.deleted == 0/1` 重复 5+ 次 → 提取 `isActive()` 属性 | ✅ | 11-04 |
| L16 | CQ-15 | `gateway/.../send/DedupStep.kt` | 13-15 | 废弃构造函数参数标记 `@Suppress("UNUSED_PARAMETER")` | ✅ | 11-04 |
| L17 | CQ-15 | `gateway/.../dispatcher/Dispatcher.kt` | 48 | `private val scope` **已删除**（死代码字段，ChatService.scope 承担全局协程生命周期管理，Dispatcher 无需自有 scope） | ✅ | 11-04 |
| L18 | CQ-15 | `common/.../exception/SendMessageException.kt` | 全文 | 死代码异常类（与 CQ-01 一起清理） | ✅ | 11-04 |
| L19 | CQ-15 | `common/.../exception/ChatException.kt` + `MessageException.kt` | 全文 | 同一 Service 分裂使用两个异常类 | ✅ | 11-04 |
| L20 | CQ-12 | `repository/.../entity/ConversationEntity.kt` | 17 | type 字段在 KDoc 和 SQL 中值矛盾 | ✅ | 11-04 |

## 测试代码专项审查（11-04 扩展）

| # | 文件 | 行号 | 问题 | 状态 |
|---|------|------|------|------|
| T01 | `common/.../idgen/SnowflakeIdGeneratorTest.kt` | 123-126 | 反射操作 private 字段 | ✅ |
| T02 | `gateway/.../send/*Step*Test.kt` | 全文 | Step 链死代码对应的测试文件需要清理 | ✅ |
| T03 | `service/.../friend/FriendServiceTest.kt` | 全文 | 缺少双向竞赛并发测试 | ✅ |
| T04 | `service/.../conversation/ConversationServiceTest.kt` | 全文 | 缺少 memberCount 并发更新测试 | ❌ 遗留（需嵌入式 Redis） |
| T05 | `service/.../admin/DeadLetterServiceTest.kt` | 全文 | 缺少 payload 补偿路径测试 | ❌ 遗留（需嵌入式 Redis） |
| T06 | `service/.../sequence/SeqServiceTest.kt` | 全文 | 缺少 Redis 重启恢复测试 | ❌ 遗留（需嵌入式 Redis） |
| T07 | `gateway/.../handler/user/LoginHandlerTest.kt` | 全文 | 缺少审计日志验证 | ✅ |

## 统计

| Plan | HIGH | MEDIUM | LOW | 合计 |
|------|------|--------|-----|------|
| 11-01 (安全加固) | 13 | 0 | 0 | 13 |
| 11-02 (数据一致性/竞态) | 11 | 0 | 0 | 11 |
| 11-03 (数据完整性与错误处理) | 0 | 30 | 0 | 30 |
| 11-04 (代码质量与测试) | 0 | 0 | 20 | 20 |
| 11-04 (测试专项) | 0 | 0 | 7 | 7 |
| **合计** | **24** | **30** | **27** | **81** |
