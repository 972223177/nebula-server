---
phase: 15
mapper: nx-pattern-mapper
---
# Phase 15 测试覆盖缺口闭合 — 代码模式映射

## 一、Repository 层测试模式

### 模式 A: Redis 组件 Mock 单元测试

**模式摘要**: 纯 MockK 单元测试，通过反射注入 mock `RedisCoroutinesCommands`，绕过 Lettuce 内部构造依赖。协程使用 `runTest` 包裹，Redis 操作使用 `coEvery`/`coVerify` 模拟。

**模板文件**: `OnlineStatusRepositoryTest.kt` 
- 路径: `repository/src/test/kotlin/com/nebula/repository/redis/OnlineStatusRepositoryTest.kt`
- 适用: SessionRepositoryTest, PrivacyRepositoryTest, MessageQueueRepositoryTest

**关键模式特征**:
1. 类注解: `@OptIn(ExperimentalLettuceCoroutinesApi::class)`
2. 反射注入模式：
   ```kotlin
   private fun injectMockRedis() {
       val field = SessionRepository::class.java.getDeclaredField("redis")
       field.isAccessible = true
       field.set(repository, mockRedis)
   }
   ```
3. 连接使用 `mockk(relaxed = true)` 简化构造
4. coEvery 设置 `redis.get("key")` / `redis.incr("key")` 等返回值
5. coVerify 验证 Redis 调用精确次数 `coVerify(exactly = 1) { redis.del(...) }`
6. 所有测试方法签名: `@Test fun xxx() = runTest`
7. 断言: `assertEquals` / `assertNull` / `assertTrue` (来自 `kotlin.test.*`)

**SessionRepositoryBatchDeleteTest 变体模式**:
- 使用同步 `verify` 而非 `coVerify`（pipeline 操作非 suspend）
- 使用 `kotlin.test.assertFailsWith` 而非 `org.junit.jupiter.api.Assertions.assertThrows`
- 提供了 `batchDeleteWithEmptyListShouldDoNothing` 边界测试模板

**模式变体对比**:

| 特征 | OnlineStatusRepositoryTest | SessionRepositoryBatchDeleteTest |
|------|---------------------------|--------------------------------|
| Redis mock 方式 | 反射注入独立的 `redis` 字段 | 直接使用 `connection` mock |
| 验证方式 | coVerify (suspend) | verify (非 suspend) |
| 异常断言 | assertNull/assertTrue | assertFailsWith\<RuntimeException\> |
| @BeforeEach | 创建 mock + 注入 | 内联创建 connection |

**推荐**: 对于新增的 SessionRepositoryTest，优先参考 **SessionRepositoryBatchDeleteTest** 的模式（直接使用 connection mock 更简洁），同时结合 **OnlineStatusRepositoryTest** 的 coEvery/coVerify 模式（因为 SessionRepository 主要方法为 suspend fun）。

---

### 模式 B: JPA 集成测试（Hibernate Session 模式）

**模式摘要**: 继承 `DatabaseTestBase`（TestContainers MySQL + Flyway），使用 Hibernate Configuration 手动构建 SessionFactory（非 Spring Data Repository），通过自定义 `doInSession` 辅助方法执行事务操作。

**模板文件**: `UserRepositoryIntegrationTest.kt`
- 路径: `repository/src/test/kotlin/com/nebula/repository/repository/UserRepositoryIntegrationTest.kt`
- 适用: 改进现有集成测试（UserRepository、FriendshipRepository、ConversationRepository）

**关键模式特征**:
1. **基类**: `class XxxTest : DatabaseTestBase()`
2. **生命周期**: `@BeforeAll` 创建 SessionFactory + `@AfterAll` 关闭
3. **SessionFactory 构建**:
   ```kotlin
   val config = Configuration()
   config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
   config.setProperty("hibernate.hbm2ddl.auto", "validate")
   config.addAnnotatedClass(UserEntity::class.java)
   config.properties["hibernate.connection.datasource"] = getDataSource()
   sessionFactory = config.buildSessionFactory()
   ```
4. **事务辅助方法**:
   ```kotlin
   private fun <T> doInTransaction(block: (EntityManager) -> T): T { ... }
   private fun <T> doInReadOnly(block: (EntityManager) -> T): T { ... }
   // 或 Session 版本:
   private fun <T> doInSession(block: (Session) -> T): T { ... }
   ```
5. **测试数据工厂**: 使用唯一计数器/ID 避免数据冲突
6. **@BeforeEach cleanup**: 清理测试数据避免污染
7. **异常断言**: `assertFailsWith<Exception>` / `assertFailsWith<PersistenceException>`

**FriendshipRepositoryIntegrationTest 变体**:
- 使用 `companion object` 定义预置测试用户常量
- 使用 `createTestUser` / `createFriendship` / `createFriendRequest` 工厂方法

**ConversationRepositoryIntegrationTest 变体**:
- 使用 `doInSession`（Session 模式）而非 `doInTransaction`（EntityManager 模式）
- @BeforeEach 清理使用 `createNativeMutationQuery` 直接删表

**推荐**: 
- 新增 MessageRepositoryTest 应参考 **ConversationRepositoryIntegrationTest**（Session 模式），因为需要测试 MessageEntity + ConversationMemberEntity 关联
- 新增 DeadLetterRepositoryTest 应参考 **ConversationRepositoryIntegrationTest**（Session 模式），因为涉及独立表操作
- 改进现有集成测试建议统一使用 **ConversationRepositoryIntegrationTest** 的 `doInSession` 模式（更简洁）

---

### 模式 C: Flyway 迁移验证测试

**模式摘要**: 继承 `DatabaseTestBase`，通过 JDBC DataSource 直接查询 `information_schema` 验证表结构。

**模板文件**: `FlywayMigrationTest.kt`
- 路径: `repository/src/test/kotlin/com/nebula/repository/testutil/FlywayMigrationTest.kt`
- 适用: Gateway 层 FlywayMigrationTest 补充 V4/V5

**关键模式特征**:
1. 直接使用 `getDataSource().connection` 执行原始 SQL
2. 查询 `information_schema.TABLES` / `information_schema.COLUMNS` 验证结构
3. `assertColumn` 辅助方法验证列名、nullability、默认值
4. 种子数据验证使用原始 `SELECT COUNT(*)` + 逐字段断言

---

## 二、Service 层测试模式

### 模式 D: 纯 MockK 单元测试（标准模式）

**模式摘要**: 使用 MockK 完全 mock 所有 Repository/依赖，构造器注入，通过 `coEvery` 控制 mock 行为，`coVerify` 验证调用次数和参数。

**模板文件**: `ConversationServiceTest.kt`
- 路径: `service/src/test/kotlin/com/nebula/service/conversation/ConversationServiceTest.kt`
- 适用: ConversationServiceTest 补充、UserPrivacyServiceTest 补充、MessageServiceTest 补充

**关键模式特征**:
1. **mock 创建**: `mockk()`（默认 strict 模式，未设行为会抛异常）
2. **依赖注入**: 构造器直接传 mock 实例
3. **@BeforeEach**: 创建所有 mock + 构建 Service 实例
4. **@AfterEach**: `clearAllMocks()` 清理状态
5. **协程测试**: 所有测试方法标记 `= runTest`
6. **test data factories**: 使用 `private fun xxx()` 或 `private val xxx by lazy {}` 创建实体
7. **异常断言**: `assertThrows<ConversationException>` + `assertEquals(BizCode.xxx, ex.bizCode)`
8. **参数匹配验证**: `coVerify { repo.save(match<Entity> { it.field == value }) }`
9. **save 返回自身**: `coEvery { repo.save(any()) } answers { firstArg() }`

**SeqServiceTest 变体模式（Redis 反射注入）**:
- 反射注入 + runTest + coEvery/coVerify
- @BeforeEach 中 `injectRedisMock(redis)` 
- 每次测试独立设置 mock 返回值
- 使用 `kotlin.test` 断言（assertTrue/assertFalse）

**UserServiceTest 变体模式**:
- 使用 `spyk()` 部分 mock（用于覆写 `verifyPassword`）
- 同时使用 `coEvery` (suspend) 和 `every` (非 suspend)
- 部分方法使用 `runBlocking` 替代 `runTest`（反模式，见 P2-02）

**FriendServiceTest 变体模式**:
- 同时使用 `coEvery` 和 `every`（非 suspend 和 suspend 混合）
- 使用 `every` 替代 `coEvery` 非 suspend repository 方法（如 friendshipRepository.findByUserIdAndFriendId）
- 复杂 mock 状态管理（如 `var friendCheckCount` 计数器）

**推荐模板选择**:

| 新增需求 | 推荐模板 | 理由 |
|---------|---------|------|
| ConversationServiceTest 补充 | ConversationServiceTest.kt | 同类文件，保持模式一致 |
| SeqServiceTest recoverSequences | SeqServiceTest.kt | 同类文件，Redis 反射注入模式 |
| MessageServiceTest 补充 (countByConversationId/checkAndSetDedup/incrementUnreadCount) | MessageServiceTest.kt | 同类文件，保持 strict mock 模式 |
| UserPrivacyServiceTest 补充 (batchGetHideOnlineStatus) | UserPrivacyServiceTest.kt 或 UserServiceTest.kt | 同类文件，PrivacyRepository mock 模式 |
| UserServiceTest 补充 (DataIntegrityViolationException) | UserServiceTest.kt | 同类文件，spyk 模式 |
| FriendServiceTest 补充 (nextCursor 断言) | FriendServiceTest.kt | 同类文件，混合 coEvery/every 模式 |

---

## 三、Gateway 层测试模式

### 模式 E: Handler 单元测试（Session 上下文模式）

**模式摘要**: 通过 MockK 注入 Service 依赖，使用 `withContext(SessionKey(session))` 携带 Session 上下文，调用 `handler.handle(req)` 验证业务逻辑。

**模板文件**: `MessageSeqHandlerTest.kt`
- 路径: `gateway/src/test/kotlin/com/nebula/gateway/handler/message/MessageSeqHandlerTest.kt`
- 适用: 新增 Handler 无 Session 异常路径测试

**关键模式特征**:
1. **Session 注入（正常场景）**:
   ```kotlin
   val resp = withSession { handler.handle(req) }
   // 或
   val resp = withContext(SessionKey(session)) { handler.handle(req) }
   ```
2. **无 Session 测试**:
   ```kotlin
   // 不在 Session 上下文中调用
   val exception = assertFailsWith<BizException> {
       handler.handle(req)
   }
   assertEquals(BizCode.UNAUTHORIZED, exception.bizCode)
   ```
3. **Helper 方法**: `TestHelper.kt` 提供 `withSession()` / `sessionContext()` / `DEFAULT_SESSION`
4. **异常断言**: `assertFailsWith<BizException>` / `assertFailsWith<MessageException>`

**ReadReportHandlerTest 变体模式（含反射注入 Redis）**:
- 同 Handler 测试模式 + 反射注入 Redis mock
- `@OptIn(ExperimentalLettuceCoroutinesApi::class)`
- 使用 `coEvery { messageService.readReport(any(), any()) } returns Unit` 简化

**推荐**:
- 新增无 Session 异常路径测试：参考 **MessageSeqHandlerTest** 的 `handleShouldRequireSession` 方法
- 修复反射注入：需要先确定修复方案（D-15-03），当前模式不建议复制到新测试

---

## 四、关键技术模式对照表

### 4.1 MockK 使用模式

| 场景 | API | 适用层 |
|------|-----|--------|
| mock 创建 | `mockk()` (strict) / `mockk(relaxed = true)` | 全部 |
| 部分 mock | `spyk(obj)` | Service |
| suspend mock | `coEvery { ... }` / `coVerify { ... }` | Service, Gateway |
| 非 suspend mock | `every { ... }` / `verify { ... }` | Repository (pipeline) |
| 参数匹配 | `any()` / `match { it.field == value }` | 全部 |
| save 返回自身 | `answers { firstArg() }` | Service |
| 验证精确次数 | `coVerify(exactly = 1) { ... }` | 全部 |
| 验证未调用 | `coVerify(exactly = 0) { ... }` | 全部 |
| void 方法 | `just Runs` | Service, Gateway |

### 4.2 协程测试模式

| 模式 | 用法 | 注意事项 |
|------|------|---------|
| runTest | `@Test fun xxx() = runTest { ... }` | 标准模式，推荐 |
| runBlocking | `runBlocking { ... }` | 反模式(P2-02)，在 runTest 中嵌套使用 |
| withSession | `withSession { handler.handle(req) }` | Gateway 层专用 |
| SessionKey | `withContext(SessionKey(session)) { ... }` | Gateway 层专用 |

### 4.3 断言风格分布

| 断言 | 来源 | 使用场景 |
|------|------|---------|
| `assertEquals` / `assertNull` / `assertTrue` / `assertFalse` / `assertNotNull` | `org.junit.jupiter.api.Assertions.*` | UserServiceTest (标准) |
| `assertEquals` / `assertNull` / `assertTrue` / `assertNotNull` / `assertFailsWith` | `kotlin.test.*` | 多数文件 (推荐) |
| `assertThrows` | `org.junit.jupiter.api.Assertions.assertThrows` | ConversationServiceTest |
| `assertFailsWith` | `kotlin.test.assertFailsWith` | MessageServiceTest, FriendServiceTest |

**⚠ 问题**: 断言风格目前不统一，P2-03 已标记。建议所有新增测试统一使用 `kotlin.test.*` 断言。

### 4.4 Testcontainers 模式

| 要素 | 配置 |
|------|------|
| 容器 | MySQL 8.0, `MySQLContainer<Nothing>("mysql:8.0")` |
| 数据库名 | `nebula_test` |
| 连接池 | HikariCP, 最大 5 连接 |
| 迁移 | Flyway, `classpath:db/migration` |
| 基类 | `DatabaseTestBase` (带有 @Testcontainers + @TestInstance( PER_CLASS)) |

---

## 五、新需求 → 模板映射

### Repository 模块新增测试

| 新测试文件 | 最接近的现有模板 | 理由 | 关键差异 |
|-----------|----------------|------|---------|
| SessionRepositoryTest | SessionRepositoryBatchDeleteTest.kt | 同类组件，pipeline 操作模式 | 需覆盖 save/findByToken/delete/refreshTtl/saveRaw/findRaw/deleteKey - 主要是 suspend 方法，需用 coEvery/coVerify |
| MessageRepositoryTest | ConversationRepositoryIntegrationTest.kt | JPA 集成测试，Hibernate Session 模式 | 需注册 MessageEntity + 可能关联实体 |
| DeadLetterRepositoryTest | ConversationRepositoryIntegrationTest.kt | 独立 JPA 表，Session 模式 | 需单独的 @BeforeEach cleanup |
| PrivacyRepositoryTest | OnlineStatusRepositoryTest.kt | Redis 组件，反射注入模式 | 混合 Redis + JPA 需两套 mock |
| MessageQueueRepositoryTest | OnlineStatusRepositoryTest.kt | Redis Stream 操作，反射注入 | 需 mock Redis Stream 命令（xadd/xread/xdel） |

### Repository 模块改进现有测试

| 改进文件 | 参考模板 | 建议改进点 |
|---------|---------|-----------|
| UserRepositoryIntegrationTest | 自身 + ConversationRepositoryIntegrationTest | 补充游标分页测试，统一 doInSession 模式 |
| FriendshipRepositoryIntegrationTest | 自身 + ConversationRepositoryIntegrationTest | 补充游标分页测试 |
| ConversationRepositoryIntegrationTest | 自身 | 补充 incrementUnreadCount、批量查询方法测试 |

### Service 模块补充测试

| 补充方法 | 所属文件 | 模板方法 | 关键模式 |
|---------|---------|---------|---------|
| dissolveGroup | ConversationServiceTest | leaveGroup 相关测试 | 模拟 conversationRepository.findById + softDeleteAll + incrementMemberCount |
| getConversation / getConversationMembers / getMemberRole | ConversationServiceTest | getGroupMembers 相关测试 | 模拟 repository 查询，验证返回值和异常 |
| leaveGroup memberCount==1 分支 | ConversationServiceTest | leaveGroup 正常路径 | coEvery { countActiveByConversationId } returns 1L |
| recoverSequences | SeqServiceTest | nextSeq / tryRestoreSeq 模式 | 反射注入 Redis，模拟 SET 和 SETNX 返回 |
| countByConversationId | MessageServiceTest | pullMessages 模式 | mock messageRepository.countByConversationId |
| checkAndSetDedup | MessageServiceTest | sendMessage 模式 | mock messageQueueRepository.checkAndSetDedup |
| incrementUnreadCount | MessageServiceTest | readReport 模式 | mock memberRepository.incrementUnreadCount |
| batchGetHideOnlineStatus | UserPrivacyServiceTest | getHideOnlineStatus 模式 | coEvery { privacyRepository.batchGetHideOnlineStatus } returns setOf(...) |
| DataIntegrityViolationException 兜底 | UserServiceTest | usernameUniquenessConstraintViolation | coEvery { userRepository.save(any()) } throws DataIntegrityViolationException(...) |
| nextCursor 断言 | FriendServiceTest | listFriends 游标分页测试 | 添加断言：`assertEquals(expectedCursor, result.nextCursor)` |

### Gateway 模块修复/补充

| 需求 | 模板 | 关键模式 |
|------|------|---------|
| ReadReportHandlerTest 反射注入修复 | ReadReportHandlerTest.kt | 需先确定 D-15-03 方案后再改 |
| RedisDeliveryTrackerTest 反射注入修复 | RedisDeliveryTrackerTest.kt | 同上 |
| FlywayMigrationTest V4/V5 补充 | repository FlywayMigrationTest.kt | 查询 information_schema，验证 dead_letters 表结构 |
| Handler 无 Session 异常路径 | MessageSeqHandlerTest.handleShouldRequireSession | 不设置 Session 上下文，预期 UNAUTHORIZED |

---

## 六、模式中发现的共性改进建议

### 6.1 断言风格统一

**问题**: 多个测试文件混用 `org.junit.jupiter.api.Assertions.*` 和 `kotlin.test.*`。
**建议**: 所有新增测试统一使用 `kotlin.test.*` 断言（它提供了 `assertFailsWith` 等更 Kotlin 化的 API）。

### 6.2 runBlocking 反模式

**问题**: UserServiceTest 和 FriendServiceTest 中多处使用 `runBlocking` 而非 `runTest`，或在 `runTest` 内部嵌套 `runBlocking`。
**建议**: 新增测试全部使用 `runTest`。对现有代码中 `runBlocking` 的使用，如果只是包裹简单的协程调用，改为 `runTest`。

### 6.3 Repository 异常类型应精确

**问题**: 集成测试中使用 `assertFailsWith<Exception>` 而非精确的异常类型。
**建议**: 唯一约束违反应使用 `DataIntegrityViolationException`（Spring）或 `ConstraintViolationException`（Hibernate）。

### 6.4 save 返回自身的模式

**问题**: 多处使用 `answers { firstArg() }` 模式，但 TestHelper.kt 中明确注释说明无法提取为独立函数。
**建议**: 保持 `answers { firstArg() }` 内联在测试中，不要尝试提取。

### 6.5 MessageServiceTest seqService 全局化

**问题**: `@BeforeEach` 设置 `coEvery { seqService.nextSeq(any(), any()) } returns 1L`，可能导致特定测试遗漏真实行为。
**建议**: 对涉及 seqService 的新增测试方法，若需要特定 seq 值，在方法内部 `coEvery` 覆盖全局设置。

### 6.6 Gateway 反射注入的替代方案

**问题**: ReadReportHandlerTest 和 RedisDeliveryTrackerTest 使用反射字段注入，导致测试与实现紧耦合。
**建议**: 方案 A（构造函数注入）是最推荐的长远方案。如短期内不可行，新增测试仍可沿用反射注入模式以保持一致。

---

## PATTERNS COMPLETE
