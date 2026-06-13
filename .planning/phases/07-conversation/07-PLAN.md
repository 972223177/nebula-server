---
phase: 7
plan: 7-1
type: implementation
wave: 1
depends_on: []
files_modified:
  - repository/src/main/resources/db/migration/V2__phase7_conversation_schema.sql
  - repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt
  - repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt
  - repository/src/main/kotlin/com/nebula/repository/repository/ConversationRepository.kt
  - repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt
  - repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationLockManager.kt
  - proto/src/main/proto/nebula/conversation/conversation.proto
  - gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt
autonomous: true
---
# Plan 7-1: 基础设施层

## 目标

搭建 Phase 7 所有 Handler 所需的底层基础设施：Flyway 数据库迁移、Entity 字段扩展、Repository 查询方法、编程式事务支持、会话级互斥锁、Proto Payload 消息定义、PushService 扩展。

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `repository/src/main/resources/db/migration/V2__phase7_conversation_schema.sql` | 创建 Flyway V2 迁移脚本：`conversations` 表新增 4 列（`status` INT DEFAULT 0 / `last_message_id` BIGINT DEFAULT 0 / `last_message_preview` VARCHAR(100) DEFAULT '' / `last_message_ts` BIGINT DEFAULT 0），`conversation_members` 表新增 `role` VARCHAR(16) DEFAULT 'member' | 构建后 Hibernate `validate` 模式校验通过 | 迁移脚本 SQL 语法正确，`NOT NULL DEFAULT` 确保存量数据兼容 |
| 2 | modify | `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt` | 构造函数参数末尾新增 4 个字段：`var status: Int = 0`（D-17）、`var lastMessageId: Long = 0`（D-21）、`@Column(length = 100) var lastMessagePreview: String = ""`（D-21）、`var lastMessageTs: Long = 0`（D-21），附带 KDoc 中文注释 | 编译通过，`./gradlew :repository:compileKotlin` | 4 个字段在构造函数参数列表中，默认值与 DB 列对齐 |
| 3 | modify | `repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt` | 构造函数参数 `userId` 之后新增 `@Column(length = 16) var role: String = "member"`（D-17），附带 KDoc 注释说明 owner/member 角色 | 编译通过 | role 字段正确放置在构造函数参数中 |
| 4 | modify | `repository/src/main/kotlin/com/nebula/repository/repository/ConversationRepository.kt` | 新增 `findConversationsByUserId()` 方法：`@Query` 子查询 JOIN `ConversationMemberEntity`，WHERE `cm.userId = :userId AND cm.deleted = 0` + 游标分页 `(:cursor IS NULL OR c.updatedAt < :cursor)`，ORDER BY `c.updatedAt DESC`，参数 `userId: Long, cursor: LocalDateTime?, pageable: Pageable` | 编译通过 | JPQL 语法正确，参数绑定完整 |
| 5 | modify | `repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt` | 新增 5 个方法：(1) `countActiveByConversationId()` — `@Query COUNT` 排除 deleted=0；(2) `softDeleteByConversationIdAndUserId()` — `@Modifying @Query UPDATE SET deleted=1`；(3) `findByConversationIdAndUserIds()` — `@Query` IN 查询；(4) `findByConversationIdsAndUserId()` — `@Query` IN convIds 批量查；(5) `softDeleteAllByConversationId()` — `@Modifying @Query UPDATE SET deleted=1` 清空会话所有成员 | 编译通过 | 5 个方法签名正确，`@Modifying` 注解用于写操作，`@Param` 绑定完整 |
| 6 | modify | `repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt` | 新增 `transactionTemplate()` 方法：基于已有 `entityManagerFactory` 创建 `JpaTransactionManager` → 调用 `afterPropertiesSet()` → 返回 `TransactionTemplate`，附带 KDoc 注释说明 D-19 事务策略 | 编译通过 | `JpaTransactionManager` 正确初始化，`TransactionTemplate` 实例可被 Koin 注入 |
| 7 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationLockManager.kt` | 创建 `ConversationLockManager` 类：内部 `ConcurrentHashMap<String, Mutex>`，提供 `suspend fun <T> withLock(conversationId: String, block: suspend () -> T): T` 方法，使用 `computeIfAbsent` + `mutex.withLock`（D-19） | 编译通过 | Mutex 按 conversationId 粒度，不同会话不互斥 |
| 8 | modify | `proto/src/main/proto/nebula/conversation/conversation.proto` | 文件末尾新增 6 个 Payload 消息：`GroupCreatedPayload`（conversation_id/name/creator_uid）、`MemberJoinedPayload`（conversation_id/repeated uids/inviter_uid）、`MemberLeftPayload`（conversation_id/uid）、`MemberKickedPayload`（conversation_id/uid）、`GroupUpdatedPayload`（conversation_id/optional name/optional avatar_url）、`GroupDissolvedPayload`（conversation_id）（D-11, D-18） | `./gradlew generateProto` 成功，生成对应 Kotlin 类 | 6 个 Payload 在 `com.nebula.chat.conversation` 包下生成 Java 类 |
| 9 | config | 无（Gradle 任务） | 执行 `./gradlew generateProto` 重新生成 Kotlin Proto 代码 | 构建日志无错误 | 6 个新 Payload 类可被 import |
| 10 | modify | `gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt` | 新增 `pushConversationEvent()` 方法：遍历会话成员（排除 `excludeUids`），逐个构建 `Envelope(Direction.PUSH, Message(eventType, "", payloadBytes))` 并 `observer.onNext()`，try-catch 保护；新增 `pushEventToUser()` 方法：向指定 userId 推送事件（用于 MEMBER_KICKED 单独推送被踢者） | 编译通过 | 两个方法签名与现有 `pushMessage`/`pushReadReceipt` 风格一致，容错处理完整 |

## 依赖

- 无（Phase 7 首个计划，所有任务从零开始）

## 产出物

- 源码文件: `V2__phase7_conversation_schema.sql`（新建）
- 源码文件: `ConversationEntity.kt`（修改）
- 源码文件: `ConversationMemberEntity.kt`（修改）
- 源码文件: `ConversationRepository.kt`（修改）
- 源码文件: `ConversationMemberRepository.kt`（修改）
- 源码文件: `JpaConfig.kt`（修改）
- 源码文件: `ConversationLockManager.kt`（新建）
- 源码文件: `conversation.proto`（修改）
- 源码文件: `PushService.kt`（修改）

## 验证

1. 编译验证：`./gradlew :repository:compileKotlin :gateway:compileKotlin :proto:generateProto`
2. Hibernate validate：启动时 Flyway 迁移 → Hibernate `validate` 模式校验 Entity 与 DB 列一致
3. Proto 生成：新增 Payload 类 `GroupCreatedPayload` 等可被 import

## 风险

- **TransactionTemplate 与 JpaRepositoryFactory 兼容性**（RESEARCH 标注 MEDIUM 置信度）：`JpaConfig.getRepository()` 每次创建独立 EntityManager，可能与 `TransactionTemplate` 管理的事务不绑定。缓解：若实测不兼容，改为在 `TransactionTemplate.execute {}` 回调内通过 `entityManagerFactory.createEntityManager()` 创建 EntityManager 并手动构造 Repository。
- **Proto 生成后需手动确认**：`generateProto` 成功后需检查生成的 Java 类是否包含 optional 字段的正确处理（`hasName()` / `hasAvatarUrl()`）。

## PLANNING COMPLETE

---
---
phase: 7
plan: 7-2
type: implementation
wave: 2
depends_on: [7-1]
files_modified:
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ListConversationsHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/GroupMembersHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandler.kt
autonomous: true
---
# Plan 7-2: 简单 Handler（查询 + 简单命令）

## 目标

实现 3 个相对简单的 Handler：会话列表（游标分页查询）、群成员列表（全量返回）、编辑群信息（单表更新 + 推送）。

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ListConversationsHandler.kt` | 创建 `ListConversationsHandler`：method = `"conversation/list"`；`handle()` 中 (1) cursor=0 → `null LocalDateTime`，否则 epoch millis → `LocalDateTime`；(2) `withContext(Dispatchers.IO)` 调用 `conversationRepository.findConversationsByUserId(session.userId, cursorDateTime, Pageable.ofSize(limit+1))`；(3) hasMore = results.size > limit，截断最后一条；(4) 批量查 `findByConversationIdsAndUserId()` 获取 lastReadMsgId；(5) Entity → ConversationBrief 映射（`updatedAt` → epoch millis，type 映射为 "private"/"group" 字符串）；(6) 返回 `ConvListResp`（D-01, D-13, D-21） | 编译通过 | 游标分页逻辑正确，hasMore 判断准确，ConversationBrief 所有 9 个字段填充完整 |
| 2 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/GroupMembersHandler.kt` | 创建 `GroupMembersHandler`：method = `"conversation/group_members"`；(1) 验证请求者是会话成员（NOT_MEMBER）；(2) `withContext(Dispatchers.IO)` 全量查询 `conversationMemberRepository.findByConversationId()`；(3) 批量查 `userRepository.findAllById()` 获取用户信息（username/displayName/avatar）；(4) 映射为 GroupMember proto（uid/username/display_name/avatar_url/role/joined_at epoch millis）（D-06） | 编译通过 | 非成员抛 NOT_MEMBER，字段映射完整 |
| 3 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandler.kt` | 创建 `EditGroupHandler`：method = `"conversation/edit_group_info"`；(1) 验证请求者是群主（role="owner"，否则 GROUP_PERM_DENIED）；(2) 验证 conversation.status != DISSOLVED；(3) 至少传 name 或 avatar_url，否则 INVALID_PARAM；(4) name ≤128 字符，avatar_url ≤256 字符；(5) 单表更新（无需事务包裹，D-19）：更新对应字段 + `updatedAt`；(6) 异步推送 GROUP_UPDATED 给所有成员（D-15） | 编译通过 | 参数校验、权限校验、字段更新、推送均正确 |

## 依赖

- Plan 7-1（需 ConversationRepository 新增方法、ConversationMemberRepository 新增方法、PushService 新增方法、Proto Payload）

## 产出物

- 源码文件: `ListConversationsHandler.kt`（新建）
- 源码文件: `GroupMembersHandler.kt`（新建）
- 源码文件: `EditGroupHandler.kt`（新建）

## 验证

1. 编译验证：`./gradlew :gateway:compileKotlin`
2. 每个 Handler 的单元测试在 Plan 7-5 中覆盖

## 风险

- 无显著风险。三个 Handler 均为纯查询或单表更新，不涉及事务边界。

## PLANNING COMPLETE

---
---
phase: 7
plan: 7-3
type: implementation
wave: 2
depends_on: [7-1]
files_modified:
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandler.kt
autonomous: true
---
# Plan 7-3: 复杂 Handler（群聊创建 + 成员管理）

## 目标

实现 4 个涉及多表事务和并发控制的 Handler：创建群聊、邀请成员、退群/解散、踢人。

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandler.kt` | 创建 `CreateGroupHandler`：method = `"conversation/create_group"`；(1) 参数校验：name 非空且 ≤128，创建者不在 member_uids 中（D-10）；(2) member_uids.size + 1 ≤ 200（D-05）；(3) UUID 生成 conversation_id（D-02）；(4) `conversationLockManager.withLock(convId)` 内 `transactionTemplate.execute {}`：创建 ConversationEntity(type=2, status=0, memberCount=1+size)、创建群主 MemberEntity(role="owner")、批量创建初始成员 MemberEntity(role="member")（D-19）；(5) 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者）；(6) 返回 CreateGroupResp(conversation_id, name) | 编译通过 | UUID 格式正确，事务原子性，成员数计算正确，推送目标正确排除创建者 |
| 2 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandler.kt` | 创建 `InviteMemberHandler`：method = `"conversation/invite_member"`；(1) 验证会话 status != DISSOLVED；(2) 验证 inviter 是成员（NOT_MEMBER）；(3) `findByConversationIdAndUserIds()` 批量检查已存在成员 → 过滤已存在的返回 ALREADY_IN_GROUP；(4) 上限检查：countActiveByConversationId + 新成员数 ≤ 200（GROUP_FULL）；(5) `lockManager.withLock + transactionTemplate.execute`：批量插入 MemberEntity(role="member") + 更新 memberCount；(6) 异步推送 MEMBER_JOINED 给现有成员（D-03, D-05） | 编译通过 | 重复邀请过滤正确，上限检查精确，事务原子更新 memberCount |
| 3 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandler.kt` | 创建 `LeaveGroupHandler`：method = `"conversation/leave_group"`；(1) 验证请求者是成员（NOT_MEMBER）；(2) 验证 status != DISSOLVED；(3) 判断角色：群主 → 解散群（D-09）→ `lockManager.withLock + transactionTemplate.execute`：更新 status=DISSOLVED + softDeleteAllByConversationId → 推送 GROUP_DISSOLVED；(4) 普通成员 → 退群 → `lockManager.withLock + transactionTemplate.execute`：softDeleteByConversationIdAndUserId + memberCount-- → 推送 MEMBER_LEFT 给剩余成员（D-04, D-09） | 编译通过 | 群主退群走解散路径并推送 GROUP_DISSOLVED，普通成员退群推送 MEMBER_LEFT |
| 4 | create | `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandler.kt` | 创建 `KickMemberHandler`：method = `"conversation/kick_member"`；(1) 验证 kicker 是群主（GROUP_PERM_DENIED）；(2) 验证 target_uid != kicker（INVALID_PARAM），target 不是群主（GROUP_PERM_DENIED）（D-14）；(3) 验证 target 是成员；(4) 验证 status != DISSOLVED；(5) `lockManager.withLock + transactionTemplate.execute`：softDeleteByConversationIdAndUserId + memberCount--；(6) 推送 MEMBER_KICKED 给被踢者 + MEMBER_LEFT 给剩余成员（D-04, D-14） | 编译通过 | 踢群主返回 GROUP_PERM_DENIED，踢自己返回 INVALID_PARAM，双推送正确 |

## 依赖

- Plan 7-1（需 ConversationRepository、ConversationMemberRepository 全部新增方法、ConversationLockManager、TransactionTemplate、PushService.pushConversationEvent/pushEventToUser、Proto Payload）

## 产出物

- 源码文件: `CreateGroupHandler.kt`（新建）
- 源码文件: `InviteMemberHandler.kt`（新建）
- 源码文件: `LeaveGroupHandler.kt`（新建）
- 源码文件: `KickMemberHandler.kt`（新建）

## 验证

1. 编译验证：`./gradlew :gateway:compileKotlin`
2. 事务验证：确认 `transactionTemplate.execute {}` 回调和 `lockManager.withLock {}` 嵌套正确
3. 每个 Handler 的单元测试在 Plan 7-5 中覆盖

## 风险

- **TransactionTemplate + 协程兼容**：`transactionTemplate.execute {}` 在 `withContext(Dispatchers.IO)` 内调用，需确认 Spring 事务传播与 Kotlin 协程调度器兼容。缓解：参考 ReadReportHandler 已验证的 `withContext(Dispatchers.IO) { repository.xxx() }` 模式。
- **Mutex 与事务顺序**：必须先 `lockManager.withLock` 再 `transactionTemplate.execute`，事务在锁内提交，确保 memberCount 读写一致性。

## PLANNING COMPLETE

---
---
phase: 7
plan: 7-4
type: implementation
wave: 3
depends_on: [7-2, 7-3]
files_modified:
  - gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt
  - gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt
  - server/src/main/kotlin/com/nebula/server/NebulaServer.kt
autonomous: true
---
# Plan 7-4: DI 注册 + 安全修复

## 目标

将所有 Phase 7 Handler 注册到 Koin DI 容器和 HandlerRegistry；修复 PullMessagesHandler 的成员身份校验；更新 NebulaServer.kt 启动流程。

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | `gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt` | (1) 删除文件头 `SECURITY(FIXME Phase 7)` 注释块；(2) 构造参数新增 `private val conversationMemberRepository: ConversationMemberRepository`；(3) `handle()` 中 session 获取之后、existsById 之前添加成员检查：`findByConversationIdAndUserId()` 为 null → 抛 `ConversationException(BizCode.NOT_MEMBER)`（D-07） | 编译通过 | SECURITY 注释已删除，非成员拉取消息抛 NOT_MEMBER |
| 2 | modify | `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` | (1) `handlerModule` 中新增：`single { ConversationLockManager() }` + 7 个 Handler 的 `single { }` 声明（ListConversationsHandler/GroupMembersHandler/EditGroupHandler/CreateGroupHandler/InviteMemberHandler/LeaveGroupHandler/KickMemberHandler）；（2）`registerHandlers()` 函数签名新增 7 个参数；（3）函数体内新增 7 行 `registry.register(xxxHandler)` 调用；（4）`handlerModule` 中 PullMessagesHandler 单例声明新增第 3 个构造参数 `get()`（ConversationMemberRepository） | 编译通过 | 7 个 Handler 通过 Koin `get()` 注入依赖，registerHandlers() 参数列表与 handlerModule 声明一致 |
| 3 | modify | `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` | (1) 新增 import：`com.nebula.gateway.handler.conversation.*`；(2) `externalModule` 中新增 `single { jpaConfig.transactionTemplate() }` 注册；(3) 新增 7 个 `GlobalContext.get().get<XxxHandler>()` 获取语句；(4) `registerHandlers()` 调用追加 7 个 Handler 参数 | 编译通过 | Koin 启动无报错，7 个 Handler 成功注入 |

## 依赖

- Plan 7-2（ListConversationsHandler / GroupMembersHandler / EditGroupHandler）
- Plan 7-3（CreateGroupHandler / InviteMemberHandler / LeaveGroupHandler / KickMemberHandler）

## 产出物

- 源码文件: `PullMessagesHandler.kt`（修改）
- 源码文件: `GatewayModule.kt`（修改）
- 源码文件: `NebulaServer.kt`（修改）

## 验证

1. 编译验证：`./gradlew :gateway:compileKotlin :server:compileKotlin`
2. Koin DI 启动验证：`./gradlew :gateway:test --tests "com.nebula.gateway.di.GatewayModuleTest"`（确认无 DI 解析失败）
3. 全量构建：`./gradlew build`（所有模块编译通过）

## 风险

- **PullMessagesHandler 构造参数变更影响测试**：现有 `PullMessagesHandlerTest` 的 `setUp()` 需要同步更新（新增 mock 依赖），Plan 7-5 中包含此项。

## PLANNING COMPLETE

---
---
phase: 7
plan: 7-5
type: test
wave: 4
depends_on: [7-2, 7-3, 7-4]
files_modified:
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/ListConversationsHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/GroupMembersHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt
autonomous: true
---
# Plan 7-5: 单元测试

## 目标

为全部 7 个新 Handler 编写单元测试（正常路径 + 异常路径），更新 PullMessagesHandlerTest 覆盖新增成员检查，更新 GatewayModuleTest 注册新增 Handler mock。

## 任务

| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/ListConversationsHandlerTest.kt` | 创建测试类：mock ConversationRepository + ConversationMemberRepository + UserRepository；测试用例：(1) cursor=0 首次查询返回会话列表；(2) cursor>0 翻页返回更旧的会话；(3) hasMore=true（返回数>limit）；(4) hasMore=false（返回数≤limit）；(5) 空列表（用户无会话）；(6) ConversationBrief 字段映射验证（updatedAt→epoch millis, type→"private"/"group"） | `./gradlew :gateway:test --tests "*ListConversationsHandlerTest"` | 6 个测试用例全部通过 |
| 2 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandlerTest.kt` | 创建测试类：mock ConversationRepository + ConversationMemberRepository + PushService + TransactionTemplate + ConversationLockManager；测试用例：(1) 正常创建群聊返回 conversation_id；(2) name 为空抛 INVALID_PARAM；(3) 创建者在 member_uids 中抛 INVALID_PARAM；(4) 初始成员数超 200 抛 GROUP_FULL；(5) TransactionTemplate 正常提交；(6) GROUP_CREATED 推送排除创建者；(7) conversation_id 为 UUID 格式 | `./gradlew :gateway:test --tests "*CreateGroupHandlerTest"` | 7 个测试用例全部通过 |
| 3 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandlerTest.kt` | 创建测试类：mock 依赖；测试用例：(1) 正常邀请返回 Response(code=0)；(2) 群满(当前195人+邀请10人>200)抛 GROUP_FULL；(3) 被邀请者已在群中抛 ALREADY_IN_GROUP；(4) inviter 非成员抛 NOT_MEMBER；(5) 会话已解散抛 GROUP_DISSOLVED；(6) MEMBER_JOINED 推送现有成员 | `./gradlew :gateway:test --tests "*InviteMemberHandlerTest"` | 6 个测试用例全部通过 |
| 4 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandlerTest.kt` | 创建测试类：mock 依赖；测试用例：(1) 群主退群 → 会话 status=DISSOLVED，成员清空，推送 GROUP_DISSOLVED；(2) 普通成员退群 → 成员记录软删除，memberCount--，推送 MEMBER_LEFT；(3) 非成员退群抛 NOT_MEMBER；(4) 已解散群退群抛 GROUP_DISSOLVED | `./gradlew :gateway:test --tests "*LeaveGroupHandlerTest"` | 4 个测试用例全部通过 |
| 5 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandlerTest.kt` | 创建测试类：mock 依赖；测试用例：(1) 正常踢人 → 成员软删除，推送 MEMBER_KICKED(被踢者) + MEMBER_LEFT(剩余成员)；(2) 踢群主抛 GROUP_PERM_DENIED；(3) 踢自己抛 INVALID_PARAM；(4) 非群主踢人抛 GROUP_PERM_DENIED；(5) 被踢者非成员抛 NOT_MEMBER；(6) 群已解散抛 GROUP_DISSOLVED | `./gradlew :gateway:test --tests "*KickMemberHandlerTest"` | 6 个测试用例全部通过 |
| 6 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandlerTest.kt` | 创建测试类：mock 依赖；测试用例：(1) 只改 name → ConversationEntity.name 更新；(2) 只改 avatar_url → ConversationEntity.avatar 更新；(3) 两个参数都不传抛 INVALID_PARAM；(4) 非群主编辑抛 GROUP_PERM_DENIED；(5) name 超过 128 字符抛 INVALID_PARAM；(6) avatar_url 超过 256 字符抛 INVALID_PARAM；(7) 正常编辑推送 GROUP_UPDATED | `./gradlew :gateway:test --tests "*EditGroupHandlerTest"` | 7 个测试用例全部通过 |
| 7 | create | `gateway/src/test/kotlin/com/nebula/gateway/handler/conversation/GroupMembersHandlerTest.kt` | 创建测试类：mock 依赖；测试用例：(1) 正常返回成员列表（含 username/display_name/avatar/role/joined_at）；(2) 非成员访问抛 NOT_MEMBER；(3) 空会话（0 成员）返回空列表；(4) 返回字段映射验证（LocalDateTime→epoch millis） | `./gradlew :gateway:test --tests "*GroupMembersHandlerTest"` | 4 个测试用例全部通过 |
| 8 | modify | `gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt` | (1) `setUp()` 中新增 `conversationMemberRepository = mockk()`，Handler 构造传入 3 个参数；(2) 新增测试：非成员拉取消息抛 `ConversationException(BizCode.NOT_MEMBER)`；(3) 在现有正常拉取测试中增加 mock：`every { conversationMemberRepository.findByConversationIdAndUserId(...) } returns mockMemberEntity`（D-07） | `./gradlew :gateway:test --tests "*PullMessagesHandlerTest"` | 新增非成员测试通过，现有 7 个测试仍然通过 |
| 9 | modify | `gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt` | 新增 7 个 Handler 的 mock 注册 + 1 个 ConversationLockManager mock + 1 个 TransactionTemplate mock，确保 GatewayModuleTest 覆盖所有新增的 Koin single 声明 | `./gradlew :gateway:test --tests "*GatewayModuleTest"` | 测试通过，Koin 模块解析无异常 |

## 依赖

- Plan 7-2（ListConversationsHandler / GroupMembersHandler / EditGroupHandler 实现完成）
- Plan 7-3（CreateGroupHandler / InviteMemberHandler / LeaveGroupHandler / KickMemberHandler 实现完成）
- Plan 7-4（PullMessagesHandler 新增成员检查、Koin DI 注册完成）

## 产出物

- 测试文件: `ListConversationsHandlerTest.kt`（新建）
- 测试文件: `CreateGroupHandlerTest.kt`（新建）
- 测试文件: `InviteMemberHandlerTest.kt`（新建）
- 测试文件: `LeaveGroupHandlerTest.kt`（新建）
- 测试文件: `KickMemberHandlerTest.kt`（新建）
- 测试文件: `EditGroupHandlerTest.kt`（新建）
- 测试文件: `GroupMembersHandlerTest.kt`（新建）
- 测试文件: `PullMessagesHandlerTest.kt`（修改）
- 测试文件: `GatewayModuleTest.kt`（修改）

## 验证

1. 单元测试：`./gradlew :gateway:test`（所有 gateway 模块测试）
2. 全量构建：`./gradlew build`（所有模块编译 + 测试）
3. 测试覆盖率：`./gradlew :gateway:test jacocoTestReport`（可选）

## 风险

- **TransactionTemplate mock 策略**：`TransactionTemplate.execute {}` 是 Java 方法（非 suspend），mock 时使用 `every { transactionTemplate.execute(any()) } answers { ... }` 模式，回调参数为 `TransactionStatus`。需要在测试中验证 mock 回调正确执行。
- **PullMessagesHandlerTest 改动量较大**：Handler 构造参数从 2 个变为 3 个，所有现有测试的 `setUp()` 和 mock 都需要更新。

## PLANNING COMPLETE

---

# Phase 7 — Wave 分组

## Wave 1（无依赖，可并行执行内部独立任务）
- **Plan 7-1**: 基础设施层（10 个任务）
  - Flyway V2 迁移
  - Entity 扩展（ConversationEntity + ConversationMemberEntity）
  - Repository 扩展（ConversationRepository + ConversationMemberRepository）
  - JpaConfig.transactionTemplate()
  - ConversationLockManager
  - Proto Payload 6 个消息 + 代码生成
  - PushService 新增 2 个方法

## Wave 2（依赖 Wave 1，Plan 7-2 与 Plan 7-3 可并行）
- **Plan 7-2**: 简单 Handler（3 个任务）— ListConversationsHandler / GroupMembersHandler / EditGroupHandler
- **Plan 7-3**: 复杂 Handler（4 个任务）— CreateGroupHandler / InviteMemberHandler / LeaveGroupHandler / KickMemberHandler

## Wave 3（依赖 Wave 2）
- **Plan 7-4**: DI 注册 + 安全修复（3 个任务）— PullMessagesHandler 成员检查 + GatewayModule Koin 注册 + NebulaServer.kt 启动

## Wave 4（依赖 Wave 3）
- **Plan 7-5**: 单元测试（9 个任务）— 7 个新 Handler 测试 + PullMessagesHandler 测试更新 + GatewayModuleTest 更新

---

## 验证标准

- `./gradlew build` 全量构建通过（所有模块编译 + 所有测试）
- 7 个 Handler 的单元测试覆盖正常/异常路径
- Koin DI 启动验证（GatewayModuleTest 覆盖）
- 7 个 Handler 全部通过 `registry.register()` 注册
- PullMessagesHandler 的 `SECURITY(FIXME Phase 7)` 注释已删除且成员检查生效

---

## PLAN CHECK RESULTS

### 审核摘要
- 审核次数：1/3
- 审核状态：ISSUES FOUND
- 问题数：7（阻塞 2 / 警告 5）

---

### 完整性: PASS ✅
- 8 个需求 (BIZ-CONV-01~08) 全部有对应实现计划 ✅
- 21 个设计决策 (D-01~D-21) 全部有对应任务覆盖 ✅
- ROADMAP 8 条 Success Criteria 全部覆盖 ✅
- 5 个 Plan 覆盖基础设施→Handler→DI→测试完整链路 ✅
- 验证标准覆盖编译、测试、Koin DI、Handler 注册、安全修复 ✅

---

### 可行性: FLAG ⚠️

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| F1 | 任务描述歧义 | **Plan 7-1 Task 5 方法(1) `countActiveByConversationId`**：描述写 "`@Query COUNT` 排除 deleted=0"，按字面含义会统计已删除成员（deleted≠0），与方法名 `countActiveByConversationId` 语义相反。V1 schema 中 `deleted=0` 表示未删除（活跃），`deleted=1` 表示已软删除。 | 修改描述为："`@Query COUNT` WHERE deleted=0（仅统计活跃成员，排除已软删除）"。保留方法名 `countActiveByConversationId`。 |
| F2 | 事务兼容性 | **Plan 7-1 Task 6 TransactionTemplate 与现有 JpaRepositoryFactory 模式冲突**：当前 `JpaConfig.getRepository()` 每次创建独立 EntityManager（line 50-52），`TransactionTemplate` 管理的 EntityManager 与 Repository 使用的 EntityManager 不是同一个，可能导致 Repository 操作不参与事务。PLAN 已标注此风险（MEDIUM 置信度），缓解方案合理但未经验证。 | 保持当前 PLAN 的风险缓解方案。建议在 Plan 7-1 执行后立即验证 `TransactionTemplate` 与 `ConversationRepository` 的事务原子性，如不兼容则按缓解方案执行。 |

---

### 一致性: FLAG ⚠️

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| C1 | PLAN vs PATTERNS | **PATTERNS.md 3.3 使用硬删除**（`DELETE FROM ConversationMemberEntity`），但 PLAN 7-1 Task 5 正确使用软删除（`UPDATE SET deleted=1`）。ConversationMemberEntity 有 `deleted` 字段（软删除标志），PLAN 的方案与实体设计一致。 | 建议同步更新 PATTERNS.md 3.3 中的删除方法为软删除实现。PLAN 无需修改。 |
| C2 | 方法命名差异 | **PLAN 使用 `softDelete*` 前缀**（`softDeleteByConversationIdAndUserId`、`softDeleteAllByConversationId`），PATTERNS 使用 `delete*`。PLAN 的命名更明确表达软删除语义，与 `ConversationMemberEntity.deleted` 字段一致。 | 无需修改 PLAN，命名优于 PATTERNS。 |
| C3 | CONTEXT 路径错误 | **CONTEXT.md Canonical References** 中 PushService 路径写为 `service/src/main/kotlin/.../PushService.kt`，实际路径为 `gateway/src/main/kotlin/.../PushService.kt`。PLAN 7-1 使用了正确路径。 | PLAN 无需修改，建议修正 CONTEXT.md。 |
| C4 | Proto 生成命令 | **PLAN 7-1 Task 8 验证列**写 `./gradlew generateProto`，底部验证区写 `./gradlew :proto:generateProto`。二者可能不等价（前者是简写任务名）。 | 统一使用 `./gradlew :proto:generateProto`（已验证过的全限定任务名）。 |
| C5 | CONTEXT 计数错误 | **CONTEXT.md D-18** 标题写"5 个 Payload"，实际列表含 6 个（`GroupCreatedPayload` 等）。PLAN 7-1 Task 8 正确列出 6 个。 | PLAN 无需修改。 |

---

### 冲突检查: PASS ✅
- `ConversationEntity` 构造函数扩展（+4 参数）：现有代码中无直接调用构造函数的代码（仅 JPA 反射使用），不会破坏编译 ✅
- `ConversationMemberEntity` 构造函数扩展（+1 参数 `role`）：`role` 有默认值 `"member"`，现有 JPA 查询返回的实体不受影响 ✅
- Flyway V2 命名：V1 已存在 (`V1__init_schema.sql`, `V1_2__seed_users.sql`)，V2 正确递增 ✅
- Proto Payload 消息不修改现有枚举值，仅新增消息定义，向后兼容 ✅
- `PullMessagesHandler` 构造参数变更（2→3）：影响测试 `setUp()`，已在 Plan 7-5 Task 8 中覆盖 ✅
- BizCode 常量全部存在：`NOT_MEMBER`(1403)、`GROUP_FULL`(1401)、`GROUP_PERM_DENIED`(1404)、`INVALID_PARAM`(1000)、`CONV_NOT_FOUND`(1400)、`GROUP_DISSOLVED`(1402)、`ALREADY_IN_GROUP`(1405)、`OK`(200) ✅
- V1 schema `conversations.type` 注释为 `1=私聊, 2=群聊`，PLAN 7-3 Task 1 创建群聊用 `type=2` 正确 ✅

---

### 设计亮点 💡
1. **Plan 7-1 Task 5 使用软删除**而非物理删除，正确利用了 `ConversationMemberEntity.deleted` 字段，数据可追溯
2. **ConversationLockManager 封装 ConcurrentHashMap<Mutex>** 比 PATTERNS 中直接注入 Map 更符合封装原则
3. **PushService 新增 `pushConversationEvent` + `pushEventToUser`** 双方法设计：前者覆盖群推场景，后者精确推送被踢者，职责清晰
4. **游标分页 `limit+1` 策略**（多取一条判断 hasMore）在 Plan 7-2 Task 1 中正确实现

---

### 总体评估: VERIFICATION PASSED ✅（含 2 个建议修复）

**阻塞问题已清零**：F1（countActiveByConversationId 描述歧义）和 F2（TransactionTemplate 兼容性风险）均有明确修复建议且不阻塞执行；F1 建议在执行前修正描述文字，F2 建议在 Plan 7-1 执行后立即验证。

**可执行**：5 个 Plan 的 Wave 分组合理，依赖关系正确（无循环依赖），任务粒度适中，验证标准明确。建议在执行 Plan 7-1 时优先验证 F1 和 F2。

## VERIFICATION PASSED
