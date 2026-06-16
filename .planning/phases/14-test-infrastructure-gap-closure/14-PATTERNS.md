# Phase 14: 测试基础设施与遗留问题 — 代码模式映射

## 1. Testcontainers 模式

### 1.1 参考模板: `DatabaseTestBase.kt`

**位置**: `repository/src/test/kotlin/com/nebula/repository/testutil/DatabaseTestBase.kt`

**模式特征**:
- 抽象类，标注 `@Testcontainers` + `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`
- `companion object` 中的静态 `@Container` 字段
- `@BeforeAll` 初始化连接参数
- `@AfterAll` 清理连接池
- 共享容器实例（所有测试方法共用一个容器）

```kotlin
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DatabaseTestBase {
    companion object {
        @Container
        private val mysql: MySQLContainer<*> = MySQLContainer<Nothing>("mysql:8.0")
            .withDatabaseName("nebula_test")
        // ...
    }
}
```

### 1.2 新建模板: `RedisTestBase.kt`

**目标位置**: `service/src/test/kotlin/com/nebula/service/testutil/RedisTestBase.kt`

**模式**:
- 沿用 `DatabaseTestBase` 的 `@Testcontainers` + `PER_CLASS` 模式
- 使用 `GenericContainer<Nothing>("redis:7-alpine")` 替代 `MySQLContainer`
- 暴露 `getRedisURI()` / `createRedisClient()` 等辅助方法

---

## 2. 测试中 Koin + Testcontainers 集成模式

### 2.1 参考模板: `HandlerRegistryTestBase.kt`

**位置**: `gateway/src/test/kotlin/com/nebula/gateway/di/HandlerRegistryTestBase.kt`

**模式特征**:
- 抽象基类，提供 `buildExternalModule()` 工厂方法
- 注册所有 Repository Mock 为 Koin 单例
- `@AfterEach` 调用 `stopKoin()` + `scope.cancel()`
- `@Tag("koin-di")` 隔离到独立 Gradle task

### 2.2 新建模板: `ServiceRedisTestBase.kt`（T06 专用）

**目标位置**: `service/src/test/kotlin/com/nebula/service/testutil/ServiceRedisTestBase.kt`

**模式**: 继承 `RedisTestBase`，提供领域 Service 所需的 Koin 模块和 Redis Client 初始化。

---

## 3. 并发测试模式

### 3.1 参考模板: T03 好友双向竞赛测试

**文件**: `service/src/test/kotlin/com/nebula/service/friend/FriendServiceTest.kt`

**模式特征**（基于 11-04-PLAN.md 的设计）:
```kotlin
@Test
fun `addFriend 双向竞赛只应创建一对好友关系`() = runTest {
    // 使用 coroutineScope + launch 实现并发
    coroutineScope {
        launch { service.addFriend(A, B) }
        launch { service.addFriend(B, A) }
    }
    // 验证最终一致性
    assertEquals(2, friendshipRepository.count()) // A→B + B→A
}
```

### 3.2 应用模板: T04 memberCount 并发测试

```kotlin
@Test
fun `memberCount 并发更新应保持一致性`() = runTest {
    coroutineScope {
        repeat(5) {
            launch { conversationService.inviteMember(convId, userId) }
            launch { conversationService.leaveGroup(convId, userId) }
        }
    }
    // 验证 member_count = SELECT COUNT(*) WHERE deleted=0
    assertEquals(conversation.memberCount, activeMemberCount)
}
```

---

## 4. MockK 参数捕获模式

### 4.1 参考模板: 现有的 Handler 测试

**文件**: `gateway/src/test/kotlin/com/nebula/gateway/handler/chat/SendMessageHandlerTest.kt`（示例）

**模式特征**:
```kotlin
// Mock 依赖
private val repository = mockk<MessageQueueRepository>()

// 定义行为
coEvery { repository.enqueue(any()) } answers {
    val message = firstArg<Message>()
    // 验证参数
    assertNotNull(message.payload)
    message
}
```

### 4.2 应用模板: T05 DeadLetter compensate 参数捕获

```kotlin
private val messageQueueRepository = mockk<MessageQueueRepository>()
private val slot = slot<EnqueueRequest>()

@Test
fun `compensate 应从 DeadLetterEntity payload 恢复`() = runTest {
    coEvery { messageQueueRepository.enqueue(capture(slot)) } returns true
    
    deadLetterService.compensate(deadLetterEntity)
    
    val captured = slot.captured
    assertNotNull(captured.fields["payload"])
    assertTrue(captured.fields["payload"]!!.length > 0)
    // 验证可 Base64 解码
    assertDoesNotThrow { Base64.getDecoder().decode(captured.fields["payload"]) }
}
```

---

## 5. seqService 恢复测试模式

### 5.1 参考模板: SeqService 现有测试（需 Redis 版本）

**文件**: `service/src/test/kotlin/com/nebula/service/sequence/SeqServiceTest.kt`（现有 MockK 版本）

### 5.2 应用模板: T06 Redis 重启恢复测试

```kotlin
class SeqServiceRestartTest : RedisTestBase() {
    private lateinit var redisClient: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var commands: RedisCommands<String, String>
    
    @BeforeEach
    fun setUp() {
        redisClient = createRedisClient()
        connection = redisClient.connect()
        commands = connection.sync()
    }
    
    @AfterEach
    fun tearDown() {
        connection.close()
        redisClient.shutdown()
    }
    
    @Test
    fun `Redis 重启后序列号应从 MySQL 恢复`() = runTest {
        // 1. 写入 5 条消息（seq 1-5）
        repeat(5) { seqService.nextSeq(convId) }
        
        // 2. 模拟 Redis 重启：FLUSHALL
        commands.flushall()
        
        // 3. 调用恢复逻辑
        seqService.recoverFromDatabase(convId)
        
        // 4. 验证下一条 seq = 6
        assertEquals(6L, seqService.nextSeq(convId))
    }
}
```

---

## 6. LoginHandler deviceId 验证模式（GC5）

### 6.1 现有重连流程

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/user/LoginHandler.kt`

**关键路径**（重连分支 ~35-42 行）:
1. `req.hasToken()` — 检测登录请求是否携带 Token（而非密码）
2. `sessionRegistry.validate(token)` — 验证 Token 有效性，返回已有 Session
3. `buildLoginResp(existingSession.userId, existingSession.token, req)` — 复用 Session 返回登录响应

**问题**: 重连时仅验证 Token，未校验请求中的 `deviceId` 与 Session 绑定的 `deviceId` 是否一致。

### 6.2 新增 deviceId 校验模式

在 `LoginHandler.handle()` 的 Token 重连分支中增加 ~5 行校验：

```kotlin
if (req.hasToken()) {
    val token = req.token
    val existingSession = sessionRegistry.validate(token)
    if (existingSession != null) {
        // GC5: 验证 deviceId — 防止 Token 跨设备盗用
        val reqDeviceId = req.deviceId
        if (reqDeviceId.isNotBlank() && existingSession.deviceId != reqDeviceId) {
            throw UserException(BizCode.TOKEN_INVALID, "Token device id mismatch")
        }
        return buildLoginResp(existingSession.userId, existingSession.token, req)
    }
}
```

---

## 7. server/build.gradle.kts 依赖模式（S8）

### 7.1 现有依赖模式

server 模块作为纯启动入口，其职责是组装和启动 NebulaServer。Phase 12 后，server 仅需直接依赖：
- `:gateway` — 提供所有 Handler + Dispatcher + Interceptor
- `:proto` — Proto 定义（`:gateway` 传递）
- `:common` — 共享类型（`:gateway` 传递）

### 7.2 清理模式

| 当前声明 | 目标 | 理由 |
|----------|------|------|
| `implementation(libs.lettuce.core)` | 删除 | 仅 `:repository` 模块需要 |
| `implementation(libs.hibernate.core)` | 删除 | 仅 `:repository` 模块需要 |
| `implementation(libs.spring.tx)` | 删除 | 仅 `:repository` 模块需要 |
| `implementation(libs.hikaricp)` | 删除 | 仅 `:repository` 模块需要 |
| `implementation(libs.spring.data.jpa)` | 删除 | 仅 `:repository` 模块需要 |
