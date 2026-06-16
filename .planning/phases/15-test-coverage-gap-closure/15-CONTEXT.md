---
phase: 15
status: contexted
---

# Phase 15: 测试覆盖缺口闭合 — 上下文

## 阶段目标

闭合 2026-06-16 三模块（service/gateway/repository）全量测试审查中发现的 P0/P1/P2 级测试覆盖缺口，核心目标是**消除无测试覆盖的核心方法/组件**，并提升测试质量。

## 来源

本阶段需求来源于 `/.planning/quick/20260616-review-test-service-gateway-repository/` 的三份审查报告：
- `service-review.md` — 9 测试文件 / 7优2良
- `gateway-review.md` — 59 测试文件 / 22优24良9中
- `repository-review.md` — 6 测试文件 / 12 个分层问题

## 问题分级

### P0 — 严重缺失（无测试覆盖的核心组件/方法）

| 编号 | 模块 | 组件/方法 | 说明 | 风险 |
|------|------|-----------|------|------|
| P0-01 | repository | **SessionRepository** 7/8 方法无测试 | save/findByToken/delete/refreshTtl 等核心 session 操作方法完全无测试。session 管理是认证体系的核心链路 | 极高 |
| P0-02 | repository | **MessageRepository / MessageRepositoryImpl** 完全无测试 | 消息写入路径的核心组件，涉及 Redis→MySQL 异步落库 | 高 |
| P0-03 | repository | **DeadLetterRepository** 完全无测试 | Phase 10 死信补偿机制的数据层，影响消息可靠性 | 高 |
| P0-04 | repository | **PrivacyRepository** 完全无测试 | 隐私设置缓存双写（Redis + 字段冗余），涉及在线状态可见性 | 高 |
| P0-05 | repository | **MessageQueueRepository** 完全无测试 | Redis Stream 消息队列操作 | 高 |
| P0-06 | service | **ConversationService.dissolveGroup** 完全未测试 | 重要群组管理功能（第299-318行），涉及群解散+成员软删除 | 高 |

### P1 — 重要遗漏（关键方法/边界未测试）

| 编号 | 模块 | 组件/方法 | 说明 |
|------|------|-----------|------|
| P1-01 | service | **SeqService.recoverSequences** 未测试 | Redis 重启恢复关键路径（第121-143行，3 个 lambda 协作） |
| P1-02 | repository | **UserRepository.findByUsernameContaining** 游标分页未测试 | 最复杂的 @Query 方法（LIKE + 游标 + 排序） |
| P1-03 | repository | **ConversationRepository.findConversationsByUserId** 游标分页未测试 | 涉及子查询 JOIN + 游标 + 排序，复杂度高 |
| P1-04 | repository | **FriendshipRepository.findFriendsByUserId** 游标分页未测试 | 带游标参数版本 |
| P1-05 | repository | **ConversationMemberRepository.incrementUnreadCount** 未测试 | 未读数递增是消息系统核心功能 |
| P1-06 | repository | **ConversationMemberRepository** 4 个批量查询方法未测试 | findByConversationIdsAndUserId、softDeleteAllByConversationId 等 |
| P1-07 | repository | **FriendRequestRepository** 2 个方法未测试 | findByFromUidAndToUid（无 status 版）、findByToUidAndStatusOrderByCreatedAtDesc |
| P1-08 | service | **ConversationService** 3 个辅助方法未测试 | getConversation（第454行）、getConversationMembers（第470行）、getMemberRole（第487行） |
| P1-09 | service | **UserPrivacyService.batchGetHideOnlineStatus** 未测试 | Public API（第62-64行） |
| P1-10 | service | **MessageService** 3 个方法未测试 | checkAndSetDedup（第245行）、incrementUnreadCount（第255行）、countByConversationId（第232行） |
| P1-11 | service | **ConversationService.leaveGroup** memberCount==1 分支未测 | 最后一个成员退群自动解散群组的分支（第267-273行） |
| P1-12 | service | **UserService.register** DataIntegrityViolationException 兜底未测 | 并发注册 UNIQUE KEY 冲突兜底（第103行） |
| P1-13 | service | **MessageService.pullMessages** limit.coerceIn(1,100) 边界未测 | 分页参数边界 |
| P1-14 | service | **FriendService.listFriends** nextCursor 未断言验证 | 游标值未在测试中断言 |
| P1-15 | gateway | **Handler 层无 Session 异常路径缺失** | 仅 MessageSeqHandlerTest 覆盖了 token 无效->UNAUTHORIZED 场景 |
| P1-16 | gateway | **ReadReportHandlerTest 反射注入 mock Redis** | 私有字段反射注入导致测试与实现紧耦合 |
| P1-17 | gateway | **RedisDeliveryTrackerTest 反射注入 mock Redis** | 同上 |

### P2 — 可改进项（测试质量）

| 编号 | 模块 | 问题 | 说明 |
|------|------|------|------|
| P2-01 | gateway | **FlywayMigrationTest 未覆盖 V4/V5** | dead_letters 表、data_integrity 约束未验证 |
| P2-02 | service | **runBlocking 嵌套 runTest 反模式** | UserServiceTest、FriendServiceTest 中多处使用 |
| P2-03 | service | **断言风格不统一** | 混用 org.junit.jupiter.api.Assertions.* 和 kotlin.test.* |
| P2-04 | gateway | **LogInterceptorTest 实质性为空** | 仅 1 个测试只验证返回值透传 |
| P2-05 | gateway | **GatewayModuleTest 重复 startKoin** | 5 次独立 startKoin/stopKoin，开销大 |
| P2-06 | gateway | **ProtoCodecTest 反序列化验证不完整** | roundtrip 只 assertNotNull，未验证字段级一致性 |
| P2-07 | gateway | **PullMessagesHandlerTest cursor 值未验证** | 未验证传递给 service 的 cursor 是否为 Long.MAX_VALUE |
| P2-08 | repository | **MySQL 集成测试使用 Hibernate Session 非 Repository 接口** | 未验证 Spring Data 方法命名约定正确性 |
| P2-09 | repository | **唯一约束异常捕获类型过于宽泛** | 使用 assertFailsWith<Exception> 而非 DataIntegrityViolationException |
| P2-10 | service | **MessageServiceTest seqService mock 全局化** | @BeforeEach 设置全局返回 seq=1 |
| P2-11 | service | **MessageServiceTest 好友关系硬编码** | findByUserIdAndFriendId(1,2) 常量值重构风险 |

## 关联需求

本阶段为纯粹测试补充阶段，无新业务需求、无 Proto 变更、无 API 变更。

涉及修改范围：
- **repository 模块**: 新增测试文件（SessionRepositoryTest、MessageRepositoryTest、DeadLetterRepositoryTest、PrivacyRepositoryTest、MessageQueueRepositoryTest），补充现有集成测试
- **service 模块**: 补充 ConversationServiceTest、SeqServiceTest、MessageServiceTest、UserPrivacyServiceTest、UserServiceTest、FriendServiceTest
- **gateway 模块**: 修复反射注入，补充无 Session 异常测试，改进 FlywayMigrationTest

## 技术决策（待讨论）

| 决策编号 | 类别 | 问题 | 选项 |
|----------|------|------|------|
| D-15-01 | Redis 测试 | SessionRepository 等纯 Redis 组件如何测试？ | A: MockK 单元测试 / B: Testcontainers Redis 集成测试 / C: 两者结合 |
| D-15-02 | 集成测试 | MessageRepository (JPA) 集成测试策略 | A: 使用现有 Testcontainers MySQL + @DataJpaTest / B: 使用 Hibernate Session 模式（同现有模式） |
| D-15-03 | 反射注入 | ReadReportHandlerTest/RedisDeliveryTrackerTest 修复方案 | A: 改为构造函数注入（添加 @VisibleForTesting） / B: 保留反射注入 / C: 重构依赖注入方式 |
| D-15-04 | scope | P2 级别质量改进项是否纳入本阶段？ | A: 全部纳入 / B: 仅纳入高价值项（P2-01/P2-02） / C: 全部延期 v1.3 |
| D-15-05 | seqService mock | 全局化与独立设置权衡 | A: 坚持每个 Test 独立 mock（更精确） / B: 保持 @BeforeEach 全局设置并添加验证 |

## 实现约束

- **不修改 Proto 定义** — 无新增消息类型
- **不修改公开 API** — 无 Handler/Service 签名变更
- **不修改业务逻辑** — 仅补充测试代码
- **测试框架保持不变** — MockK + kotlinx.coroutines.test + Testcontainers
- **不引入新依赖**

## 成功标准

- [ ] 所有 P0 问题修复（6 项组件/方法新增测试覆盖）
- [ ] 至少 80% P1 问题修复（预计 16/17 项）
- [ ] P2 问题至少选择性修复 50%
- [ ] 全量构建通过：`./gradlew compileKotlin compileTestKotlin`
- [ ] 测试全部通过：service/gateway/repository 三模块测试绿
- [ ] 审查报告中标记的问题数下降 80%+
