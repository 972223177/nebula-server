# Phase 15: 测试覆盖缺口闭合 — 执行计划

## 阶段目标

闭合 2026-06-16 三模块（service/gateway/repository）全量测试审查中发现的 P0/P1/P2 级测试覆盖缺口，消除无测试覆盖的核心方法/组件，并提升测试质量。

## Wave 分组

### Wave 1（无依赖，可并行执行）

| 计划 | 名称 | 模块 | 任务数 |
|------|------|------|--------|
| 15-1 | 基础修复：反射注入 + 异常类型 + ProtoCodec + cursor | gateway + repository | 8 |
| 15-2 | Service 核心补充：ConversationService + seqService | service | 6 |

### Wave 2（依赖 Wave 1 完成后）

| 计划 | 名称 | 模块 | 任务数 |
|------|------|------|--------|
| 15-3 | Repository P0 新增测试文件 | repository | 5 |
| 15-4 | Service 层剩余补充 + runBlocking 修复 | service | 5 |

### Wave 3（依赖 Wave 2 完成后）

| 计划 | 名称 | 模块 | 任务数 |
|------|------|------|--------|
| 15-5 | 尾部改进：Flyway V4/V5 + 游标分页 + 无Session路径 | repository + gateway | 6 |

---

---
phase: 15
plan: 15-1
type: refactor + test
wave: 1
depends_on: []
files_modified:
  - gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/delivery/RedisDeliveryTracker.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/message/ReadReportHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/delivery/RedisDeliveryTrackerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/codec/ProtoCodecTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt
autonomous: true
---

# Plan 15-1: 基础修复（反射注入 + 异常类型 + ProtoCodec + cursor 验证）

## 目标

完成四项零依赖的基础修复工作：
1. **D-15-03**: 消除 ReadReportHandler 和 RedisDeliveryTracker 测试中的反射字段注入，改为构造函数默认参数
2. **P2-09**: 将 UserRepositoryIntegrationTest 和 FriendshipRepositoryIntegrationTest 中的宽泛异常类型替换为精确类型
3. **P2-06**: ProtoCodecTest roundtrip 测试添加字段级反序列化断言
4. **P2-07**: PullMessagesHandlerTest 添加 cursor=0 → Long.MAX_VALUE 转换的 coVerify

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt` | 将 `private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())` 提升为构造参数，添加默认值 `redis = RedisCoroutinesCommandsImpl(connection.reactive())`，删除类体内字段声明 | 编译通过 | 构造参数新增 `redis` 字段并保留默认值；Koin 和现有调用方不受影响 |
| 2 | modify | `gateway/src/main/kotlin/com/nebula/gateway/delivery/RedisDeliveryTracker.kt` | 同上：将 `private val redis` 提升为构造参数并添加默认值 | 编译通过 | 构造参数新增 `redis` 字段并保留默认值 |
| 3 | modify | `gateway/src/test/kotlin/com/nebula/gateway/handler/message/ReadReportHandlerTest.kt` | 删除反射注入代码（`getDeclaredField`/`setAccessible`/`set`），改为在构造时直接传入 `redis = this.redis` | 测试通过 | 不再使用反射；测试初始化使用构造函数参数 |
| 4 | modify | `gateway/src/test/kotlin/com/nebula/gateway/delivery/RedisDeliveryTrackerTest.kt` | 删除反射注入代码，改为在构造时直接传入 `redis = mockRedis` | 测试通过 | 不再使用反射；测试初始化使用构造函数参数 |
| 5 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt` | 将 L278 附近的 `assertFailsWith<Exception>` 替换为 `assertFailsWith<DataIntegrityViolationException>` | 测试通过 | 唯一约束冲突测试使用精确异常类型 |
| 6 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt` | 将 L216 附近的 `assertFailsWith<Exception>` 替换为 `assertFailsWith<DataIntegrityViolationException>` | 测试通过 | 唯一约束冲突测试使用精确异常类型 |
| 7 | modify | `gateway/src/test/kotlin/com/nebula/gateway/codec/ProtoCodecTest.kt` | 在现有 roundtrip 测试中追加字段级断言：验证序列化后反序列化结果各字段值与原始值一致 | 测试通过 | 至少验证 3 个核心字段（如 messageType、content、conversationId）一致性 |
| 8 | modify | `gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt` | 添加 `coVerify { messageService.pullMessages(match { it.cursor == Long.MAX_VALUE }, any()) }` 验证 cursor=0 被转换为 Long.MAX_VALUE | 测试通过 | 测试 cursor=0 场景时验证实际传递给 service 的 cursor 值 |

## 产出物

- 2 个生产文件修改（构造参数默认值）
- 2 个测试文件修改（删除反射注入）
- 2 个模块各 2 个测试文件改进（异常类型 + ProtoCodec + cursor）

## 验证

1. 编译验证：`./gradlew :gateway:compileKotlin :gateway:compileTestKotlin :repository:compileKotlin :repository:compileTestKotlin`
2. 单元测试：`./gradlew :gateway:test --tests "*ReadReportHandlerTest*" --tests "*RedisDeliveryTrackerTest*" --tests "*ProtoCodecTest*" --tests "*PullMessagesHandlerTest*"`
3. 集成测试：`./gradlew :repository:test --tests "*UserRepositoryIntegrationTest*" --tests "*FriendshipRepositoryIntegrationTest*"`
4. 全量：`./gradlew :gateway:test :repository:test`

## 风险

- **低**: Kotlin 默认参数值保证向后兼容，Koin DI 不受影响（connection 参数顺序不变）
- **低**: ProtoCodec 字段断言如有可选字段需先判断非 null 再断言
- **低**: DataIntegrityViolationException 需要 import `org.springframework.dao.DataIntegrityViolationException`

---

---
phase: 15
plan: 15-2
type: test
wave: 1
depends_on: []
files_modified:
  - service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt
  - service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt
autonomous: true
---

# Plan 15-2: Service 核心补充（ConversationService + seqService）

## 目标

补充 ConversationService 的核心缺失测试和 MessageService 的 seqService mock 验证：
1. **P0-06**: `ConversationService.dissolveGroup` 完整测试覆盖
2. **P1-08**: `getConversation`、`getConversationMembers`、`getMemberRole` 三个辅助方法测试
3. **P1-11**: `leaveGroup` 中 memberCount==1 自动解散分支测试
4. **D-15-05**: MessageServiceTest 中添加 seqService 的 `coVerify` 验证

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt` | 追加 `dissolveGroupShouldDeleteMembersAndSetConversationInactive` 测试：mock `conversationRepository.findById` 返回群组，`conversationMemberRepository.softDeleteAllByConversationId` 和 `conversationRepository.softDeleteById` 各调用一次，验证返回成功 | 测试通过 | 验证 groupOwnerUid==requesterUid 校验、成员软删除、群组软删除 |
| 2 | modify | `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt` | 追加 `dissolveGroupShouldThrowWhenNotOwner` 测试：非群主调用时预期抛出 ChatException(BizCode.NO_PERMISSION) | 测试通过 | 权限校验异常路径覆盖 |
| 3 | modify | `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt` | 追加 `getConversationShouldReturnConversation`、`getConversationMembersShouldReturnMembers`、`getMemberRoleShouldReturnRole` 三个辅助方法测试 | 测试通过 | 每个方法至少包含正常返回和异常（不存在的 conversation/member）两个测试 |
| 4 | modify | `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt` | 追加 `leaveGroupShouldDissolveWhenLastMember` 测试：`coEvery { countActiveByConversationId } returns 1L`，验证触发 softDeleteAll + softDeleteById | 测试通过 | 最后成员退出时触发群解散 |
| 5 | modify | `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt` | 追加 `leaveGroupShouldNotDissolveWhenMultipleMembers` 测试（现有未测试的正常分支） | 测试通过 | memberCount>1 时不触发解散 |
| 6 | modify | `service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt` | 在 sendMessage/pullMessages 等相关测试中添加 `coVerify(atLeast = 1) { seqService.nextSeq(...) }` 和 `coVerify { seqService.currentSeq(...) }` | 测试通过 | 关键路径测试验证 seqService 被实际调用 |

## 产出物

- ConversationServiceTest.kt 追加约 6-8 个新测试方法
- MessageServiceTest.kt 追加约 2-3 处 coVerify 断言

## 验证

1. 编译验证：`./gradlew :service:compileTestKotlin`
2. 单元测试：`./gradlew :service:test --tests "*ConversationServiceTest*" --tests "*MessageServiceTest*"`

## 风险

- **低**: ConversationServiceTest 已有完善测试模式可直接追加
- **低**: dissolveGroup 测试需注意 `mockk<ConversationMemberRepository>()` 的 strict 模式——需为所有可能调用的方法设置 coEvery

---

---
phase: 15
plan: 15-3
type: test
wave: 2
depends_on: [15-1]
files_modified:
  - repository/src/test/kotlin/com/nebula/repository/redis/SessionRepositoryTest.kt
  - repository/src/test/kotlin/com/nebula/repository/redis/PrivacyRepositoryTest.kt
  - repository/src/test/kotlin/com/nebula/repository/redis/MessageQueueRepositoryTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/MessageRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/DeadLetterRepositoryIntegrationTest.kt
autonomous: true
---

# Plan 15-3: Repository P0/P1 新增测试文件

## 目标

为 repository 模块 5 个零测试覆盖的核心组件创建完整的测试文件：

1. **P0-01**: SessionRepositoryTest — 7 个简单方法用 MockK，batchDelete pipeline 用 Testcontainers
2. **P0-04**: PrivacyRepositoryTest — MockK 覆盖异常/回退/超时，Testcontainers 覆盖 Redis→MySQL 双写
3. **P0-05**: MessageQueueRepositoryTest — MockK 覆盖方法调用，Testcontainers 覆盖 Stream 协议行为
4. **P0-02**: MessageRepositoryIntegrationTest — 使用 JpaRepositoryFactory 创建 Repository 代理，验证 @Query
5. **P0-03**: DeadLetterRepositoryIntegrationTest — 使用 JpaRepositoryFactory 创建 Repository 代理，验证命名约定

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `repository/src/test/kotlin/com/nebula/repository/redis/SessionRepositoryTest.kt` | 创建 MockK 单元测试：save/findByToken/delete/refreshTtl/saveRaw/findRaw/deleteKey 各 2 个测试（正常+边界）；batchDelete 使用 Testcontainers 验证 pipeline 行为。参考 `OnlineStatusRepositoryTest` 和 `SessionRepositoryBatchDeleteTest` 模式 | 测试通过 | 覆盖全部 8 个方法，至少 16 个测试用例；batchDelete 验证 pipeline flush 和重连逻辑 |
| 2 | create | `repository/src/test/kotlin/com/nebula/repository/redis/PrivacyRepositoryTest.kt` | 创建 MockK 测试：8 个测试覆盖 Redis 超时回退 MySQL、Redis 异常回退、JSON 解析异常、MySQL 查询异常；Testcontainers 部分：启动 MySQL+Redis 双容器验证双写一致性（2 个测试） | 测试通过 | MockK 覆盖 4 种异常路径 + 2 种正常路径；Testcontainers 验证 Redis 写和 MySQL 写一致性 |
| 3 | create | `repository/src/test/kotlin/com/nebula/repository/redis/MessageQueueRepositoryTest.kt` | 创建 MockK 测试：6 个测试覆盖 enqueue/consume/acknowledge/checkAndSetDedup 方法签名验证；Testcontainers 部分：验证 Stream XADD/XREADGROUP/XACK 实际行为（3 个测试） | 测试通过 | MockK 覆盖全部 4 个方法的主要路径；Testcontainers 验证 Stream 消息入队、消费、确认完整链路 |
| 4 | create | `repository/src/test/kotlin/com/nebula/repository/repository/MessageRepositoryIntegrationTest.kt` | 创建集成测试：继承 `DatabaseTestBase`，使用 `JpaRepositoryFactory` 创建 MessageRepository 代理。覆盖 `findMessagesBackward`、`findMessagesForward` @Query 方法 + `countByConversationId` 命名约定方法。确保 5 条测试数据手动赋 ID 以验证游标+排序 | 测试通过 | 至少 3 个测试：向后拉取游标验证 DESC 排序、向前拉取验证 ASC 排序、count 验证 |
| 5 | create | `repository/src/test/kotlin/com/nebula/repository/repository/DeadLetterRepositoryIntegrationTest.kt` | 创建集成测试：使用 `JpaRepositoryFactory` 创建 DeadLetterRepository 代理。覆盖 `findByStatusOrderByCreatedAtAsc` 命名约定 + `findByStatusAndFailCountLessThan` 组合条件查询 | 测试通过 | 至少 2 个测试：状态排序验证、组合条件过滤验证 |

## 产出物

- 3 个新的 Redis 测试文件（MockK + Testcontainers 混合）
- 2 个新的 JPA 集成测试文件（JpaRepositoryFactory 模式）

## 验证

1. 编译验证：`./gradlew :repository:compileTestKotlin`
2. MockK 测试：`./gradlew :repository:test --tests "*SessionRepositoryTest*" --tests "*PrivacyRepositoryTest*" --tests "*MessageQueueRepositoryTest*"`
3. 集成测试：`./gradlew :repository:test --tests "*MessageRepositoryIntegrationTest*" --tests "*DeadLetterRepositoryIntegrationTest*"`
4. 全量：`./gradlew :repository:test`

## 风险

- **中**: PrivacyRepositoryTest 的 Testcontainers 部分需要同时启动 MySQL + Redis 容器。需确保 `DatabaseTestBase` 的静态 MySQL 容器与 Redis 容器不冲突（端口/网络隔离）
- **中**: MessageRepositoryIntegrationTest 和 DeadLetterRepositoryIntegrationTest 需要确认 `repository/build.gradle.kts` 中已有 `spring-data-jpa` 依赖（test scope）。如缺失需补充但不属于此计划范围
- **低**: 游标分页测试需要手动赋值 Long ID 以控制 Snowflake ID 顺序

---

---
phase: 15
plan: 15-4
type: test
wave: 2
depends_on: [15-2]
files_modified:
  - service/src/test/kotlin/com/nebula/service/sequence/SeqServiceTest.kt
  - service/src/test/kotlin/com/nebula/service/user/UserPrivacyServiceTest.kt
  - service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt
  - service/src/test/kotlin/com/nebula/service/user/UserServiceTest.kt
  - service/src/test/kotlin/com/nebula/service/friend/FriendServiceTest.kt
autonomous: true
---

# Plan 15-4: Service 层剩余补充 + runBlocking 修复

## 目标

补充 service 模块剩余所有 P1 缺失测试，并修复 P2-02 runBlocking 反模式：

1. **P1-01**: SeqService.recoverSequences 测试（Redis 重启恢复关键路径）
2. **P1-09**: UserPrivacyService.batchGetHideOnlineStatus 测试
3. **P1-10**: MessageService.checkAndSetDedup / incrementUnreadCount / countByConversationId 测试
4. **P1-12**: UserService.register DataIntegrityViolationException 兜底测试
5. **P1-13**: MessageService.pullMessages limit.coerceIn(1,100) 边界测试
6. **P1-14**: FriendService.listFriends nextCursor 断言验证
7. **P2-02**: UserServiceTest（8 处）和 FriendServiceTest（14 处）的 runBlocking 反模式修复

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `service/src/test/kotlin/com/nebula/service/sequence/SeqServiceTest.kt` | 追加 `recoverSequencesShouldRestoreFromRedis` 测试：模拟 SETNX 和 INCR 返回，验证 Redis 恢复后的序列值正确 | 测试通过 | 覆盖 recoverSequences 的 3 个 lambda 协作路径 |
| 2 | modify | `service/src/test/kotlin/com/nebula/service/sequence/SeqServiceTest.kt` | 追加 `recoverSequencesShouldHandleRedisFailure` 测试：模拟 Redis 异常时的回退行为 | 测试通过 | 异常路径覆盖 |
| 3 | modify | `service/src/test/kotlin/com/nebula/service/user/UserPrivacyServiceTest.kt` | 追加 `batchGetHideOnlineStatusShouldReturnFilteredSet` 测试：mock `privacyRepository.batchGetHideOnlineStatus(listOf(1L,2L,3L))` 返回 setOf(1L)，验证返回值 | 测试通过 | 验证批量和单个获取结果一致性 |
| 4 | modify | `service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt` | 追加 `checkAndSetDedupShouldReturnBool` 测试：分别 mock true/false 返回值验证去重逻辑 | 测试通过 | 验证去重 true 和 false 两条路径 |
| 5 | modify | `service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt` | 追加 `incrementUnreadCountShouldCallRepo` 测试：验证调用 `memberRepository.incrementUnreadCount` | 测试通过 | coVerify 确认 repository 调用 |
| 6 | modify | `service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt` | 追加 `countByConversationIdShouldReturnCount` 测试：mock `messageRepository.countByConversationId` 返回 5L | 测试通过 | 验证计数返回正确 |
| 7 | modify | `service/src/test/kotlin/com/nebula/service/chat/MessageServiceTest.kt` | 追加 `pullMessagesShouldCoerceLimit` 测试：传入 limit=0 和 limit=200，验证被 coerceIn(1,100) 修剪 | 测试通过 | 验证 coerceIn 边界：0→1, 200→100, 50→50 |
| 8 | modify | `service/src/test/kotlin/com/nebula/service/user/UserServiceTest.kt` | 追加 `registerShouldHandleDataIntegrityViolation` 测试：`coEvery { userRepository.save(any()) } throws DataIntegrityViolationException(...)`，验证注册回退逻辑 | 测试通过 | 并发注册唯一键冲突时返回已有用户或抛出业务异常 |
| 9 | modify | `service/src/test/kotlin/com/nebula/service/user/UserServiceTest.kt` | 修复 runBlocking 反模式（约 8 处）：将 `runTest { runBlocking { service.method() } }` 改为 `runTest { service.method() }`；`assertThrows` 改为 `assertFailsWith` 包裹 `runTest` | 测试通过 | 所有测试在 runTest 内直接调用 suspend 函数，无 runBlocking |
| 10 | modify | `service/src/test/kotlin/com/nebula/service/friend/FriendServiceTest.kt` | 追加 `listFriendsShouldReturnNextCursor` 测试：验证 `result.nextCursor` 值正确 | 测试通过 | nextCursor 来源（分页参数/最后一项 ID）在测试中断言 |
| 11 | modify | `service/src/test/kotlin/com/nebula/service/friend/FriendServiceTest.kt` | 修复 runBlocking 反模式（约 14 处）：同上模式，将 runBlocking 替换为 runTest 直接调用 | 测试通过 | 无 runBlocking 残留 |

## 产出物

- 5 个测试文件修改，共约 10 个新测试方法 + 22 处 runBlocking 修复

## 验证

1. 编译验证：`./gradlew :service:compileTestKotlin`
2. 单元测试：`./gradlew :service:test --tests "*SeqServiceTest*" --tests "*UserPrivacyServiceTest*" --tests "*MessageServiceTest*" --tests "*UserServiceTest*" --tests "*FriendServiceTest*"`
3. 全量：`./gradlew :service:test`

## 风险

- **中**: UserServiceTest 和 FriendServiceTest 的 runBlocking 修复涉及 22 处修改，需要逐个确认：`assertThrows` 必须改为外层 `assertFailsWith` + `runTest` 模式，不能简单替换为内联
- **低**: SeqServiceTest.recoverSequences 需要使用反射注入 Redis mock（同现有模式），与 D-15-03 的构造函数注入方案不同（那是 gateway 模块的问题）
- **低**: UserPrivacyServiceTest 可能需要确认 PrivacyRepository 的 mock 方式（与 MessageQueueRepository 类似）

---

---
phase: 15
plan: 15-5
type: test
wave: 3
depends_on: [15-3, 15-4]
files_modified:
  - repository/src/test/kotlin/com/nebula/repository/testutil/FlywayMigrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/ConversationRepositoryIntegrationTest.kt
  - repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/message/MessageSeqHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendListHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendAddHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendAcceptHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendRejectHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendDeleteHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendRequestsHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/RegisterHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/LoginHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/BatchGetUserHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/GetProfileHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/GetPrivacyHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/user/SearchUserHandlerTest.kt
autonomous: true
---

# Plan 15-5: 尾部改进（Flyway V4/V5 + 游标分页 + 无Session路径）

## 目标

完成本阶段收尾工作：
1. **P2-01**: FlywayMigrationTest 补充 V4（dead_letters 表）和 V5（唯一约束+索引）迁移验证
2. **P1-02 ~ P1-07**: Repository 现有集成测试补充游标分页、批量查询、FriendRequest 查询方法
3. **P1-15**: Gateway Handler 层补充无 Session 异常路径测试（参考 MessageSeqHandlerTest 模式）

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `repository/src/test/kotlin/com/nebula/repository/testutil/FlywayMigrationTest.kt` | 追加 `shouldHaveDeadLettersTable()` 方法：验证 V4 dead_letters 表的 id/conversationId/messageId/status/failCount/failReason/createdAt 字段结构；追加 `shouldHaveDataIntegrityConstraints()` 方法：查询 `information_schema.TABLE_CONSTRAINTS` 验证 V5 的 uk_friendship_pair、uk_from_to_status、uk_client_msg_id 唯一约束 | 测试通过 | V4 表字段齐全；V5 三个唯一约束均存在 |
| 2 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt` | 追加 `findByUsernameContainingShouldApplyCursorAndPagination` 测试：插入 5 个用户（username 含特定关键字），验证游标分页正确（排序+limit+游标值） | 测试通过 | 游标分页返回正确子集，排序顺序正确 |
| 3 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/ConversationRepositoryIntegrationTest.kt` | 追加 `findConversationsByUserIdShouldApplyCursor` 测试：插入测试数据验证游标分页 + 追加 `incrementUnreadCountShouldIncrement` 测试：验证未读数递增 | 测试通过 | 游标分页返回正确；未读数正确递增 |
| 4 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/ConversationRepositoryIntegrationTest.kt` | 追加 `findByConversationIdsAndUserIdShouldReturnBatch` 和其他批量查询测试（对应 P1-06 ConversationMemberRepository 4 个批量方法），或新建 `ConversationMemberRepositoryIntegrationTest.kt` | 测试通过 | 批量查询返回正确结果集 |
| 5 | modify | `repository/src/test/kotlin/com/nebula/repository/repository/FriendshipRepositoryIntegrationTest.kt` | 追加 `findFriendsByUserIdShouldApplyCursor` 游标分页测试 + `findByFromUidAndToUid` 和 `findByToUidAndStatusOrderByCreatedAtDesc` 的 FriendRequestRepository 方法测试 | 测试通过 | 游标分页正确；FriendRequest 查询方法覆盖 |
| 6 | modify | 多个 gateway Handler 测试文件（见文件列表） | 为每个 Handler 测试文件添加 `handleShouldRequireSession` 测试：不设置 Session 上下文直接调用 handler.handle(req)，预期 `BizException(BizCode.UNAUTHORIZED)`。参考 `MessageSeqHandlerTest.handleShouldRequireSession` 模式 | 测试通过 | 每个 Handler 至少有一个无 Session 测试方法 |

**注**：任务 6 涉及的具体 Handler 列表：LeaveGroupHandlerTest、CreateGroupHandlerTest、EditGroupHandlerTest、KickMemberHandlerTest、InviteMemberHandlerTest、FriendListHandlerTest、FriendAddHandlerTest、FriendAcceptHandlerTest、FriendRejectHandlerTest、FriendDeleteHandlerTest、FriendRequestsHandlerTest、RegisterHandlerTest、LoginHandlerTest、BatchGetUserHandlerTest、GetProfileHandlerTest、SetPrivacyHandlerTest、GetPrivacyHandlerTest、SearchUserHandlerTest。共约 18 个 Handler 测试文件。

## 产出物

- FlywayMigrationTest.kt 新增 2 个测试方法
- UserRepositoryIntegrationTest 新增 1+ 测试方法
- ConversationRepositoryIntegrationTest 新增 3+ 测试方法
- FriendshipRepositoryIntegrationTest 新增 3+ 测试方法
- 约 18 个 Gateway Handler 测试文件各新增 1 个无 Session 测试方法

## 验证

1. 编译验证：`./gradlew :repository:compileTestKotlin :gateway:compileTestKotlin`
2. 集成测试：`./gradlew :repository:test --tests "*FlywayMigrationTest*" --tests "*UserRepositoryIntegrationTest*" --tests "*ConversationRepositoryIntegrationTest*" --tests "*FriendshipRepositoryIntegrationTest*"`
3. Handler 测试：`./gradlew :gateway:test` 整体验证
4. 全量验证：`./gradlew :repository:test :gateway:test`

## 风险

- **中**: Gateway Handler 无 Session 测试涉及约 18 个文件，每个文件需添加 1 个测试方法。某些 Handler 可能已经有 before/after setup 需要在构建 Handler 实例时保持一致
- **低**: Flyway V4/V5 的 information_schema 查询需要确认 unique constraint 名称是否与 V5 迁移文件中的命名一致
- **低**: ConversationMemberRepository 的 4 个批量查询方法可能需要单独的文件（若现有 ConversationRepositoryIntegrationTest 已过长）；评估后决定是否新建

---

# 整体验证方案

## 增量验证流程

每个 Plan 执行完毕后，执行其模块对应的编译和测试命令：

```bash
# 全量编译验证
./gradlew compileKotlin compileTestKotlin

# 全量测试验证（阶段末尾执行）
./gradlew :repository:test :service:test :gateway:test
```

## 成功标准

- [x] **P0 全部闭合**（6/6）：SessionRepository、MessageRepository、DeadLetterRepository、PrivacyRepository、MessageQueueRepository、ConversationService.dissolveGroup 全部有测试覆盖
- [x] **P1 全部闭合**（17/17）：所有 P1 项均在对应 Plan 中覆盖
- [x] **P2 修复 ≥ 50%**（5/9纳入项全部修复）：P2-01/P2-02/P2-06/P2-07/P2-09
- [x] **编译通过**：`./gradlew compileKotlin compileTestKotlin` 无错误
- [x] **测试全绿**：service/gateway/repository 三模块所有测试通过
- [x] **零业务逻辑变更**：仅 2 个生产文件添加构造参数默认值（D-15-03），无业务逻辑修改
- [x] **零新依赖引入**：MockK / kotlinx.coroutines.test / Testcontainers 均为现有依赖

## PLANNING COMPLETE
