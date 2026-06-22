# CODEBUDDY.md

This file provides guidance to CodeBuddy when working with code in this repository.

> 中文注释规范沿用项目既有约定，新增部分聚焦于"构建命令 + 架构 + 易踩的坑"，让未来的 Agent 能在最短时间内进入状态。

---

## 一、构建 / 运行 / 测试

所有命令在仓库根目录执行。Gradle 多模块项目，JVM 工具链 21（`gradle.properties` 与各模块 `jvmToolchain(21)` 已锁定）。

### 构建

```bash
# 全量构建（含 proto 代码生成、测试、installDist）
./gradlew build

# 构建产物可分发的目录（Docker 镜像基于此目录）
./gradlew :server:installDist
# 产物路径: server/build/install/server/  （含 bin/server 启动脚本 + lib/*.jar）
```

### 运行（本地直跑）

```bash
# 启动 server 模块（main: com.nebula.server.NebulaServerKt）
./gradlew :server:run
# 依赖 MySQL + Redis 启动；config/ 目录下的 application.conf / dev.conf 自动加载
# 日志目录可通过 -Plog.dir=xxx 覆盖，默认 logs/
./gradlew :server:run -Plog.dir=../server_log
```

### Docker 部署

```bash
# 启动 mysql + redis + server（容器内网络用服务名通信，详见 docker-compose.yml）
docker compose up -d
# 重建 server 镜像
docker compose build server
```

环境变量：`ENV`（dev/prod，加载对应 `config/<env>.conf`）、`DB_HOST`、`REDIS_HOST`、`MYSQL_ROOT_PASSWORD`。

### 测试

```bash
# 全量测试
./gradlew test

# 单模块
./gradlew :gateway:test
./gradlew :service:test

# 单个测试类
./gradlew :gateway:test --tests "com.nebula.gateway.di.GatewayModuleTest"

# 单个测试方法
./gradlew :gateway:test --tests "com.nebula.gateway.di.GatewayModuleTest.chatHandlersRegisteredCorrectly"

# 只跑标了 @Tag("koin-di") 的 Koin DI 装配验证（gateway 模块专用任务）
./gradlew :gateway:koinDiTest
```

注意：`gateway` 模块 `tasks.test` 设了 `maxParallelForks = 1`（且 Koin 测试串行），以避免并行 fork 进程在测试通过后无法正常退出。

### 关键依赖版本

`gradle/libs.versions.toml` 是版本目录，**修改版本必须改这里**：
- Kotlin 2.1.20 / gRPC 1.81.0 / Protobuf 4.29.3 / Koin 4.1.0
- Lettuce 6.5.5.RELEASE / HikariCP 7.0.2 / Hibernate 6.6.8.Final / Flyway 10.22.0
- JUnit 5.11.4 / MockK 1.13.14

---

## 二、模块架构（`proto ← common ← repository ← service ← gateway ← server`）

| 模块 | 职责 | 关键包 |
| --- | --- | --- |
| `proto` | Protobuf 定义 + 生成 Java/Kotlin Stub | `proto/src/main/proto/*.proto` |
| `common` | 共享工具：异常、BizCode、雪花 ID、SessionStore、配置 | `com.nebula.common.*` |
| `repository` | DAO + Redis 仓储 + Flyway 迁移 + JPA EntityManagerFactory | `com.nebula.repository.dao.*` / `.redis.*` / `db/migration` |
| `service` | 业务编排：UserService / MessageService / ConversationService / FriendService / SeqService | `com.nebula.service.*` |
| `gateway` | gRPC 服务实现 + Handler 框架 + 拦截器 + 推送 + 投递跟踪 | `com.nebula.gateway.*` |
| `server` | **唯一入口**：`NebulaServer.kt`（main） + 启动编排 + ChatServer 生命周期 | `com.nebula.server.*` |

`server` 模块**不直接依赖** `repository` / `service`（生产代码），所有跨层访问通过 `gateway.bootstrap.ServerBootstrap` 封装。测试代码通过 `testImplementation` 显式添加。

### 数据流：gRPC → Handler → Service → Repository

1. **gRPC 入口**：`gateway/service/ChatService.kt` —— Netty 上的双向流，读取客户端帧（method + payload bytes）→ 解码为 `RequestFrame`。
2. **拦截器链**：`gateway/interceptor/*` —— 鉴权（`AuthInterceptor`，从 `currentCoroutineContext().requireSession()` 取登录态）、日志、限流。
3. **Handler 调度**：`gateway/dispatcher/HandlerRegistry` —— 按 `method` 字符串路由（`chat/send` / `message/pull` / `user/login` …）。
4. **Handler 业务**：`gateway/handler/**` —— 实现 `Handler<Req, Resp>`，**只依赖 Service 层**，不直接访问 Repository。
5. **Service 业务编排**：`service/chat/MessageService.kt` 等 —— 串联 Dedup → 持久化 → Redis 缓存 → 推送。
6. **Repository**：`repository/dao/*`（JPA + `JpaTxRunner` 手写事务，D-09）/ `repository/redis/*`（Lettuce，Dedup 与序列号）。

### Handler 注册模式（核心约定）

业务 Handler **不允许** 在 `gatewayModule` 里手写 `single { ... }`，必须经由 `HandlerCollector`：

```kotlin
// gateway/handler/chat/ChatHandlerCollector.kt
class ChatHandlerCollector(
    private val sendMessageHandler: SendMessageHandler,
    private val pullMessagesHandler: PullMessagesHandler,
    private val readReportHandler: ReadReportHandler,
    private val messageSeqHandler: MessageSeqHandler
) : HandlerCollector {
    override fun registerAll(registry: HandlerRegistry) {
        registry.register(sendMessageHandler)   // method = "chat/send"
        registry.register(pullMessagesHandler) // method = "message/pull"
        registry.register(readReportHandler)   // method = "message/read"
        registry.register(messageSeqHandler)   // method = "message/seq"
    }
}
```

新增 Handler 的标准动作：
1. 实现 `Handler<Req, Resp>`，定义 `override val method: String`。
2. 在所属 `*HandlerModule`（`gateway/di/*HandlerModule.kt`）里 `single { ... }` 注册。
3. 加入对应 `*HandlerCollector` 构造函数 + `registerAll`。
4. **同步更新**测试模块 `GatewayModuleTest.buildHandlerModule()` 中的 mock Koin 模块（否则测试和生产装配不一致会藏住 bug）。

### Koin DI 拓扑

`gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` 的 `gatewayModules` 是聚合入口：

```
serviceKoinModule          // Service 层（User/Message/Conversation/Friend/Seq/...）
frameworkModule            // HandlerRegistry / ProtoCodec / SessionRegistry / 拦截器
userHandlerModule          // Login/Register/SearchUser/... + UserHandlerCollector
chatHandlerModule          // Send/Pull/Read + ChatHandlerCollector
conversationHandlerModule  // List/Group/Create/Invite/Leave/Kick + ConversationHandlerCollector
friendHandlerModule        // Add/Accept/Reject/Requests/List/Delete + FriendHandlerCollector
messageReliabilityModule   // Phase 10: RedisDeliveryTracker/DeliveryTrackingService/死信补偿
                           // + DeliveryHandlerCollector + AdminHandlerCollector
```

⚠️ **常见踩坑**：在 `*HandlerModule` 中加了 `single { NewHandler(get()) }`，但忘了在 `*HandlerCollector` 构造函数里加对应参数 → Koin 启动时抛 `NoBeanDefFoundException`。测试模块若手动补了 `single`，CI 不会暴露，Docker 启动后才崩。**生产模块和测试模块必须保持镜像**。

### 启动顺序（D-18 / CQ-09 / CQ-05）

`server/.../NebulaServer.kt` 严格按以下顺序：

1. **logback 配置必须最先**（D-18）：`System.setProperty("logback.configurationFile", "logback-$env.xml")` 必须在 `KotlinLogging.logger {}` 触发 SLF4J 初始化之前，否则会用默认 DEBUG 级别。
2. `ConfigLoader.load()` 读 HOCON。
3. `startKoin { modules(...) }` —— 注册所有 Koin 定义。
4. `ServerBootstrap.initializeModules(koin)` —— `ModuleInitializer` 发现 + 拓扑排序 + 执行 + **逆序回滚支持**（CQ-09）。
5. `ServerBootstrap.recoverSequences(koin)` —— D-81/H21：从 MySQL 恢复 Redis 序列号。
6. `ServerBootstrap.setupDeadLetterBridge(koin)` —— M11：死信创建回调注入。
7. `koin.getAll<HandlerCollector>().forEach { it.registerAll(registry) }` —— 统一注册入口。
8. `ServerBootstrap.createChatService(koin)` + `ChatServer.start(...)`。
9. 注册 `addShutdownHook`（CQ-05）：gRPC → 消息刷盘 → Redis → DB 连接池，**严格逆序关闭**。

---

## 三、中文注释规范（强制）

所有由 AI 新生成的 Kotlin 代码必须遵守，**违反此规范视为不合格**：

1. **类 / 接口 / 枚举**：`/** ... */` KDoc 注释，说明职责 + 关键设计决策（引用 `D-编号`）+ `@param` 构造参数。
2. **方法 / 函数**：`/** ... */` KDoc 注释，说明功能 + `@param` / `@return` + 关键实现细节（线程安全、边界条件）。
3. **字段 / 属性**：data class 构造参数用 `/** ... */`；类内部字段用 `/** ... */` 或 `//`，说明用途 + 约束（取值范围、默认值）。
4. **内联逻辑**：复杂逻辑块前加 `// 中文注释` 说明意图；分支条件、边界处理必须注释。
5. **注释语言**：**所有文档注释使用中文**；技术标识符（package、class 名、方法名、关键字）保留英文原文。
6. **注释原则**：注释解释 **"为什么这么做"**，而非 "做了什么"；不要冗余（如 `// 返回结果` 在 return 前）；**设计决策引用 `D-编号`**（来自 `.planning/` 设计决策）以提高可追溯性。

---

## 四、易踩的坑（速查）

- **日志目录**：`./gradlew :server:run` 不带 `-Plog.dir=` 时默认在 `logs/`，相对当前工作目录。
- **测试并行**：`gateway/test` 用 `maxParallelForks = 1`，**单次跑全模块比预期慢**是正常的，不要改大。
- **JPA 事务**：D-09 — **不要**在 DAO / Service 里 `@Transactional`（无 Spring 上下文），统一用 `JpaTxRunner` 显式包事务。
- **Redis 序列号**：D-78 — 序列号生成**统一走 `SeqService.nextSeq`**，禁止在 MessageService / Handler 里直接 `INCR`。
- **死信桥接**：M11 — 死信创建回调由 `ServerBootstrap.setupDeadLetterBridge` 注入，**不要在业务 Service 里直接 new `DeadLetterService`**。
- **Proto 生成**：`proto/build/` 是 generated sources，**不要**手改；proto 文件改了直接 `./gradlew :proto:build` 触发重新生成。
- **配置文件**：HOCON 在 `config/`，按环境分 `application.conf`（基础）+ `dev.conf` / `prod.conf`（覆盖）。Docker 镜像把 `config/` 整个 COPY 进去并挂载 `/app/config:ro`，可热改。
- **鉴权上下文**：`currentCoroutineContext().requireSession()` 取登录态，**Handler 业务代码不要再解析 token**。
- **CI 装配 vs 生产装配**：见上文「Koin DI 拓扑」踩坑提醒。`GatewayModuleTest` 的 `buildHandlerModule()` 是手写镜像，必须和生产 `gatewayModules` 保持一致。
