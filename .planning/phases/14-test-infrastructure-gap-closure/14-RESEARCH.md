---
phase: 14
researcher: nx-researcher-1
---
# Phase 14 技术研究

## 研究范围

1. **Testcontainers Redis 集成方案** — 为 service 模块提供嵌入式 Redis 测试基础设施
2. **GC5 Token 重连 deviceId 验证** — Session 存储 deviceId 字段分析 + 验证方案
3. **S8 server/build.gradle.kts 冗余依赖清理** — 根据 Phase 12 重构后实际编译依赖分析
4. **S6/S16/S23 补充测试** — 延期测试（T04/T05/T06）实现路径

## 技术栈上下文

- **语言**: Kotlin 2.1.20
- **构建**: Gradle Kotlin DSL，6 子模块（proto ← common ← repository ← service ← gateway ← server）
- **测试框架**: JUnit 5.11.4 + MockK 1.13.14 + kotlinx.coroutines.test
- **现有 Testcontainers**: 1.20.6，已在 repository 模块使用 MySQLContainer
- **Redis 客户端**: Lettuce 6.5.5（coroutines 模式）
- **序列化**: kotlinx.serialization 1.8.1（Session JSON 序列化）

## 1. Testcontainers Redis 集成方案

### 现有 Testcontainers 模式分析

项目在 `repository` 模块已有成熟的 Testcontainers MySQL 集成（D-02, D-03, D-06）：

**基类**: `repository/src/test/kotlin/com/nebula/repository/testutil/DatabaseTestBase.kt`
- 使用 `@Testcontainers` + `@Container` 注解管理 MySQLContainer 生命周期
- 使用 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 确保容器在类级别共享
- 使用 `companion object` 的 `@JvmStatic @BeforeAll/@AfterAll` 管理 DataSource 生命周期
- Flyway 自动执行迁移

**依赖**（已在 `gradle/libs.versions.toml` 中声明）:
```toml
testcontainers = "1.20.6"
testcontainers-core = { module = "org.testcontainers:testcontainers", version.ref = "testcontainers" }
testcontainers-mysql = { module = "org.testcontainers:mysql", version.ref = "testcontainers" }
testcontainers-junit-jupiter = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
```

### 方案对比

| 维度 | 方案 A: GenericContainer | 方案 B: Community RedisContainer |
|------|--------------------------|----------------------------------|
| 额外依赖 | 无（已有 `testcontainers-core`） | 需新增 `com.redis:testcontainers-redis:2.2.2` |
| 类名 | `GenericContainer<Nothing>("redis:7-alpine")` | `RedisContainer(DockerImageName.parse("redis:6.2.6"))` |
| API 简洁度 | 需手动指定暴露端口 | 内置 Redis 默认端口 |
| 维护方 | Testcontainers 官方（Generic） | Redis 社区 |
| Redis 版本控制 | 完全可控 | 需匹配模块默认版本 |
| 与 MySQL 模式一致性 | 一致（MySQLContainer 也继承 GenericContainer） | 不同（不同类） |

**推荐方案: 方案 A — GenericContainer**

原因:
1. **零额外依赖** — `testcontainers-core` 和 `testcontainers-junit-jupiter` 已在版本目录中
2. **与 MySQL 模式一致** — MySQLContainer 同样是基于 GenericContainer 的封装
3. **版本灵活** — 可直接指定 `redis:7-alpine`，无需匹配模块版本
4. **Redis 配置简单** — 只需暴露 6379 端口，无需其他特殊配置

### 实现架构

#### 基类设计: `RedisTestBase`（新建，位于 `service` 模块）

```
service/src/test/kotlin/com/nebula/service/testutil/RedisTestBase.kt
```

```kotlin
package com.nebula.service.testutil

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RedisTestBase {

    companion object {
        @Container
        private val redis: GenericContainer<Nothing> = GenericContainer<Nothing>("redis:7-alpine")
            .withExposedPorts(6379)

        private lateinit var connection: StatefulRedisConnection<String, String>
        private lateinit var redisCommands: RedisCoroutinesCommands<String, String>

        @JvmStatic
        @BeforeAll
        fun setupRedis() {
            val client = RedisClient.create(
                "redis://${redis.host}:${redis.firstMappedPort}"
            )
            connection = client.connect()
            redisCommands = RedisCoroutinesCommandsImpl(connection.reactive())
        }

        @JvmStatic
        @AfterAll
        fun tearDownRedis() {
            connection.close()
            connection.close()
        }

        fun getConnection(): StatefulRedisConnection<String, String> = connection
        fun getCommands(): RedisCoroutinesCommands<String, String> = redisCommands
        fun getRedisPort(): Int = redis.firstMappedPort
        fun getRedisHost(): String = redis.host
    }
}
```

#### 依赖变更

在 `service/build.gradle.kts` 中新增:
```kotlin
testImplementation(libs.testcontainers.core)
testImplementation(libs.testcontainers.junit.jupiter)
```

在 `gradle/libs.versions.toml` 中无需新增任何内容（所有依赖已声明）。

### 延期测试基类共享策略

由于 T04/T05/T06 均位于 `service` 模块，共用同一 RedisTestBase 即可。具体:

| 测试 | 目标文件 | 需要 MySQL? | 需要 Redis? | 基类 |
|------|---------|------------|------------|------|
| T04 memberCount 并发 | `ConversationServiceTest.kt` | 是 (MySQL) | 否 | `DatabaseTestBase` |
| T05 DeadLetter payload 补偿 | `DeadLetterServiceTest.kt` | 是 (MySQL) | 是 (MessageQueue) | `DatabaseTestBase` + `RedisTestBase` |
| T06 SeqService 恢复 | `SeqServiceTest.kt` | 否 | 是 (直接 Lettuce) | `RedisTestBase` |

> **注意**: T04 的 memberCount 测试仅使用 MySQL（ConversationRepository JPQL），不依赖 Redis。其延期的根本原因是原有 MockK 单元测试框架无法模拟真正的并发 JPA 事务竞争条件，需要 MySQL Testcontainers 测试环境。建议在 Phase 14 中区分对待。

#### 合并基类建议

考虑到 T05 同时需要 MySQL 和 Redis，建议创建一个组合基类 `IntegrationTestBase`：

```kotlin
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IntegrationTestBase {
    companion object {
        @Container private val mysql = MySQLContainer<Nothing>("mysql:8.0")
        @Container private val redis = GenericContainer<Nothing>("redis:7-alpine").withExposedPorts(6379)
        // ... 初始化两者
    }
}
```

单个 Testcontainers 可以同时管理多个 `@Container` 静态字段，JUnit 扩展会自动按顺序启动。

## 2. GC5: deviceId 验证方案

### 当前 Token 验证流程分析

**数据流**: 原始终端设备 → LoginReq (含 deviceId) → LoginHandler → SessionRegistry.register()
```
SessionRegistry.register() 内部流程:
1. addToLocalCache(session)  — L1 ConcurrentHashMap
2. saveToRedis(session)      — L2 Redis (JSON 序列化)
3. Session 中的 deviceId 字段 → kotlinx.serialization.encodeToString() → Redis
```

**重连流程**（当前代码在 `LoginHandler.handle()` 第 35-42 行）:
```kotlin
if (req.hasToken()) {
    val token = req.token
    val existingSession = sessionRegistry.validate(token)
    if (existingSession != null) {
        return buildLoginResp(existingSession.userId, existingSession.token, req)
    }
}
```

**问题**: 重连时仅验证 Token 是否有效，未校验请求中的 `deviceId` 是否与 Session 中绑定的 `deviceId` 一致。这意味着 Token 被窃取后，攻击者可以从任意设备使用该 Token 重连。

### Session 已有字段分析

`gateway/src/main/kotlin/com/nebula/gateway/session/Session.kt`:
```kotlin
data class Session(
    val userId: Long,
    val token: String,
    val deviceType: String,
    val deviceId: String,        // ✅ 已有此字段
    val connectionId: String
)
```

**结论**: `Session` 已包含 `deviceId` 字段，SessionStore 存储/序列化均已包含 deviceId。不需要新增任何存储字段。

### deviceId 来源

在 `LoginHandler.buildLoginResp()` 中:
```kotlin
.setDeviceId(req.deviceId)  // 从客户端请求的 LoginReq.deviceId 获取
```

客户端在首次登录（密码）或重连（Token）时均会携带 `deviceId` 字段（protobuf 定义）。

### 修改方案

在 `LoginHandler.handle()` 的重连分支中加入 deviceId 校验:

```kotlin
// 场景 1: Token 重连（AUTH-02）— 加入 deviceId 验证（D-14-06 / GC5）
if (req.hasToken()) {
    val token = req.token
    val existingSession = sessionRegistry.validate(token)
    if (existingSession != null) {
        // GC5: 验证 deviceId 一致性，防止 Token 被跨设备盗用
        val reqDeviceId = req.deviceId
        if (reqDeviceId.isNotBlank() && existingSession.deviceId != reqDeviceId) {
            throw UserException(BizCode.TOKEN_INVALID, "Token 绑定的设备 ID 不匹配")
        }
        return buildLoginResp(existingSession.userId, existingSession.token, req)
    }
}
```

**设计考量**:
- 当 `req.deviceId` 为空时跳过验证（兼容旧客户端）
- 验证失败使用 `TOKEN_INVALID` 错误码（与 Token 过期/无效保持一致）
- token 验证 + deviceId 验证双重保障

### 影响范围

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `LoginHandler.kt` | 修改 | 重连分支加入 deviceId 校验 |
| `Session.kt` | 无变更 | `deviceId` 字段已存在 |
| `SessionStore.kt` | 无变更 | 存储接口不变 |
| `SessionRegistry.kt` | 无变更 | 验证逻辑不变，由 LoginHandler 负责 deviceId 对比 |
| `AuthInterceptor.kt` | 无变更 | Token 验证不变 |
| `LoginHandlerTest.kt` | 补充测试 | 添加 deviceId 不匹配测试用例 |

## 3. T04: memberCount 并发更新测试

### 测试目标

验证并发 `inviteMember` + `leaveGroup` 调用下，`conversation.member_count` 与 `SELECT COUNT(*) FROM conversation_members WHERE deleted=0` 的一致性。

### 依赖分析

- ConversationRepository — MySQL 操作（incrementMemberCount JPQL 原子更新）
- ConversationMemberRepository — MySQL 操作（softDelete + save）
- **不需要 Redis**

### 实现方案

使用现有 `DatabaseTestBase` 基类 + `kotlinx.coroutines.test` 协程并发测试:

```kotlin
class ConversationServiceConcurrencyTest : DatabaseTestBase() {
    // 使用真实 MySQL 数据库，通过 Koin 或手动注入 repository
    // 并发调用 inviteMember + leaveGroup
    // 验证最终 memberCount == COUNT(*)
    
    @Test
    fun memberCountShouldMatchActualCountAfterConcurrentOperations() = runTest {
        // Given: 创建群聊（ownerUid + 10 个成员）
        // When: 并发邀请 + 退群（使用 coroutineScope + repeat + launch）
        // Then: 验证 conversation.member_count == SELECT COUNT(*) WHERE deleted=0
    }
}
```

### 注意事项

- `incrementMemberCount` 使用 JPQL 原子 `UPDATE conversation SET member_count = member_count + :delta`，天然线程安全
- 测试验证的是 `get` 操作的可见性（最终一致性）
- 建议使用 `withContext(Dispatchers.IO)` 包裹数据库操作

## 4. T05: DeadLetter payload 补偿测试

### 测试目标

创建带 payload 的死信 → 调用 `compensate()` → 验证 stream fields 中 `payload` 为 Base64 编码。

### 依赖分析

- DeadLetterRepository — MySQL 操作（save + findByStatusAndFailCountLessThan）
- MessageQueueRepository — Redis Stream 操作（enqueue）
- **Redis + MySQL 都需要**

### 实现方案

使用组合基类（MySQL + Redis），测试步骤:

```kotlin
class DeadLetterServiceCompensateTest : /* 组合基类待设计 */ {
    @Test
    fun compensateWithPayloadShouldEncodeBase64() = runTest {
        // Given: 创建一条带 payload 的死信记录（通过 real repository）
        val payload = "test payload data".toByteArray()
        val entity = deadLetterService.create(
            conversationId = "conv-1",
            senderUid = 1L,
            messageType = 1,
            content = "content",
            payload = payload,
            clientTs = 1000L,
            failReason = "test"
        )
        
        // When: 调用 compensate
        val count = deadLetterService.compensate()
        
        // Then: 验证 stream fields 中 payload 为 Base64 编码
        // 通过监听 Redis Stream XREAD 验证
    }
}
```

**关键验证点**: `DeadLetterService.compensate()` 第 177 行:
```kotlin
"payload" to (item.payload?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "")
```

## 5. T06: SeqService Redis 重启恢复测试

### 测试目标

写入 5 条消息（seq 1-5）→ `FLUSHALL` Redis → 调用恢复逻辑 → 验证下一条消息 seq=6。

### 依赖分析

- SeqService — 直接 Lettuce Redis 操作
- `FLUSHALL` — Redis 命令
- **仅需 Redis**

### 实现方案

使用 `RedisTestBase`:

```kotlin
class SeqServiceRecoveryTest : RedisTestBase() {
    @Test
    fun seqShouldContinueAfterRedisFlush() = runTest {
        // Given: 写入 seq 1-5
        val convId = "conv-recovery"
        val uid = 1001L
        val conn = getConnection()
        val seqService = SeqService(conn)
        
        repeat(5) { seqService.nextSeq(convId, uid) }
        assertEquals(5, seqService.currentSeq(convId, uid))
        
        // When: FLUSHALL
        getCommands().flushall()
        
        // 调用恢复逻辑（SETNX）
        seqService.tryRestoreSeq(convId, uid, 6L)
        
        // Then: 下一条消息 seq=6
        val nextSeq = seqService.nextSeq(convId, uid)
        assertEquals(6L, nextSeq)
    }
}
```

### 注意事项

- 反射注入 mock 的现有测试风格在集成测试中不再需要
- `SeqService` 构造函数需要 `StatefulRedisConnection<String, String>`，从 `RedisTestBase.getConnection()` 获取
- `tryRestoreSeq` 使用 `SETNX` 原子操作，仅在 Key 不存在时设置

## 6. S8: server/build.gradle.kts 冗余依赖清理

### 当前依赖分析

server 模块 `src/main/kotlin/` 的直接 import 分析结果:

| 依赖 | 直接在 server 代码中使用? | 结论 |
|------|-------------------------|------|
| `project(":gateway")` | ✅ ServerBootstrap, ChatService, etc. | **保留** |
| `project(":proto")` | ✅ 间接通过 gateway 使用 | **保留**（Phase 12 显式声明原则） |
| `project(":common")` | ✅ ApplicationConfig, RedisConfig etc. | **保留** |
| `libs.typesafe.config` | ✅ ConfigLoader 直接使用 | **保留** |
| `libs.kotlin.logging` | ✅ NebulaServer.kt 直接使用 | **保留** |
| `libs.koin.core` | ✅ startKoin, GlobalContext | **保留** |
| gRPC 全栈 | ✅ ChatServer NettyServerBuilder | **保留** |
| `libs.lettuce.core` | ❌ 未在 server 代码中 import | **可移除** |
| `libs.kotlinx.coroutines.core` | ❌ 未在 server 代码中 import | **可移除** |
| `libs.hibernate.core` | ❌ 未在 server 代码中 import | **可移除** |
| `libs.spring.tx` | ❌ 未在 server 代码中 import | **可移除** |
| `libs.hikaricp` | ❌ 未在 server 代码中 import | **可移除** |
| `libs.spring.data.jpa` | ❌ 未在 server 代码中 import | **可移除** |

### 清理方案

```kotlin
// Phase 14 (S8): 清理 Phase 12 implementation 重构后可见的冗余依赖
// 以下依赖已由 :gateway → :service → :repository 传递提供，server 编译无需直接声明
// - libs.lettuce.core        — Redis 连接，由 repository 模块管理
// - libs.kotlinx.coroutines.core — 协程核心，由 gateway 模块传递
// - libs.hibernate.core      — JPA ORM，由 repository 模块管理
// - libs.spring.tx           — 事务管理，由 repository 模块管理
// - libs.hikaricp            — 连接池，由 repository 模块管理
// - libs.spring.data.jpa     — Spring Data JPA，由 repository 模块管理
```

**风险**: 低。这些依赖在 runtime 仍通过 transitive 路径存在。移除后仅影响 server 模块的 compile classpath，不影响 runtime classpath。

**验证方法**: 清理后执行 `./gradlew server:compileKotlin` 验证编译通过，`./gradlew server:test` 验证测试通过。

## 7. S6/S16/S23 测试补充规划

### 优先级排序

| 优先级 | 测试 | 依赖 | 工作量评估 |
|--------|------|------|-----------|
| **P0** | S8 依赖清理 | 无 | 1 行删除 |
| **P0** | GC5 deviceId 验证 | 无 | ~5 行修改 + 1 个测试用例 |
| **P1** | T06 SeqService 恢复 | RedisTestBase | ~30 行测试代码 |
| **P1** | T05 DeadLetter payload | MySQL + Redis | ~50 行测试代码 |
| **P2** | T04 memberCount 并发 | MySQL | ~60 行测试代码 |

### 实施路径

1. **Step 1**: 清理 `server/build.gradle.kts`（S8，零风险）
2. **Step 2**: 添加 deviceId 验证（GC5，低风险）
3. **Step 3**: 在 `service/build.gradle.kts` 添加 Testcontainers 依赖，创建 `RedisTestBase`
4. **Step 4**: 实现 T06（仅需 Redis，独立性强）
5. **Step 5**: 创建组合基类 `IntegrationTestBase`（MySQL + Redis），实现 T05
6. **Step 6**: 实现 T04（使用现有 DatabaseTestBase）

## 实现路径建议

### 文件变更汇总

| 文件 | 操作 | 所属任务 |
|------|------|---------|
| `server/build.gradle.kts` | 删除 6 行冗余依赖 | S8 |
| `gateway/.../LoginHandler.kt` | 添加 deviceId 校验 | GC5 |
| `gateway/.../LoginHandlerTest.kt` | 添加 deviceId 不匹配测试 | GC5 |
| `service/build.gradle.kts` | 添加 testcontainers + lettuce test 依赖 | T06/T05 |
| `service/.../testutil/RedisTestBase.kt` | 新建 Redis Testcontainers 基类 | T06/T05 |
| `service/.../testutil/IntegrationTestBase.kt` | 新建 MySQL+Redis 组合基类 | T05 |
| `service/.../conversation/ConversationServiceTest.kt` | 添加 T04 并发测试 | T04 |
| `service/.../admin/DeadLetterServiceTest.kt` | 添加 T05 补偿测试 | T05 |
| `service/.../sequence/SeqServiceTest.kt` | 添加 T06 恢复测试 | T06 |

### 潜在风险

1. **Docker 环境依赖**: Testcontainers 需要 Docker 环境。`repository` 模块已在 `build.gradle.kts` 中配置 Docker API 版本兼容（`api.version=1.44`）。service 模块测试需相同配置。
2. **测试执行时间**: Testcontainers 容器启动需 5-15 秒，建议 T05/T06 使用 `@TestInstance(PER_CLASS)` 共享容器（已在基类中实现）。
3. **端口冲突**: 使用随机端口映射（`withExposedPorts` 而非 `setPort`），避免测试并行执行时端口冲突。
4. **Ryuk 容器**: Testcontainers 默认启用 Ryuk 清理容器。若 CI 环境网络受限，需设置 `TESTCONTAINERS_RYUK_DISABLED=true`。
5. **Lettuce 连接**: `RedisTestBase` 使用 `RedisClient.create()` 创建连接，注意在 `@AfterAll` 中正确关闭连接。

## 参考资源

- [现有 MySQL Testcontainers 基类](repository/src/test/kotlin/com/nebula/repository/testutil/DatabaseTestBase.kt)
- [现有会话模型](gateway/src/main/kotlin/com/nebula/gateway/session/Session.kt)
- [LoginHandler Token 重连](gateway/src/main/kotlin/com/nebula/gateway/handler/user/LoginHandler.kt)
- [DeadLetterService compensate](service/src/main/kotlin/com/nebula/service/admin/DeadLetterService.kt) — 第 149-202 行
- [SeqService](service/src/main/kotlin/com/nebula/service/sequence/SeqService.kt) — tryRestoreSeq 第 104-107 行
- [server 模块依赖](server/build.gradle.kts)
- [版本目录](gradle/libs.versions.toml)
- Testcontainers 文档: https://java.testcontainers.org/
- Redis Alpine 镜像: https://hub.docker.com/_/redis

## RESEARCH COMPLETE
