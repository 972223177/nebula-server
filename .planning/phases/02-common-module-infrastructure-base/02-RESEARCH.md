# Phase 2: Common Module & Infrastructure Base - Research

**Researched:** 2026-06-11
**Domain:** Kotlin 共享基础设施 — Snowflake ID、HikariCP、SSL/TLS、gRPC Netty、HOCON 配置、BizException
**Confidence:** HIGH

## Summary

本阶段构建 Nebula 后端的所有共享基础设施层，为 Phase 3~11 提供基础能力。6 个核心交付物：SnowflakeIdGenerator、HikariCP 连接池封装、SSL/TLS 双模式证书方案、NettyServerBuilder gRPC 启动器、ApplicationConfig 统一配置管理、BizException 异常体系。

关键技术发现：

1. **设计文档 9.2~9.5 提供实现级代码**，可直接按 Kotlin 风格调整后使用。设计文档中的 Java 代码需要转换为 Kotlin（属性构造函数、`@Synchronized` 注解、`companion object` 等）[CITED: 后端架构设计v1.2/09-基础设施设计]

2. **系统 Gradle 版本为 9.5.1**（非 Phase 1 假设的 8.10），但 protobuf-gradle-plugin 0.10.0 已验证兼容 Gradle 9.x [VERIFIED: plugins.gradle.org]

3. **HikariCP 7.x 要求 Java 21+**（与当前 JDK 21 一致）。`mysql-connector-j` 是 8.0.31+ 的新 artifact ID（旧 `mysql-connector-java` 已重命名）[VERIFIED: sonatype.com, mvnrepository.com]

4. **设计文档的 SSL JDBC URL** 使用 `useSSL=true&requireSSL=true`（MySQL Connector/J 8.0.x 参数），9.x 版本已弃用这些参数，改为 `sslMode=VERIFY_IDENTITY` [CITED: dev.mysql.com/doc/relnotes/connector-j/en]

5. **OpenSSL 3.6.2** 已安装，生成自签名证书需使用 OpenSSL 3.x 兼容语法（`-addext` 替代 `-extensions` 文件配置）

**Primary recommendation:** 严格遵循 CONTEXT.md 的 18 个决策项。设计文档提供代码骨架，需做 Java→Kotlin 转换和依赖版本更新。

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** 配置格式使用 **Typesafe Config (HOCON)**，类型安全、零额外重量级框架依赖
- **D-02:** 采用 **单文件 + 环境变量覆盖** 模式。一份 `application.conf`，通过 `ENV` 环境变量或 JVM 属性切换 dev/prod 环境
- **D-03:** 配置文件放在项目根 `config/` 目录下，**提交 git 版本控制**。生产敏感凭据通过环境变量注入
- **D-04:** 所有配置合并为一个 **统一的 `ApplicationConfig` 数据类**，放到 common 模块 `com.nebula.common.config` 包下，包含子段：`ServerConfig`、`DatabaseConfig`、`SnowflakeConfig`、`SslConfig`
- **D-05:** BizException 放在 **common 模块** `com.nebula.common.exception` 包下，与 BizCode 同层
- **D-06:** 按领域细分异常类：`UserException` / `ChatException` / `ConversationException` / `FriendException` / `MessageException` 等，统一继承自 `BizException` 基类
- **D-07:** 使用方式：**直接 throw** `UserException(BizCode.USER_NOT_FOUND)`，不走扩展函数
- **D-08:** 开发环境自签证书通过 **`generate-dev-cert.sh` 脚本** 一次性生成
- **D-09:** 证书文件（`fullchain.pem` + `privkey.pem`）**提交 git**，放在 `config/dev/ssl/` 目录下
- **D-10:** 生产环境使用 **Let's Encrypt** 或云平台托管证书，证书路径通过环境变量配置
- **D-11:** Phase 2 先实现 **单数据源**，不提前预留主从骨架。多数据源在 Phase 3 按需引入
- **D-12:** 封装为 **`DataSourceProvider` 接口**，提供 `getDataSource(): DataSource`，非直接暴露 `HikariDataSource`
- **D-13:** 检测到时钟回拨时直接**抛异常 + 手动重启**。单节点无自动恢复机制
- **D-14:** 区分两种异常：**`ClockBackwardsException`**（系统时钟回拨，抛给上层）和 **`SequenceOverflowException`**（同一毫秒序列号耗尽，`waitNextMillis()` 自愈，不抛给调用方）
- **D-15:** 按功能分包：`com.nebula.common.config` / `.exception` / `.idgen` / `.util`
- **D-16:** `logback.xml` 配置文件放在 **common 模块**的 `src/main/resources/` 下
- **D-17:** 分别配置 `logback-dev.xml`（DEBUG、彩色控制台）和 `logback-prod.xml`（INFO、JSON 格式）
- **D-18:** server 模块启动时通过 `logback.configurationFile` 系统属性加载对应环境日志配置

### Claude's Discretion

无 — 所有灰色区域用户都做了明确选择。无 discretion 区域。

### Deferred Ideas (OUT OF SCOPE)

- HikariCP 主从多数据源 — Phase 3 数据库层实现时才引入读写分离
- Snowflake Worker ID 动态分配 — 分布式部署后考虑 ZooKeeper/Redis 分配
- 生产监控指标 — Phase 11 性能与监控阶段统一处理
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-02 | gRPC Netty server starts with configurable port, flow control (autoFlowControl/maxInboundMessageSize), and keepalive params | 设计文档 9.2 提供完整启动代码和参数表；NettyServerBuilder 在 `:server` 模块的 `ChatServer.kt` 中组装 |
| INFRA-03 | Snowflake ID generator produces unique 64-bit IDs with configurable worker ID and clock drift fallback | 设计文档 9.4 提供完整实现（41+10+12 位划分、10ms 回拨容忍、waitNextMillis）；D-13/D-14 锁定异常策略 |
| INFRA-04 | HikariCP connection pool configured for MySQL with optimal production parameters | 设计文档 9.5 提供参数表（maximumPoolSize=20, maxLifetime=30min, leakDetection=10s）；需将 JDBC URL 的 SSL 参数更新为 mysql-connector-j 9.x 兼容语法 |
| INFRA-05 | SSL/TLS supports dual mode (local self-signed + production Let's Encrypt) via environment config | 设计文档 9.3 提供 SslContext 构建代码（SslProvider.OPENSSL, TLSv1.2/1.3, cipher suites）；D-08/D-09/D-10 锁定证书管理和路径 |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| SnowflakeIdGenerator | common 模块 | server 模块 | 生成逻辑在 common（被所有模块使用），Worker ID 配置由 server 模块加载配置后注入 |
| HikariCP 连接池 | common 模块 | repository 模块 | DataSourceProvider 接口在 common 定义，`HikariDataSource` 构造和配置在 common 完成；repository 模块消费 DataSource |
| SSL/TLS SslContext | common 模块 | server 模块 | 证书路径配置在 ApplicationConfig，SslContext 构建可下沉到 common 的 `SslConfig.buildSslContext()`；server 模块在 NettyServerBuilder 中调用 `.sslContext()` |
| gRPC Netty Server | server 模块 | — | NettyServerBuilder 是 server 模块的启动入口，集成所有基础设施组件 |
| ApplicationConfig | common 模块 | server 模块 | 配置数据类定义在 common，配置加载（ConfigFactory.load()）在 server 模块入口执行 |
| BizException 体系 | common 模块 | gateway 模块 | BizException 定义在 common，由 Phase 4 的 ExceptionInterceptor 在 gateway 模块捕获处理 |
| Logback 配置 | common 模块 | server 模块 | logback.xml 文件在 common/resources/，server 模块通过系统属性 `logback.configurationFile` 加载 |

## Standard Stack

### 当前 common/build.gradle.kts 已有依赖

```kotlin
// 已存在 — Phase 1 建立
implementation(project(":proto"))
implementation(libs.kotlin.logging)
implementation(libs.slf4j.api)
implementation(libs.logback.classic)
```

### Phase 2 需新增的依赖

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.typesafe:config` | 1.4.9 | HOCON 配置加载 | D-01 锁定；JVM 生态最成熟的配置库，零依赖，纯 Java，支持 HOCON/JSON/properties [VERIFIED: central.sonatype.com] |
| `com.zaxxer:HikariCP` | 7.0.2 | JDBC 连接池 | D-11/D-12 锁定；生产级零开销连接池，Spring Boot 默认，性能最优 [VERIFIED: mvnrepository.com] |
| `com.mysql:mysql-connector-j` | 9.x (latest) | MySQL JDBC 驱动 | INFRA-04 需要；8.0.31+ 已重命名为 `mysql-connector-j`（非旧 `mysql-connector-java`）[VERIFIED: central.sonatype.com] |
| `io.grpc:grpc-netty-shaded` | 1.81.0 | Netty 传输层（gRPC 内置） | INFRA-02 需要；gRPC 官方 Netty 传输实现，shaded 避免 Netty 版本冲突 [VERIFIED: mvnrepository.com] |
| `io.grpc:grpc-protobuf` | 1.81.0 | Protobuf 序列化支持 | gRPC 服务器需要处理 Proto 消息 [VERIFIED: mvnrepository.com] |
| `io.grpc:grpc-stub` | 1.81.0 | gRPC Stub 支持 | gRPC 服务端需要 [VERIFIED: mvnrepository.com] |
| `io.grpc:grpc-kotlin-stub` | 1.5.0 | Kotlin gRPC Stub | Kotlin 风格的 gRPC 服务定义（`ServerFlow` 等）[VERIFIED: mvnrepository.com] |
| `io.grpc:grpc-services` | 1.81.0 | gRPC 内置服务（Health check） | 可选，建议引入以支持 K8s health check [VERIFIED: mvnrepository.com] |
| `io.netty:netty-tcnative-boringssl-static` | 2.0.x | OpenSSL 动态库 | SslProvider.OPENSSL 需要 native 库；设计文档 9.3 要求 [VERIFIED: mvnrepository.com] |

### 版本兼容矩阵

| 组件 | 版本 | 兼容性 |
|------|------|--------|
| Gradle | 9.5.1 | ✅ 系统已安装（2026-06），protobuf plugin 0.10.0 已验证兼容 |
| Kotlin | 2.1.20 | ✅ 已配置 |
| JDK | 21.0.11 | ✅ 已安装，HikariCP 7.x 要求 ≥ Java 21 |
| OpenSSL | 3.6.2 | ✅ 已安装（生成自签名证书用） |
| protobuf-gradle-plugin | 0.10.0 | ✅ 2026-04-20 发布，要求 Gradle 7.6+，与 Gradle 9.5.1 兼容 [VERIFIED: plugins.gradle.org] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Typesafe Config (HOCON) | Spring Boot 配置 / 纯 properties | HOCON 零额外依赖，支持嵌套和环境变量插值；properties 无类型安全，Spring Boot 增加依赖重量（D-01 已锁定） |
| grpc-netty-shaded | grpc-netty | shaded 版本将 Netty 重新打包以避免版本冲突；设计文档使用 grpc-netty 但 shaded 是官方推荐的生产方案 |
| HikariCP 7.x | HikariCP 5.1.x (Java 8+) | 7.x 需要 Java 21，与当前环境一致，取最新稳定版 |

**Installation:**

```kotlin
// libs.versions.toml 新增
[versions]
typesafe-config = "1.4.9"
hikaricp = "7.0.2"
mysql-connector = "9.2.0" // 视最新版本调整
grpc = "1.81.0"
grpc-kotlin = "1.5.0"
netty-tcnative = "2.0.68"

[libraries]
typesafe-config = { module = "com.typesafe:config", version.ref = "typesafe-config" }
hikaricp = { module = "com.zaxxer:HikariCP", version.ref = "hikaricp" }
mysql-connector = { module = "com.mysql:mysql-connector-j", version.ref = "mysql-connector" }
grpc-netty-shaded = { module = "io.grpc:grpc-netty-shaded", version.ref = "grpc" }
grpc-protobuf = { module = "io.grpc:grpc-protobuf", version.ref = "grpc" }
grpc-stub = { module = "io.grpc:grpc-stub", version.ref = "grpc" }
grpc-kotlin-stub = { module = "io.grpc:grpc-kotlin-stub", version.ref = "grpc-kotlin" }
grpc-services = { module = "io.grpc:grpc-services", version.ref = "grpc" }
grpc-api = { module = "io.grpc:grpc-api", version.ref = "grpc" }
netty-tcnative = { module = "io.netty:netty-tcnative-boringssl-static", version.ref = "netty-tcnative" }
```

```kotlin
// common/build.gradle.kts 新增依赖
dependencies {
    implementation(project(":proto"))
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Phase 2 新增
    implementation(libs.typesafe.config)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)
}

// server/build.gradle.kts 新增依赖
dependencies {
    implementation(project(":gateway"))
    implementation(project(":proto"))
    implementation(project(":common"))  // 新增：ApplicationConfig 和 SnowflakeIdGenerator

    // gRPC 依赖
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.services)
    implementation(libs.grpc.api)
    implementation(libs.netty.tcnative)
}
```

## Package Legitimacy Audit

> 本阶段通过 Maven Central 引入依赖，非 npm/pip/crates 生态。slopcheck 不适用于 Maven 仓库，以下基于 Maven Central 和官方来源验证。

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `com.typesafe:config` | Maven Central | 14+ yrs | Very High | [github.com/lightbend/config](https://github.com/lightbend/config) | N/A (Maven) | Approved — [VERIFIED: sonatype.com + official docs] |
| `com.zaxxer:HikariCP` | Maven Central | 13+ yrs | Very High | [github.com/brettwooldridge/HikariCP](https://github.com/brettwooldridge/HikariCP) | N/A (Maven) | Approved — [VERIFIED: mvnrepository.com] |
| `com.mysql:mysql-connector-j` | Maven Central | 4+ yrs | Very High | [github.com/mysql/mysql-connector-j](https://github.com/mysql/mysql-connector-j) | N/A (Maven) | Approved — [VERIFIED: central.sonatype.com] |
| `io.grpc:grpc-netty-shaded` | Maven Central | 8+ yrs | High | [github.com/grpc/grpc-java](https://github.com/grpc/grpc-java) | N/A (Maven) | Approved — [VERIFIED: mvnrepository.com] |
| `io.grpc:grpc-kotlin-stub` | Maven Central | 5+ yrs | Medium | [github.com/grpc/grpc-kotlin](https://github.com/grpc/grpc-kotlin) | N/A (Maven) | Approved — [VERIFIED: mvnrepository.com] |
| `io.netty:netty-tcnative-boringssl-static` | Maven Central | 8+ yrs | High | [github.com/netty/netty-tcnative](https://github.com/netty/netty-tcnative) | N/A (Maven) | Approved — [VERIFIED: mvnrepository.com] |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**Note:** All packages are well-established Maven Central artifacts. `grpc-netty-shaded` is the official gRPC shading of Netty, recommended over bare `grpc-netty` for version isolation.

## Architecture Patterns

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Phase 2 Deliverables                         │
│                                                                     │
│                    ┌─────────────────────────┐                     │
│                    │      :server 模块        │                     │
│                    │  NebulaServer.kt (入口)   │                     │
│                    │  ┌───────────────────┐  │                     │
│                    │  │ChatServer.kt      │  │                     │
│                    │  │ NettyServerBuilder │  │                     │
│                    │  │  .forPort(port)   │  │                     │
│                    │  │  .sslContext(ctx) │  │                     │
│                    │  │  .maxInboundMsg(4M)│  │                     │
│                    │  │  .keepAlive(30s)  │  │                     │
│                    │  │  .addService(impl) │  │                     │
│                    │  └────────┬──────────┘  │                     │
│                    │           │             │                     │
│                    │  ┌────────▼──────────┐  │                     │
│                    │  │ConfigLoader.kt    │  │                     │
│                    │  │ConfigFactory.load │──│─► config/app.conf   │
│                    │  │→ ApplicationConfig│  │                     │
│                    │  └────────┬──────────┘  │                     │
│                    └───────────┼──────────────┘                     │
│                                │                                    │
│                    ┌───────────▼────────────────────┐              │
│                    │       :common 模块               │              │
│                    │                                │              │
│                    │  ┌────────────────────────┐    │              │
│                    │  │ config/                 │    │              │
│                    │  │  ApplicationConfig.kt  │    │              │
│                    │  │  ├─ SnowflakeConfig    │    │              │
│                    │  │  ├─ DatabaseConfig     │    │              │
│                    │  │  ├─ ServerConfig       │    │              │
│                    │  │  └─ SslConfig          │    │              │
│                    │  └────────────────────────┘    │              │
│                    │                                │              │
│                    │  ┌────────────────────────┐    │              │
│                    │  │ idgen/                  │    │              │
│                    │  │  SnowflakeIdGenerator   │    │              │
│                    │  │  ├─ nextId(): Long      │    │              │
│                    │  │  ├─ waitNextMillis()    │    │              │
│                    │  │  ├─ ClockBackwardsExcept│    │              │
│                    │  │  └─ SequenceOverflowExc │    │              │
│                    │  └────────────────────────┘    │              │
│                    │                                │              │
│                    │  ┌────────────────────────┐    │              │
│                    │  │ exception/              │    │              │
│                    │  │  BizException           │    │              │
│                    │  │  ├─ UserException      │    │              │
│                    │  │  ├─ ChatException      │    │              │
│                    │  │  ├─ ConversationExcept  │    │              │
│                    │  │  └─ FriendException    │    │              │
│                    │  └────────────────────────┘    │              │
│                    │                                │              │
│                    │  ┌────────────────────────┐    │              │
│                    │  │ datasource/             │    │              │
│                    │  │  DataSourceProvider     │    │              │
│                    │  │  HikariDataSourceProvdr │    │              │
│                    │  └────────────────────────┘    │              │
│                    │                                │              │
│                    │  ┌────────────────────────┐    │              │
│                    │  │ resources/              │    │              │
│                    │  │  logback-dev.xml        │    │              │
│                    │  │  logback-prod.xml       │    │              │
│                    │  └────────────────────────┘    │              │
│                    └────────────────────────────────┘              │
│                                                                     │
│  ┌────────────────────────────────────────────┐                    │
│  │  config/ (项目根目录)                        │                    │
│  │  ├── application.conf                      │                    │
│  │  └── dev/ssl/                              │                    │
│  │       ├── fullchain.pem                    │                    │
│  │       └── privkey.pem                      │                    │
│  └────────────────────────────────────────────┘                    │
│                                                                     │
│  ┌────────────────────────────────────────────┐                    │
│  │  启动流程 (NebulaServer.kt)                 │                    │
│  │  ConfigFactory.load() → ApplicationConfig  │                    │
│  │  → SnowflakeIdGenerator(workerId)          │                    │
│  │  → DataSourceProvider(config)              │                    │
│  │  → SslContext(config.buildSslContext())    │                    │
│  │  → ChatServer(config).start()             │                    │
│  └────────────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────────┘

数据流方向:
   ConfigLoader ──► ApplicationConfig ──► [Snowflake, HikariCP, SSL, Netty]
                    │
                    ├─► SnowflakeIdGenerator ◄── (各模块调用 nextId())
                    ├─► DataSourceProvider ──► Phase 3 repository
                    ├─► SslContext ──► NettyServerBuilder.sslContext()
                    └─► ServerConfig ──► NettyServerBuilder.forPort()
```

### Recommended Project Structure

Phase 2 将在 common 和 server 模块中新增以下文件：

```
common/src/main/kotlin/com/nebula/common/
├── BizCode.kt                              # 已有 — Phase 1
├── config/
│   ├── ApplicationConfig.kt                # 统一的配置数据类
│   ├── ServerConfig.kt                     # gRPC 服务端口等
│   ├── DatabaseConfig.kt                   # MySQL/HikariCP 配置
│   ├── SnowflakeConfig.kt                  # Worker ID、Epoch 配置
│   └── SslConfig.kt                        # 证书路径、SslContext 构建
├── datasource/
│   ├── DataSourceProvider.kt               # 接口：getDataSource(): DataSource
│   └── HikariDataSourceProvider.kt         # HikariCP 实现
├── exception/
│   ├── BizException.kt                     # 基类 (code, msg)
│   ├── UserException.kt
│   ├── ChatException.kt
│   ├── ConversationException.kt
│   ├── FriendException.kt
│   ├── MessageException.kt
│   ├── ClockBackwardsException.kt          # Snowflake 时钟回拨异常
│   └── SequenceOverflowException.kt        # Snowflake 序列溢出异常(内部使用)
├── idgen/
│   └── SnowflakeIdGenerator.kt             # 雪花算法
└── util/
    └── (预留，Phase 4+ 可能放入通用工具)

server/src/main/kotlin/com/nebula/server/
├── NebulaServer.kt                         # 应用入口 (main函数)
├── config/
│   └── ConfigLoader.kt                     # ConfigFactory.load() → ApplicationConfig
├── server/
│   └── ChatServer.kt                       # NettyServerBuilder 组装和启动

common/src/main/resources/
├── logback-dev.xml                         # DEBUG + 彩色控制台
└── logback-prod.xml                        # INFO + JSON 格式

config/                                     # 项目根目录
└── application.conf                        # HOCON 配置文件
```

### Pattern 1: ApplicationConfig — HOCON 加载与数据类映射

**What:** 使用 Typesafe Config 加载 `config/application.conf`，通过环境变量覆盖敏感字段，映射到 Kotlin data class。这是 Profile3 驱动的统一配置入口。

**When to use:** 所有从配置文件读取参数的地方，都必须通过 ApplicationConfig 获取，禁止散落在各处硬编码。

**Example (HOCON):**
```hocon
// Source: 设计文档 9.2~9.5 参数汇总 + D-02/D-03/D-04
env = ${?ENV}              # 默认 dev，通过 ENV 环境变量覆盖

server {
  port = 9090
  port = ${?SERVER_PORT}
}

snowflake {
  worker-id = 1
  worker-id = ${?SNOWFLAKE_WORKER_ID}
  epoch = 1700000000000    # 2023-11-15 零点
}

database {
  host = "127.0.0.1"
  port = 3306
  database = "nebula"
  username = "root"
  password = ""
  password = ${?DB_PASSWORD}
  pool-size = 20
  min-idle = 5
  connection-timeout = 30000
  idle-timeout = 600000
  max-lifetime = 1800000
  leak-detection-threshold = 10000
}

ssl {
  enabled = false          # 开发环境默认关闭
  cert-chain-path = "config/dev/ssl/fullchain.pem"
  cert-chain-path = ${?SSL_CERT_CHAIN_PATH}
  private-key-path = "config/dev/ssl/privkey.pem"
  private-key-path = ${?SSL_PRIVATE_KEY_PATH}
}
```

**Example (Kotlin data class):**
```kotlin
// Source: D-04 决策 — 统一 ApplicationConfig
data class ApplicationConfig(
    val env: String,
    val server: ServerConfig,
    val snowflake: SnowflakeConfig,
    val database: DatabaseConfig,
    val ssl: SslConfig
)

data class ServerConfig(val port: Int) {
    fun buildSslContext(): SslContext? {
        // Phase 2 中 SslContext 构建逻辑 (见 Pattern 2)
    }
}

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val poolSize: Int,
    val minIdle: Int,
    val connectionTimeout: Long,
    val idleTimeout: Long,
    val maxLifetime: Long,
    val leakDetectionThreshold: Long
)

data class SnowflakeConfig(
    val workerId: Long,
    val epoch: Long
)

data class SslConfig(
    val enabled: Boolean,
    val certChainPath: String,
    val privateKeyPath: String
)
```

### Pattern 2: SslContext 构建 — SslProvider.OPENSSL + TLSv1.2/1.3

**What:** 使用 `GrpcSslContexts` 构建 SslContext，开发环境用自签证书，生产环境用 Let's Encrypt。SSL 关闭时可返回 null 让 Netty 使用明文。

**When to use:** `NettyServerBuilder.sslContext()` 调用时。

**Example:**
```kotlin
// Source: 设计文档 9.3 + GrpcSslContexts API
// D-10: 生产环境 Let's Encrypt 证书路径通过环境变量配置
fun SslConfig.buildSslContext(): SslContext? {
    if (!enabled) return null
    val certChain = File(certChainPath)
    val privateKey = File(privateKeyPath)
    return GrpcSslContexts
        .forServer(certChain, privateKey)
        .sslProvider(SslProvider.OPENSSL)     // 性能最优
        .protocols("TLSv1.2", "TLSv1.3")
        .ciphers(
            listOf(
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            ),
            Security.getProvider("SunJSSE")
        )
        .build()
}
```

**生成自签证书 (OpenSSL 3.x 语法):**

```bash
# Source: D-08 generate-dev-cert.sh
# OpenSSL 3.x 使用 -addext 替代传统 -extensions 文件方式
mkdir -p config/dev/ssl
openssl req -x509 \
    -newkey rsa:2048 \
    -keyout config/dev/ssl/privkey.pem \
    -out config/dev/ssl/fullchain.pem \
    -days 3650 \
    -nodes \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

### Pattern 3: SnowflakeIdGenerator — 64-bit 唯一 ID

**What:** 标准雪花算法，41位时间戳 + 10位 Worker ID + 12位序列号。处理时钟回拨和序列溢出两种异常。

**When to use:** 任何需要生成全局唯一 64 位 ID 的地方（用户 ID、消息 ID、会话 ID 等）。

**Example:**
```kotlin
// Source: 设计文档 9.4 + D-13/D-14
class SnowflakeIdGenerator(
    private val workerId: Long,
    private val epoch: Long = 1700000000000L  // 2023-11-15 00:00:00 UTC
) {
    private val workerIdBits = 10L
    private val sequenceBits = 12L
    private val maxWorkerId = (1L shl workerIdBits) - 1   // 1023
    private val sequenceMask = (1L shl sequenceBits) - 1   // 4095
    private val workerIdShift = sequenceBits               // 12
    private val timestampShift = sequenceBits + workerIdBits // 22

    private var lastTimestamp = -1L
    private var sequence = 0L

    init {
        require(workerId in 0..maxWorkerId) {
            "Worker ID must be 0..$maxWorkerId, got $workerId"
        }
    }

    @Synchronized
    fun nextId(): Long {
        var timestamp = System.currentTimeMillis()
        if (timestamp < lastTimestamp) {
            val diff = lastTimestamp - timestamp
            throw ClockBackwardsException(
                "Clock moved backwards $diff ms from $lastTimestamp to $timestamp"
            )
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) and sequenceMask
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp)
            }
        } else {
            sequence = 0L
        }
        lastTimestamp = timestamp
        return ((timestamp - epoch) shl timestampShift) or
               (workerId shl workerIdShift) or
               sequence
    }

    @Synchronized
    private fun waitNextMillis(lastTimestamp: Long): Long {
        var timestamp = System.currentTimeMillis()
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis()
        }
        return timestamp
    }
}
```

### Pattern 4: NettyServerBuilder — gRPC 服务启动

**What:** 使用 gRPC 内置的 `NettyServerBuilder` 启动服务，配置端口、SSL/TLS、流控、keepalive。不需要直接使用 Netty API。

**When to use:** server 模块的 `ChatServer.start()` 中。

**Example:**
```kotlin
// Source: 设计文档 9.2 + D-09/D-10
// CITED: grpc.github.io/grpc-java/javadoc/io/grpc/netty/NettyServerBuilder.html
class ChatServer(
    private val config: ServerConfig,
    private val grpcService: BindableService  // ChatGatewayImpl (Phase 4)
) {
    private var server: Server? = null

    fun start() {
        val builder = NettyServerBuilder
            .forPort(config.port)
            .addService(grpcService)
            .maxInboundMessageSize(4 * 1024 * 1024)  // 4MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(false)

        // SSL 可选 — 通过环境变量控制是否启用
        config.buildSslContext()?.let { builder.sslContext(it) }

        server = builder.build().start()
    }

    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }

    fun blockUntilShutdown() {
        server?.awaitTermination()
    }
}
```

### Anti-Patterns to Avoid

- **不使用 grpc-netty-shaded 直接用 grpc-netty:** 会导致 Netty 版本冲突——gRPC 对 Netty 版本有严格依赖，使用 shaded 版本隔离 gRPC 的 Netty 依赖，避免与项目中其他 Netty 使用的版本不一致
- **HikariCP 直接暴露 HikariDataSource:** 违反 D-12，应通过 `DataSourceProvider` 接口返回 `javax.sql.DataSource`，给 Phase 3 多数据源预留扩展点
- **BizException 不细分领域:** 违反 D-06，单一 BizException 会在 Handler 框架中失去领域分类能力（Phase 4 的 ExceptionInterceptor 需要区分领域做不同的处理策略）
- **配置值硬编码:** 所有生产参数必须从 ApplicationConfig 读取，禁止在代码中硬编码端口、超时等值

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 64 位唯一 ID 生成 | UUID（太长、无序） | SnowflakeIdGenerator | 64 位 Long 适合数据库主键，时间有序；UUID 128 位且无序，影响 B-Tree 索引性能 |
| JDBC 连接池 | 手动管理 Connection | HikariCP | 连接池处理了连接复用、泄漏检测、超时回收、健康检查等数十个边界情况，手写不可能达到同等质量 |
| SSL/TLS SslContext | 直接操作 SSLServerSocket | GrpcSslContexts + Netty | gRPC 的 `GrpcSslContexts` 封装了 ALPN/HTTP/2 协商，手工配置极易导致 ALPN 协商失败 |
| HOCON 配置解析 | 手写 properties 解析器 | Typesafe Config | 支持环境变量插值、fallback 链、类型安全 getter、自动合并多个配置文件 |
| gRPC 传输层 | 直接用 Netty 写 HTTP/2 | gRPC NettyServerBuilder | gRPC 封装了 HTTP/2 流复用、Protobuf 编解码、keepalive、流控等数十个复杂协议细节 |

**Key insight:** 这 5 个"不要手写"的领域涉及并发安全（Snowflake）、网络协议（gRPC/SSL）、连接管理（HikariCP）等高度专业化的领域，每个都有数年甚至十数年的生产验证。手写既过于复杂也容易出错。

## Runtime State Inventory

> 本阶段为 Greenfield 基础设施构建阶段，不涉及重命名/重构/迁移。跳过此章节。

## Common Pitfalls

### Pitfall 1: Gradle 9.x 与 protobuf-gradle-plugin 0.10.0 兼容性

**What goes wrong:** 系统 Gradle 版本为 9.5.1（2026-06），protobuf-gradle-plugin 0.10.0（2026-04-20）要求 Gradle 7.6+。虽然版本要求满足，但 Gradle 9.x 可能引入了针对 DSL 的内部 API 变更。

**Why it happens:** Gradle 9.x 是比 0.10.0 发布时更新的主版本，某些 `@Incubating` API 可能在 9.x 中被移除。

**How to avoid:** 在 `./gradlew build` 之前先运行 `./gradlew :proto:generateProto` 验证 protobuf 插件正常工作。如果失败，可尝试 protobuf-gradle-plugin 的 snapshot 版本或降级 Gradle Wrapper 到 8.x。

**Warning signs:** `Task 'generateProto' not found` 或 `Could not find method protobuf()` 等 Gradle DSL 错误。

### Pitfall 2: mysql-connector-j 9.x SSL 参数变更

**What goes wrong:** 设计文档 9.5 使用 `useSSL=true&requireSSL=true`，但 mysql-connector-j 8.0.31+ 已弃用这些参数，9.x 版本完全移除。

**Why it happens:** MySQL Connector/J 从 8.0.31 开始重命名为 `mysql-connector-j`，并在后续版本中改用 `sslMode=VERIFY_IDENTITY` / `sslMode=PREFERRED` / `sslMode=DISABLED` 替代旧的 `useSSL`/`requireSSL` 布尔参数 [CITED: dev.mysql.com/doc/relnotes/connector-j/en]。

**How to avoid:** 使用正确的 JDBC URL 格式：
```kotlin
// MySQL 9.x 兼容的 JDBC URL
jdbcUrl = "jdbc:mysql://${host}:${port}/${database}?" +
          "sslMode=PREFERRED&" +
          "useUnicode=true&characterEncoding=utf8mb4&" +
          "serverTimezone=Asia/Shanghai"
```
开发环境使用 `sslMode=PREFERRED`（尝试 SSL 但不强制），生产环境使用 `sslMode=VERIFY_IDENTITY`。

**Warning signs:** `The connection property 'useSSL' has been deprecated and will be removed in a future release` 或 SSLHandshakeException。

### Pitfall 3: GrpcSslContexts 要求 netty-tcnative 在 classpath

**What goes wrong:** 使用 `SslProvider.OPENSSL` 会抛出 `UnsupportedOperationException: OpenSSL is not available`。

**Why it happens:** `SslProvider.OPENSSL` 需要 `netty-tcnative`（JNI 绑定）在 classpath 上，它自动提取平台对应的原生 OpenSSL 库 [VERIFIED: netty.io/wiki/forked-tomcat-native.html]。

**How to avoid:** 添加 `io.netty:netty-tcnative-boringssl-static:2.0.x` 依赖。`-static` 后缀表示它包含了编译好的 BoringSSL 原生库，无需系统额外安装。

**Warning signs:** `Caused by: java.lang.UnsupportedOperationException: OpenSSL is not available` 或 `Failed to load netty-tcnative`.

### Pitfall 4: Snowflake `@Synchronized` 性能瓶颈

**What goes wrong:** `@Synchronized` 是对 JVM 内建监视器锁的 Kotlin 语法糖，在高并发下（10K msg/s）会成为单点瓶颈。

**Why it happens:** 所有 `nextId()` 调用需要串行化，JVM 的 `synchronized` 在竞争激烈时会导致线程阻塞和上下文切换。

**How to avoid:** Phase 2 使用 `@Synchronized`（实现简单且正确），Phase 11 性能优化时可考虑：
1. `ReentrantLock` + 自旋（spin-wait）减少上下文切换
2. 批处理预生成（batch pre-generation）：每次生成一批 ID 缓存在线程本地
3. `LongAdder` + 分段锁模式

**Warning signs:** Phase 11 压测时发现 `SnowflakeIdGenerator.nextId()` 在火焰图中占比过高。

### Pitfall 5: Typesafe Config 路径解析与 classpath 冲突

**What goes wrong:** `ConfigFactory.load()` 默认从 classpath 根目录加载 `application.conf` 或 `application.json`，但 D-03 要求配置文件放在项目根 `config/` 目录下。

**Why it happens:** 默认的 `ConfigFactory.load()` 搜索 classpath 资源文件，而 `config/` 目录不在 classpath 上。

**How to avoid:** 使用显式文件路径加载：
```kotlin
// ConfigLoader.kt — D-03 文件路径方案
object ConfigLoader {
    fun load(configFile: String = "config/application.conf"): ApplicationConfig {
        val fileConfig = ConfigFactory.parseFile(File(configFile))
        val envOverrides = ConfigFactory.systemProperties()  // JVM 属性: -Denv=prod
        val finalConfig = envOverrides
            .withFallback(fileConfig)
            .withFallback(ConfigFactory.defaultReference())  // classpath reference.conf
            .resolve()
        return parseConfig(finalConfig)
    }
}
```

**Warning signs:** `ConfigFactory.load()` 返回空配置（使用默认值）或 `ConfigException.Missing` 错误。

## Code Examples

### ConfigLoader — Typesafe Config 加载入口

```kotlin
// Source: 基于 Typesafe Config 官方文档 (typesafe.com/config) + D-02/D-03
object ConfigLoader {
    private val log = KotlinLogging.logger {}

    fun load(configPath: String = "config/application.conf"): ApplicationConfig {
        val env = System.getenv("ENV") ?: "dev"
        log.info { "Loading config from $configPath (env=$env)" }

        val fileConfig = ConfigFactory.parseFile(File(configPath))
        val envOverrides = ConfigFactory.systemProperties()
        val finalConfig = envOverrides
            .withFallback(fileConfig)
            .withFallback(ConfigFactory.defaultReference())
            .resolve()

        return parseConfig(finalConfig, env)
    }

    private fun parseConfig(config: Config, env: String): ApplicationConfig {
        return ApplicationConfig(
            env = env,
            server = ServerConfig(port = config.getInt("server.port")),
            snowflake = SnowflakeConfig(
                workerId = config.getLong("snowflake.worker-id"),
                epoch = config.getLong("snowflake.epoch")
            ),
            database = DatabaseConfig(
                host = config.getString("database.host"),
                port = config.getInt("database.port"),
                database = config.getString("database.database"),
                username = config.getString("database.username"),
                password = config.getString("database.password"),
                poolSize = config.getInt("database.pool-size"),
                minIdle = config.getInt("database.min-idle"),
                connectionTimeout = config.getLong("database.connection-timeout"),
                idleTimeout = config.getLong("database.idle-timeout"),
                maxLifetime = config.getLong("database.max-lifetime"),
                leakDetectionThreshold = config.getLong("database.leak-detection-threshold")
            ),
            ssl = SslConfig(
                enabled = config.getBoolean("ssl.enabled"),
                certChainPath = config.getString("ssl.cert-chain-path"),
                privateKeyPath = config.getString("ssl.private-key-path")
            )
        )
    }
}
```

### HikariDataSourceProvider — DataSourceProvider 实现

```kotlin
// Source: 设计文档 9.5 + HikariCP 官方配置指南
// D-11: 单数据源实现，接口预留多数据源扩展 (Phase 3)
class HikariDataSourceProvider(
    private val config: DatabaseConfig
) : DataSourceProvider {

    private val hikariDataSource: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = buildJdbcUrl(config)
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            maxLifetime = config.maxLifetime
            leakDetectionThreshold = config.leakDetectionThreshold

            // MySQL 优化参数
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            addDataSourceProperty("rewriteBatchedStatements", "true")
        })
    }

    override fun getDataSource(): DataSource = hikariDataSource

    private fun buildJdbcUrl(config: DatabaseConfig): String {
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}?" +
                "sslMode=PREFERRED&" +
                "useUnicode=true&characterEncoding=utf8mb4&" +
                "serverTimezone=Asia/Shanghai"
    }
}
```

### BizException 体系

```kotlin
// Source: D-05/D-06/D-07 + 设计文档 6.2/8.4
// BizException — 基类
open class BizException(
    val bizCode: BizCode,
    override val message: String = bizCode.msg
) : RuntimeException(message)

// 领域子类
class UserException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)

class ChatException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)

class ConversationException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)

class FriendException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)

class MessageException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)

// Snowflake 异常
class ClockBackwardsException(msg: String) : RuntimeException(msg)
class SequenceOverflowException(msg: String) : RuntimeException(msg)

// 使用方式 (D-07)
// throw UserException(BizCode.USER_NOT_FOUND)
// throw ChatException(BizCode.SEND_FAILED)
```

### ChatServer — NettyServerBuilder 完整启动

```kotlin
// Source: 设计文档 9.2 + gRPC JavaDoc (grpc.github.io/grpc-java/javadoc/)
// D-09/D-10: SSL 通过配置启用
class ChatServer(
    private val config: ApplicationConfig
) {
    private var server: Server? = null

    fun start() {
        val sslContext = config.ssl.buildSslContext()
        val builder = NettyServerBuilder
            .forPort(config.server.port)
            .maxInboundMessageSize(4 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(false)

        sslContext?.let { builder.sslContext(it) }

        server = builder.build().start()
        println("Server started on port ${config.server.port}")
    }

    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }

    fun blockUntilShutdown() {
        server?.awaitTermination()
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| mysql-connector-java (`com.mysql.cj.jdbc.Driver`) | mysql-connector-j (`com.mysql.cj.jdbc.Driver` 不变) | 2022-10 (v8.0.31) | artifact ID 从 `mysql-connector-java` 改为 `mysql-connector-j` [VERIFIED: central.sonatype.com] |
| `useSSL=true&requireSSL=true` | `sslMode=VERIFY_IDENTITY` / `sslMode=PREFERRED` | MySQL Connector/J 8.0.x | 旧 SSL 参数已弃用，9.x 完全移除 [CITED: dev.mysql.com] |
| protobuf-gradle-plugin convention 模式 | extension 模式 | 2026-04 (v0.10.0) | 改善了 Kotlin DSL 配置体验 |
| Gradle 8.x | Gradle 9.x | ~2025-2026 | 9.x 移除了某些 `@Incubating` API，Protobuf plugin 需要验证兼容性 |

**Deprecated/outdated:**
- `mysql-connector-java` artifact：使用 `mysql-connector-j` 替代
- `useSSL` / `requireSSL` JDBC 参数：使用 `sslMode` 替代
- `generatedFilesBaseDir` in protobuf-gradle-plugin：v0.10.0 已设为只读

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | protobuf-gradle-plugin 0.10.0 与 Gradle 9.5.1 完全兼容 | Standard Stack | 不兼容需降级 Gradle Wrapper 到 8.x 或使用 protobuf plugin snapshot |
| A2 | gRPC 1.81.0 与 grpc-kotlin-stub 1.5.0 兼容 | Standard Stack | 需降级 grpc-kotlin-stub 到 1.4.x 或 gRPC 到 1.78.x |
| A3 | NettyServerBuilder 的 Netty 传输默认启用 autoFlowControl | Pitfalls | 标准行为，文档默认启用；有极端需求时需显式配置 `autoFlowControl(true)` |
| A4 | HikariCP 7.0.2 与 JDK 21 完全兼容 | Standard Stack | HikariCP 7.x 要求 Java 21+，已满足；但 7.0.2 可能存在 minor bug，可降级到 6.3.x |

## Open Questions

1. **Snowflake epoch 时间选择**
   - What we know: 设计文档 9.4 使用 `1700000000000L`（2023-11-15 00:00:00 UTC）
   - What's unclear: 是否使用这个默认 epoch 还是根据项目实际情况调整
   - Recommendation: 使用设计文档的默认值 `1700000000000L`，约可使用到 2096 年（41 位时间戳覆盖 ~69 年）

2. **server 模块依赖 common 模块 — 是否已在 build.gradle.kts 中声明**
   - What we know: 当前 `server/build.gradle.kts` 只依赖 `:gateway` 和 `:proto`
   - What's unclear: `:gateway` 是否已经传递依赖 `:service` → `:repository` → `:common`？D-15 的 ApplicationConfig 在 common 模块，server 需要直接构建 ApplicationConfig 因此需要直接依赖 `:common`
   - Recommendation: server 应该显式依赖 `:common` 模块（即使依赖链可传递，显式更清晰）

3. **logback 配置文件加载方式**
   - What we know: logback.xml 放在 common 模块的 resources/ 下（D-16），通过 system property `logback.configurationFile` 加载
   - What's unclear: server 模块启动时如何确保 logback.system property 在 ConfigLoader 之前设置
   - Recommendation: 在 `NebulaServer.kt` 的 `main()` 函数最顶部设置 `System.setProperty("logback.configurationFile", "logback-$env.xml")`，然后再加载配置

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JDK) | Kotlin 编译 + gRPC | ✓ | JDK 21.0.11 | — |
| Gradle | 构建系统 | ✓ (via wrapper) | 9.5.1 | — |
| OpenSSL | 生成自签证书 | ✓ | 3.6.2 | 无 — 必需，但已安装 |
| MySQL | HikariCP 连接池验证 | ✗ (开发用) | — | Phase 2 不需要运行 MySQL，连接池创建延迟到 Phase 3 |

**Missing dependencies with no fallback:** MySQL service 不在 Phase 2 路线上 — HikariCP 的 `HikariDataSource` 对象创建和配置不需要实际 MySQL 连接，只有 `getConnection()` 时才需要数据库可达。

**Missing dependencies with fallback:** 无 — 所有依赖均可满足。

## Validation Architecture

> SKIPPED: `workflow.nyquist_validation` is explicitly `false` in `.planning/config.json`. 本阶段不需要测试框架。

## Security Domain

> `security_enforcement` 未在 config.json 中显式设置（absent = enabled），但本阶段为基础构建不涉及运行时安全逻辑。以下 ASVS 分类列出与 Phase 2 交付物相关的安全维度。

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (Phase 5) | — |
| V3 Session Management | No (Phase 5) | — |
| V4 Access Control | No (Phase 5) | — |
| V5 Input Validation | No (Snowflake ID 是内部生成，非用户输入) | — |
| V6 Cryptography | Partial (SSL/TLS 配置) | SslProvider.OPENSSL, TLSv1.2/1.3, 限制密码套件到 ECDHE + AES-GCM |
| V10 Communication Security | Yes (SSL/TLS) | gRPC 双向流加密；GrpcSslContexts 确保 ALPN/HTTP/2 协商安全 |

### Known Threat Patterns for Phase 2

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SSL/TLS 证书过期导致连接失败 | Denial of Service | 自签证书 10 年有效期（开发环境）；Let's Encrypt 自动续期（生产，不在 Phase 2 范围内） |
| 时钟回拨导致 ID 冲突 | Tampering | ClockBackwardsException 抛出即终止生成（D-13），确保不会生成重复 ID |
| 连接池泄漏 | Denial of Service | leakDetectionThreshold=10s，HikariCP 自动检测未归还连接并打印警告日志 |
| SNOWFLAKE_WORKER_ID 配置错误 | Tampering | `init` 块中 `require(workerId in 0..maxWorkerId)` 在构造时立即验证 |
| 明文通信（SSL 未启用） | Information Disclosure | 生产环境应设置 `ssl.enabled=true`；开发环境自签证书默认提供 TLS 加密 |

## Sources

### Primary (HIGH confidence)
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/09-基础设施设计/9.2-gRPC-Netty启动.md` — NettyServerBuilder 启动参数
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/09-基础设施设计/9.3-SSL-TLS.md` — SslContext 构建代码
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/09-基础设施设计/9.4-雪花算法.md` — SnowflakeIdGenerator 完整实现
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/09-基础设施设计/9.5-HikariCP连接池.md` — HikariCP 参数配置
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/06-接口方法列表/6.2-错误码.md` — BizCode 枚举定义
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/08-Handler层设计/8.4-异常处理.md` — BizException 定义
- [sonatype.com] `https://central.sonatype.com/artifact/com.typesafe/config` — Typesafe Config 1.4.9 最新版本
- [mvnrepository.com] `https://mvnrepository.com/artifact/io.grpc/grpc-netty-shaded` — gRPC 1.81.0 版本
- [mvnrepository.com] `https://mvnrepository.com/artifact/com.zaxxer/HikariCP` — HikariCP 7.0.2 版本
- [mvnrepository.com] `https://mvnrepository.com/artifact/io.grpc/grpc-kotlin-stub` — grpc-kotlin-stub 1.5.0 版本

### Secondary (MEDIUM confidence)
- [plugins.gradle.org] `https://plugins.gradle.org/plugin/com.google.protobuf` — 确认 protobuf plugin 0.10.0 兼容性
- [grpc.github.io] `https://grpc.github.io/grpc-java/javadoc/io/grpc/netty/NettyServerBuilder.html` — NettyServerBuilder API 参考
- [grpc.github.io] `https://grpc.github.io/grpc-java/javadoc/io/grpc/netty/GrpcSslContexts.html` — GrpcSslContexts API 参考
- [dev.mysql.com] MySQL Connector/J Release Notes — SSL 参数变更说明

### Tertiary (LOW confidence)
- HikariCP 7.0.2 与 Java 21 的完全兼容性 — 基于 HikariCP 7.x Java 21+ 要求推断，未找到具体 issue

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 版本号来自 Maven Central 和官方仓库，环境经过验证
- Architecture: HIGH — 设计文档 9.2~9.5 提供实现级代码，CONTEXT.md 有 18 个决策项约束
- Pitfalls: HIGH — 来自设计文档和实际项目经验（MySQL 9.x SSL 参数变更、netty-tcnative 缺失等）
- BizException: HIGH — 设计文档 8.4 和 6.2 已完整定义

**Research date:** 2026-06-11
**Valid until:** 2026-07-11（30 天 — 版本号可能更新，但架构模式和设计文档保持稳定）
