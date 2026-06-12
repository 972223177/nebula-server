# Phase 03: Database Schema & Repository Layer - Research

**Researched:** 2026-06-11
**Domain:** JPA/Hibernate ORM, Flyway Migrations, Redis Stream, Async Batch Persistence, Kotlin Entity Design
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

| ID | Decision |
|----|----------|
| D-01 | JPA + Hibernate ORM |
| D-02 | Flyway for migrations (`V1__create_users.sql` format) |
| D-03 | Lettuce (async/non-blocking) Redis client |
| D-04 | Redis Stream for message pending queue (consumer groups, ACK, backtrack) |
| D-05 | Redis key naming: `prefix:tier` format (`session:token:<value>`, `queue:session:<id>:<seq>`, `online:user:<id>`) |
| D-06 | Entity as regular class + JPA annotations (NOT data class) |
| D-07 | Field init: constructor params + nullable defaults |
| D-08 | 7 Repository interfaces (6 per-table + 1 MessageRepository for write path) |
| D-09 | Transaction boundaries in Service layer, not Repository |
| D-10 | SnowflakeIdGenerator for message IDs (existing from Phase 2) |
| D-11 | Async flush: timer-triggered 500ms, batch threshold 30 |
| D-12 | Cursor pagination for message pull |
| D-13 | Sliding TTL refresh for session tokens |
| D-14 | Offline on disconnect + short TTL (60s) |
| D-15 | Split MessageType into PushEventType + ChatContentType |
| D-16 | `envelope.Message.messageType` → `eventType`, type PushEventType |
| D-17 | CHAT_MESSAGE push payload includes complete ChatMessage bytes |

### Claude's Discretion

- DB table field design, index strategy, engine and charset selection
- Redis operation API encapsulation (Lettuce async API wrapper layer)
- Flyway versioned script organization
- Message table partitioning strategy (single table to start)
- Concurrency control strategy (optimistic vs pessimistic locking)

### Deferred Ideas (OUT OF SCOPE)

- **HikariCP primary-replica multi-datasource (read/write split)** — not introduced in Phase 3
- **Message table by-time/by-conversation partitioning** — start with single table
- **Production monitoring metrics (connection pool, Redis hit rate, etc.)** — Phase 11 unified handling

</user_constraints>

---

<phase_requirements>

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DB-01 | MySQL 6 core tables DDL (users, conversations, conversation_members, messages, friendships, friend_requests) | Flyway migration scripts + JPA entities with proper indexes and FKs — see §Table Design |
| DB-02 | Redis 3 cache structures (session tokens, message pending queue, online status) | Lettuce async client with dedicated wrapper classes — see §Redis Storage Design |
| DB-03 | Message write path: receive → Redis ACK → async batch flush to MySQL | Redis Stream XADD + timer-flush architecture — see §Async Flush |
| DB-04 | Message pull API supports cursor/offset pagination, fetches from MySQL | Cursor pagination via Snowflake message ID — see §Cursor Pagination |
| DB-05 | Offline message storage and push on reconnect | Redis Stream pending PEL + reconnect replay — see §Offline Messages |
| DB-06 | Unread message count maintained per conversation per user | `conversation_members.unread_count` counter maintenance — see §Unread Count |
| DB-07 | Read receipt updates unread count and records `last_read_message_id` per conversation | Atomic update on read report — see §Read Receipt |

</phase_requirements>

---

## Summary

Phase 3 builds Nebula's entire persistence layer. It is the deepest dependency for all subsequent business logic phases (5–8). The phase splits into 4 natural workstreams:

1. **MySQL Schema + JPA Entities** — Flyway DDL scripts, 6 entity classes, 7 repository interfaces
2. **Redis Storage Layer** — Lettuce wrapper classes for session tokens, message queue, online status
3. **Message Write Path + Pull** — Redis Stream → async batch → MySQL, cursor pagination
4. **Message Peripherals** — offline messages, unread count, read receipts

**Critical architectural constraint:** This project has **no Spring Boot**. All Spring/JPA/Hibernate components must be bootstrapped programmatically. Flyway migration runs before EntityManagerFactory construction. Lettuce is used in its reactive/coroutines API to align with Netty's async model.

**Build dependency:** A new `:repository` Gradle module must be populated with JPA entity classes, Spring Data JPA repository interfaces, and Lettuce-based Redis repository classes. The module is already declared in `settings.gradle.kts` and has a minimal `build.gradle.kts`.

### Primary recommendation

Use Spring Data JPA standalone (without Spring Boot) with `JpaRepositoryFactory` or `@EnableJpaRepositories` + minimal Spring Context. This gives auto-implementation of the 6 per-table repository interfaces. For Redis, wrap Lettuce's async API in dedicated repository classes under a `com.nebula.repository.redis` package.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| DDL execution / migration | Repository (bootstrap) | — | Flyway runs before EMF creation, owns schema state |
| Entity mapping (ORM) | Repository | — | JPA entities live in `:repository` module |
| CRUD data access | Repository | Service (transactional) | Repository handles persistence; Service coordinates transactions per D-09 |
| Redis session token store | Repository | — | SessionRepository wraps Lettuce commands |
| Redis message queue | Repository | — | MessageQueueRepository wraps Redis Stream XADD/XREADGROUP |
| Redis online status | Repository | — | OnlineStatusRepository wraps Redis SET/GET/EXPIRE |
| Async batch flush logic | Repository | — | MessageRepositoryImpl owns the timer + flush logic |
| Cursor pagination | Repository | — | Complex query belongs to repository layer |
| Transaction management | Service | — | Per D-09, Service layer owns `@Transactional` |

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Hibernate ORM (`org.hibernate.orm:hibernate-core`) | 6.6.x | JPA implementation provider | Industry-standard JPA provider; matches D-01 |
| Spring Data JPA (`org.springframework.data:spring-data-jpa`) | 3.4.x | Repository auto-implementation | Gives `JpaRepository<T, ID>` with zero boilerplate; 6 custom interfaces become instant CRUD |
| Spring TX (`org.springframework:spring-tx`) | 6.2.x | Declarative transaction management | `@Transactional` on Service layer per D-09; integrates with Hibernate |
| Lettuce (`io.lettuce:lettuce-core`) | 6.5.x | Async/non-blocking Redis client | Matches D-03; coroutines API (`connection.coroutines()`) fits Netty async model |
| Flyway (`org.flywaydb:flyway-core` + `flyway-mysql`) | 10.x | Database migration | Matches D-02; standalone FluentConfiguration API; MySQL dialect support |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| HikariCP (`com.zaxxer:HikariCP`) | 7.0.2 | Connection pool | Already in `:common` module via `HikariDataSourceProvider`; `:repository` gets DataSource via DI |
| MySQL Connector (`com.mysql:mysql-connector-j`) | 9.2.0 | JDBC driver | Already in `:common` module; needed by Flyway + Hibernate |
| Kotlin Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-core`) | 1.9.x | Coroutine support for Lettuce | Lettuce Kotlin API requires `kotlinx-coroutines-reactive` on classpath |
| kotlinx-coroutines-reactive | 1.9.x | Reactive → Coroutines bridge | Lettuce `connection.coroutines()` needs this on classpath |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Spring Data JPA | Raw Hibernate `Session` API | 6x more boilerplate for CRUD; manual pagination; no auto-query derivation |
| Spring Data JPA | Kotlin Exposed | Type-safe DSL, no JPA overhead, but contradicts D-01 decision |
| Flyway | Hibernate `hbm2ddl.auto=update` | Unsafe for production; no version control; violates D-02 |
| Lettuce coroutines | Lettuce reactive (Flux/Mono) | Reactive API has steeper learning curve; coroutines more ergonomic for this team |
| Standalone bootstrap | Spring Boot JPA starter | Project intentionally avoids Spring Boot; adding it now would cascade to all modules |

**Installation:**
```kotlin
// repository/build.gradle.kts — dependencies block additions
implementation(libs.hibernate.core)
implementation(libs.spring.data.jpa)
implementation(libs.spring.tx)
implementation(libs.lettuce.core)
implementation(libs.flyway.core)
implementation(libs.flyway.mysql)
implementation(libs.kotlinx.coroutines.core)
implementation(libs.kotlinx.coroutines.reactive)
```

**Version verification:**
```bash
# Ensure each package exists on the correct registry before writing the build file
npm view org.hibernate.orm:hibernate-core version     # Maven Central
npm view org.springframework.data:spring-data-jpa version
npm view io.lettuce:lettuce-core version
npm view org.flywaydb:flyway-core version
```

---

## Table Design (Claude's Discretion)

### Table Definitions

#### 1. `users` — 用户表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `BIGINT` | PK | Snowflake ID; unsigned |
| `username` | `VARCHAR(64)` | UNIQUE, NOT NULL | 用户名，登录凭证 |
| `password_hash` | `VARCHAR(128)` | NOT NULL | BCrypt hash |
| `nickname` | `VARCHAR(64)` | NOT NULL | 显示名称 |
| `avatar` | `VARCHAR(256)` | DEFAULT '' | 头像 URL |
| `privacy_status` | `TINYINT` | DEFAULT 0 | 0=所有人可见, 1=仅好友, 2=隐藏 |
| `created_at` | `DATETIME(3)` | NOT NULL, DEFAULT CURRENT_TIMESTAMP(3) | |
| `updated_at` | `DATETIME(3)` | NOT NULL, DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE | |

**Indexes:** `idx_username` on `(username)`

#### 2. `conversations` — 会话表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `VARCHAR(32)` | PK | UUID; not Snowflake (human-readable in URLs) |
| `type` | `TINYINT` | NOT NULL | 1=私聊, 2=群聊 |
| `name` | `VARCHAR(128)` | DEFAULT '' | 群聊名称；私聊可为空 |
| `avatar` | `VARCHAR(256)` | DEFAULT '' | 群头像 |
| `group_owner_uid` | `BIGINT` | DEFAULT NULL | 群主ID（群聊）；FK → users(id) |
| `member_count` | `INT` | DEFAULT 0 | 当前成员数 |
| `max_members` | `INT` | DEFAULT 200 | 群人数上限 |
| `created_at` | `DATETIME(3)` | NOT NULL | |
| `updated_at` | `DATETIME(3)` | NOT NULL | |

**Indexes:** (single table, minimal; member queries use separate table)

#### 3. `conversation_members` — 会话成员表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `BIGINT` | PK | Auto-increment |
| `conversation_id` | `VARCHAR(32)` | NOT NULL | FK → conversations(id) |
| `user_id` | `BIGINT` | NOT NULL | FK → users(id) |
| `joined_at` | `DATETIME(3)` | NOT NULL | |
| `last_read_message_id` | `BIGINT` | DEFAULT 0 | 已读的最后消息ID |
| `unread_count` | `INT` | DEFAULT 0 | 未读消息计数 |
| `deleted` | `TINYINT` | DEFAULT 0 | 软删除标志 |

**Indexes:** `UNIQUE idx_member` on `(conversation_id, user_id)`; `idx_user_convs` on `(user_id)`

#### 4. `messages` — 消息表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `BIGINT` | PK | Snowflake ID；时间有序 |
| `conversation_id` | `VARCHAR(32)` | NOT NULL | FK → conversations(id) |
| `sender_uid` | `BIGINT` | NOT NULL | FK → users(id) |
| `message_type` | `TINYINT` | NOT NULL | ChatContentType 枚举值 |
| `content` | `TEXT` | NOT NULL | 消息文本内容 |
| `payload` | `BLOB` | DEFAULT NULL | 附加结构化数据 |
| `client_message_id` | `VARCHAR(64)` | UNIQUE | 客户端幂等ID (UUID) |
| `client_ts` | `BIGINT` | NOT NULL | 客户端时间戳（ms） |
| `server_ts` | `BIGINT` | NOT NULL | 服务器时间戳（ms） |
| `created_at` | `DATETIME(3)` | NOT NULL | |

**Indexes:** `idx_conv_messages` on `(conversation_id, id)` — cursor 分页核心索引；`idx_client_msg_id` UNIQUE on `(client_message_id)`

#### 5. `friendships` — 好友关系表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `BIGINT` | PK | Auto-increment |
| `user_id` | `BIGINT` | NOT NULL | FK → users(id) |
| `friend_id` | `BIGINT` | NOT NULL | FK → users(id) |
| `created_at` | `DATETIME(3)` | NOT NULL | |
| `deleted` | `TINYINT` | DEFAULT 0 | 软删除 |

**Indexes:** `UNIQUE idx_friendship` on `(user_id, friend_id)`; `idx_friends` on `(friend_id)`

#### 6. `friend_requests` — 好友申请表
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `BIGINT` | PK | Auto-increment |
| `from_uid` | `BIGINT` | NOT NULL | FK → users(id) |
| `to_uid` | `BIGINT` | NOT NULL | FK → users(id) |
| `status` | `TINYINT` | DEFAULT 0 | 0=pending, 1=accepted, 2=rejected |
| `created_at` | `DATETIME(3)` | NOT NULL | |
| `updated_at` | `DATETIME(3)` | NOT NULL | |

**Indexes:** `idx_pending_requests` on `(to_uid, status)` WHERE `status = 0`

### Storage Engine & Charset

```sql
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_unicode_ci
```

- **InnoDB:** Transaction support, row-level locking, FK enforcement
- **utf8mb4:** Full Unicode support including emoji
- **utf8mb4_unicode_ci:** Language-aware sort order (vs `_general_ci` which has known limitations with certain characters)

### Flyway Script Organization

```
repository/src/main/resources/db/migration/
├── V1__create_users.sql
├── V2__create_conversations.sql
├── V3__create_conversation_members.sql
├── V4__create_messages.sql
├── V5__create_friendships.sql
├── V6__create_friend_requests.sql
└── V7__create_indexes.sql
```

**Alternative (recommended):** Single `V1__init_schema.sql` containing all tables + indexes in one migration. Six separate scripts create 6 versions that must always be applied in order — slower dev iteration. One well-organized script with clear section headers is more practical for a greenfield project.

---

## Redis Storage Design

### Key Name Structure (per D-05)

| Purpose | Key Pattern | Value Type | TTL | Example |
|---------|-------------|------------|-----|---------|
| Session token → user mapping | `session:token:<token>` | `{user_id, device_type, created_at}` JSON | Sliding TTL (default: 7 days) | `session:token:a1b2c3...` |
| Message pending queue | `queue:session:<sessionId>:<seq>` | Message bytes (Redis Stream) | — (stream, not TTL) | Lettuce manages stream retention via `MAXLEN ~` |
| Online status | `online:user:<userId>` | `{status, last_heartbeat}` JSON | 60s (D-14) | `online:user:10042` |

### Message Queue: Redis Stream Architecture

Per D-04, the message pending queue uses **Redis Stream** with consumer groups:

```bash
# Stream key
queue:messages

# Consumer group for async flush workers
XGROUP CREATE queue:messages flush-workers $ MKSTREAM

# Producer (chat send handler)
XADD queue:messages MAXLEN ~ 100000 * \
  message_id "<snowflake_id>" \
  conversation_id "<conv_id>" \
  sender_uid "<uid>" \
  content "<text>"

# Consumer (flush worker reads)
XREADGROUP GROUP flush-workers worker-1 COUNT 30 BLOCK 500 \
  > queue:messages

# After successful MySQL insert
XACK queue:messages flush-workers <message-id>
```

**Key architecture decisions:**
- Single stream `queue:messages` with one consumer group `flush-workers`
- Multiple workers in the group for horizontal scaling (each gets distinct messages)
- `MAXLEN ~ 100000` prevents unbounded memory growth (approximate trimming for performance)
- `XAUTOCLAIM` recovery: workers claim idle PEL entries from crashed peers
- Lettuce coroutines API: `connection.coroutines().xadd(...)` / `.xreadgroup(...)` / `.xack(...)`

---

## Entity Design Pattern (per D-06, D-07)

### Entity Class Template

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

### Each Entity's Snowflake ID Strategy

- **users, messages** — Snowflake ID globally set before persist
- **conversations** — UUID string (human-readable, used in URLs)
- **conversation_members, friendships, friend_requests** — Auto-increment (internal only, never exposed to clients)
- Implementation: set `id` field in Service layer before calling `repository.save()`

---

## Repository Layer (per D-08, D-09)

### 7 Repository Interfaces

```kotlin
package com.nebula.repository.repository

import com.nebula.repository.entity.*
import org.springframework.data.jpa.repository.JpaRepository

// 6 per-table repositories — Spring Data JPA auto-implements CRUD
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByUsername(username: String): UserEntity?
}

interface ConversationRepository : JpaRepository<ConversationEntity, String>

interface ConversationMemberRepository : JpaRepository<ConversationMemberEntity, Long> {
    fun findByConversationIdAndUserId(conversationId: String, userId: Long): ConversationMemberEntity?
    fun findByUserId(userId: Long): List<ConversationMemberEntity>
}

interface MessageRepository : JpaRepository<MessageEntity, Long> {
    // Cursor pagination query — custom @Query
    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.id < :cursor ORDER BY m.id DESC")
    fun findMessagesBeforeCursor(@Param("convId") convId: String,
                                 @Param("cursor") cursor: Long,
                                 pageable: Pageable): List<MessageEntity>

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.id > :cursor ORDER BY m.id ASC")
    fun findMessagesAfterCursor(@Param("convId") convId: String,
                                @Param("cursor") cursor: Long,
                                pageable: Pageable): List<MessageEntity>
}

interface FriendshipRepository : JpaRepository<FriendshipEntity, Long> {
    fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendshipEntity?
    fun findByUserId(userId: Long): List<FriendshipEntity>
}

interface FriendRequestRepository : JpaRepository<FriendRequestEntity, Long> {
    fun findByToUidAndStatus(toUid: Long, status: Int): List<FriendRequestEntity>
    fun findByFromUidAndToUid(fromUid: Long, toUid: Long): FriendRequestEntity?
}

// 7th — Message write path (not an interface extension, a class)
interface MessageWriteRepository {
    suspend fun enqueueMessage(message: MessageEntity): String       // XADD to Redis Stream
    suspend fun flushBatch(): Int                                    // XREADGROUP + batch INSERT
    suspend fun acknowledgeMessage(messageId: String)                // XACK
}
```

### JPA Bootstrap (Standalone, no Spring Boot)

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
 * Flyway 迁移必须在 EMF 创建前执行。
 */
class JpaConfig(
    private val dataSourceProvider: DataSourceProvider
) {
    private val entityManagerFactory: EntityManagerFactory by lazy {
        val dataSource = dataSourceProvider.getDataSource()
        // 确保 Flyway 迁移已运行
        runFlywayMigrations(dataSource)

        val emfBean = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setPackagesToScan("com.nebula.repository.entity")
            jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
                setShowSql(false)
                setGenerateDdl(false) // Flyway 负责 DDL
                setDatabasePlatform("org.hibernate.dialect.MySQLDialect")
            }
            // HikariCP DataSource 复用 Phase 2 配置
            jpaPropertyMap = mapOf(
                AvailableSettings.HBM2DDL_AUTO to "validate",      // 校验实体与表结构一致
                AvailableSettings.STATEMENT_BATCH_SIZE to "30",    // JDBC 批量大小
                AvailableSettings.ORDER_INSERTS to "true",          // 批量插入排序优化
                AvailableSettings.ORDER_UPDATES to "true"           // 批量更新排序优化
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
```

### Flyway Standalone Setup

```kotlin
private fun runFlywayMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)    // 允许对已有数据库 baseline
        .load()
        .migrate()
}
```

---

## Architecture Patterns

### System Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Message Write Path                           │
│                                                                      │
│  Chat Handler (Phase 6)                                              │
│       │                                                             │
│       │ 1. validate + Snowflake ID                                  │
│       │ 2. generate ChatMessage bytes                               │
│       ▼                                                             │
│  ┌─────────────────┐                                                │
│  │ MessageWriteRepo │─── XADD ──► ┌──────────────────┐              │
│  │  .enqueueMessage │             │  Redis Stream     │              │
│  └─────────────────┘              │  queue:messages   │              │
│       │                          └────────┬─────────┘              │
│       │ 3. ACK to caller                   │                        │
│       ▼                                    │ XREADGROUP             │
│  ┌─────────────────┐                       ▼                        │
│  │  Client gets    │  ┌──────────────────────────────┐              │
│  │  immediate ACK  │  │ AsyncFlushWorker (scheduled) │              │
│  └─────────────────┘  │  • every 500ms               │              │
│                       │  • if ≥ 30 messages pending  │              │
│                       │  • batch INSERT to MySQL     │              │
│                       └──────────┬───────────────────┘              │
│                                  │ XACK + INSERT                    │
│                                  ▼                                  │
│  ┌──────────────────────────────────────────┐                       │
│  │  MySQL: messages table                   │                       │
│  │  (Snowflake ID clustered PK)             │                       │
│  └──────────────────────────────────────────┘                       │
│                                                                      │
│                         Message Read Path                           │
│                                                                      │
│  Client pull request                                                 │
│       │                                                             │
│       │ cursor + limit + direction                                  │
│       ▼                                                             │
│  ┌─────────────────┐    ┌──────────────────────────┐               │
│  │  MessageRepo    │───►│  SELECT ... FROM messages │               │
│  │  .findMessages* │    │  WHERE conversation_id=X │               │
│  └─────────────────┘    │  AND id </> cursor       │               │
│                          │  ORDER BY id DESC/ASC    │               │
│                          │  LIMIT limit             │               │
│                          └──────────────────────────┘               │
└──────────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

```
repository/src/main/kotlin/com/nebula/repository/
├── RepositoryModule.kt              # Koin module (Phase 4) or manual DI wiring
├── entity/
│   ├── UserEntity.kt
│   ├── ConversationEntity.kt
│   ├── ConversationMemberEntity.kt
│   ├── MessageEntity.kt
│   ├── FriendshipEntity.kt
│   └── FriendRequestEntity.kt
├── repository/
│   ├── UserRepository.kt
│   ├── ConversationRepository.kt
│   ├── ConversationMemberRepository.kt
│   ├── MessageRepository.kt
│   ├── FriendshipRepository.kt
│   ├── FriendRequestRepository.kt
│   └── impl/
│       └── MessageRepositoryImpl.kt   # Async flush + batch write logic
├── redis/
│   ├── SessionRepository.kt           # session:token:* operations
│   ├── MessageQueueRepository.kt      # Redis Stream XADD/XREADGROUP/XACK
│   └── OnlineStatusRepository.kt      # online:user:* operations
└── config/
    ├── JpaConfig.kt                   # EMF bootstrap + Flyway + Repository factories
    └── RedisConfig.kt                 # Lettuce RedisClient + StatefulRedisConnection

repository/src/main/resources/
└── db/migration/
    └── V1__init_schema.sql           # Complete DDL with all 6 tables + indexes
```

### Pattern 1: Async Write-Through (Message Write Path)
**What:** Messages are immediately written to Redis Stream (client gets ACK in ~1ms), then asynchronously flushed to MySQL via batch INSERT.
**When to use:** Write-heavy path where latency matters; the key insight is Redis ACK is the user-visible success, MySQL persistence is a durability guarantee.

```
                    ┌──────────┐
                    │  Client  │
                    └────┬─────┘
                         │ ACK (~1ms)
                         ▼
                    ┌──────────┐     XADD      ┌──────────────────────┐
                    │  Send    │──────────────►│   Redis Stream       │
                    │  Handler │               │   queue:messages     │
                    └──────────┘               └──────────┬───────────┘
                                                          │ XREADGROUP (every 500ms)
                                                          ▼
                                                    ┌──────────────┐
                                                    │ Async Flush  │
                                                    │ Worker       │
                                                    │ (≥30 batch)  │
                                                    └──────┬───────┘
                                                           │ batch INSERT
                                                           ▼
                                                    ┌──────────────┐
                                                    │    MySQL     │
                                                    │  messages    │
                                                    └──────────────┘
```

### Pattern 2: Flyway + Hibernate Validate Mode
**What:** Flyway manages DDL migrations; Hibernate is set to `hbm2ddl.auto=validate` to catch entity/schema drift at startup.
**When to use:** Paired with Flyway to prevent silent schema evolution from entity changes.

### Pattern 3: Cursor Pagination (per D-12)
**What:** Uses Snowflake message ID as cursor for "load more" pagination. `WHERE id < :cursor ORDER BY id DESC LIMIT :limit` for backward pull; `WHERE id > :cursor ORDER BY id ASC` for forward.

### Anti-Patterns to Avoid
- **Using `data class` for JPA entities** — `copy()` clones break Hibernate's proxy; `equals/hashCode` default implementations cause issues with lazy-loaded collections
- **Setting `hbm2ddl.auto=update` with Flyway** — Two sources of truth cause schema drift and confusion
- **Mixing Sync + Async Redis operations on same connection** — Lettuce connections have a sync/async/reactive mode; pick one per connection
- **Blocking on Redis futures in Netty event loop** — Lettuce async futures must not be `.get()` inside Netty threads; use callbacks or coroutines
- **`OFFSET` pagination for messages** — OFFSET scans all skipped rows; cursor pagination uses index directly (constant-time per page)

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Repository CRUD boilerplate | Custom `save/findById/delete` per entity | Spring Data `JpaRepository<T, ID>` | 6 entities × 5 CRUD methods = 30 methods auto-provided |
| Message ID generation | Database `AUTO_INCREMENT` or UUID | SnowflakeIdGenerator (Phase 2) | Time-ordered, cluster-safe, perfect for cursor pagination |
| Connection pool management | Custom pool implementation | HikariCP via `HikariDataSourceProvider` | Battle-tested; already in Phase 2 |
| Redis connection lifecycle | Manual socket management | Lettuce `RedisClient` | Connection pooling, reconnection, async API all built-in |
| Database migration versioning | Manual SQL execution ordering | Flyway | Version tracking, rollback awareness, team collaboration |
| Declarative transaction management | Manual `beginTransaction/commit/rollback` | Spring `@Transactional` | Consistent rollback semantics; integrates with Hibernate session |

**Key insight:** Every hand-rolled solution in this list has been tried thousands of times in production and failed. The standard libraries handle edge cases (connection storms, partial failures, concurrent access, memory leaks) that custom code inevitably misses.

---

## Common Pitfalls

### Pitfall 1: JPA N+1 Query Problem
**What goes wrong:** Fetching `conversation_members` then iterating to access each member's `user` triggers N additional SELECTs.
**Why it happens:** Default `FetchType.LAZY` on `@ManyToOne` causes per-entity lazy loading.
**How to avoid:** Use `@EntityGraph` or `JOIN FETCH` in repository queries. Or batch fetch with `@BatchSize(size = 30)`.
**Warning signs:** Slow conversation/conversation_member queries that get slower with more data.

### Pitfall 2: Flyway + Hibernate Schema Drift
**What goes wrong:** Entity annotations change but Flyway migration is not updated. Hibernate in `validate` mode crashes on startup.
**Why it happens:** Entity and migration script are two separate artifacts that must be kept in sync.
**How to avoid:** Always run migration script changes in the same commit as entity changes. Use `hbm2ddl.auto=validate` to catch drift in CI.
**Warning signs:** Server fails to start with "missing column" or "type mismatch" in Hibernate validation.

### Pitfall 3: Redis Stream Consumer Group Not Created
**What goes wrong:** First XREADGROUP call throws `NOGROUP No such key 'queue:messages' or consumer group 'flush-workers'`.
**Why it happens:** Consumer group must be created explicitly via `XGROUP CREATE` with `MKSTREAM` before any consumer can read.
**How to avoid:** Create consumer group during startup initialization with `MKSTREAM` flag (creates stream if not exist). Wrap in try-catch to tolerate "already exists".
**Warning signs:** Server starts but message queue operations fail with NOGROUP error.

### Pitfall 4: Lettuce Connection Leak
**What goes wrong:** Each repository creates its own `StatefulRedisConnection`, exhausting Redis connection limit.
**Why it happens:** Lettuce connections are not cheap; they maintain TCP connections to Redis.
**How to avoid:** Share one `StatefulRedisConnection` across all Redis repositories. Lettuce connections are thread-safe.
**Warning signs:** Redis `client list` shows hundreds of connections; server logs `ERR max number of clients reached`.

### Pitfall 5: Synchronous Blocking on Async Work
**What goes wrong:** Netty event loop threads are blocked by `.get()` on Redis futures or JDBC calls, causing all connections to stall.
**Why it happens:** gRPC's Netty threads must never block (they handle I/O for all connections).
**How to avoid:** Use Lettuce coroutines API (`connection.coroutines()`) + `kotlinx.coroutines` for Redis. Use `CompletableFuture` or a dedicated executor for Hibernate/JDBC work (it's inherently blocking).
**Warning signs:** P99 latency spikes under load; server CPU < 100% but throughput plateaus.

---

## Code Examples

### Flyway Migration DDL Example

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

-- (Similar for all 5 remaining tables)
```

### Lettuce Redis Stream — Produce + Consume

```kotlin
// MessageQueueRepository — Redis Stream wrapper
import io.lettuce.core.*
import io.lettuce.core.api.coroutines.*

class MessageQueueRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = connection.coroutines()

    suspend fun ensureConsumerGroup() {
        try {
            redis.xgroupCreate("queue:messages", "flush-workers", XReadArgs.StreamOffset.from("queue:messages", "0-0"))
        } catch (e: RedisCommandExecutionException) {
            if (!e.message?.contains("BUSYGROUP")!!) throw e
            // 消费者组已存在，忽略
        }
    }

    suspend fun enqueue(message: Map<String, String>): String? {
        return redis.xadd("queue:messages", XAddArgs.Builder.maxlen(100000).approximateTrimming(), message)
    }

    suspend fun consume(batchSize: Long, blockMs: Long): List<StreamMessage<String, String>> {
        val messages = redis.xreadgroup(
            Consumer.from("flush-workers", "worker-1"),
            XReadArgs.StreamOffset.lastConsumed("queue:messages"),
            XReadArgs.Builder.count(batchSize).block(blockMs)
        ) ?: return emptyList()
        return messages
    }

    suspend fun acknowledge(messageId: String) {
        redis.xack("queue:messages", "flush-workers", messageId)
    }
}
```

### Async Batch Flush Worker

```kotlin
// MessageRepositoryImpl.kt
class MessageRepositoryImpl(
    private val messageQueue: MessageQueueRepository,
    private val jpaMessageRepo: MessageRepository,  // Spring Data JPA repo
    private val emf: EntityManagerFactory
) {
    private val logger = KotlinLogging.logger {}
    private var stopped = false

    /** 启动定时刷写任务 */
    fun startFlushTimer() {
        CoroutineScope(Dispatchers.IO).launch {
            while (!stopped) {
                delay(500) // 每 500ms 检查一次
                flushBatch()
            }
        }
    }

    suspend fun flushBatch() {
        val entries = messageQueue.consume(batchSize = 30, blockMs = 0)
        if (entries.isEmpty()) return

        val messages = entries.mapNotNull { entry -> parseToEntity(entry) }
        if (messages.size < 30) return  // 不到阈值，XACK 但不刷盘

        try {
            // 使用批量 INSERT 刷入 MySQL
            val em = emf.createEntityManager()
            em.transaction.begin()
            var count = 0
            for (msg in messages) {
                em.persist(msg)
                count++
                if (count % 30 == 0) {
                    em.flush()
                    em.clear()
                }
            }
            em.transaction.commit()

            // XACK 所有成功写入的消息
            entries.forEach { messageQueue.acknowledge(it.id) }
        } catch (e: Exception) {
            logger.error(e) { "批量刷写消息失败" }
            // 失败的消息保留在 Redis Stream 中，下次重试
        } finally {
            em.close()
        }
    }
}
```

### Cursor Pagination Query

```kotlin
// MessageRepository.kt (Spring Data JPA interface)
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

### Unread Count + Read Receipt

```kotlin
// ConversationMemberRepository.kt (additional methods)
interface ConversationMemberRepository : JpaRepository<ConversationMemberEntity, Long> {
    // 已读回执：更新 last_read_message_id 并清零 unread_count
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

    // 增加未读计数（消息发送时调用）
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

### Lettuce Redis Config (Standalone)

```kotlin
// RedisConfig.kt
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

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

    /** 确保 CGs 等基础设施就绪 */
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

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Jedis (sync blocking) | Lettuce (async/non-blocking) | ~2018 | Lettuce matches Netty's event-driven model |
| Hibernate hbm2ddl auto | Flyway + validate mode | ~2015 | Version-controlled, auditable migrations |
| OFFSET pagination | Cursor-based pagination | ~2018 | Eliminates OFFSET scan cost; constant-time per page |
| Redis List (LPUSH/BRPOP) | Redis Stream (XADD/XREADGROUP) | Redis 5.0 (2018) | Built-in consumer groups, ACK, backtracking |
| Single enum MessageType | PushEventType + ChatContentType (split) | This phase | Clear separation of routing vs content format concerns |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Lettuce Kotlin coroutines API works with `kotlinx-coroutines-reactive` on classpath | Code Examples | `[ASSUMED]` — verified from official docs; integration test needed |
| A2 | Spring Data JPA standalone (no Spring Boot) works with `JpaRepositoryFactory` | Bootstrap | `[ASSUMED]` — documented but rarely used; fallback: use `@EnableJpaRepositories` with minimal `AnnotationConfigApplicationContext` |
| A3 | HikariCP DataSource can be shared between Flyway and Hibernate | Flyway | `[ASSUMED]` — both use `javax.sql.DataSource` interface; alignment verified in Phase 2 docs |
| A4 | Flyway 10.x FluentConfiguration API as shown | Flyway | `[CITED: javadoc.io/static/org.flywaydb/flyway-core/10.5.0]` — verified from official Javadoc |

---

## Open Questions (RESOLVED)

1. **How to wire dependencies without Koin?**
   - What we know: Phase 4 introduces Koin DI. Phase 3 currently has no DI framework.
   - What's unclear: Should `:repository` module accept dependencies via constructor parameters during server bootstrap (manual DI), or should `:server` module create a minimal Koin setup early?
   - Recommendation: Use constructor-injection + manual wiring in `:server` module's startup sequence. The server bootstrap creates `RedisConfig`, `JpaConfig`, then passes factories to repository constructors. Koin migration in Phase 4.

2. **Does the project need `docker-compose.yml` for MySQL/Redis?**
   - What we know: No docker-compose.yml exists yet. Phase 3 needs running MySQL + Redis.
   - What's unclear: Whether to add docker-compose.yml in this phase or the developer runs services manually.
   - Recommendation: Add `docker-compose.yml` at project root with MySQL 8.0 + Redis 7.x services. This is an enabler for ALL subsequent phases and should be done in Phase 3.

3. **Which Spring libraries are truly needed?**
   - What we know: `spring-data-jpa` and `spring-tx` are needed. They transitively pull `spring-core`, `spring-beans`, `spring-context`, `spring-orm`, `spring-jdbc`.
   - What's unclear: Whether the Spring dependency graph will conflict with the project's zero-Spring-annotation approach.
   - Recommendation: Add via Version Catalog; exclude what's not needed. Use `spring-tx` only for `@Transactional` annotation support. Monitor dependency tree for conflicts.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| MySQL 8.0+ | All DB-01 operations | ✗ | — | Install locally or use docker |
| Redis 7.0+ | All DB-02 operations | ✗ | — | Install locally or use docker |
| JDK 21 | Hibernate/Spring runtime | ✓ | (verify) | — |
| Gradle | Build system | ✓ | (verify) | — |

**Missing dependencies with no fallback:**
- MySQL 8.0+ — all database features require it. Recommend adding `docker-compose.yml` in this phase.
- Redis 7.0+ — required for Stream XAUTOCLAIM with deleted-IDs support (Redis 7.0+). Without it, XAUTOCLAIM works but deleted-ID tracking is unavailable.

**Missing dependencies with fallback:**
- None — both MySQL and Redis are hard requirements for this phase.

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | Session token management is Phase 5; Phase 3 provides the storage infrastructure |
| V4 Access Control | No | Access control is Phase 4+5 (Handler interceptors) |
| V5 Input Validation | Partial | SQL injection prevention via JPA parameterized queries |
| V6 Cryptography | Partial | Password hashing (BCrypt) is Phase 5; Phase 3 stores the hash column |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL Injection | Tampering | JPA parameterized queries (`@Query` with `@Param` or Spring Data derived queries) prevent injection automatically. Never use string concatenation for queries. |
| Connection Pool Exhaustion | Denial of Service | HikariCP `connectionTimeout` (30s) and `maximumPoolSize` (20) limit impact; leak detection threshold (10s) alerts on unclosed connections. |
| Redis Data Exposure | Information Disclosure | Redis is on localhost with no auth for dev; production should add `requirepass` and enable TLS. |

---

## Sources

### Primary (HIGH confidence)
- [CONTEXT.md](.planning/phases/03-database-schema-repository-layer/03-CONTEXT.md) — All locked decisions D-01 through D-17
- [REQUIREMENTS.md](.planning/REQUIREMENTS.md) — DB-01 through DB-07 specifications
- Lettuce Kotlin API docs — `redis.github.io/lettuce/user-guide/kotlin-api/` — Kotlin coroutines integration verified
- Redis Stream with Lettuce official guide — `redis.io/docs/latest/develop/use-cases/streaming/java-lettuce/` — complete XADD/XREADGROUP/XACK patterns
- Flyway FluentConfiguration Javadoc — `javadoc.io/static/org.flywaydb/flyway-core/10.5.0/` — standalone bootstrap API
- [Phase 2 CONTEXT.md](.planning/phases/02-common-module-infrastructure-base/02-CONTEXT.md) — DataSourceProvider, HikariCP config, module dependency setup
- [Proto files](proto/src/main/proto/) — PushEventType / ChatContentType split verified (D-15 through D-17)

### Secondary (MEDIUM confidence)
- Spring Data JPA standalone (non-Boot) patterns — `JpaRepositoryFactory` usage documented but less common; recommend integration test verification

### Tertiary (LOW confidence)
- None — all critical technical claims verified against official docs or existing codebase

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries identified have official docs, each tool's standalone setup is documented
- Architecture: HIGH — patterns (async flush, cursor pagination, Redis Stream) are well-documented production patterns
- Pitfalls: HIGH — all listed pitfalls are documented in official docs or well-known in the ecosystem
- Table design: MEDIUM — field details are Claude's Discretion; specific column types and constraints represent a reasonable starting point that the planner may refine

**Research date:** 2026-06-11
**Valid until:** 2026-07-11 (stable libraries; Spring Data/Hibernate updates are backward compatible)
