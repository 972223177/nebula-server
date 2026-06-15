---
phase: 11
type: patterns
---

# Phase 11: Code Quality & Production Hardening — 代码模式映射

## 1. TransactionTemplate 事务包裹模式

### 1.1 已有模式（参考实现）

#### A. ConversationLockManager.withLock() — 锁 + 事务模板用法约定

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationLockManager.kt:36-39`

```kotlin
suspend fun <T> withLock(conversationId: String, block: suspend () -> T): T {
    val mutex = locks.computeIfAbsent(conversationId) { Mutex() }
    return mutex.withLock { block() }
}
```

**约定**: 在 `withLock` 内部包裹 `transactionTemplate.execute { }`，保证事务在锁内提交（D-19）。KDoc 注释第 14-19 行给出了标准用法范例。

#### B. Handler 注入 TransactionTemplate — Koin DI 模式

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/ConversationHandlerModule.kt:26-28`

```kotlin
// InviteMemberHandler 同时注入 ConversationLockManager + TransactionTemplate
single { InviteMemberHandler(get(), get(), get(), get(), get()) }
// → ConversationService + LockManager + TxTemplate + PushService + ConvMemberRepo
```

**约定**: Handler 层通过构造注入 `TransactionTemplate`（Koin `get()` 自动解析），`ConversationService` 不感知事务边界。

#### C. 当前 Handler 内的 TransactionTemplate 使用 — 声明但未实际包裹

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandler.kt:29-62`

```kotlin
class InviteMemberHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
    private val pushService: PushService,
    private val conversationMemberRepository: ConversationMemberRepository
) : Handler<InviteMemberReq, Response> {
    
    override suspend fun handle(req: InviteMemberReq): Response {
        val session = currentCoroutineContext().requireSession()
        // ⚠️ H17: 此处未使用 lockManager.withLock() + transactionTemplate.execute() 包裹
        val newMemberUids = conversationService.inviteMember(req, session.userId)
        // 推送在事务外执行（正确：推送不应在事务内）
        // ...
    }
}
```

**关键发现**: `InviteMemberHandler` 注入了 `transactionTemplate` 和 `lockManager`，但在 `handle()` 中**未实际调用**它们来包裹 `conversationService.inviteMember()`——事务包裹仍未生效（H17 + H20）。

### 1.2 映射目标

| 文件 | 方法 | 需要的事务范围 | 参考 Handler |
|------|------|---------------|-------------|
| `ConversationService.kt:64` | `createGroup()` | `conversationRepo.save() + memberRepo.save()` × N | `CreateGroupHandler` 需注入 `TransactionTemplate` + `ConversationLockManager` |
| `ConversationService.kt:186` | `inviteMember()` | for 循环内多次 `memberRepo.save()` + `conversationRepo.save()` | `InviteMemberHandler` 已有注入，需在 handle() 中实际包裹 |
| `ConversationService.kt:244` | `leaveGroup()` | `memberRepo.softDelete()` + `conversationRepo.save()` | `LeaveGroupHandler` 已有注入，需实际包裹 |
| `ConversationService.kt:284` | `kickMember()` | `memberRepo.softDelete()` + `conversationRepo.save()` | `KickMemberHandler` 已有注入，需实际包裹 |
| `FriendService.kt:69` | `addFriend()` (双向竞赛路径) | 跨 4 Repository 写入：`friendRequestRepo.save() + friendshipRepo.save() + conversationRepo.save() + memberRepo.save()` × 2 | `FriendAddHandler` 需注入 `TransactionTemplate` |
| `FriendService.kt:180` | `acceptFriendRequest()` | 跨 4 Repository 写入 | `FriendAcceptHandler` 需注入 `TransactionTemplate` |

### 1.3 修复后的目标写法示例

```kotlin
// InviteMemberHandler.handle() 修复后
override suspend fun handle(req: InviteMemberReq): Response {
    val session = currentCoroutineContext().requireSession()
    
    // D-79: 锁 + 事务包裹
    var newMemberUids: List<Long>
    lockManager.withLock(req.conversationId) {
        transactionTemplate.execute {
            runBlocking {  // TransactionTemplate.execute() 是同步的，需要桥接
                newMemberUids = conversationService.inviteMember(req, session.userId)
            }
        }
    }
    // 推送在事务外（正确）
    // ...
}
```

**注意**: `TransactionTemplate.execute()` 是同步阻塞 API，需 `runBlocking { }` 桥接。ConversationService 方法本身是 `suspend` 函数，在 `runBlocking` 内调用可正常工作。

---

## 2. Repository JPQL @Modifying 原子更新模式

### 2.1 已有模式（参考实现）

**文件**: `repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt:28-57`

```kotlin
// 模式 A: 简单批量原子递增
@Modifying
@Query("""
    UPDATE ConversationMemberEntity cm
    SET cm.unreadCount = cm.unreadCount + 1
    WHERE cm.conversationId = :convId AND cm.userId <> :senderId
""")
fun incrementUnreadCount(
    @Param("convId") conversationId: String,
    @Param("senderId") senderId: Long
)

// 模式 B: 多字段原子更新
@Modifying
@Query("""
    UPDATE ConversationMemberEntity cm
    SET cm.lastReadMessageId = :lastReadMsgId,
        cm.unreadCount = 0
    WHERE cm.conversationId = :convId AND cm.userId = :userId
""")
fun updateReadReceipt(
    @Param("convId") conversationId: String,
    @Param("userId") userId: Long,
    @Param("lastReadMsgId") lastReadMsgId: Long
)

// 模式 C: 软删除使用 @Modifying UPDATE
@Modifying
@Query("""
    UPDATE ConversationMemberEntity cm
    SET cm.deleted = 1
    WHERE cm.conversationId = :convId AND cm.userId = :userId
""")
fun softDeleteByConversationIdAndUserId(
    @Param("convId") conversationId: String,
    @Param("userId") userId: Long
)
```

**约定**:
- `@Modifying` 注解标记写操作
- JPQL 使用实体属性名（非数据库列名），例如 `cm.memberCount`（对应数据库 `member_count`）
- 参数通过 `@Param` 绑定
- 不需要 `@Transactional`（由调用方事务包裹）

### 2.2 映射目标 — memberCount 原子更新（D-82, H22）

| 新增 Repository 方法 | 替换的调用点 | 说明 |
|---------------------|-------------|------|
| `ConversationRepository.incrementMemberCount(convId, delta)` | `ConversationService.inviteMember():226-233` | 替换 `countActiveBy → set memberCount → save` |
| 同上 | `ConversationService.leaveGroup():267-274` | 同上 |
| 同上 | `ConversationService.kickMember():316-323` | 同上 |

### 2.3 新增方法签名

```kotlin
// 文件: ConversationRepository.kt，新增方法
/**
 * 原子更新会话成员计数（D-82）。
 *
 * 单条 UPDATE 语句保证数据库侧原子性，替代 loadCount → set → save 的非原子模式。
 *
 * @param conversationId 会话 ID
 * @param delta 成员计数变化量（+1 或 -1）
 */
@Modifying
@Query("""
    UPDATE ConversationEntity c
    SET c.memberCount = c.memberCount + :delta,
        c.updatedAt = CURRENT_TIMESTAMP
    WHERE c.id = :convId
""")
fun incrementMemberCount(
    @Param("convId") conversationId: String,
    @Param("delta") delta: Int
)
```

---

## 3. Flyway Migration 模式

### 3.1 已有命名规范

| 文件 | 命名模式 | 用途 |
|------|---------|------|
| `V1__init_schema.sql` | `V{version}__{description}.sql` | 初始建表 |
| `V1_2__seed_users.sql` | `V{major}_{minor}__{description}.sql` | 种子数据 |
| `V2__phase7_conversation_schema.sql` | 按 Phase 编号 | DDL 变更（ALTER TABLE） |
| `V3__add_friend_request_message.sql` | 功能描述 | 新增字段 |
| `V4__add_dead_letters.sql` | 功能描述 | 新建表 + 索引 |

### 3.2 约定的结构

- **文件位置**: `repository/src/main/resources/db/migration/`
- **版本号**: 整数递增，下一版本为 V5
- **注释**: 使用 `-- ` SQL 行注释说明设计决策编号和用途
- **ENGINE**: InnoDB + utf8mb4_unicode_ci
- **DDL 示例参考**: `V4__add_dead_letters.sql:1-23`

### 3.3 映射目标 — V5 Migration（H23, H24, D-80）

需要新增 `V5__phase11_data_integrity.sql`:

```sql
-- V5__phase11_data_integrity.sql
-- Phase 11: 数据一致性与竞态修复
-- D-80: 好友双向竞赛 — DB 唯一约束 + 幂等 catch
-- H23: friend_requests 无 UNIQUE 约束
-- H24: dead_letters.client_msg_id 仅普通索引

-- 1. friend_requests: 防止同一对用户重复 pending 申请（D-80）
ALTER TABLE friend_requests 
    ADD UNIQUE KEY uk_from_to_status (from_uid, to_uid, status);

-- 2. dead_letters: client_msg_id 升级为 UNIQUE（H24）
ALTER TABLE dead_letters 
    DROP INDEX idx_client_msg_id,
    ADD UNIQUE KEY uk_client_msg_id (client_msg_id);

-- 3. friendships: 确保 (user_smaller, user_larger) 唯一（D-80）
-- 注：V1 已有 uk_friendship(user_id, friend_id)，但语义是单向。
-- 此处确保按 smaller/larger 排序后的唯一性：
-- ALTER TABLE friendships ADD UNIQUE KEY uk_friendship_pair (user_id, friend_id);
-- (V1 已存在此约束，视实际情况决定是否需要调整)
```

**命名规范**: `V{version}__phase{N}_{description}.sql`，延续 Phase 编号惯例。

---

## 4. withContext(Dispatchers.IO) 阻塞 JPA 包裹模式

### 4.1 已有模式（参考实现）

#### A. Service 层标准模式 — ConversationService

**文件**: `service/src/main/kotlin/com/nebula/service/conversation/ConversationService.kt:113-117`

```kotlin
// 批量写入 — 单次 withContext 包裹多个 JPA 调用
withContext(Dispatchers.IO) {
    conversationRepository.save(conv)
    conversationMemberRepository.save(ownerMember)
    memberEntities.forEach { conversationMemberRepository.save(it) }
}
```

#### B. Service 层查询模式 — FriendService

**文件**: `service/src/main/kotlin/com/nebula/service/friend/FriendService.kt:80-82`

```kotlin
// 单次查询 — 每个 JPA 调用独立 withContext
val existingFriendship = withContext(Dispatchers.IO) {
    friendshipRepository.findByUserIdAndFriendId(smaller, larger)
}
```

#### C. Handler 层包裹模式 — SendMessageHandler

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/chat/send/SendMessageHandler.kt:88-91`

```kotlin
// Handler 内的 JPA 调用也包裹 withContext(IO)
val members = withContext(Dispatchers.IO) {
    conversationMemberRepository
        .findByConversationId(result.conversationId)
}.filter { it.userId != result.senderUid }
```

### 4.2 映射目标 — PushService 缺失包裹（D-84, M18）

| 文件 | 行号 | 问题 | 修复方式 |
|------|------|------|---------|
| `PushService.kt` | 58 | `pushMessage()` 中 `findByConversationId()` 直接调用 | 包裹 `withContext(Dispatchers.IO) { }` |
| `PushService.kt` | 174 | `pushConversationEvent()` 中 `findByConversationId()` 直接调用 | 同上 |

### 4.3 修复示例

```kotlin
// PushService.pushMessage() 修复后
suspend fun pushMessage(convId: String, chatMessage: ChatMessage, excludeUid: Long) {
    val members = withContext(Dispatchers.IO) {
        conversationMemberRepository.findByConversationId(convId)
    }
    // ... 其余逻辑不变（内存操作：StreamObserver 遍历）
}
```

---

## 5. Handler/Service/Repository 分层模式

### 5.1 已有模式（参考实现）

#### A. Handler — 轻量编排层

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/user/LoginHandler.kt:23-64`

```kotlin
class LoginHandler(
    private val userService: UserService,         // 依赖 Service
    private val sessionRegistry: SessionRegistry   // 依赖 Gateway 组件
) : Handler<LoginReq, LoginResp> {
    override val method: String = "user/login"
    
    override suspend fun handle(req: LoginReq): LoginResp {
        // Handler 层不包含业务逻辑，仅编排 Service + Gateway 组件
        if (req.hasToken()) {
            val existingSession = sessionRegistry.validate(token)
            if (existingSession != null) {
                return buildLoginResp(existingSession.userId, existingSession.token, req)
            }
        }
        val userId = userService.loginByPassword(req)
        val token = UUID.randomUUID().toString()
        return buildLoginResp(userId, token, req)
    }
}
```

**约定**:
- Handler 通过构造注入依赖（Service + Gateway 组件）
- `method` 属性定义路由 key
- `handle()` 是 suspend 函数
- 不包含业务逻辑，仅编排调用

#### B. Service — 业务逻辑层

**文件**: `service/src/main/kotlin/com/nebula/service/conversation/ConversationService.kt:35-39`

```kotlin
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val userRepository: UserRepository
)
```

**约定**:
- Service 只依赖 Repository 层（不依赖 Gateway 组件）
- JPA 调用包裹 `withContext(Dispatchers.IO)`
- 异常使用 `BizException` 子类（`ConversationException` 等）

#### C. Koin DI 注册模式

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/ChatHandlerModule.kt:21-34`

```kotlin
val chatHandlerModule = module {
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { UserStreamRegistry() }
    single { PushService(get(), get(), get()) }
    single { SendMessageHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) }
    // ...
    single<HandlerCollector> { ChatHandlerCollector(get(), get(), get(), get()) }
}
```

### 5.2 映射目标

| 修复场景 | 对应 Handler | 需要的注入变更 |
|---------|-------------|--------------|
| H10: LoginHandler 审计日志 | `LoginHandler` | 注入 Logger 或审计 Service，在 handle() 中添加成功/失败日志 |
| M24: 未读计数持久化 | `SendMessageHandler` | 在 `asyncUnreadAndPush()` 中增加 `memberRepo.incrementUnreadCount()` DB 写入 |
| M30: 非结构化 launch | `SendMessageHandler` | 已有 `sendHandlerScope` 注入，确认 `scope.launch {}` 使用正确 scope |

### 5.3 Handler 注入 TransactionTemplate 的模式

| Handler | 已注入 TxTemplate? | 已注入 LockManager? | 实际使用了事务包裹? |
|---------|-------------------|---------------------|-------------------|
| `CreateGroupHandler` | ❌ | ✅ | ❌ |
| `InviteMemberHandler` | ✅ | ✅ | ❌ |
| `LeaveGroupHandler` | ✅ | ✅ | ❌ |
| `KickMemberHandler` | ✅ | ✅ | ❌ |
| `FriendAddHandler` | 需查看 | ✅ | 需查看 |
| `FriendAcceptHandler` | 需查看 | ✅ | 需查看 |

---

## 6. KDoc 中文注释规范

### 6.1 已有规范示例

#### A. Entity 层 — 字段注释

**文件**: `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt:14-17`

```kotlin
class ConversationEntity(
    /** 会话类型：0=私聊, 2=群聊 */
    @Column(nullable = false)
    var type: Int,
    // ...
    /** 会话状态：0=正常, 1=已解散（D-17） */
    @Column(nullable = false)
    var status: Int = 0,
)
```

#### B. 类级别 KDoc — 完整模式

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationLockManager.kt:7-21`

```kotlin
/**
 * 会话级互斥锁管理器（D-19）。
 *
 * 按 conversationId 粒度提供互斥锁，确保同一会话的并发操作串行执行，
 * 避免 memberCount 读写竞争。不同会话之间的操作不互斥。
 *
 * 用法：
 * ```
 * conversationLockManager.withLock(conversationId) {
 *     transactionTemplate.execute {
 *         // 事务内的成员操作
 *     }
 * }
 * ```
 */
```

#### C. 方法级别 KDoc — 参数和返回值

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationLockManager.kt:26-35`

```kotlin
/**
 * 在指定会话的互斥锁保护下执行代码块。
 *
 * 使用 computeIfAbsent 惰性创建 Mutex（无锁竞争时无需创建新 Mutex）。
 * 必须先获取锁再执行事务，保证事务在锁内提交（D-19）。
 *
 * @param conversationId 会话 ID
 * @param block 在锁保护下执行的挂起代码块
 * @return 代码块的返回值
 */
```

### 6.2 映射目标 — KDoc 值矛盾修复（L01, L20）

**文件**: `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt:15`

```kotlin
// 当前: /** 会话类型：0=私聊, 2=群聊 */
// SQL:  type INT NOT NULL COMMENT '1=私聊, 2=群聊'
// 矛盾: KDoc 写 0=私聊，SQL 写 1=私聊

// 修复后：
/** 会话类型：1=私聊, 2=群聊（与 V1__init_schema.sql COMMENT 一致） */
```

**同时需检查**: Service 层使用的常量 `CONV_TYPE_PRIVATE = 0`（`ConversationService.kt:45`、`FriendService.kt:50`、`MessageService.kt:51`）是否与实际存储值一致——如果 DB 存的是 `1=私聊`，则代码中的 `0` 是错误的，需要批量修正。

---

## 7. CoroutineScope 生命周期管理模式

### 7.1 已有模式

#### A. Dispatcher 全局 scope — 标准模式

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/Dispatcher.kt:47-54`

```kotlin
@Suppress("unused")
private val scope = CoroutineScope(
    Dispatchers.IO +
    SupervisorJob() +
    CoroutineExceptionHandler { _, e ->
        logger.warn(e) { "Unhandled exception in dispatcher scope" }
    }
)
```

**特征**:
- `SupervisorJob()` 隔离子协程异常
- `CoroutineExceptionHandler` 兜底防止 JVM 崩溃
- `Dispatchers.IO` 线程池

#### B. Koin DI 注入 scope — SendMessageHandler 模式

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/ChatHandlerModule.kt:23`

```kotlin
single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
```

**使用方** (`SendMessageHandler.kt:73`):
```kotlin
scope.launch {
    asyncUnreadAndPush(result)
}
```

**约定**:
- Scope 通过 Koin `single(named("xxx"))` 注册，Handler 通过 `get(named("xxx"))` 注入
- 使用 `SupervisorJob()` 隔离异常
- `launch {}` 协程运行在 `Dispatchers.IO` 上

### 7.2 映射目标 — 非结构化 launch 修复（M30, M19）

| 文件 | 行号 | 问题 | 修复 |
|------|------|------|------|
| `SendMessageHandler.kt` | 73 | `scope.launch {}` 已使用注入的 scope | 确认 scope 使用 `SupervisorJob()` ✅ |
| `ChatService.kt` | ~484 | `scope.launch {}` 在线状态标记 | 检查 scope 定义是否正确 |
| `MessageRepositoryImpl.kt` | 116 | 独立 CoroutineScope 创建但 `stop()` 不 cancel | 添加 `job.cancel()` |

---

## 8. 测试文件结构模式

### 8.1 已有模式 — 单元测试

#### A. Service 单元测试 — MockK + runTest

**文件**: `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt:34-99`

```kotlin
class ConversationServiceTest {
    // 使用 lateinit var + mockk() 创建依赖
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var service: ConversationService

    @BeforeEach
    fun setup() {
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        service = ConversationService(conversationRepository, conversationMemberRepository, userRepository)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // 使用 runTest 包裹 suspend 函数测试
    @Test
    fun createGroupShouldCreateConversationAndMembersSuccessfully() = runTest {
        coEvery { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        
        val result = service.createGroup(req, ownerUid)
        
        assertEquals("测试群", result.name)
        coVerify(exactly = 1) { conversationRepository.save(match<ConversationEntity> { ... }) }
    }

    // 异常断言
    @Test
    fun createGroupShouldThrowInvalidParamWhenNameIsBlank() = runTest {
        val ex = assertThrows<ConversationException> { service.createGroup(req, ownerUid) }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }
}
```

**约定**:
- `MockK` mock 框架 + `coEvery/coVerify` 协程支持
- `runTest` 测试协程上下文
- `@BeforeEach` 初始化 mock，`@AfterEach` 清理
- 方法命名: `{method}Should{expectedBehavior}`
- 中文测试方法注释 `/** 群名称为空时抛出 INVALID_PARAM */`

#### B. Handler 单元测试 — 轻量模式

**文件**: `gateway/src/test/kotlin/com/nebula/gateway/handler/user/LoginHandlerTest.kt:30-127`

```kotlin
class LoginHandlerTest {
    private lateinit var userService: UserService
    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var handler: LoginHandler

    @BeforeEach
    fun setUp() {
        userService = mockk()
        sessionRegistry = mockk<SessionRegistry>()
        handler = LoginHandler(userService, sessionRegistry)
    }

    @Test
    fun loginByPasswordShouldSucceed() = runTest {
        coEvery { userService.loginByPassword(any()) } returns 1001L
        val resp = handler.handle(req)
        assertEquals(1001L, resp.uid)
    }
}
```

### 8.2 映射目标 — 缺失测试

| 测试需求 | 参考模板文件 | 测试类型 |
|---------|------------|---------|
| T03: 好友双向竞赛并发测试 | `ConversationServiceTest.kt` | Service 单元测试 + 并发场景 |
| T04: memberCount 并发更新测试 | `ConversationServiceTest.kt` | Service 单元测试 |
| T05: DeadLetter payload 补偿测试 | `DeadLetterServiceTest.kt` | Service 单元测试 |
| T06: SeqService Redis 重启恢复测试 | `SeqServiceTest.kt` (如存在) | Service 集成测试 |
| T07: LoginHandler 审计日志验证 | `LoginHandlerTest.kt` | Handler 单元测试 |
| T01: SnowflakeIdGeneratorTest 反射替代 | `SnowflakeIdGeneratorTest.kt` | 单元测试改进 |

### 8.3 测试文件命名规范

| 被测文件 | 测试文件 | 位置 |
|---------|---------|------|
| `ConversationService.kt` | `ConversationServiceTest.kt` | `service/src/test/kotlin/.../conversation/` |
| `LoginHandler.kt` | `LoginHandlerTest.kt` | `gateway/src/test/kotlin/.../handler/user/` |
| `FriendService.kt` | `FriendServiceTest.kt` | `service/src/test/kotlin/.../friend/` |
| `DeadLetterService.kt` | `DeadLetterServiceTest.kt` | `service/src/test/kotlin/.../admin/` |
| `SeqService.kt` | `SeqServiceTest.kt` | `service/src/test/kotlin/.../sequence/` |

---

## 9. 补充模式：空安全与代码质量

### 9.1 !! 空断言替换（D-86）

**问题分布**（L08-L12）:

| 文件 | 行号 | 当前写法 | 推荐替换 |
|------|------|---------|---------|
| `MessageService.kt` | 252 | `id!!` | `id ?: error("MessageEntity.id 不应为 null")` |
| `UserService.kt` | 92,120,157,183,212 | `user.id!!` | `requireNotNull(user.id) { "用户ID不能为null" }` |
| `ConversationService.kt` | 151,164 | `it.id!!` | `it.id ?: error("会话ID不能为null")` |
| `ChatService.kt` | 253,312 | `merge().!!` | `requireNotNull(...)` 前置校验 |
| `JpaConfig.kt` | 47 | `emfBean.getObject()!!` | `?: error("EntityManagerFactory 未初始化")` |

### 9.2 魔法数字 → 命名常量/枚举（L02-L06）

**问题分布**:

| 文件 | 字段 | 当前 | 建议 |
|------|------|------|------|
| `ConversationEntity.kt:15` | `type` | Int: 0/2 | 枚举 `ConversationType` |
| `ConversationEntity.kt:39` | `status` | Int: 0/1 | 枚举 `ConversationStatus` |
| `UserEntity.kt` | `privacyStatus` | Int: 0/1/2 | 枚举 `PrivacyLevel` |
| `FriendRequestEntity.kt` | `status` | Int: 0/1/2 | 枚举 `FriendRequestStatus` |
| `FriendshipEntity.kt` | `deleted` | Int: 0/1 | 常量 `DELETED_FLAG` 或 `isActive()` 属性 |
| 6+ 文件 | `member.deleted == 0/1` | 多处 | 提取 `ConversationMemberEntity.isActive()` 扩展属性 |

### 9.3 DRY 提取 — 重复逻辑（L13-L15）

**参考模式**:

```kotlin
// L14: 重复 9 次的模式 → 提取为 Repository 扩展
// 当前（在 ConversationService.kt, FriendService.kt 等多处出现）:
withContext(Dispatchers.IO) { 
    conversationRepository.findById(convId).orElse(null) 
}

// 建议提取为:
suspend fun ConversationRepository.findByIdOrNull(id: String): ConversationEntity? =
    withContext(Dispatchers.IO) { findById(id).orElse(null) }

// L13: 重复 4+ 次
// 当前:
member == null || member.deleted == 1

// 建议提取为:
val ConversationMemberEntity.isActive: Boolean get() = deleted == 0
```

---

## 10. 模式综合决策表

| 修复 | 参考模式 | 模板文件 | 关键差异 |
|------|---------|---------|---------|
| H14-H19: 事务包裹 | TransactionTemplate + ConversationLockManager | `ConversationLockManager.kt:36` `InviteMemberHandler.kt:29-33` | Service 层无事务感知，需在 Handler 层包裹 |
| H22: memberCount 原子 | `@Modifying @Query` JPQL | `ConversationMemberRepository.kt:28-37` | 新增 `ConversationRepository.incrementMemberCount()` |
| H21: SeqService 恢复 | ModuleInitializer 模式 | `NebulaServer.kt:66-69` | 启动时遍历会话初始化 Redis Key |
| H23-H24: 唯一约束 | Flyway Migration V4 模式 | `V4__add_dead_letters.sql` | 新增 V5 migration |
| M18: PushService IO | `withContext(Dispatchers.IO)` | `ConversationService.kt:113` | PushService 内 JPA 调用改为包裹模式 |
| M01-M07: Step 链死代码 | 无（需删除） | — | 7 文件 + 对应测试直接删除 |
| M30: 非结构化 launch | `sendHandlerScope` | `ChatHandlerModule.kt:23` | 已有注入，确认 scope 正确 |
| L01-L20: KDoc + 魔法数字 | 中文 KDoc 规范 | `ConversationEntity.kt:14-40` | 修复注释值矛盾 |
| T03-T07: 缺失测试 | MockK + runTest | `ConversationServiceTest.kt` | 按模板补充测试 |
| M09-M12: payload 丢失 | DeadLetterService.compensate/retry | `DeadLetterService.kt:142,205` | payload 替换空串为实际数据 |
| M15: 分页 total bug | Repository query 模式 | `DeadLetterService.kt:239-244` | findByStatus 后单独 countByStatus 获取 total |
| M16: markPermanentFailed bug | compensate 模式 | `DeadLetterService.kt:255-260` | 修复 failCount 查询条件逻辑 |

---

## 11. 总结

### 核心模式清单

| # | 模式名称 | 模板文件（行号） | 映射文件数 |
|---|---------|-----------------|-----------|
| 1 | TransactionTemplate 事务包裹 | `ConversationLockManager.kt:36-39` | 6 Service 方法 |
| 2 | JPQL @Modifying 原子更新 | `ConversationMemberRepository.kt:28-57` | 1 Repository 新增方法，3 调用点替换 |
| 3 | Flyway Migration | `V4__add_dead_letters.sql:1-23` | 1 新 Migration 文件 |
| 4 | withContext(Dispatchers.IO) | `ConversationService.kt:113` | 2 PushService 方法 |
| 5 | Handler/Service/Repository 分层 | `LoginHandler.kt:23-64` / `ConversationService.kt:35-39` | 多处 Handler 注入变更 |
| 6 | KDoc 中文注释规范 | `ConversationLockManager.kt:7-35` | 1 Entity 注释修正 + 全局魔法数字清理 |
| 7 | CoroutineScope 生命周期 | `Dispatcher.kt:47-54` / `ChatHandlerModule.kt:23` | 1 MessageRepositoryImpl stop() 修复 |
| 8 | 测试文件结构 (MockK + runTest) | `ConversationServiceTest.kt:34-99` | 5 个缺失测试补充 |

### 修复实施顺序建议

1. **先安全后业务**: Wave 1 (安全加固 + ShutdownHook + 异常处理) → Wave 2 (事务 + 数据一致性) → Wave 3 (代码质量)
2. **先基础设施后逻辑**: Flyway Migration → TransactionTemplate → JPQL 原子更新 → 逻辑修复
3. **每修复补测试**: 按 T01-T07 顺序，在对应修复完成后立即补充测试
4. **全量回归每波**: Wave 1 完成后全量测试 → Wave 2 → Wave 3

## PATTERNS COMPLETE
