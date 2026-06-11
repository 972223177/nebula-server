# Phase 03: Database Schema & Repository Layer — Pattern Map

**Mapped:** 2026-06-11
**Files analyzed:** 22 new/modified files
**Analogs found:** 14 with matches / 22 total

## File Classification

| # | New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|---|
| 01 | `repository/build.gradle.kts` (修改) | build | — | `common/build.gradle.kts` | exact |
| 02 | `repository/src/main/resources/db/migration/V1__init_schema.sql` | migration | batch | 无 — 首个 SQL 迁移 | none |
| 03 | `repository/src/main/kotlin/com/nebula/repository/entity/UserEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` (KDoc 风格) | partial |
| 04 | `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` | partial |
| 05 | `repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` | partial |
| 06 | `repository/src/main/kotlin/com/nebula/repository/entity/MessageEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` | partial |
| 07 | `repository/src/main/kotlin/com/nebula/repository/entity/FriendshipEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` | partial |
| 08 | `repository/src/main/kotlin/com/nebula/repository/entity/FriendRequestEntity.kt` | model | CRUD | `common/.../config/DatabaseConfig.kt` | partial |
| 09 | `repository/src/main/kotlin/com/nebula/repository/repository/UserRepository.kt` | repository | CRUD | `common/.../datasource/DataSourceProvider.kt` (接口隔离) | partial |
| 10 | `repository/src/main/kotlin/com/nebula/repository/repository/ConversationRepository.kt` | repository | CRUD | `DataSourceProvider.kt` | partial |
| 11 | `repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt` | repository | CRUD | `DataSourceProvider.kt` | partial |
| 12 | `repository/src/main/kotlin/com/nebula/repository/repository/MessageRepository.kt` | repository | CRUD+cursor | `DataSourceProvider.kt` | partial |
| 13 | `repository/src/main/kotlin/com/nebula/repository/repository/FriendshipRepository.kt` | repository | CRUD | `DataSourceProvider.kt` | partial |
| 14 | `repository/src/main/kotlin/com/nebula/repository/repository/FriendRequestRepository.kt` | repository | CRUD | `DataSourceProvider.kt` | partial |
| 15 | `repository/src/main/kotlin/com/nebula/repository/repository/impl/MessageRepositoryImpl.kt` | repository | event-driven+batch | 无 — 首个异步刷写实现 | none |
| 16 | `repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt` | config | bootstrap | `HikariDataSourceProvider.kt` (延迟初始化+构造注入) | role-match |
| 17 | `repository/src/main/kotlin/com/nebula/repository/config/RedisConfig.kt` | config | bootstrap | `HikariDataSourceProvider.kt` (延迟初始化+构造注入) | role-match |
| 18 | `repository/src/main/kotlin/com/nebula/repository/redis/SessionRepository.kt` | service | request-response | `SnowflakeIdGenerator.kt` (工具类模式) | partial |
| 19 | `repository/src/main/kotlin/com/nebula/repository/redis/MessageQueueRepository.kt` | service | event-driven | 无 — 首个 Lettuce Redis Stream 封装 | none |
| 20 | `repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt` | service | request-response | `SnowflakeIdGenerator.kt` (工具类模式) | partial |
| 21 | `docker-compose.yml` | config | infrastructure | 无 — 首个 Docker Compose 文件 | none |
| 22 | `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` (修改) | entrypoint | bootstrap | 已有文件的修改 | exact |

## Pattern Assignments

### `repository/build.gradle.kts` (build)

**Analog:** `common/build.gradle.kts`

**完整结构** (整个文件，19 行):
```kotlin
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Phase 2 新增 — 基础设施依赖
    implementation(libs.typesafe.config)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)
    implementation(libs.grpc.netty.shaded)
}
```

**需要复制的约定:**
- 相同 `plugins`、`java`、`kotlin` 模板
- 使用 `api(project(":common"))` 代替 `implementation`（因 `:repository` 会被 `:service` 等模块依赖，需传递依赖）
- 使用 `libs.*` version catalog 引用依赖
- 当前 `repository/build.gradle.kts` 已有骨架，只需替换 `dependencies` 块

**新增依赖 (替换现有 `dependencies` 块):**
```kotlin
dependencies {
    api(project(":common"))
    implementation(project(":proto"))

    // JPA + Hibernate ORM (D-01)
    implementation(libs.hibernate.core)
    implementation(libs.spring.data.jpa)
    implementation(libs.spring.tx)

    // Lettuce Redis 客户端 (D-03, D-04)
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // Flyway 数据库迁移 (D-02)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
}
```

---

### `repository/src/main/resources/db/migration/V1__init_schema.sql` (migration, batch)

**Analog:** 无 — 代码库中首个 Flyway 迁移脚本

**模式来源:** RESEARCH.md 第 682-700 行

**DDL 模板:**
```sql
-- repository/src/main/resources/db/migration/V1__init_schema.sql

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT          NOT NULL PRIMARY KEY,
    username    VARCHAR(64)     NOT NULL,
    password_hash VARCHAR(128)  NOT NULL,
    nickname    VARCHAR(64)     NOT NULL,
    avatar      VARCHAR(256)    NOT NULL DEFAULT '',
    privacy_status TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**关键约定:**
- 所有表使用 `ENGINE=InnoDB` + `CHARSET=utf8mb4` + `COLLATE=utf8mb4_unicode_ci`
- 使用 `CREATE TABLE IF NOT EXISTS` 确保幂等
- `DATETIME(3)` 精确到毫秒
- 外键关系在应用层维护（JPA），DDL 中不写 `FOREIGN KEY` 约束（避免分布表迁移问题）
- 索引使用 `INDEX idx_name` / `UNIQUE KEY uk_name` 命名规范
- 在单个 V1 文件中创建全部 6 张表 + 索引

---

### `repository/.../entity/UserEntity.kt` (model, CRUD)

**Analog:** `common/.../config/DatabaseConfig.kt` — 用于 KDoc 注释风格和字段文档规范

**KDoc 文档模式** (`DatabaseConfig.kt` lines 8-31):
```kotlin
/**
 * 关系型数据库连接池（HikariCP）配置。
 *
 * 各超时参数按毫秒为单位，在连接争用较高或网络不稳定时应适当调整。
 */
data class DatabaseConfig(
    /** 数据库主机地址，仅支持 IP 或可解析域名，不支持 JDBC URL 片段拼接 */
    val host: String,
    /** 数据库端口，MySQL 默认为 3306，PG 为 5432 */
    val port: Int,
    // ...
)
```

**JPA 实体模式** (来自 RESEARCH.md 第 320-368 行，D-06/D-07 策略):
```kotlin
package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 用户实体，映射 users 表。
 *
 * D-06: 使用常规 class + JPA 注解，非 data class。
 * D-07: 必填字段在构造参数中声明，DB 自动生成字段用可空默认值。
 */
@Entity
@Table(name = "users")
class UserEntity(
    /** 用户名，登录唯一凭证 */
    @Column(nullable = false, unique = true, length = 64)
    var username: String,

    /** BCrypt 密码哈希 */
    @Column(nullable = false, length = 128)
    var passwordHash: String,

    /** 显示名称 */
    @Column(nullable = false, length = 64)
    var nickname: String,

    /** 头像 URL */
    @Column(length = 256)
    var avatar: String = "",

    /** 在线状态可见性：0=所有人, 1=仅好友, 2=隐藏 */
    @Column(nullable = false)
    var privacyStatus: Int = 0
) {
    /** 用户 ID，Snowflake 算法生成 */
    @Id
    @Column(nullable = false)
    var id: Long? = null

    /** 创建时间，DB 自动生成 */
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    /** 更新时间，DB 自动更新 */
    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
```

**每个实体的 ID 策略:**
| 实体 | ID 类型 | 生成方式 |
|------|---------|----------|
| `UserEntity` | `Long?` | Snowflake 算法 |
| `ConversationEntity` | `String` | UUID |
| `ConversationMemberEntity` | `Long?` | MySQL auto-increment |
| `MessageEntity` | `Long?` | Snowflake 算法 |
| `FriendshipEntity` | `Long?` | MySQL auto-increment |
| `FriendRequestEntity` | `Long?` | MySQL auto-increment |

**构造参数 vs 类体字段:**
- **构造参数:** 应用层提供的必填/可选字段（username、nickname、avatar 等）
- **类体字段:** DB 自动生成或由基础设施赋值的字段（id、createdAt、updatedAt）

---

### `repository/.../repository/UserRepository.kt` (repository, CRUD)

**Analog:** `common/.../datasource/DataSourceProvider.kt` — 接口隔离模式

**接口模式** (`DataSourceProvider.kt` lines 1-17):
```kotlin
package com.nebula.common.datasource

import javax.sql.DataSource

/**
 * 数据源提供者接口。
 *
 * 定义获取 [DataSource] 的契约，允许不同实现切换连接池策略（如 HikariCP、Druid 等）。
 */
interface DataSourceProvider {
    /**
     * 获取 [DataSource] 实例。
     *
     * 调用方无需关心连接池的生命周期，由实现类负责初始化和关闭。
     */
    fun getDataSource(): DataSource
}
```

**Repository 接口模式** (来自 RESEARCH.md 第 383-430 行):
```kotlin
package com.nebula.repository.repository

import com.nebula.repository.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 用户数据仓库。
 *
 * Spring Data JPA 自动实现 CRUD 方法（save、findById、findAll、delete 等）。
 * 自定义查询方法通过方法名派生或 @Query 注解实现。
 */
interface UserRepository : JpaRepository<UserEntity, Long> {
    /**
     * 按用户名查找用户。
     *
     * username 字段在 DB 层有 UNIQUE 约束，最多返回一个结果。
     */
    fun findByUsername(username: String): UserEntity?
}
```

**关键约定:**
- 所有 Repository 接口继承 `JpaRepository<T, ID>`
- 自定义查询遵循 Spring Data 方法名派生规则
- 如需复杂 JPQL 使用 `@Query` + `@Param`
- 修改操作使用 `@Modifying` + `@Query`

---

### `repository/.../repository/MessageRepository.kt` (repository, CRUD+cursor)

**模式来源:** RESEARCH.md 第 400-411 行，第 800-828 行

```kotlin
package com.nebula.repository.repository

import com.nebula.repository.entity.MessageEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 消息数据仓库。
 *
 * 除基础 CRUD 外，提供基于 Snowflake ID 的游标分页查询（D-12）。
 * 消息写入走异步路径（Redis Stream → batch flush），不直接使用 save()。
 */
interface MessageRepository : JpaRepository<MessageEntity, Long> {

    /** 向后拉取（更旧的消息）— cursor 为上一页最后一条消息的 id */
    @Query("""
        SELECT m FROM MessageEntity m
        WHERE m.conversationId = :convId AND m.id < :cursor
        ORDER BY m.id DESC
    """)
    fun findMessagesBackward(
        @Param("convId") conversationId: String,
        @Param("cursor") cursor: Long,
        pageable: Pageable
    ): List<MessageEntity>

    /** 向前拉取（更新的消息）— cursor 为当前第一条消息的 id */
    @Query("""
        SELECT m FROM MessageEntity m
        WHERE m.conversationId = :convId AND m.id > :cursor
        ORDER BY m.id ASC
    """)
    fun findMessagesForward(
        @Param("convId") conversationId: String,
        @Param("cursor") cursor: Long,
        pageable: Pageable
    ): List<MessageEntity>
}
```

---

### `repository/.../repository/ConversationMemberRepository.kt` (repository, CRUD)

**附加模式:** 未读计数 + 已读回执（RESEARCH.md 第 830-861 行）

```kotlin
interface ConversationMemberRepository : JpaRepository<ConversationMemberEntity, Long> {
    fun findByConversationIdAndUserId(conversationId: String, userId: Long): ConversationMemberEntity?
    fun findByUserId(userId: Long): List<ConversationMemberEntity>

    /** 已读回执：更新 last_read_message_id 并清零 unread_count (DB-07) */
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

    /** 增加未读计数（消息发送时调用，DB-06） */
    @Modifying
    @Query("""
        UPDATE ConversationMemberEntity cm
        SET cm.unreadCount = cm.unreadCount + 1
        WHERE cm.conversationId = :convId AND cm.userId != :senderId
    """)
    fun incrementUnreadCount(
        @Param("convId") conversationId: String,
        @Param("senderId") senderId: Long
    )
}
```

---

### `repository/.../config/JpaConfig.kt` (config, bootstrap)

**Analog:** `HikariDataSourceProvider.kt` — 延迟初始化 + 构造注入模式

**延迟初始化模式** (`HikariDataSourceProvider.kt` lines 14-55):
```kotlin
class HikariDataSourceProvider(
    private val config: DatabaseConfig
) : DataSourceProvider {

    private val hikariDataSource: HikariDataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = buildJdbcUrl()
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            // ...
        }.let(::HikariDataSource)
    }

    override fun getDataSource(): DataSource = hikariDataSource
}
```

**JPA 引导模式** (RESEARCH.md 第 434-498 行):
```kotlin
package com.nebula.repository.config

import com.nebula.common.datasource.DataSourceProvider
import jakarta.persistence.EntityManagerFactory
import org.hibernate.cfg.AvailableSettings
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import javax.sql.DataSource

/**
 * 创建 [EntityManagerFactory] 并初始化 Spring Data JPA Repository 代理。
 *
 * 由于项目不使用 Spring Boot，手动 bootstrap JPA 和 Spring Data。
 * Flyway 迁移必须在 EMF 创建前执行（D-02）。
 */
class JpaConfig(
    private val dataSourceProvider: DataSourceProvider
) {
    private val entityManagerFactory: EntityManagerFactory by lazy {
        val dataSource = dataSourceProvider.getDataSource()
        runFlywayMigrations(dataSource)

        val emfBean = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setPackagesToScan("com.nebula.repository.entity")
            jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
                setShowSql(false)
                setGenerateDdl(false) // Flyway 负责 DDL
                setDatabasePlatform("org.hibernate.dialect.MySQLDialect")
            }
            jpaPropertyMap = mapOf(
                AvailableSettings.HBM2DDL_AUTO to "validate",
                AvailableSettings.STATEMENT_BATCH_SIZE to "30",
                AvailableSettings.ORDER_INSERTS to "true",
                AvailableSettings.ORDER_UPDATES to "true"
            )
        }
        emfBean.afterPropertiesSet()
        emfBean.getObject()!!
    }

    /** 获取每个实体对应的 Spring Data JPA Repository 代理 */
    fun <T, ID> getRepository(repositoryInterface: Class<T>): T {
        val factory = JpaRepositoryFactory(entityManagerFactory)
        return factory.getRepository(repositoryInterface)
    }
}

/** Flyway 迁移执行 */
private fun runFlywayMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
}
```

---

### `repository/.../config/RedisConfig.kt` (config, bootstrap)

**模式来源:** RESEARCH.md 第 865-893 行

```kotlin
package com.nebula.repository.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Lettuce Redis 客户端配置。
 *
 * 使用单一 [StatefulRedisConnection] 供所有 Redis Repository 共享（避免连接泄漏，见 Pitfall 4）。
 * 连接使用 [by lazy] 延迟初始化。
 */
class RedisConfig(
    private val host: String = "127.0.0.1",
    private val port: Int = 6379
) {
    val client: RedisClient by lazy {
        RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build())
    }

    val connection: StatefulRedisConnection<String, String> by lazy {
        client.connect()
    }

    /** 确保消费者组等基础设施就绪 */
    suspend fun initializeRedisInfra(messageQueueRepo: MessageQueueRepository) {
        messageQueueRepo.ensureConsumerGroup()
    }

    fun shutdown() {
        connection.close()
        client.shutdown()
    }
}
```

---

### `repository/.../redis/MessageQueueRepository.kt` (service, event-driven)

**模式来源:** RESEARCH.md 第 704-739 行

```kotlin
package com.nebula.repository.redis

import io.lettuce.core.*
import io.lettuce.core.api.coroutines.*

/**
 * Redis Stream 消息队列封装（D-04）。
 *
 * 提供生产端 XADD、消费端 XREADGROUP 和确认 XACK 操作。
 * 所有方法为挂起函数，与 Netty 异步模型对齐。
 */
class MessageQueueRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = connection.coroutines()

    /** 确保消费者组存在（启动时调用，重复调用安全） */
    suspend fun ensureConsumerGroup() {
        try {
            redis.xgroupCreate(
                "queue:messages", "flush-workers",
                XReadArgs.StreamOffset.from("queue:messages", "0-0")
            )
        } catch (e: RedisCommandExecutionException) {
            if (!e.message?.contains("BUSYGROUP")!!) throw e
            // 消费者组已存在，忽略
        }
    }

    /** 将消息写入 Redis Stream */
    suspend fun enqueue(message: Map<String, String>): String? {
        return redis.xadd(
            "queue:messages",
            XAddArgs.Builder.maxlen(100000).approximateTrimming(),
            message
        )
    }

    /** 消费一批消息 */
    suspend fun consume(batchSize: Long, blockMs: Long): List<StreamMessage<String, String>> {
        return redis.xreadgroup(
            Consumer.from("flush-workers", "worker-1"),
            XReadArgs.StreamOffset.lastConsumed("queue:messages"),
            XReadArgs.Builder.count(batchSize).block(blockMs)
        ) ?: emptyList()
    }

    /** 确认消息已被处理 */
    suspend fun acknowledge(messageId: String) {
        redis.xack("queue:messages", "flush-workers", messageId)
    }
}
```

---

### `repository/.../redis/SessionRepository.kt` (service, request-response)

**模式来源:** RESEARCH.md 第 275 行（Redis key 设计）+ `SnowflakeIdGenerator.kt` 作为工具类模式参考

```kotlin
package com.nebula.repository.redis

import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Session Token 缓存操作封装（DB-02, D-13）。
 *
 * Redis key 格式: session:token:<token>
 * 滑动 TTL 刷新策略：活跃用户的每次请求自动续期 TTL（默认 7 天）。
 */
class SessionRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = connection.coroutines()

    companion object {
        private const val KEY_PREFIX = "session:token:"
        private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L  // 7 天
    }

    suspend fun save(token: String, userData: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.setex("$KEY_PREFIX$token", ttlSeconds, userData)
    }

    suspend fun findByToken(token: String): String? {
        return redis.get("$KEY_PREFIX$token")
    }

    /** 滑动续期 TTL (D-13) */
    suspend fun refreshTtl(token: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.expire("$KEY_PREFIX$token", ttlSeconds)
    }

    suspend fun delete(token: String) {
        redis.del("$KEY_PREFIX$token")
    }
}
```

---

### `repository/.../redis/OnlineStatusRepository.kt` (service, request-response)

**Key 命名:** `online:user:<userId>` (D-05), TTL = 60s (D-14)

```kotlin
package com.nebula.repository.redis

import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.StatefulRedisConnection

/**
 * 用户在线状态缓存操作封装（DB-02, D-14）。
 *
 * Redis key 格式: online:user:<userId>
 * 断连即标记离线 + 短 TTL（60s）（D-14）。
 */
class OnlineStatusRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = connection.coroutines()

    companion object {
        private const val KEY_PREFIX = "online:user:"
        private const val TTL_SECONDS = 60L   // D-14: 短 TTL
    }

    suspend fun setOnline(userId: Long, statusData: String) {
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, statusData)
    }

    suspend fun setOffline(userId: Long) {
        redis.del("$KEY_PREFIX$userId")
    }

    suspend fun isOnline(userId: Long): Boolean {
        return redis.get("$KEY_PREFIX$userId") != null
    }
}
```

---

### `repository/.../repository/impl/MessageRepositoryImpl.kt` (repository, event-driven+batch)

**模式来源:** RESEARCH.md 第 744-796 行

```kotlin
package com.nebula.repository.repository.impl

import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.MessageRepository
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.*

/**
 * 消息写入路径实现（DB-03, D-11）。
 *
 * 架构：Redis Stream（即时 ACK）→ 定时批量刷入 MySQL
 * - 消息先通过 [MessageQueueRepository.enqueue] 写入 Redis Stream，客户端即刻收到 ACK
 * - 后台协程每 500ms 检查积压消息，>= 30 条时触发批量 INSERT
 * - 刷写失败的消息保留在 Redis Stream 中，下次定时任务重试
 */
class MessageRepositoryImpl(
    private val messageQueue: MessageQueueRepository,
    private val jpaMessageRepo: MessageRepository,
    private val emf: EntityManagerFactory
) {
    private val logger = KotlinLogging.logger {}
    private var stopped = false

    /** 启动定时刷写任务（D-11: 500ms 间隔，30 条阈值） */
    fun startFlushTimer() {
        CoroutineScope(Dispatchers.IO).launch {
            while (!stopped) {
                delay(500)
                flushBatch()
            }
        }
    }

    suspend fun flushBatch() {
        val entries = messageQueue.consume(batchSize = 30, blockMs = 0)
        if (entries.isEmpty()) return

        val messages = entries.mapNotNull { entry -> parseToEntity(entry) }
        if (messages.size < 30) return  // 不到阈值，暂不刷盘

        try {
            val em = emf.createEntityManager()
            em.transaction.begin()
            var count = 0
            for (msg in messages) {
                em.persist(msg)
                count++
                if (count % 30 == 0) { em.flush(); em.clear() }
            }
            em.transaction.commit()
            entries.forEach { messageQueue.acknowledge(it.id) }
        } catch (e: Exception) {
            logger.error(e) { "批量刷写消息失败" }
            // 消息保留在 Redis Stream 中，下次重试
        } finally {
            em.close()
        }
    }
}
```

---

### `server/.../NebulaServer.kt` (修改, bootstrap)

**现有启动顺序扩展模式** (lines 23-49):

需要在 Step 4（HikariCP 初始化）之后、Step 5（gRPC 启动）之前插入:
```kotlin
// Step 4.5: Phase 3 — 初始化持久化层
val jpaConfig = JpaConfig(dataSourceProvider)
val redisConfig = RedisConfig(
    host = config.redis.host,
    port = config.redis.port
)
// 获取各 Repository 代理
val userRepo = jpaConfig.getRepository(UserRepository::class.java)
val conversationRepo = jpaConfig.getRepository(ConversationRepository::class.java)
// ... 其他 Repository
val messageQueueRepo = MessageQueueRepository(redisConfig.connection)
redisConfig.initializeRedisInfra(messageQueueRepo)
```

---

## Shared Patterns

### 1. KDoc 注释规范
**来源:** `CODEBUDDY.md` + `DatabaseConfig.kt` (所有字段级 `/** */`)

**适用:** 所有新建的 Kotlin 文件（Entity、Repository、Config、Redis wrapper）

**规则:**
- 类/接口 KDoc：说明职责 + 关键设计决策引用（D-编号）
- 构造参数 KDoc：每行 `/** 中文说明 */` 在前
- 方法 KDoc：功能说明 + `@param` / `@return`（复杂方法）
- 内联逻辑：`// 中文注释` 解释"为什么这么做"

**示例:**
```kotlin
/**
 * 用户实体，映射 users 表。
 *
 * D-06: 使用常规 class + JPA 注解，非 data class。
 * D-07: 必填字段在构造参数中声明，DB 自动生成字段用可空默认值。
 */
```

### 2. 构造参数注入（无 DI 框架）
**来源:** `HikariDataSourceProvider.kt`, `ChatServer.kt`, `SnowflakeIdGenerator.kt`

**适用:** 所有 `JpaConfig`、`RedisConfig`、Redis Repository 类

**规则:**
- 所有依赖通过 `class Xxx(private val dep: DepType)` 注入
- 延迟资源使用 `by lazy` 初始化（连接池、EMF、RedisClient）
- 当前无 DI 框架（Phase 4 引入 Koin）

### 3. 包路径命名
**来源:** 现有 `common` 模块

**适用:** `:repository` 模块所有类

| Module | Base Package |
|--------|-------------|
| `:common` | `com.nebula.common.*` |
| `:repository` | `com.nebula.repository.*` |
| `entity` 子包 | `com.nebula.repository.entity.*` |
| `repository` 子包 | `com.nebula.repository.repository.*` |
| `redis` 子包 | `com.nebula.repository.redis.*` |
| `config` 子包 | `com.nebula.repository.config.*` |

### 4. Flyway + Hibernate Validate 模式
**来源:** RESEARCH.md §Pattern 2

**适用:** `JpaConfig.kt`

- Flyway 管理 DDL 变更
- Hibernate `hbm2ddl.auto=validate` 在启动时校验实体 vs 表结构一致性
- 禁止同时使用 `hbm2ddl.auto=update`（Anti-Pattern）

### 5. Redis 连接共享模式
**来源:** RESEARCH.md Pitfall 4

**适用:** 所有 Redis Repository 类

- 全局共享一个 `StatefulRedisConnection<String, String>`
- 通过构造参数注入（`RedisConfig` → 各 Repository）
- 每个 Repository 通过 `connection.coroutines()` 获取协程命令接口
- 禁止每个 Repository 创建独立连接

### 6. 异步边界处理
**来源:** RESEARCH.md Pitfall 5

**适用:** `MessageQueueRepository`、`MessageRepositoryImpl`

- Lettuce 使用 `connection.coroutines()` 挂起函数
- Hibernate/JDBC 使用 `Dispatchers.IO` 或专用执行器（JDBC 是阻塞 API）
- 禁止在 Netty 事件循环中 `.get()` 阻塞等待

## No Analog Found

以下文件在代码库中没有现有 analog，应使用 RESEARCH.md 中的模式：

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `V1__init_schema.sql` | migration | batch | 首个 Flyway 迁移脚本 |
| `MessageRepositoryImpl.kt` | repository | event-driven+batch | 首个异步刷写实现 |
| `MessageQueueRepository.kt` | service | event-driven | 首个 Lettuce Redis Stream 封装 |
| `docker-compose.yml` | config | infrastructure | 首个 Docker Compose 文件 |

## Metadata

**Analog search scope:** `common/`, `server/`, `proto/`, `settings.gradle.kts`, `gradle/libs.versions.toml`
**Files scanned:** 30+
**Pattern extraction date:** 2026-06-11
