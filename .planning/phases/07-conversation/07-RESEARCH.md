---
phase: 7
researcher: nx-researcher
---
# Phase 7: Conversation — 技术研究

**日期:** 2026-06-13
**领域:** 会话列表、群聊创建、成员管理、系统消息推送
**置信度:** HIGH

## 研究范围

本阶段需要解决以下技术问题：
1. 数据库 Schema 扩展（ConversationEntity + status/lastMessage 字段，ConversationMemberEntity + role）
2. Repository 层新增查询方法（会话列表 JOIN 查询、批量成员查询、成员计数、成员删除）
3. 多表写入的事务策略与并发控制（创建群聊 / 邀请成员 / 退群 / 踢人 / 解散群）
4. 系统消息（GroupCreated / MemberJoined / MemberLeft 等）的 Payload Proto 定义与推送方式
5. 会话列表的游标分页查询策略
6. 7 个新 Handler 的实现模式与 Koin DI 注册

---

## 技术栈上下文

- **语言:** Kotlin（全项目统一）
- **框架:** gRPC + Netty（唯一通信方式，NettyServerBuilder 构建 gRPC Server，双向流）
- **序列化:** Protobuf
- **数据库:** MySQL（6 张核心表） + Redis（未读计数/去重）
- **ORM:** Spring Data JPA + Hibernate（通过 JpaRepositoryFactory 手动创建 Repository）
- **DI:** Koin 4.x
- **协程:** kotlinx.coroutines（suspend 函数 + Dispatchers.IO）
- **现有事务:** 无 Spring PlatformTransactionManager — RegisterHandler 使用手动 `EntityManager.transaction.begin/commit/rollback`
- **推送基础设施:** PushService + UserStreamRegistry + Envelope(Direction.PUSH, Message, payload)

---

## 1. 数据库 Schema 扩展

### 1.1 需求分析 (D-17, D-21)

需要新增以下列：

| 表 | 新增列 | 类型 | 默认值 | 用途 |
|---|--------|------|--------|------|
| conversations | `status` | INT | 0 | 0=正常，1=已解散（软删除，保留消息记录） |
| conversations | `last_message_id` | BIGINT | 0 | 最后一条消息的 Snowflake ID |
| conversations | `last_message_preview` | VARCHAR(100) | '' | 最后一条消息的文本预览（截断 100 字符） |
| conversations | `last_message_ts` | BIGINT | 0 | 最后一条消息的 epoch millis 时间戳 |
| conversation_members | `role` | VARCHAR(16) | 'member' | 成员角色：owner / member |

### 1.2 方案：Flyway V2 迁移

**推荐方案：** 创建 `V2__phase7_conversation_schema.sql`，使用 `ALTER TABLE ADD COLUMN`。

```sql
-- V2__phase7_conversation_schema.sql
ALTER TABLE conversations
    ADD COLUMN status INT NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已解散',
    ADD COLUMN last_message_id BIGINT NOT NULL DEFAULT 0 COMMENT '最后一条消息ID',
    ADD COLUMN last_message_preview VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后一条消息预览',
    ADD COLUMN last_message_ts BIGINT NOT NULL DEFAULT 0 COMMENT '最后一条消息时间戳(ms)';

ALTER TABLE conversation_members
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'member' COMMENT '成员角色: owner/member';
```

**优点：**
- Flyway 自动执行，与现有 `V1__init_schema.sql` 模式一致
- `NOT NULL DEFAULT` 确保存量数据兼容（已有会话的 status=0, role='member'）

**注意事项：**
- Hibernate `validate` 模式会校验 Entity 字段与表列一致，新增 Entity 字段与 Flyway 迁移必须同步
- `ConversationEntity.updatedAt` 保持 `LocalDateTime` 类型（D-17 决策），Handler 层映射为 epoch millis

### 1.3 Entity 变更

**ConversationEntity 新增字段（`repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt`）：**

```kotlin
/** 会话状态：0=正常，1=已解散（D-09 软删除策略，保留消息记录） */
var status: Int = 0,

/** 最后一条消息 ID（Snowflake），消息发送时由 Phase 6 SendMessageHandler 更新（D-21） */
var lastMessageId: Long = 0,

/** 最后一条消息文本预览，截断 100 字符（D-21） */
@Column(length = 100)
var lastMessagePreview: String = "",

/** 最后一条消息时间戳（epoch millis）（D-21） */
var lastMessageTs: Long = 0,
```

> **重要：** 这三个 lastMessage 字段需要添加到构造函数参数列表中（非类体内），因为它们是 `ConversationEntity` 构造函数的必需参数（与现有字段 `type`, `name` 等同级）。

**ConversationMemberEntity 新增字段：**

```kotlin
/** 成员角色：owner（群主）/ member（普通成员），与 proto GroupMember.role 对齐（D-17） */
@Column(length = 16)
var role: String = "member",
```

---

## 2. Repository 层扩展

### 2.1 ConversationRepository 新增方法

#### 方案：JPA @Query 子查询 + Pageable

```kotlin
/**
 * 按用户 ID 查询参与的会话列表，支持游标分页（D-01, D-13, D-21）。
 *
 * 使用子查询而非 JOIN 的原因：
 * - 子查询可复用 `idx_user_convs` 索引高效获取 conversation_id 列表
 * - 外层查询按 updated_at 倒序 + 游标分页，利用 conversations 主键索引
 * - 单表返回 ConversationEntity，lastMessage 字段已反规范化（D-21），无需 JOIN messages 表
 *
 * @param userId 用户 ID
 * @param cursor 游标（LocalDateTime），null 表示首次查询
 * @param pageable 分页参数（limit + 1 用于判断 hasMore）
 * @return 按 updatedAt 倒序的会话列表
 */
@Query("""
    SELECT c FROM ConversationEntity c
    WHERE c.id IN (
        SELECT cm.conversationId FROM ConversationMemberEntity cm
        WHERE cm.userId = :userId AND cm.deleted = 0
    )
    AND (:cursor IS NULL OR c.updatedAt < :cursor)
    ORDER BY c.updatedAt DESC
""")
fun findConversationsByUserId(
    @Param("userId") userId: Long,
    @Param("cursor") cursor: LocalDateTime?,
    pageable: Pageable
): List<ConversationEntity>
```

**优点：**
- 单次查询，DB 层面完成过滤和排序
- 利用 `idx_user_convs(user_id)` 索引
- 复用 Spring Data JPA Pageable

**缺点：**
- 子查询在极端大量会话时（>1000）性能可能不如 JOIN + 覆盖索引（但 v1 用户会话数通常 < 100）
- 返回的 ConversationEntity 不含 `lastReadMsgId`（来自 ConversationMemberEntity），需要额外批量查询

### 2.2 ConversationMemberRepository 新增方法

```kotlin
/**
 * 按会话 ID 统计成员数量（D-05 群人数上限检查）。
 * 排除已软删除的成员记录（deleted = 0）。
 */
@Query("SELECT COUNT(cm) FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.deleted = 0")
fun countActiveByConversationId(@Param("convId") conversationId: String): Int

/**
 * 软删除指定成员（D-09 软删除策略）。
 * 设置 deleted = 1 而非物理删除，保留消息记录可追溯。
 */
@Modifying
@Query("UPDATE ConversationMemberEntity cm SET cm.deleted = 1 WHERE cm.conversationId = :convId AND cm.userId = :userId")
fun softDeleteByConversationIdAndUserId(
    @Param("convId") conversationId: String,
    @Param("userId") userId: Long
)

/**
 * 按会话 ID 和用户 ID 列表批量查询成员（邀请成员时检查是否已在群中，D-03）。
 * 返回匹配的成员列表，不存在的用户不会出现在结果中（JPA 行为）。
 */
@Query("SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId IN :userIds AND cm.deleted = 0")
fun findByConversationIdAndUserIds(
    @Param("convId") conversationId: String,
    @Param("userIds") userIds: List<Long>
): List<ConversationMemberEntity>

/**
 * 按会话 ID 列表和用户 ID 批量查询 lastReadMsgId（会话列表填充）。
 * 用于在获取会话列表后一次查询补充所有会话的 last_read_msg_id。
 */
@Query("SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId IN :convIds AND cm.userId = :userId AND cm.deleted = 0")
fun findByConversationIdsAndUserId(
    @Param("convIds") convIds: List<String>,
    @Param("userId") userId: Long
): List<ConversationMemberEntity>

/**
 * 清空会话的所有成员（D-09 解散群聊时使用）。
 * 设置所有成员的 deleted = 1。
 */
@Modifying
@Query("UPDATE ConversationMemberEntity cm SET cm.deleted = 1 WHERE cm.conversationId = :convId")
fun softDeleteAllByConversationId(@Param("convId") conversationId: String)
```

> **注意：** `findByConversationIdAndUserId` 和 `findByConversationId` 已在现有代码中存在，直接复用。`incrementUnreadCount` 和 `updateReadReceipt` 也已在 Phase 6 实现。

---

## 3. 事务与并发控制策略 (D-19)

### 3.1 现状分析

项目当前 **没有配置 Spring PlatformTransactionManager**。查看 `JpaConfig`：

```
JpaConfig.getRepository() → JpaRepositoryFactory(EntityManager) — 每次创建新的 EntityManager
```

`RegisterHandler` 使用手动事务管理：
```kotlin
val em = emf.createEntityManager()
em.transaction.begin()
// ... persist/flush ...
em.transaction.commit()
```

`spring-tx` 库（v6.2.6）已在 `libs.versions.toml` 中声明，但未被使用。

### 3.2 方案 A：配置 Spring TransactionTemplate（推荐）

在 `JpaConfig` 中基于已有的 `EntityManagerFactory` 创建 `JpaTransactionManager`，通过 Koin 注入 `TransactionTemplate`。

**实现步骤：**

1. 在 `repository` 模块的 `JpaConfig` 中新增方法：
```kotlin
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.support.TransactionTemplate

fun transactionTemplate(): TransactionTemplate {
    val tm = JpaTransactionManager(entityManagerFactory)
    tm.afterPropertiesSet()
    return TransactionTemplate(tm)
}
```

2. 在 Koin DI 中注册：
```kotlin
// repository 模块
single { jpaConfig.transactionTemplate() }
```

3. Handler 中使用：
```kotlin
class CreateGroupHandler(
    private val transactionTemplate: TransactionTemplate,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    // ...
) : Handler<CreateGroupReq, CreateGroupResp> {
    
    override suspend fun handle(req: CreateGroupReq): CreateGroupResp {
        return withContext(Dispatchers.IO) {
            transactionTemplate.execute {
                // 创建 ConversationEntity
                val conv = ConversationEntity(/* ... */)
                conversationRepository.save(conv)
                // 批量创建 ConversationMemberEntity
                // ...
                conv  // 返回值
            }
        }!!
    }
}
```

**优点：**
- 标准的 Spring 事务管理，声明式回滚（RuntimeException 自动回滚）
- D-19 明确要求使用 `TransactionTemplate.execute {}`
- `spring-tx` 已声明为依赖，只需配置，无需新增依赖
- 与协程兼容（事务在 `withContext(Dispatchers.IO)` 块内执行）

**缺点：**
- 需要理解 `JpaTransactionManager` 与 `JpaRepositoryFactory` 手动创建 Repository 的协作方式
- `JpaRepositoryFactory` 每次 `getRepository()` 创建新 EntityManager，TransactionTemplate 管理的是 `EntityManagerFactory` 级别的事务，需要验证 EntityManager 共享

**潜在问题 & 解决方案：**
- `JpaRepositoryFactory(em)` 使用独立 EntityManager，可能不参与 TransactionTemplate 管理的事务
- **解决：** 需要改为从 `EntityManagerFactory` 获取事务绑定的 EntityManager：
```kotlin
// 在 TransactionTemplate.execute 回调中
val em = entityManagerFactory.createEntityManager()
// Spring 的 JpaTransactionManager 会将 em 绑定到当前线程
```

或使用 `SharedEntityManagerCreator`：
```kotlin
val em = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
```

实际上，最简单的方式是让 Repository 的查询在 TransactionTemplate 管理的事务内执行即可。Hibernate 的 Session/EntityManager 绑定由 `JpaTransactionManager` 处理。

### 3.3 方案 B：手动 EntityManager 事务（备选）

沿用 `RegisterHandler` 模式，在每个需要事务的 Handler 中手动管理。

```kotlin
val em = emf.createEntityManager()
try {
    em.transaction.begin()
    // ... 操作 ...
    em.transaction.commit()
} catch (e: Exception) {
    if (em.transaction.isActive) em.transaction.rollback()
    throw e
} finally {
    em.close()
}
```

**优点：**
- 与现有 `RegisterHandler` 模式一致，无额外配置
- 事务边界完全显式可控

**缺点：**
- 代码重复，每个 Handler 都需要 try-catch-finally 模板
- 与 D-19 的 `TransactionTemplate` 决策不一致
- 手动管理容易出错（忘记 close、忘记 rollback）

**推荐：方案 A**，与 D-19 决策对齐。

### 3.4 并发控制：Mutex 按会话串行化 (D-19)

**推荐方案：** 创建 `ConversationLockManager` 组件，使用 `ConcurrentHashMap<String, Mutex>`。

```kotlin
package com.nebula.gateway.handler.conversation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级互斥锁管理器（D-19）。
 *
 * 按 conversationId 串行化并发写操作（创建群聊 / 邀请成员 / 退群 / 踢人 / 解散群），
 * 防止 memberCount 等字段竞态。
 *
 * 使用 kotlinx.coroutines Mutex（非阻塞），与协程环境兼容。
 */
class ConversationLockManager {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * 在指定会话的互斥锁保护下执行操作。
     *
     * @param conversationId 会话 ID
     * @param block 需要串行化执行的操作
     */
    suspend fun <T> withLock(conversationId: String, block: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock { block() }
    }
}
```

**注册到 Koin：**
```kotlin
single { ConversationLockManager() }
```

**使用示例：**
```kotlin
conversationLockManager.withLock(req.conversationId) {
    transactionTemplate.execute {
        // 检查成员数 → 验证权限 → 插入新成员 → 更新 memberCount
    }
}
```

**优点：**
- 细粒度锁（按 conversationId），不同群聊操作可并行
- `kotlinx.coroutines.sync.Mutex` 是协程友好的挂起锁，不阻塞线程
- 简单且可靠

**注意事项：**
- Mutex 不会自动清理，长期运行可能积累（但 conversationId 数量有限，可接受）
- 如需清理，可定期扫描 `mutexes` 移除长时间未使用的条目（非 v1 必需）

---

## 4. 系统消息推送 (D-04, D-11, D-18)

### 4.1 Payload Proto 定义

在 `proto/src/main/proto/nebula/conversation/conversation.proto` 中新增 6 个 Payload 消息：

```protobuf
// ---- 推送 Payload 消息（D-11, D-18） ----

// 群聊创建通知（推送给初始成员，排除创建者）
message GroupCreatedPayload {
  string conversation_id = 1;    // 新群会话 ID
  string name = 2;               // 群名称
  int64 creator_uid = 3;         // 创建者 UID
}

// 成员加入通知（推送给群内现有成员）
message MemberJoinedPayload {
  string conversation_id = 1;    // 会话 ID
  repeated int64 uids = 2;       // 新加入成员 UID 列表
  int64 inviter_uid = 3;         // 邀请者 UID
}

// 成员退出通知（推送给剩余成员）
message MemberLeftPayload {
  string conversation_id = 1;    // 会话 ID
  int64 uid = 2;                 // 退出者 UID
}

// 成员被踢通知（推送给被踢者）
message MemberKickedPayload {
  string conversation_id = 1;    // 会话 ID
  int64 uid = 2;                 // 被踢者 UID
}

// 群资料更新通知（推送给所有成员）
message GroupUpdatedPayload {
  string conversation_id = 1;    // 会话 ID
  optional string name = 2;      // 新名称（如有变更）
  optional string avatar_url = 3; // 新头像（如有变更）
}

// 群解散通知（推送给所有成员）
message GroupDissolvedPayload {
  string conversation_id = 1;    // 会话 ID
}
```

> **D-18 决策：** `GROUP_INVITED=6` 枚举值保留但 Phase 7 不触发，预留 v2 审批制入群。

### 4.2 PushService 扩展

**推荐方案：** 新增 `pushConversationEvent()` 方法，遵循现有 `pushMessage()` / `pushReadReceipt()` 模式。

```kotlin
/**
 * 向会话成员推送会话事件（D-04, D-11）。
 *
 * 流程：
 * 1. 查询会话所有在线成员（排除 excludeUids 中的用户）
 * 2. 逐个构建 PUSH Envelope 并投递
 * 3. 单个 observer 异常 try-catch，不影响其他 observer（D-05 容错）
 *
 * @param convId 会话 ID
 * @param eventType 推送事件类型（GROUP_CREATED / MEMBER_JOINED 等）
 * @param payloadBytes 序列化后的 Payload Proto 字节
 * @param excludeUids 排除的用户 ID 集合（如创建者不需要收 GROUP_CREATED）
 */
suspend fun pushConversationEvent(
    convId: String,
    eventType: PushEventType,
    payloadBytes: com.google.protobuf.ByteString,
    excludeUids: Set<Long> = emptySet()
) {
    val members = conversationMemberRepository.findByConversationId(convId)
    for (member in members) {
        if (member.userId in excludeUids) continue
        val observers = userStreamRegistry.getStreams(member.userId)
        for (observer in observers) {
            try {
                val envelope = Envelope.newBuilder()
                    .setDirection(Direction.PUSH)
                    .setRequestId("")
                    .setMessage(Message.newBuilder()
                        .setEventType(eventType)
                        .setContent("")
                        .setPayload(payloadBytes)
                        .build())
                    .build()
                observer.onNext(envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to push $eventType to userId=${member.userId}" }
                userStreamRegistry.removeStream(member.userId, observer)
            }
        }
    }
}
```

**推送到指定用户（用于 MEMBER_KICKED 单独通知被踢者）：**

```kotlin
/**
 * 向指定用户推送事件（用于踢人通知等需要单独推送的场景）。
 */
fun pushEventToUser(userId: Long, eventType: PushEventType, payloadBytes: com.google.protobuf.ByteString) {
    val observers = userStreamRegistry.getStreams(userId)
    for (observer in observers) {
        try {
            val envelope = Envelope.newBuilder()
                .setDirection(Direction.PUSH)
                .setRequestId("")
                .setMessage(Message.newBuilder()
                    .setEventType(eventType)
                    .setContent("")
                    .setPayload(payloadBytes)
                    .build())
                .build()
            observer.onNext(envelope)
        } catch (e: Exception) {
            logger.error(e) { "Failed to push $eventType to userId=$userId" }
            userStreamRegistry.removeStream(userId, observer)
        }
    }
}
```

**优点：**
- 复用现有 `Envelope(Direction.PUSH, Message, payload)` 模式
- 与 `pushMessage()` 结构相似，代码可读性强
- 支持排除指定用户（如创建者不接收 GROUP_CREATED）
- 容错：单个 observer 异常不影响其他 observer

### 4.3 推送时机与排除策略汇总

| 操作 | 事件类型 | 推送目标 | 排除 |
|------|----------|----------|------|
| create_group | GROUP_CREATED | 初始成员 | 创建者 |
| invite_member | MEMBER_JOINED | 群内现有成员 | 无 |
| leave_group (成员) | MEMBER_LEFT | 剩余成员 | 退群者 |
| leave_group (群主) | GROUP_DISSOLVED | 所有成员 | 无 |
| kick_member | MEMBER_KICKED | 被踢者（单独推送） | 无 |
| kick_member | MEMBER_LEFT | 剩余成员 | 被踢者 |
| edit_group_info | GROUP_UPDATED | 所有成员 | 无 |

---

## 5. 会话列表查询策略 (D-01, D-13, D-21)

### 5.1 查询流程

```
1. 从 ConversationMemberEntity 获取用户参与的会话 ID 列表
   → findByUserId(userId) [已有方法]
   → 过滤 deleted = 0

2. 通过 ConversationRepository.findConversationsByUserId() 单次查询
   → 子查询 JOIN conversation_members（利用 idx_user_convs 索引）
   → WHERE c.updatedAt < cursor（游标分页）
   → ORDER BY c.updatedAt DESC
   → LIMIT limit + 1（多取一条用于判断 hasMore）

3. 批量查询 lastReadMsgId
   → findByConversationIdsAndUserId(convIds, userId) [新增方法]

4. 内存映射 → ConvListResp
   → ConversationEntity → ConversationBrief（lastMessage 字段已在 Entity 中）
   → lastReadMsgId 从 member 记录补充
```

### 5.2 游标分页细节

- **首次请求:** `cursor = 0` → 内部转换为 `cursor = null`（无 WHERE 游标约束）
- **翻页:** `cursor = 上一页最后一条的 updatedAt epoch millis` → 转换为 `LocalDateTime`
- **hasMore:** 查询 `limit + 1` 条，如果返回数量 > limit，则 hasMore = true，截掉最后一条
- **排序:** `ORDER BY c.updatedAt DESC` — 最近活跃的会话排最前面

### 5.3 性能评估

- 单个用户通常 < 100 个会话，最多数百个，查询在毫秒级完成
- `idx_user_convs(user_id)` 覆盖 conversation_members 表
- conversations 表按主键 `id` 查询，无额外索引需求
- 批量查询 lastReadMsgId（IN 查询）一次 DB 往返

---

## 6. 各 Handler 实现路径

### 6.1 Handler 清单与文件组织 (D-12)

| 文件 | method | 复杂度 | 事务需求 |
|------|--------|--------|----------|
| `ListConversationsHandler.kt` | conversation/list | 中 | 无（纯查询） |
| `CreateGroupHandler.kt` | conversation/create_group | 高 | 是（写 Conversation + N×Member） |
| `InviteMemberHandler.kt` | conversation/invite_member | 高 | 是（写 Member + 更新 memberCount） |
| `LeaveGroupHandler.kt` | conversation/leave_group | 高 | 是（删 Member 或 解散群） |
| `KickMemberHandler.kt` | conversation/kick_member | 中 | 是（删 Member + 更新 memberCount） |
| `EditGroupHandler.kt` | conversation/edit_group_info | 低 | 否（单表更新） |
| `GroupMembersHandler.kt` | conversation/group_members | 低 | 无（纯查询） |

存放路径：`gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/`

### 6.2 CreateGroupHandler 实现大纲

```
1. 参数校验：name 非空且 ≤128 字符，创建者不在 member_uids 中 (D-10)
2. 成员上限检查：member_uids.size + 1 ≤ 200 (D-05)
3. UUID 生成 conversation_id (D-02)
4. 事务 + 互斥锁：
   a. 创建 ConversationEntity(type=2 群聊, status=0, memberCount=1+member_uids.size)
   b. 创建创建者的 ConversationMemberEntity(role="owner")
   c. 批量创建初始成员的 ConversationMemberEntity(role="member")
5. 事务提交后 → 异步推送 GROUP_CREATED 给初始成员（排除创建者）(D-04)
6. 返回 CreateGroupResp(conversation_id, name)
```

### 6.3 InviteMemberHandler 实现大纲

```
1. 获取会话，验证 status != DISSOLVED (D-09)
2. 验证 inviter 是成员 (D-03)
3. 过滤已在群中的 uid (D-03 前置检查，已存在则跳过)
4. 上限检查：当前成员数 + 新成员数 ≤ 200 (D-05)
5. 事务 + 互斥锁：
   a. 批量插入 ConversationMemberEntity(role="member")
   b. 更新 conversation.memberCount
6. 事务提交后 → 异步推送 MEMBER_JOINED 给现有成员 (D-04)
```

### 6.4 LeaveGroupHandler 实现大纲

```
1. 验证请求者是会话成员 (D-03)
2. 获取会话，验证 status != DISSOLVED
3. 判断角色：
   - 群主 (role="owner")：解散群聊 (D-09)
     → 事务 + 互斥锁：
       a. 更新 ConversationEntity.status = DISSOLVED
       b. 清空所有成员 (deleted = 1)
     → 异步推送 GROUP_DISSOLVED 给所有成员
   - 普通成员 (role="member")：退群
     → 事务 + 互斥锁：
       a. 软删除成员记录
       b. 更新 conversation.memberCount--
     → 异步推送 MEMBER_LEFT 给剩余成员 (D-04)
```

### 6.5 KickMemberHandler 实现大纲

```
1. 验证 kicker 是群主 (role="owner") (D-14)
2. 验证 conversation.status != DISSOLVED
3. 验证 target_uid != kicker (D-14: 不能踢自己)
4. 验证 target 不是群主 (D-14: 不能踢群主)
5. 验证 target 是成员
6. 事务 + 互斥锁：
   a. 软删除目标成员记录
   b. 更新 conversation.memberCount--
7. 事务提交后：
   a. 异步推送 MEMBER_KICKED 给被踢者
   b. 异步推送 MEMBER_LEFT 给剩余成员 (D-04)
```

### 6.6 EditGroupHandler 实现大纲

```
1. 验证请求者是群主 (role="owner") (D-15)
2. 验证 conversation.status != DISSOLVED
3. 验证至少传了 name 或 avatar_url (D-15)
4. 长度校验：name ≤128, avatar_url ≤256
5. 单表更新（无需事务）：
   - 有 name → 更新 conversation.name
   - 有 avatar_url → 更新 conversation.avatar
   - 更新 conversation.updatedAt
6. 异步推送 GROUP_UPDATED 给所有成员 (D-04)
```

### 6.7 GroupMembersHandler 实现大纲

```
1. 验证请求者是会话成员
2. 全量返回成员列表 (D-06)
3. 通过 UserRepository.findAllById() 批量获取用户信息
4. 映射为 GroupMember proto（含 uid, username, display_name, avatar_url, role, joined_at）
```

### 6.8 ListConversationsHandler 实现大纲

```
1. 获取用户参与的所有会话（游标分页）
2. 批量获取 lastReadMsgId
3. 映射为 ConversationBrief
4. LocalDateTime → epoch millis 映射（D-17）
5. lastMessagePreview 截断 100 字符（由 SendMessageHandler 写入时已截断）
```

### 6.9 PullMessagesHandler 安全修复 (D-07)

在 `PullMessagesHandler.handle()` 开头添加成员检查：

```kotlin
// D-07: 成员身份验证
val member = withContext(Dispatchers.IO) {
    conversationMemberRepository.findByConversationIdAndUserId(
        req.conversationId, session.userId
    )
} ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员，无权拉取消息")
```

---

## 7. DI 注册模式 (D-12)

### Koin handlerModule 新增

```kotlin
// Phase 7: Conversation
single { ConversationLockManager() }
single { ListConversationsHandler(get(), get()) }     // ConversationRepository, ConversationMemberRepository
single { CreateGroupHandler(get(), get(), get(), get(), get()) }
single { InviteMemberHandler(get(), get(), get(), get(), get()) }
single { LeaveGroupHandler(get(), get(), get(), get(), get()) }
single { KickMemberHandler(get(), get(), get(), get(), get()) }
single { EditGroupHandler(get(), get(), get()) }
single { GroupMembersHandler(get(), get(), get()) }
```

### registerHandlers() 新增参数

7 个新 Handler 作为函数参数追加，每个调用 `registry.register(handler)`。

---

## 8. 风险与注意事项

### 8.1 TransactionTemplate 与现有 Repository 兼容性

**风险：** `JpaConfig.getRepository()` 每次创建独立 EntityManager，可能与 `TransactionTemplate` 管理的事务不绑定。

**缓解措施：**
- 需在实现时验证：在 `TransactionTemplate.execute {}` 回调内通过 `getRepository()` 获取的 Repository 的 EntityManager 是否参与了同一个事务
- 如果 `JpaRepositoryFactory` 创建的 SimpleJpaRepository 使用独立 EntityManager，可能需要改为注入 `EntityManagerFactory` 并在事务回调内创建 EntityManager
- 或者将 Repository 的 EntityManager 改为通过 `EntityManagerFactory.getObject().createEntityManager()` 在事务上下文中获取

### 8.2 推送与事务的顺序

**风险：** 推送在事务提交后执行（fire-and-forget），如果推送时发现事务实际未提交成功（虽然概率极低），客户端会收到"假"通知。

**缓解措施：**
- 当前设计：事务先提交 → 成功后异步推送。这是正确顺序。
- 参考 SendMessageHandler 模式：写入先完成 → 返回响应 → fire-and-forget 推送
- 推送失败不影响业务操作，best-effort 语义

### 8.3 memberCount 竞态

**风险：** 两个客户端同时邀请成员，memberCount 可能计算错误。

**缓解措施：**
- `ConversationLockManager` 按 conversationId 串行化，同一会话的写操作互斥
- 事务内先查询当前成员数 → 验证上限 → 插入 → 更新 memberCount
- 或者使用 SQL `UPDATE conversations SET member_count = member_count + ? WHERE id = ?` 原子更新

### 8.4 群主退群解散的边界

**风险：** 群主退群 → 解散群，但如果群主退群请求和踢人请求并发，可能产生不一致。

**缓解措施：**
- 互斥锁按 conversationId 串行化，防止并发修改
- 解散前检查 status（非 DISSOLVED 才执行）
- 踢人前检查 conversation.status（非 DISSOLVED 才执行）

### 8.5 群人数上限的精确性

**风险：** 当前成员数 + 新成员数 ≤ 200 的检查在事务内执行，但事务隔离级别可能影响计数。

**缓解措施：**
- 互斥锁确保同一会话串行化
- 使用 `countActiveByConversationId()` 在事务内精确计数
- MySQL 默认 REPEATABLE-READ 隔离级别在单会话串行化下足够

### 8.6 Proto 文件修改后的代码生成

**风险：** 修改 `conversation.proto` 添加 Payload 消息后，需要重新生成 Kotlin 代码。

**缓解措施：**
- 按 Phase 1 建立的 protobuf gradle 插件流程，运行 `gradle generateProto` 即可
- Payload 消息是纯新增，不影响现有 Request/Response 消息

---

## 9. 参考资源

### 现有代码

| 文件 | 用途 |
|------|------|
| `repository/.../entity/ConversationEntity.kt` | 需修改：新增 status, lastMessageId, lastMessagePreview, lastMessageTs |
| `repository/.../entity/ConversationMemberEntity.kt` | 需修改：新增 role |
| `repository/.../repository/ConversationRepository.kt` | 需扩展：新增 findConversationsByUserId |
| `repository/.../repository/ConversationMemberRepository.kt` | 需扩展：新增 4 个方法 |
| `gateway/.../handler/chat/send/SendMessageHandler.kt` | 参考：Step 链模式、fire-and-forget 异步推送 |
| `gateway/.../handler/chat/send/ValidateStep.kt` | 参考：成员身份校验模式 |
| `gateway/.../handler/message/ReadReportHandler.kt` | 参考：成员检查 + PushService 集成 |
| `gateway/.../handler/user/GetProfileHandler.kt` | 参考：简单 Handler 模式 |
| `gateway/.../handler/user/BatchGetUserHandler.kt` | 参考：批量查询 + findAllById 模式 |
| `gateway/.../push/PushService.kt` | 需扩展：新增 pushConversationEvent / pushEventToUser |
| `gateway/.../di/GatewayModule.kt` | 需扩展：新增 7 个 Handler + ConversationLockManager |
| `gateway/.../handler/user/RegisterHandler.kt` | 参考：手动 EntityManager 事务模式（若不用 TransactionTemplate） |
| `repository/.../config/JpaConfig.kt` | 需扩展：新增 transactionTemplate() 方法（若用方案 A） |
| `gateway/.../handler/message/PullMessagesHandler.kt` | 需修改：添加成员检查 (D-07) |

### Proto 文件

| 文件 | 需修改 |
|------|--------|
| `proto/.../nebula/conversation/conversation.proto` | 新增 6 个 Payload 消息 |
| `proto/.../nebula/message_type.proto` | 已定义 PushEventType 枚举（含 GROUP_CREATED ~ MEMBER_KICKED），无需修改 |

### Flyway 迁移

| 文件 | 内容 |
|------|------|
| `repository/src/main/resources/db/migration/V2__phase7_conversation_schema.sql` | 新增 5 个列 |

---

## RESEARCH COMPLETE

**置信度评估：**
- Schema 设计: HIGH — 基于明确的 D-17、D-21 决策
- Repository 查询: HIGH — 遵循现有 JPA @Query 模式
- 事务策略: MEDIUM — TransactionTemplate 集成需在实现阶段验证与 JpaRepositoryFactory 的兼容性
- 并发控制: HIGH — Mutex 模式简单可靠，与协程兼容
- Push 推送: HIGH — 完全复用 PushService + Envelope 模式
- Handler 实现: HIGH — 所有 7 个 Handler 的实现路径清晰，有明确参考模式
