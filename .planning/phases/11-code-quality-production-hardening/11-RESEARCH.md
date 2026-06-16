---
phase: 11
type: research
researcher: nx-researcher-1
date: 2026-06-15
---

# Phase 11: Code Quality & Production Hardening — 技术研究

## 研究范围

基于 CONTEXT.md 中 15 个 CQ 需求和 13 个技术决策（D-76 ~ D-88），以及 REVIEW.md 中 81 个具体问题，对 Phase 11 涉及的核心技术点进行系统性研究。

## 技术栈上下文

- **语言**：Kotlin
- **框架**：gRPC 双向流（Netty）、Spring Data JPA + Hibernate、Lettuce Redis 客户端
- **数据库**：MySQL 8.x + Redis 6.x
- **配置**：Typesafe Config (HOCON)、HikariCP 连接池
- **DI**：Koin
- **日志**：kotlin-logging（SLF4J 门面 + Logback 实现）
- **协程**：kotlinx.coroutines

---

## 1. CQ-02 安全加固（Plan 11-01）

### 1.1 HOCON `${?VAR}` 环境变量注入

**对应决策**：D-77、D-78

**现有状态分析**：

当前 `application.conf` 已使用 `${?VAR}` 语法（如 `password = ${?DB_PASSWORD}`），但问题在于仍保留了明文默认值 `password = "root123"`。这意味着如果环境变量未设置，密码会回退到明文默认值，不满足生产安全要求。

**技术方案**：

| 方案 | 描述 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **A: 移除默认值 + required** | 生产敏感的 key（password）不提供默认值，仅通过 `${?VAR}` 注入，缺失时启动报错 | 强制生产安全、零配置泄露风险 | 本地开发需显式设置环境变量 | ⭐⭐⭐⭐⭐ |
| **B: 分环境默认值** | 通过 `${?ENV}` 判断环境，dev 用明文默认值、prod 无默认值 | 开发便利 | 配置文件包含明文密码（即使仅 dev 使用），存在误提交到生产风险 | ⭐⭐ |
| **C: 引入 .env 文件** | 使用 dotenv-kotlin 加载 `.env` 文件 | 开发体验好 | 引入新依赖，违反 D-78 决策 | ⭐ |

**推荐方案 A**，实现路径：

```hocon
# application.conf — 生产安全密码注入
database {
    host = "127.0.0.1"
    host = ${?DB_HOST}
    port = 3306
    port = ${?DB_PORT}
    database = "nebula"
    database = ${?DB_NAME}
    username = "root"
    username = ${?DB_USER}
    # D-77: 生产环境不提供默认密码，缺失时启动即报错
    password = ${?DB_PASSWORD}
    ...
}

redis {
    host = "127.0.0.1"
    host = ${?REDIS_HOST}
    port = 6379
    port = ${?REDIS_PORT}
    # D-77: Redis 密码仅在需要时通过环境变量提供
    password = ${?REDIS_PASSWORD}
}
```

**注意事项**：

- Typesafe Config 的 `${?VAR}` 语法特性：如果环境变量 VAR 未设置，则该行会被**完全忽略**（而非设为 null 或空字符串）。因此 `password = "root123"` + `password = ${?DB_PASSWORD}` 的模式是：当 `DB_PASSWORD` 未设置时，使用上一行的 `"root123"`；设置时覆盖。
- 移除明文默认值后，`password = ${?DB_PASSWORD}` 单独一行：如果 `DB_PASSWORD` 未设置，`config.getString("database.password")` 会抛出 `ConfigException.Missing`。

**ConfigLoader 增强**：建议在 `ConfigLoader.parseConfig()` 中调用 `config.checkValid(ConfigFactory.defaultReference())` 做全面校验，将缺失密钥的错误信息集中报告。

### 1.2 Redis TLS 配置

**对应问题**：H03 — `RedisConfig.kt:26` 未启用 TLS

**现有状态分析**：

```kotlin
// 当前代码 — 无 SSL，无密码
val client: RedisClient by lazy {
    RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build())
}
```

**技术方案**：

Lettuce 支持两种 SSL 连接方式：

| 方案 | 代码 | 适用场景 | 推荐 |
|------|------|------|------|
| **A: RedisURI Builder** | `RedisURI.Builder.redis(host).withPort(port).withSsl(true).withPassword(password).build()` | 显式配置，类型安全 | ⭐⭐⭐⭐⭐ |
| **B: rediss:// URI** | `RedisClient.create("rediss://password@host:port")` | 简洁，字符串配置 | ⭐⭐⭐ |

**推荐方案 A**，与现有 Builder 风格一致。

**配置注入改造**：

```kotlin
// common/src/main/kotlin/com/nebula/common/config/RedisConfig.kt
data class RedisConfig(
    val host: String = "127.0.0.1",
    val port: Int = 6379,
    /** Redis 密码，null 表示无密码（开发环境），非 null 时启用 AUTH */
    val password: String? = null,
    /** 是否启用 TLS，生产环境应为 true */
    val ssl: Boolean = false
)
```

```kotlin
// repository/src/main/kotlin/com/nebula/repository/config/RedisConfig.kt
// 改名为 RedisConnectionConfig（避免与 common 层 RedisConfig 冲突）
val client: RedisClient by lazy {
    val uriBuilder = RedisURI.builder()
        .withHost(host)
        .withPort(port)
        .withSsl(config.ssl)
    config.password?.let { uriBuilder.withPassword(it.toCharArray()) }
    RedisClient.create(uriBuilder.build())
}
```

**分环境策略**（D-77）：
- dev：`ssl = false`，`password = null`（Docker Compose 本地 Redis）
- prod：`ssl = true`，`password = ${?REDIS_PASSWORD}`（TLS 加密 + AUTH 认证）

### 1.3 MySQL SSL 模式

**对应问题**：H01 — `HikariDataSourceProvider.kt:77-78` `sslMode=PREFERRED`

**现有状态分析**：

```kotlin
// 当前 buildJdbcUrl()
"jdbc:mysql://...?sslMode=PREFERRED&..."
```

`PREFERRED` 的含义：尝试 SSL 加密 → 如果服务器不支持则降级到明文。在生产环境中，这意味着如果攻击者干扰 SSL 协商（如中间人降级攻击），连接会静默回退到明文。

**MySQL Connector/J `sslMode` 属性对照**：

| sslMode | 含义 | 安全性 | 适用场景 |
|---------|------|--------|------|
| `DISABLED` | 不使用 SSL | 无 | 仅本地开发 |
| `PREFERRED` | 尝试 SSL，失败则降级明文（默认值） | 低（可降级） | 不推荐生产 |
| `REQUIRED` | 必须 SSL，不验证证书 | 中（加密但未验证身份） | 内网 TLS |
| `VERIFY_CA` | 必须 SSL + 验证 CA 签名 | 高 | **生产推荐** |
| `VERIFY_IDENTITY` | 必须 SSL + 验证 CA + 验证主机名 | 最高 | 严格生产环境 |

**推荐方案**：`sslMode=VERIFY_CA`

```kotlin
// 改造后 buildJdbcUrl()
private fun buildJdbcUrl(): String {
    val sslMode = if (config.sslEnabled) "VERIFY_CA" else "DISABLED"
    return "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
            "?sslMode=$sslMode" +
            "&useUnicode=true" +
            "&characterEncoding=UTF-8" +
            "&serverTimezone=UTC"
}
```

**附带要求**：
- 生产环境需配置 `trustCertificateKeyStoreUrl` 或导入 CA 证书到 JDK truststore
- `DatabaseConfig` 需要新增 `sslEnabled: Boolean` 字段

### 1.4 JVM Shutdown Hook

**对应问题**：H04 — `NebulaServer.kt:98-102` 无 Shutdown Hook，`ChatServer.stop()` 不可达

**现有状态分析**：

```kotlin
// NebulaServer.main() 末尾
val chatServer = ChatServer(config)
chatServer.start(chatService)
chatServer.blockUntilShutdown()  // 阻塞等待 gRPC Server 关闭
// chatServer.stop() 从未被调用
```

问题：`blockUntilShutdown()` 会阻塞 main 线程直到 gRPC Server 的 `awaitTermination()` 返回。但如果进程收到 SIGTERM，JVM 直接退出，`blockUntilShutdown()` 后的 `stop()` 代码不可达，且 Redis/DB 连接池不会被正确关闭。

**推荐模式**：

```kotlin
fun main() {
    // ... 初始化步骤 ...

    // 注册 Shutdown Hook — 确保 JVM 正常终止时执行清理
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "JVM shutdown hook triggered, starting graceful shutdown..." }
        
        // 1. 停止 gRPC Server（拒绝新连接，等待现有 RPC 完成）
        chatServer.stop()
        
        // 2. 停止补偿定时任务
        deadLetterCompensator.stop()
        
        // 3. 关闭 Redis 连接
        redisConfig.shutdown()
        
        // 4. 关闭 HikariCP 连接池
        (hikariDataSourceProvider.getDataSource() as? HikariDataSource)?.close()
        
        logger.info { "Graceful shutdown complete." }
    })

    // 启动服务
    chatServer.start(chatService)
    
    // 阻塞等待（替代 blockUntilShutdown）
    chatServer.blockUntilShutdown()
}
```

**关键注意事项**（来自 Baeldung 和官方文档）：
1. **Shutdown Hook 只在正常终止时执行**：`SIGTERM`/`Ctrl+C`/`System.exit()` → 执行；`SIGKILL`(`kill -9`)/`Runtime.halt()` → 不执行。这是 JVM 级别的限制，无法绕过。
2. **Hook 执行顺序不确定**：不要依赖多个 hook 之间的执行顺序。建议只注册一个 hook。
3. **Hook 是未启动的线程**：不能注册已启动的线程。使用 `Thread {}` 匿名线程 + lambda 模式。
4. **Hook 中不要执行耗时操作**：JVM 关闭有时间限制，长时间操作可能被强制中断。
5. **Hook 本身不应抛出异常**：使用 try-catch 包裹每个清理步骤，确保一个步骤失败不影响后续。

**与现有 `blockUntilShutdown()` 的协作**：

保留 `blockUntilShutdown()` 作为正常路径的阻塞等待，Shutdown Hook 作为 JVM 关闭信号的响应入口。两者互补：正常 exit → `blockUntilShutdown` 返回；kill 信号 → Shutdown Hook 触发。

---

## 2. CQ-03/04 事务保护与数据一致性（Plan 11-02）

### 2.1 TransactionTemplate 在 Kotlin 中的使用模式

**对应决策**：D-79（TransactionTemplate 沿用 Phase 7）

**现有状态分析**：

项目已有 `ConversationLockManager` 使用 `TransactionTemplate`，Handler 层（如 `InviteMemberHandler`）注入了 `TransactionTemplate`。但 `ConversationService` 内部方法（如 `createGroup`、`inviteMember`、`leaveGroup`、`kickMember`）的跨 Repository 写入**未包裹事务**。

当前 `inviteMember()` 的事务问题：

```kotlin
// 当前代码 — 非原子操作
suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long> {
    // ... 多次 withContext(Dispatchers.IO) { repo.save() } ...
    // 如果中间某次 save 失败，之前的 save 不会回滚
}
```

**TransactionTemplate 在 Kotlin 中的推荐模式**：

```kotlin
// 方式 A: execute 回调（推荐 — 最简洁）
transactionTemplate.execute {
    conversationRepository.save(conv)
    conversationMemberRepository.save(ownerMember)
    memberEntities.forEach { conversationMemberRepository.save(it) }
}

// 方式 B: execute 带返回值
val result = transactionTemplate.execute {
    conversationRepository.save(conv)
    // 返回事务内的结果
}

// 方式 C: 需要事务传播行为控制
transactionTemplate.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
transactionTemplate.execute { ... }
```

**Kotlin 特有的注意事项**：

- `TransactionTemplate.execute()` 接受 `TransactionCallback<T>`（SAM 接口），Kotlin lambda 可直接传入
- 事务内的异常（包括 `DataIntegrityViolationException`）会自动触发回滚
- 事务内不要使用 `withContext(Dispatchers.IO)` 切换线程，因为 Spring 事务绑定在 ThreadLocal 上。事务包裹的代码块本身就应该在合适的线程中执行

**修复模式**：

```kotlin
// ConversationService.inviteMember() 修复后
suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long> {
    val convId = req.conversationId
    
    return withContext(Dispatchers.IO) {
        transactionTemplate.execute {
            val conv = conversationRepository.findById(convId).orElse(null)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 批量查询替代 N+1（CQ-07）
            val existingMap = conversationMemberRepository
                .findByConversationIdAndUserIds(convId, req.uidsList)
                .associateBy { it.userId }

            val newMemberUids = mutableListOf<Long>()
            val now = LocalDateTime.now()

            for (uid in req.uidsList) {
                val existing = existingMap[uid]
                when {
                    existing != null && existing.deleted == 0 -> continue
                    existing != null && existing.deleted == 1 -> {
                        existing.deleted = 0
                        existing.joinedAt = now
                        conversationMemberRepository.save(existing)
                    }
                    else -> {
                        val member = ConversationMemberEntity(
                            conversationId = convId, userId = uid, role = ROLE_MEMBER
                        )
                        member.joinedAt = now
                        conversationMemberRepository.save(member)
                    }
                }
                newMemberUids.add(uid)
            }

            // D-82: JPQL 原子更新 memberCount
            if (newMemberUids.isNotEmpty()) {
                conversationRepository.incrementMemberCount(convId, newMemberUids.size)
            }
            newMemberUids
        }
    }
}
```

**关键设计点**：
- `withContext(Dispatchers.IO)` 包裹整个事务 — 事务在 IO 线程中执行
- `transactionTemplate.execute` 内部所有操作共享同一事务，异常自动回滚
- 批量查询 `findByConversationIdAndUserIds` 替代逐个查询（CQ-07 N+1 修复）

### 2.2 JDBC 隔离级别选型

**对应问题**：H14-H19 — 跨 Repository 写入无事务保护

**隔离级别对比**：

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | 性能 | 适用场景 |
|---------|------|-----------|------|------|------|
| `READ_UNCOMMITTED` | 是 | 是 | 是 | 最高 | 仅日志/统计 |
| `READ_COMMITTED` | 否 | 是 | 是 | 高 | **推荐默认** |
| `REPEATABLE_READ` | 否 | 否 | 是(部分) | 中 | 一致性要求高 |
| `SERIALIZABLE` | 否 | 否 | 否 | 最低 | 金融/对账 |

**推荐方案**：`READ_COMMITTED`（MySQL 默认级别）

**理由**：
1. Phase 10 已通过 DB 唯一约束（`uk_client_msg_id`、`uk_member`、`uk_friendship`）和 JPQL 原子更新（`incrementMemberCount`）处理竞态，不需要更高级别
2. 好友双向竞赛通过 `DuplicateKeyException` 幂等处理（D-80），在 `READ_COMMITTED` 下即可正常工作
3. 性能最优，避免 `REPEATABLE_READ` 的间隙锁开销

**TransactionTemplate 自定义隔离级别**（仅在需要时）：

```kotlin
transactionTemplate.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
```

### 2.3 JPQL `@Modifying` 原子更新

**对应决策**：D-82 — `memberCount` JPQL 原子更新

**现有状态**：

当前 `ConversationService` 中 `memberCount` 更新采用 `loadCount → set → save` 模式（非原子）：

```kotlin
// 非原子模式 — 存在竞态
val currentCount = withContext(Dispatchers.IO) {
    conversationMemberRepository.countActiveByConversationId(convId)
}
conv.memberCount = currentCount.toInt()
withContext(Dispatchers.IO) { conversationRepository.save(conv) }
```

**修复方案** — 新增 Repository 方法：

```kotlin
// ConversationRepository.kt — 新增原子更新方法
/**
 * 原子递增会话成员计数（D-82）。
 *
 * 使用 JPQL UPDATE + @Modifying，数据库侧单条原子语句保证一致性，
 * 避免应用层 loadCount → set → save 的 TOCTOU 竞态。
 *
 * @param conversationId 会话 ID
 * @param delta 成员数变化量（正数为增加，负数为减少）
 */
@Modifying
@Query("""
    UPDATE ConversationEntity c 
    SET c.memberCount = c.memberCount + :delta, c.updatedAt = CURRENT_TIMESTAMP 
    WHERE c.id = :convId
""")
fun incrementMemberCount(
    @Param("convId") conversationId: String,
    @Param("delta") delta: Int
)
```

**注意**：
- `@Modifying` 方法默认不自动 flush，需在调用后手动 `flush()` 或依赖事务提交
- JPQL 的 `CURRENT_TIMESTAMP` 是 Hibernate 函数，确保跨数据库兼容
- `delta` 使用带符号整数，同一个方法支持 `inviteMember(+N)`、`leaveGroup(-1)`、`kickMember(-1)`

### 2.4 DB 唯一约束 + DuplicateKeyException 幂等模式

**对应决策**：D-80 — 好友双向竞态修复

**现有状态分析**：

`friendships` 表当前约束：
```sql
UNIQUE KEY uk_friendship (user_id, friend_id)
```

但好友添加是双向的（A 加 B → 插入两条：`(A,B)` 和 `(B,A)`），当前约束只保证单向唯一。如果两个请求并发添加好友，可能出现：
- 请求 1：插入 `(A, B)`、`(B, A)`
- 请求 2：同时插入 `(A, B)`、`(B, A)`
- 其中一条因 UK 冲突失败

**D-80 修复方案**：

新增 Flyway migration V5：
```sql
-- V5__add_friendship_unique.sql
-- D-80: 好友双向唯一约束，防止并发重复添加
ALTER TABLE friendships 
    ADD UNIQUE KEY uk_friendship_pair (
        LEAST(user_id, friend_id), 
        GREATEST(user_id, friend_id)
    );
```

**Kotlin 幂等 catch 模式**：

```kotlin
// FriendService.addFriend() 事务内
transactionTemplate.execute {
    // ... 正常插入逻辑 ...
}.let { result ->
    result
} ?: run {
    // 事务回滚后，尝试幂等查询
    withContext(Dispatchers.IO) {
        friendshipRepository.findByUserIds(userId, friendId)
    }
}
```

**更优模式 — try-catch 在事务外层**：

```kotlin
suspend fun addFriend(userId: Long, friendId: Long): FriendshipEntity {
    return try {
        withContext(Dispatchers.IO) {
            transactionTemplate.execute {
                // 双向插入
                val uf = friendshipRepository.save(FriendshipEntity(userId = userId, friendId = friendId))
                val fu = friendshipRepository.save(FriendshipEntity(userId = friendId, friendId = userId))
                Pair(uf, fu)
            }
        }?.first!! // 事务成功，返回其中一条
    } catch (e: DataIntegrityViolationException) {
        // D-80: 唯一约束冲突 → 幂等返回
        logger.warn(e) { "好友关系已存在，幂等返回: ($userId, $friendId)" }
        withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(userId, friendId)
                ?: throw FriendException(BizCode.FRIEND_ALREADY_EXISTS)
        }
    }
}
```

**注意**：`DataIntegrityViolationException` 需要在事务提交时才会抛出（取决于 flush 时机）。如果使用 `save()` 的默认行为（延迟 flush），需要在事务内显式 `flush()` 才能立即捕获：

```kotlin
transactionTemplate.execute {
    friendshipRepository.saveAndFlush(entity) // 立即 flush 以触发 UK 检查
}
```

---

## 3. CQ-06 数据丢失修复（Plan 11-03）

### 3.1 DataIntegrityViolationException → 死信模式

**对应问题**：M11 — `DataIntegrityViolationException` 时批量 XACK 但无死信记录

**现有状态分析**：

```kotlin
// MessageRepositoryImpl.kt 现有代码（推测）
try {
    messageRepository.saveAll(messages)
} catch (e: DataIntegrityViolationException) {
    // 批量 XACK 移除消息（消息丢失）
    messageQueueRepository.xack(messages)
    // 缺少：创建死信记录
}
```

**修复方案 — Repository 层异常转死信**：

```kotlin
// MessageRepositoryImpl.kt — 修复后
try {
    entityManager.flush()  // 强制 flush 以立即触发 UK 检查
    messageRepository.saveAll(messages)
} catch (e: DataIntegrityViolationException) {
    logger.warn(e) { "消息落库唯一约束冲突，转死信: count=${messages.size}" }
    
    // 1. 为每条冲突消息创建死信记录
    messages.forEach { msg ->
        deadLetterService.create(
            conversationId = msg.conversationId,
            senderUid = msg.senderUid,
            messageType = msg.messageType,
            content = msg.content,
            payload = msg.payload,       // D-75: 保留 payload
            clientMsgId = msg.clientMessageId,
            clientTs = msg.clientTs,
            failReason = "DataIntegrityViolation: ${e.mostSpecificCause.message}"
        )
    }
    
    // 2. 从 Redis Stream 移除（避免重复消费）
    messageQueueRepository.xack(messages)
}
```

**关键设计点**：
- `entityManager.flush()` 确保唯一约束在 save 时被立即检测，而非事务提交时
- `e.mostSpecificCause` 获取最底层异常信息（如 `DuplicateKeyException`），便于排查
- payload 必须保留（当前为 null 或空串的问题见 3.2）

### 3.2 异步 Payload 序列化策略

**对应问题**：M12 — 消息异步路径 payload 序列化丢失；M22 — payload 在 Redis Stream 写入时为 `""`

**现有状态**：

```kotlin
// DeadLetterService.compensate() — payload 硬编码空串
val streamFields = mapOf(
    "payload" to ""  // ❌ payload 丢失
)

// DeadLetterService.retry() — 同样问题
val streamFields = mapOf(
    "payload" to ""  // ❌ payload 丢失
)
```

**修复策略**：

死信补偿和重试时，`DeadLetterEntity` 中的 `payload` 字段（BLOB）已保存原始消息的序列化数据。修复方案：

```kotlin
// DeadLetterService.compensate() — 修复后
val streamFields = mapOf(
    "msg_id" to (item.msgId?.toString() ?: ""),
    "conversation_id" to item.conversationId,
    "sender_uid" to item.senderUid.toString(),
    "message_type" to item.messageType.toString(),
    "content" to item.content,
    "client_message_id" to (item.clientMsgId ?: ""),
    "client_ts" to item.clientTs.toString(),
    "server_ts" to System.currentTimeMillis().toString(),
    // D-75: 从 DeadLetterEntity 恢复 payload
    "payload" to (item.payload?.let { Base64.getEncoder().encodeToString(it) } ?: "")
)
```

**Payload 序列化策略选型**：

| 方案 | 描述 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **A: Base64 编码** | `ByteArray → Base64 String → Redis → Base64 decode → ByteArray` | 无数据丢失、可逆、Redis 兼容 | 体积膨胀 ~33% | ⭐⭐⭐⭐⭐ |
| **B: 直接存字节** | `ByteArray → Redis binary` | 无膨胀 | Lettuce 的 `Map<String, String>` 不支持字节 | ⭐ |
| **C: JSON 序列化** | `ByteArray → JSON → Redis → JSON → ByteArray` | 人类可读 | Base64 更通用，ProtoBuf 本身已紧凑 | ⭐⭐⭐ |

**推荐方案 A**：Base64 编码。Redis Stream field 是 `String → String` 映射，Base64 是最可靠的字节 ↔ 字符串互转方式。

### 3.3 createDeadLetter 调用链的 payload 传递

**对应问题**：M09、M10 — compensate/retry payload 硬编码空串，createDeadLetter 保存了 payload 但补偿路径未使用

修复：死信补偿时从 `item.payload` 字段读取（3.2 已覆盖）。关键在于创建死信时必须传入正确的 payload：

```kotlin
// ChatService 创建死信时 — 确保 payload 正确传入
deadLetterService.create(
    conversationId = convId,
    senderUid = senderUid,
    messageType = messageType,
    content = content,
    payload = originalPayload,  // ← 必须从原始消息中获取
    clientMsgId = clientMsgId,
    clientTs = clientTs,
    failReason = "Delivery failed after max retries"
)
```

---

## 4. CQ-07 N+1 查询消除（Plan 11-03）

### 4.1 Spring Data JPA JOIN FETCH vs @EntityGraph

**对应决策**：D-83 — JOIN + 正确性+性能双重验证

**现有 N+1 问题**：

```kotlin
// ConversationService.inviteMember() — 每次循环查询一次 DB
for (uid in req.uidsList) {
    val existing = withContext(Dispatchers.IO) {
        conversationMemberRepository.findByConversationIdAndUserId(convId, uid)  // N 次查询
    }
}
```

**方案对比**：

| 方案 | 代码 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **A: JPQL IN 批量查询** | `findByConversationIdAndUserIds(convId, userIds)` | 简单直接、无关联映射 | 需要手动写 JPQL | ⭐⭐⭐⭐⭐ |
| **B: JOIN FETCH** | `@Query("SELECT c FROM ConversationEntity c JOIN FETCH c.members WHERE c.id = :id")` | 一次查询加载实体 + 关联集合 | 需要实体关联映射（当前未设置）、可能产生笛卡尔积 | ⭐⭐⭐ |
| **C: @EntityGraph** | `@EntityGraph(attributePaths = ["members"])` | 声明式、复用 JPA 关联 | 需要在实体层配置关联关系 | ⭐⭐⭐ |

**推荐方案 A**：批量查询（IN 子句）。

**理由**：
1. 当前 `ConversationMemberEntity` 只有 `conversationId` 和 `userId` 字段，没有 `@ManyToOne` 关联到 `ConversationEntity`，使用 JOIN FETCH 需要先配置实体关联
2. `findByConversationIdAndUserIds` 方法已存在于 `ConversationMemberRepository`（行 95-102），可以直接使用
3. 批量查询将 N+1 变为 2 次查询（1 次查会话 + 1 次批量查成员）

**修复代码**：

```kotlin
// ConversationService.inviteMember() 修复后
suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long> {
    val convId = req.conversationId

    return withContext(Dispatchers.IO) {
        transactionTemplate.execute {
            val conv = conversationRepository.findById(convId).orElse(null)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 验证操作者是群主（保留单次查询）
            val operatorMember = conversationMemberRepository
                .findByConversationIdAndUserId(convId, operatorUid)
            if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可邀请成员")
            }

            // CQ-07 修复：批量查询替代 N+1
            val existingMap = conversationMemberRepository
                .findByConversationIdAndUserIds(convId, req.uidsList)
                .associateBy { it.userId }

            // ... 后续处理使用 existingMap 替代逐个查询 ...
        }
    }
}
```

### 4.2 正确性 + 性能双重验证策略

**对应决策**：D-83 要求

**验证清单**：

1. **单元测试**：修复前后相同输入的输出一致性
   ```kotlin
   @Test
   fun inviteMemberBatchQueryMatchesIndividual() = runTest {
       val batchResult = service.inviteMember(req, userId)  // 修复后
       val singleResult = legacyInviteMember(req, userId)   // 修复前（方法保留引用）
       assertEquals(singleResult, batchResult)
   }
   ```

2. **性能验证**：通过 Hibernate 统计或日志记录查询次数
   ```kotlin
   // 测试中启用 Hibernate 统计
   val statistics = entityManager.entityManagerFactory
       .unwrap(SessionFactory::class.java).statistics
   statistics.clear()
   // 执行操作
   val count = statistics.queryExecutionCount
   // 断言 count == 2（1 次会话 + 1 次批量成员）
   ```

---

## 5. CQ-08/13 业务逻辑修复（Plan 11-03）

### 5.1 DeadLetterService 分页 total 计数

**对应问题**：M15 — `query()` 按状态过滤时 `total` 取自未过滤的 `findAll()`

**现有问题代码**：

```kotlin
suspend fun query(page: Int, pageSize: Int, status: String?): Page<DeadLetterEntity> {
    val pageable = PageRequest.of(page - 1, pageSize)
    return withContext(Dispatchers.IO) {
        if (status.isNullOrBlank()) {
            deadLetterRepository.findAll(pageable)
        } else {
            deadLetterRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
                .let { items ->
                    val total = deadLetterRepository.findAll(pageable).totalElements  // ❌ 使用未过滤的 total
                    PageImpl(items, pageable, total)
                }
        }
    }
}
```

Bug 分析：
- 当 `status` 不为空时，`items` 是按 `status` 过滤后的数据
- 但 `total` 来自 `findAll(pageable).totalElements`，是**全表**计数
- 导致前端分页显示总页数错误（如过滤后只有 3 条，但 total 显示 1000 条）

**修复方案**：

在 DeadLetterRepository 中新增按状态计数的查询：

```kotlin
// DeadLetterRepository.kt
/**
 * 按状态统计死信记录总数。
 *
 * @param status 死信状态（pending/retrying/permanent_failed/retry_success）
 * @return 该状态下的死信记录总数
 */
@Query("SELECT COUNT(d) FROM DeadLetterEntity d WHERE d.status = :status")
fun countByStatus(@Param("status") status: String): Long
```

修复后的 Service 代码：

```kotlin
suspend fun query(page: Int, pageSize: Int, status: String?): Page<DeadLetterEntity> {
    val pageable = PageRequest.of(page - 1, pageSize)
    return withContext(Dispatchers.IO) {
        if (status.isNullOrBlank()) {
            deadLetterRepository.findAll(pageable)
        } else {
            val items = deadLetterRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
            val total = deadLetterRepository.countByStatus(status)  // ✅ 按过滤条件计数
            PageImpl(items, pageable, total)
        }
    }
}
```

### 5.2 clientMessageId 去重下沉

**对应问题**：M20、M21 — `checkAndSetDedup()` 声明但未调用，`DedupStep` 退化为 no-op

**现有状态分析**：

1. `MessageQueueRepository.checkAndSetDedup()` 使用 Redis `SET NX` 实现幂等检查
2. `DedupStep` 是死代码（属于 SendMessageStep 链 7 文件之一，CQ-01 待清理）
3. 消息发送路径中，clientMessageId 去重逻辑没有实际执行

**最佳位置分析**：

| 位置 | 优点 | 缺点 | 推荐 |
|------|------|------|------|
| **A: Handler 层**（SendMessageHandler） | 最早拦截、减少无效处理 | 增加 Handler 复杂度 | ⭐⭐⭐ |
| **B: Service 层**（MessageService.sendMessage） | 业务逻辑集中、可复用 | 仍需下行到 Redis | ⭐⭐⭐⭐ |
| **C: Repository 层**（MessageQueueRepository） | 最接近数据源、天然原子性 | 调用链较长 | ⭐⭐⭐⭐⭐ |

**推荐方案 C：Repository 层原子去重**。

在 `MessageService` 或 `SendMessageHandler` 的消息发送入口调用 `checkAndSetDedup()`：

```kotlin
// SendMessageHandler.handle() 中
override suspend fun handle(req: SendMessageReq): Response {
    // Step 1: clientMessageId 去重（CQ-13）
    if (req.hasClientMessageId()) {
        val isDuplicate = messageQueueRepository.checkAndSetDedup(
            req.clientMessageId, 
            session.userId
        )
        if (!isDuplicate) {
            // 重复消息，幂等返回
            return buildDuplicateResponse(req.clientMessageId)
        }
    }
    
    // Step 2-N: 正常发送流程
    ...
}
```

**MySQL UNIQUE 约束增强**（防御底层）：

```sql
-- V5 migration: dead_letters client_msg_id 改为 UNIQUE
ALTER TABLE dead_letters 
    DROP INDEX idx_client_msg_id,
    ADD UNIQUE KEY uk_dead_letter_client_msg (client_msg_id);
```

### 5.3 markPermanentFailed 死查询修复

**对应问题**：M16 — `findByStatusAndFailCountLessThan(STATUS_RETRYING, 0, ...)` 永远返回空

Bug 分析：
```kotlin
// failCount < 0 条件永远为 false（failCount 从 0 开始，只增不减）
deadLetterRepository.findByStatusAndFailCountLessThan(
    STATUS_RETRYING, 0, Pageable.ofSize(BATCH_SIZE)
)
```

**修复方案**：

```kotlin
// DeadLetterRepository.kt — 正确的查询方法
/**
 * 查找需标记为永久失败的死信记录（D-75）。
 *
 * @param status 死信状态
 * @param minFailCount 最小失败次数阈值（>= 此值的记录将被标记为永久失败）
 * @param pageable 分页参数
 * @return 符合条件的死信记录列表
 */
@Query("""
    SELECT d FROM DeadLetterEntity d 
    WHERE d.status = :status AND d.failCount >= :minFailCount
""")
fun findByStatusAndFailCountGreaterThanEqual(
    @Param("status") status: String,
    @Param("minFailCount") minFailCount: Int,
    pageable: Pageable
): List<DeadLetterEntity>
```

Service 修复：

```kotlin
suspend fun markPermanentFailed() {
    val toMarkFailed = withContext(Dispatchers.IO) {
        deadLetterRepository.findByStatusAndFailCountGreaterThanEqual(
            STATUS_RETRYING, 
            MAX_COMPENSATE_RETRIES, 
            Pageable.ofSize(BATCH_SIZE)
        )
    }
    // ... 标记逻辑不变 ...
}
```

---

## 6. CQ-09 启动健壮性（Plan 11-01）

### 6.1 Init 链回滚模式

**对应问题**：H08 — `NebulaServer.kt:68` init 链无 try-catch 回滚

**现有状态**：

```kotlin
// 当前代码 — 无异常处理
initializers.topologicalSort().forEach { it.init() }
```

如果第 3 个初始化器抛异常，前 2 个已分配的资源（如连接池、线程池）不会被释放。

**回滚模式**：

```kotlin
// NebulaServer.main() — 修复后
try {
    initializers.topologicalSort().forEach { it.init() }
} catch (e: Exception) {
    logger.error(e) { "Module initialization failed, rolling back..." }
    // 逆序执行已初始化模块的 shutdown
    val initialized = initializers.filter { it.isInitialized }
    initialized.reversed().forEach { module ->
        try {
            module.shutdown()
        } catch (shutdownEx: Exception) {
            logger.error(shutdownEx) { "Failed to shutdown ${module.name}" }
        }
    }
    throw IllegalStateException("Server startup failed, rolled back", e)
}
```

**要求**：`ModuleInitializer` 接口需新增 `isInitialized` 属性和 `shutdown()` 方法。

### 6.2 ConfigLoader 配置校验增强

**对应问题**：H09 — 无配置范围校验 + 无 File.exists() 检查

**SSL 证书预校验**（H07）：

```kotlin
// SslConfig.buildSslContext() — 修复后
fun SslConfig.buildSslContext(): SslContext? {
    if (!enabled) return null
    
    val certChain = File(certChainPath)
    val privateKey = File(privateKeyPath)
    
    // CQ-09: SSL 证书文件存在性预校验
    require(certChain.exists()) { 
        "SSL certificate chain file not found: ${certChain.absolutePath}" 
    }
    require(privateKey.exists()) { 
        "SSL private key file not found: ${privateKey.absolutePath}" 
    }
    require(certChain.canRead()) { 
        "SSL certificate chain file not readable: ${certChain.absolutePath}" 
    }
    require(privateKey.canRead()) { 
        "SSL private key file not readable: ${privateKey.absolutePath}" 
    }
    
    return GrpcSslContexts.forServer(certChain, privateKey)
        .sslProvider(SslProvider.OPENSSL)
        .protocols("TLSv1.2", "TLSv1.3")
        .ciphers(listOf(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
        ))
        .build()
}
```

**配置范围校验**（ConfigLoader）：

```kotlin
// ConfigLoader.parseConfig() 增加校验
private fun validateConfig(config: ApplicationConfig) {
    // 端口范围校验
    require(config.server.port in 1024..65535) {
        "Server port must be in range 1024-65535, got ${config.server.port}"
    }
    
    // 连接池大小校验
    require(config.database.poolSize in 1..100) {
        "Database pool size must be in range 1-100, got ${config.database.poolSize}"
    }
    
    // Redis 端口校验
    require(config.redis.port in 1..65535) {
        "Redis port must be in range 1-65535, got ${config.redis.port}"
    }
    
    // 配置文件存在性已由 ConfigLoader.load() 中的 parseFile 隐式校验
}
```

---

## 7. CQ-10 日志与可观测性（Plan 11-04）

### 7.1 println → kotlin-logging (SLF4J)

**对应问题**：H11 — `ChatServer.kt:75` 使用 `println()` 替代结构化日志

**修复**：将 `println` 替换为 `kotlin-logging`：

```kotlin
// ChatServer.kt — 修复后
class ChatServer(private val config: ApplicationConfig) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    fun start(chatService: ChatService) {
        // ...
        server = builder.build().start()
        logger.info { "gRPC server started on port ${config.server.port}" +
                if (config.ssl.enabled) " (SSL enabled)" else "" }
    }
}
```

**项目中 `println` 使用现状**：搜索确认 `ChatServer.kt:75` 是唯一的 `println()` 调用点。其他模块已统一使用 kotlin-logging。

### 7.2 登录审计日志

**对应问题**：H10 — 无登录审计日志（成功/失败）

**审计日志格式设计**：

```
[AUDIT] user_login | uid=1001 | ip=192.168.1.10 | device=mobile | success=true | ts=2026-06-15T10:30:00Z
[AUDIT] user_login | uid=1001 | ip=192.168.1.10 | device=mobile | success=false | reason=invalid_password | ts=2026-06-15T10:30:05Z
```

**存储策略**：

| 方案 | 描述 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **A: SLF4J + Markers** | 使用 SLF4J Marker `AUDIT` 标记审计日志，logback 配置独立 appender 写入 `audit.log` | 不引入新库、配置灵活 | 依赖日志框架配置 | ⭐⭐⭐⭐⭐ |
| **B: 数据库表** | 创建 `audit_logs` 表记录 | 可查询、持久化 | 写入延迟、增加 DB 负载 | ⭐⭐⭐ |
| **C: 文件追加** | 直接写文件 | 简单 | 缺乏结构化、难以检索 | ⭐⭐ |

**推荐方案 A**：SLF4J Marker + 独立 audit.log 文件。

实现步骤：

1. **定义 Audit Marker**：
   ```kotlin
   // common 层
   object AuditMarkers {
       val LOGIN = MarkerFactory.getMarker("AUDIT_LOGIN")
   }
   ```

2. **LoginHandler 增加审计日志**：
   ```kotlin
   override suspend fun handle(req: LoginReq): LoginResp {
       val clientIp = currentCoroutineContext()[ClientIpKey]?.ip ?: "unknown"
       
       // 场景 1: Token 重连
       if (req.hasToken()) {
           val existingSession = sessionRegistry.validate(req.token)
           if (existingSession != null) {
               logger.info(AuditMarkers.LOGIN) { 
                   "user_login | uid=${existingSession.userId} | ip=$clientIp | " +
                   "device=${req.deviceType} | method=token | success=true" 
               }
               return buildLoginResp(existingSession.userId, existingSession.token, req)
           }
       }
       
       // 场景 2: 密码登录
       try {
           val userId = userService.loginByPassword(req)
           val token = UUID.randomUUID().toString()
           logger.info(AuditMarkers.LOGIN) { 
               "user_login | uid=$userId | ip=$clientIp | " +
               "device=${req.deviceType} | method=password | success=true" 
           }
           return buildLoginResp(userId, token, req)
       } catch (e: UserException) {
           logger.warn(AuditMarkers.LOGIN) { 
               "user_login | username=${req.username} | ip=$clientIp | " +
               "device=${req.deviceType} | method=password | success=false | " +
               "reason=${e.message}" 
           }
           throw e
       }
   }
   ```

3. **logback 配置**：
   ```xml
   <!-- logback-prod.xml -->
   <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <file>logs/audit.log</file>
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <fileNamePattern>logs/audit.%d{yyyy-MM-dd}.log</fileNamePattern>
           <maxHistory>90</maxHistory>
       </rollingPolicy>
       <encoder>
           <pattern>%d{ISO8601} %msg%n</pattern>
       </encoder>
   </appender>
   
   <!-- Audit Marker 独立过滤 -->
   <appender name="AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
           <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
               <marker>AUDIT_LOGIN</marker>
           </evaluator>
           <onMatch>ACCEPT</onMatch>
           <onMismatch>DENY</onMismatch>
       </filter>
       <!-- ... -->
   </appender>
   ```

---

## 8. CQ-11 协程与线程安全（Plan 11-03）

### 8.1 withContext(Dispatchers.IO) 包裹 JPA 操作

**对应决策**：D-84

**问题定位**：PushService 中阻塞 JPA 调用未包裹 `withContext(Dispatchers.IO)`

```kotlin
// PushService.pushMessage() — 当前代码
suspend fun pushMessage(convId: String, chatMessage: ChatMessage, excludeUid: Long) {
    val members = conversationMemberRepository.findByConversationId(convId)  // ❌ 阻塞 IO 在协程线程
    // ...
}

// PushService.pushConversationEvent() — 同样的问题
suspend fun pushConversationEvent(...) {
    val members = conversationMemberRepository.findByConversationId(convId)  // ❌ 阻塞 IO
    // ...
}
```

**修复**：

```kotlin
suspend fun pushMessage(convId: String, chatMessage: ChatMessage, excludeUid: Long) {
    val members = withContext(Dispatchers.IO) {
        conversationMemberRepository.findByConversationId(convId)
    }
    // ... 内存操作 + observer.onNext() 保持原线程 ...
}
```

**判断是否需要 `withContext(Dispatchers.IO)` 的规则**：
- **需要 IO 调度器**：JPA CRUD、Redis 网络 I/O、JDBC 操作
- **不需要**：纯内存计算、`observer.onNext()`（Netty 事件循环线程）、简单字段访问
- **已正确使用**：`ConversationService` 中 95% 的 JPA 调用已包裹，PushService 是遗漏

### 8.2 @Volatile 缺失

**对应问题**：H12 — `ChatServer.kt:28` `private var server: Server? = null` 缺少 `@Volatile`

**问题分析**：

```kotlin
class ChatServer(...) {
    private var server: Server? = null  // ❌ 无 @Volatile
}
```

`server` 字段由启动线程写入（`start()`），由 Shutdown Hook 线程读取（`stop()`），根据 Java Memory Model，跨线程必须使用 `@Volatile` 或 `synchronized`。

**修复**：

```kotlin
@Volatile
private var server: Server? = null
```

### 8.3 CoroutineScope 生命周期管理

**对应决策**：D-85 — ApplicationScope + SupervisorJob()

**问题分析**：

1. **M19**：`MessageRepositoryImpl.kt:116` 独立 `CoroutineScope` 创建但 `stop()` 不 cancel
2. **M30**：`SendMessageHandler.kt:73` `launch{}` 非结构化协程 — fire-and-forget

**修复方案**：

1. **非结构化 launch → 托管 CoroutineScope**：

```kotlin
// SendMessageHandler — 使用 sendHandlerScope（ChatHandlerModule 已注册）
class SendMessageHandler(
    private val messageService: MessageService,
    private val pushService: PushService,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val messageQueueRepository: MessageQueueRepository,
    private val sendHandlerScope: CoroutineScope  // ← 注入托管 scope
) : Handler<SendMessageReq, Response> {
    
    override suspend fun handle(req: SendMessageReq): Response {
        // ...
        // 异步推送和未读计数
        sendHandlerScope.launch {
            try {
                pushService.pushMessage(convId, chatMessage, session.userId)
                conversationMemberRepository.incrementUnreadCount(convId, session.userId)
            } catch (e: Exception) {
                logger.error(e) { "Async push/unread failed" }
            }
        }
        // ...
    }
}
```

2. **MessageRepositoryImpl scope 管理**：

```kotlin
class MessageRepositoryImpl(...) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun stop() {
        scope.cancel()  // ✅ 取消所有子协程
    }
}
```

### 8.4 RateLimiter 逐出策略

**对应问题**：H13 — `RateLimitInterceptor.kt:36` `userSemaphores` 无逐出机制导致缓慢泄漏

**问题分析**：

`ConcurrentHashMap<String, Semaphore>` 中用户的信号量对象一旦创建就永不删除。对于一次性用户（注册后不活跃）或离职用户，Semaphore 永久占用内存。

**修复方案对比**：

| 方案 | 描述 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **A: LRU 过期** | 每次 `get` 前检查 `lastAccessTime`，超过 TTL 移除 | 精确控制内存 | 需要维护额外时间戳 | ⭐⭐⭐⭐ |
| **B: 定时清理** | 独立协程定期扫描并移除未使用的条目 | 简单 | 定时粒度粗、可能误删 | ⭐⭐⭐ |
| **C: Caffeine Cache** | 使用 Guava/Caffeine 缓存替代 ConcurrentHashMap | 内置过期策略 | 引入新依赖 | ⭐⭐⭐⭐⭐ |
| **D: WeakReference** | 使用 `WeakHashMap` | JDK 原生 | Semaphore 可能在仍使用时被 GC | ⭐ |

**推荐方案 C（长期）** 或 **方案 B（短期最小改动）**：

**短期修复（方案 B — 最小改动）**：

```kotlin
// RateLimitInterceptor — 增加定时清理
companion object {
    private const val CLEANUP_INTERVAL_MS = 600_000L  // 10 分钟
}

// 在 RateLimitInterceptor 初始化时启动清理协程
init {
    cleanupScope.launch {
        while (isActive) {
            delay(CLEANUP_INTERVAL_MS)
            val snapshot = userSemaphores.keys().toList()
            snapshot.forEach { key ->
                val sem = userSemaphores[key]
                if (sem != null && sem.availablePermits() == permitsPerUser) {
                    // 所有许可都可用 → 用户已长时间无请求，移除
                    userSemaphores.remove(key, sem)
                }
            }
        }
    }
}
```

**长期优化（方案 C — Caffeine）**：

```kotlin
private val userSemaphores: Cache<String, Semaphore> = Caffeine.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .removalListener { key, value, cause ->
        log.debug { "Rate limiter semaphore removed: key=$key, cause=$cause" }
    }
    .build()
```

---

## 9. CQ-14/15 代码质量与测试加固（Plan 11-04）

### 9.1 !! 空断言替换策略

**对应决策**：D-86 — 按上下文选择

**项目中 `!!` 使用情况**：

| 位置 | 代码 | 场景 | 推荐替换 |
|------|------|------|------|
| `MessageService.kt:252` | `id!!` | 方法内部，预期非空 | `?: error("msg id must not be null")` |
| `UserService.kt:92,120,157,183,212` | `user.id!!` | Service 层内部属性 | `?: error("user id must not be null")` |
| `ConversationService.kt:151,164` | `entity.id!!` | 列表映射 | 过滤 `filterNotNull()` + 日志警告 |
| `ChatService.kt:253,312` | `merge().!!` | NPE 高风险 | `requireNotNull(mergeResult)` |
| `JpaConfig.kt:47` | `emfBean.getObject()!!` | 初始化阶段 | `?: error("EntityManagerFactory not available")` |

**替换规则速查表**（D-86 细化）：

```kotlin
// 1. 参数校验 → requireNotNull
fun process(user: User?) {
    val u = requireNotNull(user) { "user must not be null" }
}

// 2. 内部逻辑预期非空 → ?: error("message")
val id = entity.id ?: error("Entity id must not be null at this point")

// 3. 可选/降级 → ?: return 或 ?: throw BizException
val member = memberMap[id] ?: return  // 不是一个有效成员，跳过
val conv = conversationRepository.findById(id).orElse(null) 
    ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

// 4. 列表过滤 → filterNotNull
val convIds = result.map { it.id }.filterNotNull()
```

### 9.2 DRY 重构 — 提取公共扩展函数

**对应问题**：L13、L14、L15 — 重复代码模式

**(1) `isActive()` 扩展属性**（L13）：

```kotlin
// ConversationMemberEntity.kt — 扩展属性
/** 成员是否为活跃状态（未软删除）（D-86）。 */
val ConversationMemberEntity.isActive: Boolean get() = deleted == 0

// 使用
val activeMembers = members.filter { it.isActive }
member.isActive  // 替代 member.deleted == 0
```

**(2) `findByIdOrNull()` 扩展函数**（L14）：

```kotlin
// ConversationRepository 扩展（或在 ConversationService 内部定义）
/**
 * 按 ID 查找会话，不存在时返回 null（D-86）。
 *
 * 封装 withContext(Dispatchers.IO) + repository.findById().orElse(null) 的重复模式。
 */
suspend fun ConversationRepository.findByIdOrNull(id: String): ConversationEntity? {
    return withContext(Dispatchers.IO) {
        findById(id).orElse(null)
    }
}

// 使用
val conv = conversationRepository.findByIdOrNull(convId)
    ?: throw ConversationException(BizCode.CONV_NOT_FOUND)
```

**(3) `isActive()` 属性 for Friendship**（L15）：

```kotlin
// FriendshipEntity.kt — 扩展属性
/** 好友关系是否有效（未软删除）（D-86）。 */
val FriendshipEntity.isActive: Boolean get() = deleted == 0
```

### 9.3 魔法数字 → 枚举

**对应问题**：L02-L06 — Int 魔法数字

**ConversationType 枚举**（L02, L20）：

```kotlin
// common 层
/**
 * 会话类型枚举（D-87, CQ-12）。
 *
 * 统一 conversations.type 字段的值定义，消除 Entity 注释与 SQL COMMENT 的矛盾。
 * - SQL COMMENT: 1=私聊, 2=群聊 → 以 SQL DDL 为准
 * - Entity 注释原有 0=私聊 是错误值，修复为与 SQL 一致
 */
enum class ConversationType(val code: Int) {
    /** 私聊会话 */
    PRIVATE(1),
    /** 群聊会话 */
    GROUP(2);

    companion object {
        fun fromCode(code: Int): ConversationType = entries.first { it.code == code }
    }
}
```

**FriendRequestStatus 枚举**（L04）：

```kotlin
enum class FriendRequestStatus(val code: Int) {
    PENDING(0),
    ACCEPTED(1),
    REJECTED(2);
}
```

### 9.4 异常类型精简

**对应问题**：L19 — 同一 Service 分裂使用两个异常类

`ChatException` 和 `MessageException` 服务于同一个模块，合并为统一的 `ChatException` 或保持按命名空间区分。建议：

```kotlin
// 按模块统一异常类
class ChatException(code: BizCode, message: String? = null) : 
    BizException(code.code, message ?: code.defaultMessage)
class UserException(code: BizCode, message: String? = null) : 
    BizException(code.code, message ?: code.defaultMessage)
class ConversationException(code: BizCode, message: String? = null) : 
    BizException(code.code, message ?: code.defaultMessage)
```

如果 `MessageException` 仅用于消息发送失败场景，合并到 `ChatException`。

### 9.5 测试改进要点

**对应问题**：L07、T01-T07

| 问题 | 改进方案 |
|------|------|
| **T01/L07**: 反射操作 SnowflakeIdGenerator private 字段 | 抽取 `Clock` 接口（`fun millis(): Long`），默认 `SystemClock`，测试用 `FakeClock` |
| **T02**: Step 链死代码测试清理 | 随 CQ-01 死代码删除同步移除 |
| **T03**: 好友双向竞赛测试 | 使用 `runBlocking` + 多个 `launch` 并发调用 `addFriend`，验证只插入一对记录 |
| **T04**: memberCount 并发更新测试 | 并发 invite + leave，验证 `conversation.member_count` 等于 `SELECT COUNT(*) WHERE deleted=0` |
| **T05**: payload 补偿路径测试 | 创建带 payload 的死信 → 调 `compensate()` → 验证 enqueue 的 fields["payload"] 非空 |
| **T06**: SeqService 重启恢复测试 | 写入消息 → 清 Redis → 调恢复逻辑 → 验证序列号从 `MAX(seq)+1` 恢复 |
| **T07**: 审计日志验证 | Mock Appender → 调 LoginHandler → 验证包含 `AUDIT_LOGIN` Marker 的日志事件 |

---

## 10. 实现优先级与依赖

### Wave 1 (P0 HIGH)：安全加固 + 数据一致性/竞态

| 顺序 | 任务 | 前置依赖 | 覆盖 CQ | 研究结论 |
|------|------|------|------|------|
| 1 | DatabaseConfig 密码移除默认值 + ConfigLoader 校验增强 | 无 | CQ-02, CQ-09 | 移除明文密码、配置范围校验 |
| 2 | RedisConfig 改造（密码 + TLS） | 无 | CQ-02 | RedisURI Builder + withSsl + withPassword |
| 3 | MySQL sslMode 切换 | 无 | CQ-02 | PREFERRED → VERIFY_CA |
| 4 | JVM Shutdown Hook | 2-3 完成后 | CQ-05 | addShutdownHook + 清理链 |
| 5 | TransactionTemplate 事务包裹 | 4 完成后 | CQ-03 | withContext(IO) 包裹整个事务 |
| 6 | DB 唯一约束 + DuplicateKeyException 幂等 | 5 完成后 | CQ-04 | D-80 方案 |
| 7 | SeqService 启动恢复 | 无 | CQ-04 | D-81 方案 |
| 8 | memberCount JPQL 原子更新 | 5 完成后 | CQ-04 | D-82 方案 |

### Wave 2 (P1 MEDIUM)：数据完整性与错误处理

| 顺序 | 任务 | 前置依赖 | 覆盖 CQ | 研究结论 |
|------|------|------|------|------|
| 9 | 死代码清理（Step 链 7 文件） | Wave 1 完成 | CQ-01 | 删除文件 + 移除 Koin 注册 |
| 10 | DataIntegrityViolationException → 死信 | 9 完成后 | CQ-06 | Repository 层转死信 |
| 11 | Payload 序列化修复 | 10 完成后 | CQ-06 | Base64 编码策略 |
| 12 | N+1 查询消除 | 5 完成后 | CQ-07 | 批量查询 findByConversationIdAndUserIds |
| 13 | DeadLetter 分页 total 修复 | 9 完成后 | CQ-08 | countByStatus 方法 |
| 14 | clientMessageId 去重下沉 | 9 完成后 | CQ-13 | Repository 层 checkAndSetDedup |
| 15 | withContext(IO) 包裹 PushService | 5 完成后 | CQ-11 | D-84 方案 |
| 16 | CoroutineScope 生命周期管理 | 9 完成后 | CQ-11 | D-85 方案 |

### Wave 3 (P2 LOW)：代码质量与测试加固

| 顺序 | 任务 | 前置依赖 | 覆盖 CQ | 研究结论 |
|------|------|------|------|------|
| 17 | println → kotlin-logging | Wave 2 完成 | CQ-10 | 仅 1 处修改 |
| 18 | 登录审计日志 | 17 完成后 | CQ-10 | SLF4J Marker + audit.log |
| 19 | 魔法数字 → 枚举 | Wave 2 完成 | CQ-12 | ConversationType 等枚举 |
| 20 | !! 空断言替换 | Wave 2 完成 | CQ-15 | D-86 按场景选择策略 |
| 21 | DRY 扩展函数提取 | 20 完成后 | CQ-15 | isActive/idOrNull 等 |
| 22 | @Volatile 修复 | Wave 2 完成 | CQ-11 | 1 行修改 |
| 23 | RateLimiter 逐出策略 | Wave 2 完成 | CQ-11 | 定时清理（短期）/ Caffeine（长期） |
| 24 | Clock 接口抽取 | Wave 2 完成 | CQ-14 | SystemClock + FakeClock |
| 25 | 测试补充（并发/补偿/审计） | 1-24 全部完成后 | CQ-14 | D-88 测试审查点 |

---

## 11. 关键技术风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|------|
| **密码移除默认值导致开发环境启动失败** | 开发效率 | 项目 README 和 docker-compose 中明确标注必需的环境变量；提供 `.env.example` 模板 |
| **sslMode=VERIFY_CA 需要 CA 证书** | 生产部署复杂度 | 文档说明如何导入 CA 证书或配置 truststore；内网环境可用 `REQUIRED` 降级 |
| **TransactionTemplate 包裹后异常回滚语义变化** | 数据一致性 | 充分测试事务回滚路径，确保补偿逻辑正确触发 |
| **批量查询结果与逐个查询不一致** | 逻辑回归 | D-83 双重验证：单元测试验证语义一致性 + 性能对比 |
| **CoroutineScope cancel 后 fire-and-forget 任务丢失** | 消息推送丢失 | 关键的 fire-and-forget 操作在 cancel 前完成或记录补偿 |

---

## 12. 参考资源

- [Typesafe Config — HOCON 规范](https://github.com/lightbend/config/blob/main/HOCON.md)
- [Lettuce SSL Connections](https://redis.github.io/lettuce/advanced-usage/ssl-connections/)
- [Lettuce Connecting Redis — Password Authentication](https://redis.github.io/lettuce/user-guide/connecting-redis/)
- [MySQL Connector/J — Using SSL](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-using-ssl.html)
- [Baeldung — JVM Shutdown Hooks](https://www.baeldung.com/jvm-shutdown-hooks)
- [Spring — Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [Spring Data JPA — @Modifying Annotation](https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/Modifying.html)
- [Kotlin — Coroutine Dispatchers.IO](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html)
- [kotlin-logging GitHub](https://github.com/oshai/kotlin-logging)
- [Caffeine Cache](https://github.com/ben-manes/caffeine)
- 现有项目代码路径：
  - `server/src/main/kotlin/com/nebula/server/NebulaServer.kt` — 启动入口
  - `server/src/main/kotlin/com/nebula/server/server/ChatServer.kt` — gRPC 服务管理
  - `server/src/main/kotlin/com/nebula/server/config/ConfigLoader.kt` — 配置加载
  - `common/src/main/kotlin/com/nebula/common/datasource/HikariDataSourceProvider.kt` — 数据库连接池
  - `repository/src/main/kotlin/com/nebula/repository/config/RedisConfig.kt` — Redis 连接配置
  - `service/src/main/kotlin/com/nebula/service/conversation/ConversationService.kt` — 会话服务
  - `service/src/main/kotlin/com/nebula/service/admin/DeadLetterService.kt` — 死信服务
  - `gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt` — 推送服务
  - `gateway/src/main/kotlin/com/nebula/gateway/handler/user/LoginHandler.kt` — 登录处理
  - `gateway/src/main/kotlin/com/nebula/gateway/interceptor/RateLimitInterceptor.kt` — 限流拦截器
  - `gateway/src/main/kotlin/com/nebula/gateway/di/ChatHandlerModule.kt` — 协程作用域定义
  - `gateway/src/main/kotlin/com/nebula/gateway/admin/DeadLetterCompensator.kt` — 死信补偿调度

---

## RESEARCH COMPLETE
