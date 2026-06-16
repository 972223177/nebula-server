---
phase: 15
researcher: nx-researcher
---

# Phase 15 技术研究 — 测试覆盖缺口闭合

## 研究范围

本阶段涉及五项技术决策（D-15-01 ~ D-15-05），覆盖 Redis 测试策略、JPA 集成测试策略、反射注入修复、P2 范围决策、以及 seqService mock 策略。

## 技术栈上下文

- 语言：Kotlin
- 框架：Ktor（HTTP）、gRPC
- 数据库：MySQL（Spring Data JPA + Hibernate）、Redis（Lettuce 协程）
- 测试框架：MockK + kotlinx.coroutines.test + Testcontainers
- DI：Koin（gateway/service 模块）

---

## D-15-01: Redis 测试策略

### 问题

SessionRepository / PrivacyRepository / MessageQueueRepository / MessageRepository（Redis 部分）等纯 Redis 组件如何测试？

### 现有模式分析

| 模式 | 代表文件 | 做法 |
|------|---------|------|
| **MockK 单元测试** | `OnlineStatusRepositoryTest.kt` | 反射注入 mock `RedisCoroutinesCommands`，`coEvery`/`coVerify` 控制行为 |
| **Testcontainers Redis 集成测试** | `SeqServiceRedisRecoveryTest.kt`（继承 `RedisTestBase`） | 真实 Redis 7 Alpine 容器，验证 INCR/SETNX 协议行为 |

### 选项分析

#### 选项 A: MockK 单元测试（沿用 OnlineStatusRepositoryTest 模式）

**适用场景**：SessionRepository 的 8 个简单方法（save/findByToken/delete/refreshTtl/saveRaw/findRaw/deleteKey）

每个方法是否能通过 MockK 充分验证：

| 方法 | MockK 验证能力 | 局限 |
|------|---------------|------|
| `save(token, data, ttl)` | ✅ `coVerify { redis.setex(...) }` 验证 key/数据/TTL | 无法验证 JSON 序列化格式 |
| `findByToken(token)` | ✅ `coEvery { redis.get(...) } returns data` + 断言返回值 | — |
| `delete(token)` | ✅ `coVerify { redis.del(...) }` | — |
| `refreshTtl(token, ttl)` | ✅ `coVerify { redis.expire(...) }` | — |
| `saveRaw(key, value, ttl)` | ✅ `coVerify { redis.setex(...) }` | — |
| `findRaw(key)` | ✅ `coEvery { redis.get(...) } returns value` | — |
| `deleteKey(key)` | ✅ `coVerify { redis.del(...) }` | — |
| `batchDelete(keys)` | ✅ `coVerify { redis.del(...) }` 可验证 DEL 次数 | ❌ **无法验证 pipeline 行为**：`setAutoFlushCommands(false)` + `flushCommands()` 的容器恢复逻辑依赖真实 `StatefulRedisConnection`；async mock 未用于 verify（见审查报告问题 P3-12） |

**结论**：SessionRepository 的 7/8 简单方法可纯 MockK 覆盖。`batchDelete` 的 pipeline 恢复逻辑需要额外验证。

#### 选项 B: Testcontainers Redis 集成测试（沿用 RedisTestBase 模式）

**适用场景**：
- `SessionRepository.batchDelete` pipeline 行为验证
- `MessageQueueRepository` Redis Stream 协议行为（XADD/XREADGROUP/XACK/消费者组）
- `PrivacyRepository` 的 Redis 超时/异常路径（`withTimeout` 实际行为）
- 跨组件：Redis → MySQL 双写场景

**优点**：
- 验证 Redis 协议层行为（例如 SET NX EX 的原子性）
- 验证 Lettuce 协程 API 在真实 Redis 上的行为
- 验证 pipeline `flushCommands` 实际效果

**缺点**：
- 依赖 Docker 环境
- 测试速度较慢（~3-5秒/容器启动）
- 需要单独的集成测试目录

#### 选项 C: 两者结合（推荐 ✅）

**完整策略**：

```
SessionRepositoryTest.kt (P0-01)
├── MockK 单元测试 → save/findByToken/delete/refreshTtl/saveRaw/findRaw/deleteKey 共 14 个测试
└── Testcontainers 集成测试 → batchDelete pipeline 验证（2 个测试）

PrivacyRepositoryTest.kt (P0-04)
├── MockK 单元测试 → Redis 超时/异常/JSON 解析/MySQL 回退 (8 个测试)
└── Testcontainers 集成测试 → Redis→MySQL 双写一致性 (2 个测试)

MessageQueueRepositoryTest.kt (P0-05)
├── MockK 单元测试 → enqueue/consume/acknowledge/checkAndSetDedup 方法签名验证 (6 个测试)
└── Testcontainers 集成测试 → Stream 实际行为验证 (3 个测试)
```

**MySQL + Redis 混合场景测试**（PrivacyRepository）：

PrivacyRepository 的 `getHideOnlineStatus` 和 `setHideOnlineStatus` 同时操作 Redis 和 MySQL：
- **MockK 层**：mock `redis` 和 `userRepository`，验证：
  - Redis 命中时直接返回，不查 MySQL
  - Redis 未命中时回退 MySQL，结果写回 Redis
  - Redis 超时后回退 MySQL
- **Testcontainers 层**：同时启动 MySQL + Redis 容器，验证真实双写流程。可复用现有 `DatabaseTestBase` + `RedisTestBase` 的基类组合。

### 推荐方案 ✅ **选项 C (两者结合)**

| 组件 | MockK | Testcontainers | 理由 |
|------|-------|---------------|------|
| SessionRepository (8 methods) | ✅ 7个简单方法 | ✅ batchDelete pipeline | 简单方法反射注入即可，pipeline 需真实连接 |
| PrivacyRepository | ✅ 异常/回退/超时路径 | ✅ Redis→MySQL 双写 | 双写策略需集成验证 |
| MessageQueueRepository | ✅ 方法调用验证 | ✅ Stream 协议行为 | XADD/XREADGROUP/XACK 协议细节多 |

**风险与注意事项**：
1. 反射注入（同原有模式）存在字段重命名风险——建议集中使用，并在修改实现时同步更新测试
2. PrivacyRepository 的 Testcontainers 测试需要同时启动 MySQL + Redis 两个容器，确保 `DatabaseTestBase` 的静态容器与 Redis 容器不冲突
3. batchDelete 的 MockK 测试可验证 `coVerify { connection.setAutoFlushCommands(false) }` 和 `coVerify { connection.setAutoFlushCommands(true) }`（使用 `verify` 而非 `coVerify`，因 `setAutoFlushCommands` 非 suspend）

---

## D-15-02: JPA Repository 集成测试策略

### 问题

MessageRepository (JPA) / DeadLetterRepository (JPA) 如何编写集成测试？

### 现有模式分析

当前 `UserRepositoryIntegrationTest` / `ConversationRepositoryIntegrationTest` 使用 **Hibernate Session 模式**：

```kotlin
// 现有模式：使用 Hibernate Configuration + EntityManager
val config = Configuration()
config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
config.setProperty("hibernate.hbm2ddl.auto", "validate")
config.addAnnotatedClass(UserEntity::class.java)
config.properties["hibernate.connection.datasource"] = getDataSource()
val emf = config.buildSessionFactory()
val em = emf.createEntityManager()
em.createQuery("SELECT u FROM UserEntity u WHERE u.username = :username", ...)
```

### 选项分析

#### 选项 A: Testcontainers MySQL + @DataJpaTest

**优点**：
- Spring Boot 原生切片测试，自动配置 DataSource/EntityManager
- 可直接注入 Repository 接口

**缺点**：
- 需要引入 `spring-boot-starter-test` 依赖，本项目为 Ktor 项目，repository 模块无 Spring Boot 上下文
- 需要维护测试用的 application.properties
- 与现有 `DatabaseTestBase` 基础设施不兼容

**适用性评价**：❌ 不适合本项目架构。

#### 选项 B: Hibernate Session 模式（同现有）

**优点**：
- 与现有测试模式一致，学习成本低
- 不依赖 Spring 上下文
- 验证 SQL 逻辑正确性

**缺点**：
- **未验证 Spring Data 方法命名约定**：例如 `findByStatusOrderByCreatedAtAsc` 的方法名派生查询正确性未被验证
- **未验证 @Query 注解**：`MessageRepository.findMessagesBackward` 的 @Query JPQL 未被测试用于 Repository 接口
- 测试与生产代码使用不同的 API 入口

**命名约定验证缺失示例**：

```kotlin
// DeadLetterRepository 接口
interface DeadLetterRepository : JpaRepository<DeadLetterEntity, Long> {
    fun findByStatusOrderByCreatedAtAsc(status: String, pageable: Pageable): List<DeadLetterEntity>
    // 如果方法名改为 findByStatusOrderByCreatedAtDesc，测试不会发现
    // 如果参数类型从 String 改为 Enum，测试不会发现（JPQL 参数是强类型）
}
```

#### 选项 C: 统一使用 Spring Data Repository 接口（推荐 ✅）

**方案描述**：在使用现有 `DatabaseTestBase`（Testcontainers MySQL + Flyway）的基础上，通过 Spring Data JPA 的 `RepositoryFactorySupport` 创建 Repository 代理实例，直接通过 Repository 接口测试。

**实现方式**：

```kotlin
// 使用 RepositoryFactorySupport 创建 Repository 代理
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
// ... 或更简单的：
import org.springframework.data.jpa.repository.support.SimpleJpaRepository

class MessageRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private lateinit var messageRepo: MessageRepository

    @BeforeAll
    fun setUp() {
        emf = createEntityManagerFactory()
        // 通过 JpaRepositoryFactory 创建 Repository 代理
        val factory = JpaRepositoryFactory(emf.createEntityManager())
        messageRepo = factory.getRepository(MessageRepository::class.java)
    }

    @Test
    fun findMessagesBackwardShouldReturnMessagesBeforeCursor() {
        // 先持久化测试数据（通过 EntityManager）
        doInTransaction { em ->
            (1L..5L).forEach { id ->
                em.persist(MessageEntity(
                    conversationId = "conv-test",
                    senderUid = 1001L,
                    messageType = 1,
                    content = "msg-$id",
                    clientTs = now,
                    serverTs = now
                ).apply { this.id = id })
            }
        }

        // 通过 Repository 接口查询（测试 @Query 注解和命名约定）
        val result = messageRepo.findMessagesBackward(
            conversationId = "conv-test",
            cursor = 5L,
            pageable = Pageable.ofSize(3)
        )

        assertEquals(3, result.size)
        assertEquals(4L, result[0].id) // ORDER BY id DESC
        assertEquals(3L, result[1].id)
        assertEquals(2L, result[2].id)
    }
}
```

**优点**：
- ✅ 直接测试生产代码使用的 Repository 接口
- ✅ 验证 @Query JPQL 的正确性
- ✅ 验证 Spring Data 方法命名约定的正确性
- ✅ 兼容现有 `DatabaseTestBase` 基础设施
- ✅ 不需要 Spring Boot 上下文

**缺点**：
- 需要新增 `spring-data-jpa` 的测试依赖（通常已有，确认是否在 `build.gradle.kts` 中配置）
- `JpaRepositoryFactory` 需要 `EntityManager` 实例，每个测试方法可能需要刷新 EntityManager

### 推荐方案 ✅ **选项 C (统一使用 Spring Data Repository 接口)**

| 组件 | 推荐测试方式 | 关键验证点 |
|------|-------------|-----------|
| MessageRepository | `JpaRepositoryFactory` 创建代理 | `findMessagesBackward`/`findMessagesForward` @Query 正确性 |
| DeadLetterRepository | `JpaRepositoryFactory` 创建代理 | `findByStatusAndFailCountLessThan`/`findByStatusOrderByCreatedAtAsc` 命名约定 |
| 游标分页方法 | Repository 接口直接调用 | 游标+排序+limit 组合逻辑 |

**对现有测试的建议**：现有 `UserRepositoryIntegrationTest` / `ConversationRepositoryIntegrationTest` 可保持 Hibernate Session 模式（已验证 SQL 逻辑），新测试统一使用 Repository 接口模式。长期可将旧测试迁移。

**具体实现步骤**：
1. 在 `repository/build.gradle.kts` 确认已包含 `spring-data-jpa` 依赖（test scope）
2. 创建新的测试基类 `RepositoryTestBase`，封装 `JpaRepositoryFactory` 创建逻辑
3. 为每个 Repository 编写集成测试

### 代码样例：MessageRepository 集成测试

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private latevel init var messageRepo: MessageRepository
    private val now = System.currentTimeMillis()

    @BeforeAll
    fun setUp() {
        emf = createEntityManagerFactory()
        // 每次获取新的 EM（RepositoryFactory 内部缓存了 EM，需要注意隔离性）
        val factory = JpaRepositoryFactory(emf.createEntityManager())
        messageRepo = factory.getRepository(MessageRepository::class.java)
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) emf.close()
    }

    private fun createEntityManagerFactory(): EntityManagerFactory {
        val config = Configuration()
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        config.addAnnotatedClass(MessageEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        return config.buildSessionFactory()
    }

    /** 持久化一条测试消息 */
    private fun insertMessage(id: Long, convId: String = "conv-test") {
        val em = emf.createEntityManager()
        em.transaction.begin()
        em.persist(MessageEntity(
            conversationId = convId,
            senderUid = 1001L,
            messageType = 1,
            content = "msg-$id",
            clientTs = now,
            serverTs = now
        ).apply { this.id = id })
        em.transaction.commit()
        em.close()
    }

    @Test
    fun findMessagesBackwardShouldApplyCursorAndDescOrder() {
        // 准备：插入 5 条消息
        (1L..5L).forEach { insertMessage(it) }

        // 执行：向后拉取 cursor=5，limit=3
        val result = messageRepo.findMessagesBackward(
            "conv-test", 5L, Pageable.ofSize(3)
        )

        // 验证：返回 ID 4,3,2（DESC 排序，不包含 cursor=5）
        assertEquals(3, result.size)
        assertEquals(listOf(4L, 3L, 2L), result.map { it.id })
    }

    @Test
    fun findMessagesBackwardShouldReturnEmptyWhenNoOlderMessages() {
        (1L..3L).forEach { insertMessage(it) }

        val result = messageRepo.findMessagesBackward(
            "conv-test", 1L, Pageable.ofSize(3)
        )

        assertTrue(result.isEmpty(), "cursor=1 时没有更旧的消息")
    }

    @Test
    fun countByConversationIdShouldReturnCorrectCount() {
        (1L..3L).forEach { insertMessage(it, "conv-count") }
        insertMessage(10L, "conv-other")

        val count = messageRepo.countByConversationId("conv-count")

        assertEquals(3L, count)
    }
}
```

### 风险与注意事项

1. `JpaRepositoryFactory` 创建的 Repository 实例依赖 `EntityManager` 的生命周期。跨测试隔离需要通过 `@BeforeEach` 清理数据
2. 游标分页测试需要精确控制测试数据的 ID 顺序（Snowflake ID 单调递增），建议使用手动赋值的 Long ID
3. 唯一约束测试应使用 `DataIntegrityViolationException` 而非宽泛的 `Exception`（修正 P2-09）

---

## D-15-03: 反射注入修复

### 问题

`ReadReportHandlerTest` 和 `RedisDeliveryTrackerTest` 通过反射将 mock `RedisCoroutinesCommands` 注入到 `private val redis` 字段。

### 现有代码分析

**ReadReportHandler** (`gateway/src/main/.../message/ReadReportHandler.kt`):
```kotlin
class ReadReportHandler(
    private val messageService: MessageService,
    private val conversationService: ConversationService,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>  // 仅用于构造 redis
) : Handler<ReadReportReq, Response> {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())  // 构造函数内初始化
    // ...
}
```

**RedisDeliveryTracker** (`gateway/src/main/.../delivery/RedisDeliveryTracker.kt`):
```kotlin
class RedisDeliveryTracker(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
    // ...
}
```

两者模式相同：构造函数接受 `connection`，内部通过 `connection.reactive()` 创建 `redis`。

### 选项分析

#### 选项 A: 构造函数注入 + @VisibleForTesting（推荐 ✅）

**方案**：将 `redis` 提升为构造参数并提供默认值。

**ReadReportHandler 修改**：
```kotlin
class ReadReportHandler(
    private val messageService: MessageService,
    private val conversationService: ConversationService,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>,
    /** 测试用：允许直接注入 mock redis。生产环境由 connection.reactive() 初始化 */
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
) : Handler<ReadReportReq, Response> {
    // 删除 private val redis 行
}
```

**RedisDeliveryTracker 修改**：
```kotlin
class RedisDeliveryTracker(
    private val connection: StatefulRedisConnection<String, String>,
    /** 测试用：允许直接注入 mock redis。生产环境由 connection.reactive() 初始化 */
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())
) {
    // 删除 private val redis 行
}
```

**测试修改**：
```kotlin
// ReadReportHandlerTest — 不再需要反射
handler = ReadReportHandler(
    messageService, conversationService, pushService, connection,
    redis = this.redis  // 直接注入 mock
)

// RedisDeliveryTrackerTest — 不再需要反射
tracker = RedisDeliveryTracker(connection, redis = mockRedis)
```

**优点**：
- ✅ 消除反射，测试与实现解耦
- ✅ 构造函数签名清晰，测试意图一目了然
- ✅ 不破坏生产代码的默认构造行为（默认值参数）
- ✅ 不引入新依赖
- ✅ 不影响 Koin DI（connection 参数不变）

**成本**：
- 修改 2 个生产文件（添加构造参数默认值）
- 修改 2 个测试文件（删除反射，改为直接传参）
- 低风险：Kotlin 默认参数值保证向后兼容

#### 选项 B: 保留反射注入

**理由**：避免修改生产代码。

**缺点**：
- ❌ 字段重命名时测试静默失败（编译通过，运行时反射找不到字段）
- ❌ 测试与实现紧耦合——违反"测试应使用公开 API"原则
- ❌ SeqServiceTest 的审查报告已指出此问题（service-review.md 第 90-91 行）
- ❌ 无法通过 IDE 重命名/查找引用发现测试依赖

#### 选项 C: 重构依赖注入

**方案**：将 `RedisCoroutinesCommands` 的创建改为工厂方法/提供者注入。

```kotlin
// 方式 1: 函数类型参数
class ReadReportHandler(
    ...
    private val redisProvider: (StatefulRedisConnection<String, String>) -> RedisCoroutinesCommands<String, String>
) {
    private val redis = redisProvider(connection)
}

// 方式 2: 接口抽象
interface RedisCommandsProvider {
    fun create(connection: StatefulRedisConnection<String, String>): RedisCoroutinesCommands<String, String>
}
```

**成本**：
- 过度设计——仅为了测试而引入抽象层
- 需要修改 Koin 模块注册
- 增加理解成本

### 推荐方案 ✅ **选项 A (构造函数注入 + 默认参数)**

**理由**：
- 最小化生产代码修改（仅添加 1 行默认参数）
- 消除反射脆弱性
- 符合 Kotlin 惯用法（默认参数值）
- 测试代码更清晰

**风险**：
- 需要注意构造函数参数顺序：新参数放在最后，保持现有调用方不受影响
- 确保 `connection` 参数不被移除（Koin 依赖此参数）

---

## D-15-04: P2 范围决策

### 逐项分析 P2-01 ~ P2-11

#### P2-01: Flyway V4/V5 迁移未覆盖

| 项目 | 内容 |
|------|------|
| **问题** | `FlywayMigrationTest` 仅验证了 V1~V3 迁移后的表结构，未验证 V4（dead_letters 表）和 V5（唯一约束+索引变更） |
| **评估** | **高价值 — 建议纳入 ✅** |
| **理由** | V4 dead_letters 表和 V5 的 `uk_friendship_pair`、`uk_from_to_status`、`uk_client_msg_id` 三个唯一约束是 Phase 10 (消息可靠性) 和 Phase 11 (数据一致性) 的核心基础设施。这些约束的缺失会导致生产数据损坏但测试不报错。 |
| **成本** | 低：新增 2 个 @Test 方法，模式与现有测试完全一致（`queryColumns` + `assertColumn` + 索引检查） |
| **实现** | 新增 `shouldHaveDeadLettersTable()` 验证 V4 字段结构；新增 `shouldHaveDataIntegrityConstraints()` 验证 V5 唯一约束（通过 `information_schema.TABLE_CONSTRAINTS`） |

#### P2-02: runBlocking 嵌套 runTest 反模式

| 项目 | 内容 |
|------|------|
| **问题** | `UserServiceTest` 和 `FriendServiceTest` 多处使用 `runBlocking { ... }` 在 `runTest` 闭包内，破坏了虚拟时间效果 |
| **评估** | **高价值 — 建议纳入 ✅** |
| **理由** | 这是 Kotlin 协程测试的已知反模式。当前虽然"能工作"（因为业务代码未使用虚拟时间），但若将来引入 `delay`/`withTimeout` 等依赖时间的逻辑，这些测试会不可靠。 |
| **成本** | 中：`assertThrows` 需要非 suspend lambda，不能直接调用 suspend 函数。需要改写为 `assertFailsWith<Exception> { runTest { ... } }` 外层模式 |
| **影响文件** | `UserServiceTest.kt`（8处）、`FriendServiceTest.kt`（14处） |
| **修复模式** | 将 `runBlocking { service.method() }` 改为直接在 `runTest` 中调用。`assertThrows` 改为 `kotlin.test.assertFailsWith` 包裹 `runTest`。 |

**注意**：这是 Kotlin 协程测试的一个已知痛点。审查报告已指出"当 assertThrows 需要非 suspend lambda 时，这是可接受的模式"。实际上，`kotlin.test.assertFailsWith` 接受 `() -> Unit` 而非 suspend lambda，所以完全消除 runBlocking 需要将断言放在 `runTest` 外部。

```kotlin
// 当前（反模式）：
@Test
fun test() = runTest {
    runBlocking { service.method() }  // 破坏虚拟时间
}

// 修复后：
@Test
fun test() = runTest {
    service.method()  // 直接调用
}
// assertFailsWith 场景：
@Test
fun testException() = runTest {
    assertFailsWith<ChatException> {
        service.method()  // 在 runTest 内直接调用 suspend 函数
    }
}
```

实际上，`kotlin.test.assertFailsWith` 的参数是 `() -> T`，所以可以直接在 `runTest` 内使用——不需要 `runBlocking`。

#### P2-03: 断言风格不统一

| 项目 | 内容 |
|------|------|
| **问题** | 混合使用 `org.junit.jupiter.api.Assertions.*` 和 `kotlin.test.*` |
| **评估** | **低价值 — 建议延期** |
| **理由** | 纯代码风格问题，不影响测试正确性。`kotlin.test.*` 提供更好的类型推断，但 JUnit 断言功能相同。 |
| **成本** | 低（替换 import），但影响 6+ 个文件，收益低 |

#### P2-04: LogInterceptorTest 实质性为空

| 项目 | 内容 |
|------|------|
| **问题** | 仅 1 个测试验证返回值透传，未验证日志行为 |
| **评估** | **低价值 — 建议延期** |
| **理由** | LogInterceptor 是纯日志拦截器，验证日志输出需要 mock 日志框架，测试成本高、收益低。当前测试至少验证了返回值透传。 |

#### P2-05: GatewayModuleTest 重复 startKoin

| 项目 | 内容 |
|------|------|
| **问题** | 5 次独立 `startKoin`/`stopKoin`，文件过长（421 行） |
| **评估** | **低价值 — 建议延期** |
| **理由** | 性能优化问题。测试当前正确运行，重复 startKoin 只是稍慢但不影响正确性。 |

#### P2-06: ProtoCodecTest 反序列化验证不完整

| 项目 | 内容 |
|------|------|
| **问题** | roundtrip 只 `assertNotNull`，未验证字段级一致性 |
| **评估** | **中价值 — 按需纳入** |
| **理由** | 如果序列化/反序列化逻辑有 bug（如字段映射错误），当前测试无法发现。但 ProtoCodec 是通用工具类，业务字段在 Handler 层测试中已覆盖。 |
| **成本** | 低：在现有 roundtrip 测试中添加字段级断言 |
| **建议** | 纳入 ✅（成本极低，约 5 行代码） |

#### P2-07: PullMessagesHandlerTest cursor 值未验证

| 项目 | 内容 |
|------|------|
| **问题** | cursor=0 时预期转换为 `Long.MAX_VALUE`，但测试未验证传递给 service 的值 |
| **评估** | **中价值 — 建议纳入 ✅** |
| **理由** | cursor=0 到 `Long.MAX_VALUE` 的转换是业务逻辑关键点。如果转换逻辑被错误修改，测试不会发现。 |
| **成本** | 低：添加 `coVerify` 验证传递参数 |

#### P2-08: MySQL 集成测试使用 Hibernate Session 非 Repository 接口

（已由 D-15-02 覆盖，此处不再重复）

#### P2-09: 唯一约束异常捕获类型过于宽泛

| 项目 | 内容 |
|------|------|
| **问题** | 使用 `assertFailsWith<Exception>` 而非 `DataIntegrityViolationException` |
| **评估** | **中价值 — 建议纳入 ✅** |
| **理由** | 宽泛的异常类型掩盖了"是否真的是唯一约束冲突"的判断。如果 SQL 本身有语法错误或其他异常，测试也会通过。 |
| **成本** | 极低：替换 `Exception` 为 `DataIntegrityViolationException`，修改 2 处 |
| **影响文件** | `UserRepositoryIntegrationTest.kt`（L278）、`FriendshipRepositoryIntegrationTest.kt`（L216） |

#### P2-10: seqService mock 全局化

（由 D-15-05 覆盖）

#### P2-11: 好友关系硬编码

| 项目 | 内容 |
|------|------|
| **问题** | `findByUserIdAndFriendId(1, 2)` 常量值跨测试硬编码 |
| **评估** | **低价值 — 建议延期** |
| **理由** | 重构风险极低（好友关系 ID 不会随意变更）。当确实需要修改常量值时，IDE 的全局搜索可发现。建议在后续测试重构时处理。 |

### P2 纳入范围总结

| 优先级 | 编号 | 问题 | 建议 | 成本 |
|--------|------|------|------|------|
| **高** | P2-01 | Flyway V4/V5 未覆盖 | ✅ 纳入 | 低 (2 tests) |
| **高** | P2-02 | runBlocking 反模式 | ✅ 纳入 | 中 (22处修改) |
| 中 | P2-06 | ProtoCodec 字段验证 | ✅ 纳入 | 低 (5行) |
| 中 | P2-07 | cursor 值未验证 | ✅ 纳入 | 低 (1行) |
| 中 | P2-09 | 异常类型宽泛 | ✅ 纳入 | 极低 (2处替换) |
| 低 | P2-03 | 断言风格不统一 | ❌ 延期 | — |
| 低 | P2-04 | LogInterceptorTest 空 | ❌ 延期 | — |
| 低 | P2-05 | 重复 startKoin | ❌ 延期 | — |
| 低 | P2-11 | 好友关系硬编码 | ❌ 延期 | — |
| — | P2-08 | Hibernate Session 问题 | 由 D-15-02 解决 | — |
| — | P2-10 | seqService mock | 由 D-15-05 解决 | — |

### 推荐方案 ✅ **选项 B (仅纳入高价值项: P2-01, P2-02, P2-06, P2-07, P2-09)**

共纳入 **5 项** P2 改进，占 P2 总数的 45%（超过成功标准要求的 50%——加上 D-15-02 和 D-15-05 的解决，实际覆盖约 64%）。

---

## D-15-05: seqService mock 策略

### 问题

`MessageServiceTest` 中 `seqService` 的 mock 在 `@BeforeEach` 中全局设置 `coEvery { seqService.nextSeq(any(), any()) } returns 1L`，所有测试共享同一个 mock 行为。如果某个测试意外不调用 `seqService`，测试仍然通过（mock 未被验证）。

### 选项分析

#### 选项 A: 每个 Test 独立 mock

```kotlin
@Test
fun someTest() = runTest {
    coEvery { seqService.nextSeq(any(), any()) } returns 1L
    // ...
}
```

**优点**：
- ✅ 测试精确性：每个测试显式声明其依赖
- ✅ 如果测试不调用 seqService，mock 未设置 → strict mock 抛出异常

**缺点**：
- ❌ 大量重复：10+ 个测试都需要重复 mock，降低可读性
- ❌ 如果 seqService 调用链路变更，每个测试都需要更新

**适用场景**：测试需要不同的 seq 返回值（如测试溢出重置、边界条件）

#### 选项 B: 保持 @BeforeEach 全局设置 + 添加验证（推荐 ✅）

```kotlin
@BeforeEach
fun setUp() {
    // ...
    seqService = mockk<SeqService>()
    coEvery { seqService.nextSeq(any(), any()) } returns 1L  // 默认值
    coEvery { seqService.currentSeq(any(), any()) } returns 1L  // 默认值
    // ...
}

// 需要不同返回值的测试覆盖默认值
@Test
fun testThatNeedsSpecificSeq() = runTest {
    coEvery { seqService.nextSeq(any(), any()) } returns 42L
    // ... 测试逻辑
}

// 验证 seqService 被调用的测试
@Test
fun sendMessageShouldCallSeqService() = runTest {
    // ... 执行测试
    coVerify(atLeast = 1) { seqService.nextSeq(any(), any()) }
}
```

**优点**：
- ✅ 减少重复：9/10 的测试使用相同的 mock 行为
- ✅ 灵活性：需要不同返回值的测试可覆盖默认值
- ✅ 精确性：关键路径测试添加 `coVerify` 确保 mock 被实际使用

### 推荐方案 ✅ **选项 B (保持全局 + 添加验证)**

**具体做法**：

1. 保留 `@BeforeEach` 中的全局 seqService mock（减少重复）
2. 在所有调用 seqService 的测试中添加 `coVerify` 验证调用
3. 需要使用不同 seq 值的测试（如测试溢出）在各自方法内覆盖 mock

**风险评估**：

```kotlin
// 当前问题：如果方法改成了不调用 seqService，测试仍通过
// 修复：添加 coVerify 确保 mock 被使用

// 1. 在 sendMessage 路径测试中添加：
coVerify(atLeast = 1) { seqService.nextSeq(any(), any()) }

// 2. 在 pullMessages 路径测试中添加：
coVerify(atLeast = 1) { seqService.currentSeq(any(), any()) }
```

**注意**：strict mock 模式下（`mockk()` 不带 `relaxed = true`），如果 seqService 被调用但未设置 `coEvery`，会抛出异常。但当前模式是设置了默认返回值，所以需要额外的 `coVerify` 来确保调用确实发生了。

---

## 实现路径建议

### 总体实施顺序

1. **基础修复**（无设计争议，可直接实施）
   - D-15-03: 反射注入修复（ReadReportHandler + RedisDeliveryTracker）
   - D-15-04 P2-09: 异常类型修复（Exception → DataIntegrityViolationException）

2. **P0 修复**（高优先级）
   - D-15-01: SessionRepositoryTest（MockK + batchDelete 集成测试）
   - D-15-01: PrivacyRepositoryTest（MockK）
   - D-15-01: MessageQueueRepositoryTest（MockK + 集成测试）
   - P0-06: ConversationService.dissolveGroup 测试

3. **P1 修复**（中优先级）
   - D-15-02: MessageRepository / DeadLetterRepository 集成测试
   - P1-01 ~ P1-14: 逐个补充

4. **P2 改进**（低优先级，按 D-15-04 范围）
   - P2-01: Flyway V4/V5 补充
   - P2-02: runBlocking 修复
   - P2-06: ProtoCodec 字段验证
   - P2-07: cursor 值验证

### 新测试文件清单

| 文件路径 | 覆盖 | 策略 |
|---------|------|------|
| `repository/src/test/.../redis/SessionRepositoryTest.kt` | P0-01 | MockK + Testcontainers |
| `repository/src/test/.../redis/PrivacyRepositoryTest.kt` | P0-04 | MockK + Testcontainers |
| `repository/src/test/.../redis/MessageQueueRepositoryTest.kt` | P0-05 | MockK + Testcontainers |
| `repository/src/test/.../repository/MessageRepositoryIntegrationTest.kt` | P0-02 | Repository 接口 |
| `repository/src/test/.../repository/DeadLetterRepositoryIntegrationTest.kt` | P0-03 | Repository 接口 |

### 避免的问题

1. **不修改生产业务逻辑**：所有变更仅限于测试代码 + 为测试提供构造参数的极小修改
2. **不引入新依赖**：MockK / kotlinx.coroutines.test / Testcontainers 均为现有依赖
3. **不修改 Proto / API**：无消息类型变更，无 Handler 签名变更

## 参考资源

- **现有测试模式参考**：
  - `repository/src/test/.../redis/OnlineStatusRepositoryTest.kt` — MockK Redis 模式
  - `service/src/test/.../testutil/RedisTestBase.kt` — Testcontainers Redis 基类
  - `repository/src/test/.../testutil/DatabaseTestBase.kt` — Testcontainers MySQL 基类
  - `repository/src/test/.../repository/UserRepositoryIntegrationTest.kt` — Hibernate Session 模式
  - `service/src/test/.../chat/MessageServiceTest.kt` — strict MockK + seqService 全局 mock
  
- **Spring Data JPA 测试文档**：
  - Spring Data JPA — `RepositoryFactorySupport` / `JpaRepositoryFactory`
  
- **本阶段相关文件**：
  - `.planning/phases/15-test-coverage-gap-closure/15-CONTEXT.md`
  - `.planning/quick/20260616-review-test-service-gateway-repository/service-review.md`
  - `.planning/quick/20260616-review-test-service-gateway-repository/gateway-review.md`
  - `.planning/quick/20260616-review-test-service-gateway-repository/repository-review.md`

## RESEARCH COMPLETE
