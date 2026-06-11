# Phase 02: Common Module & Infrastructure Base — Pattern Map

**Mapped:** 2026-06-11
**Files analyzed:** 28 (10 new common files, 3 new server files, 4 build config changes, 4 config assets, 2 logback configs, 5 exception classes)
**Analogs found:** 27 / 28 (1 script is shell, no analog needed)

**Key note:** This is essentially a **greenfield infrastructure build**. The only existing Kotlin file is `BizCode.kt` (Phase 1). Most patterns come from design documents 9.2–9.5 rather than existing codebase analog files. The `BizCode.kt` file establishes the project's Kotlin conventions and is the closest analog for all new Kotlin files.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `common/…/config/ApplicationConfig.kt` | config | static | `BizCode.kt` | style-match |
| `common/…/config/ServerConfig.kt` | config | static | `BizCode.kt` | style-match |
| `common/…/config/DatabaseConfig.kt` | config | static | `BizCode.kt` | style-match |
| `common/…/config/SnowflakeConfig.kt` | config | static | `BizCode.kt` | style-match |
| `common/…/config/SslConfig.kt` | config + utility | static | `BizCode.kt` | style-match |
| `common/…/datasource/DataSourceProvider.kt` | utility | request-response | `BizCode.kt` | style-match |
| `common/…/datasource/HikariDataSourceProvider.kt` | utility | CRUD | `BizCode.kt` | style-match |
| `common/…/exception/BizException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/UserException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/ChatException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/ConversationException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/FriendException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/MessageException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/ClockBackwardsException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/exception/SequenceOverflowException.kt` | utility | static | `BizCode.kt` | style-match |
| `common/…/idgen/SnowflakeIdGenerator.kt` | utility | CRUD | 设计文档 9.4 + `BizCode.kt` | spec-match |
| `server/…/NebulaServer.kt` | entrypoint | static | `BizCode.kt` | style-match |
| `server/…/config/ConfigLoader.kt` | config | static | `BizCode.kt` | style-match |
| `server/…/server/ChatServer.kt` | controller | request-response | 设计文档 9.2 | spec-match |
| `common/build.gradle.kts` | build config | — | existing `common/build.gradle.kts` | exact |
| `server/build.gradle.kts` | build config | — | existing `server/build.gradle.kts` | exact |
| `gradle/libs.versions.toml` | build config | — | existing `gradle/libs.versions.toml` | exact |
| `common/src/main/resources/logback-dev.xml` | config | static | 无 (greenfield) | — |
| `common/src/main/resources/logback-prod.xml` | config | static | 无 (greenfield) | — |
| `config/application.conf` | config | static | 设计文档 9.2~9.5 参数汇总 | spec-match |
| `config/dev/ssl/generate-dev-cert.sh` | script | — | 无 (shell 脚本) | — |

## Pattern Assignments

### `common/…/config/ApplicationConfig.kt` (config, static)

**Analog:** `common/…/BizCode.kt` (仅 Kotlin 风格)

**Imports pattern** (lines 1-2):
```kotlin
package com.nebula.common.config
```

**Core pattern — 嵌套 data class + 扩展函数** (参考 RESEARCH.md Pattern 1 + D-04):
```kotlin
// ApplicationConfig.kt — 统一的配置数据类 (D-04)
// 所有子段: ServerConfig, DatabaseConfig, SnowflakeConfig, SslConfig
data class ApplicationConfig(
    val env: String,
    val server: ServerConfig,
    val snowflake: SnowflakeConfig,
    val database: DatabaseConfig,
    val ssl: SslConfig
)
```

**Style conventions** (从 `BizCode.kt` lines 1-3):
```kotlin
package com.nebula.common.config
// Kotlin 标准风格，无特殊注解
// 使用 data class 而非普通 class
// 属性在构造函数中声明
```

---

### `common/…/config/ServerConfig.kt` (config, static)

**Analog:** `BizCode.kt` (Kotlin 风格)

**Core pattern:**
```kotlin
package com.nebula.common.config

data class ServerConfig(
    val port: Int
)
```

---

### `common/…/config/DatabaseConfig.kt` (config, static)

**Analog:** `BizCode.kt` (Kotlin 风格)

**Core pattern:**
```kotlin
package com.nebula.common.config

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
```

---

### `common/…/config/SnowflakeConfig.kt` (config, static)

**Analog:** `BizCode.kt` (Kotlin 风格)

**Core pattern:**
```kotlin
package com.nebula.common.config

data class SnowflakeConfig(
    val workerId: Long,
    val epoch: Long
)
```

---

### `common/…/config/SslConfig.kt` (config + utility, static)

**Analog:** `BizCode.kt` (基本 Kotlin 风格中的 `companion object` 模式)

**Imports pattern** (RESEARCH.md Pattern 2 + 设计文档 9.3):
```kotlin
package com.nebula.common.config

import io.grpc.netty.GrpcSslContexts
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslProvider
import java.io.File
import java.security.Security
```

**Core pattern — 扩展函数** (设计文档 9.3 + RESEARCH.md Pattern 2):
```kotlin
// SslConfig.kt — 证书配置 + SslContext 构建 (D-08/D-09/D-10)
data class SslConfig(
    val enabled: Boolean,
    val certChainPath: String,
    val privateKeyPath: String
)

// 扩展函数构建 SslContext — SSL 关闭时返回 null
fun SslConfig.buildSslContext(): SslContext? {
    if (!enabled) return null
    val certChain = File(certChainPath)
    val privateKey = File(privateKeyPath)
    return GrpcSslContexts
        .forServer(certChain, privateKey)
        .sslProvider(SslProvider.OPENSSL)
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

---

### `common/…/datasource/DataSourceProvider.kt` (utility, request-response)

**Analog:** `BizCode.kt` (Kotlin 风格接口)

**Imports pattern:**
```kotlin
package com.nebula.common.datasource

import javax.sql.DataSource
```

**Core pattern — 接口** (D-12)
```kotlin
// DataSourceProvider.kt — 数据源接口 (D-12)
// Phase 3 扩展为路由数据源时新增实现类
interface DataSourceProvider {
    fun getDataSource(): DataSource
}
```

---

### `common/…/datasource/HikariDataSourceProvider.kt` (utility, CRUD)

**Analog:** `BizCode.kt` (Kotlin 风格)

**Imports pattern** (RESEARCH.md Pattern + 设计文档 9.5):
```kotlin
package com.nebula.common.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
```

**Core pattern — DataSourceProvider 实现** (D-11/D-12 + Pitfall 2 SSL 参数修正):
```kotlin
// HikariDataSourceProvider.kt — 单数据源 HikariCP 实现 (D-11)
// 通过接口 getDataSource() 返回，非直接暴露 HikariDataSource (D-12)
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
        // Pitfall 2: mysql-connector-j 9.x 使用 sslMode 替代 useSSL/requireSSL
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}?" +
                "sslMode=PREFERRED&" +
                "useUnicode=true&characterEncoding=utf8mb4&" +
                "serverTimezone=Asia/Shanghai"
    }
}
```

---

### `common/…/exception/BizException.kt` (utility, static)

**Analog:** `BizCode.kt` — `common/…/BizCode.kt` lines 1-4 (同包、import 风格)

**Imports pattern** (BizCode.kt lines 1-2):
```kotlin
package com.nebula.common.exception

import com.nebula.common.BizCode
```

**Core pattern — 开放基类** (D-05/D-07 + RESEARCH.md BizException 体系):
```kotlin
// BizException.kt — 统一业务异常基类 (D-05)
// 与 BizCode.kt 联合使用 (D-07: throw BizException(BizCode.USER_NOT_FOUND))
open class BizException(
    val bizCode: BizCode,
    override val message: String = bizCode.msg
) : RuntimeException(message)
```

---

### `common/…/exception/UserException.kt` (utility, static)

**Analog:** `BizCode.kt` (类定义风格)

**Core pattern** (D-06: 按领域细分):
```kotlin
package com.nebula.common.exception

import com.nebula.common.BizCode

class UserException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)
```

---

### `common/…/exception/ChatException.kt` (utility, static)

**Pattern** (与 UserException 完全相同结构):
```kotlin
package com.nebula.common.exception

import com.nebula.common.BizCode

class ChatException(bizCode: BizCode, msg: String = bizCode.msg)
    : BizException(bizCode, msg)
```

---

### `common/…/exception/ConversationException.kt` (utility, static)

**Pattern:** 同上，类名改为 `ConversationException`

---

### `common/…/exception/FriendException.kt` (utility, static)

**Pattern:** 同上，类名改为 `FriendException`

---

### `common/…/exception/MessageException.kt` (utility, static)

**Pattern:** 同上，类名改为 `MessageException`

---

### `common/…/exception/ClockBackwardsException.kt` (utility, static)

**Core pattern** (D-13 + 设计文档 9.4):
```kotlin
package com.nebula.common.exception

// Snowflake 时钟回拨异常 — 抛给上层，不自动恢复 (D-13)
// 由 K8s 健康检查 kill Pod 后自动重建
class ClockBackwardsException(msg: String) : RuntimeException(msg)
```

---

### `common/…/exception/SequenceOverflowException.kt` (utility, static)

**Core pattern** (D-14: waitNextMillis 自愈，不抛给调用方):
```kotlin
package com.nebula.common.exception

// Snowflake 序列溢出异常 — 内部使用，waitNextMillis 自愈 (D-14)
// 不抛给调用方，仅用于 SnowflakeIdGenerator 内部跟踪
class SequenceOverflowException(msg: String) : RuntimeException(msg)
```

---

### `common/…/idgen/SnowflakeIdGenerator.kt` (utility, CRUD)

**Analog:** 设计文档 9.4 (实现级代码) + `BizCode.kt` (Kotlin 风格)

**Imports pattern:**
```kotlin
package com.nebula.common.idgen

import com.nebula.common.exception.ClockBackwardsException
```

**Core pattern — SnowflakeIdGenerator** (设计文档 9.4 + D-13/D-14):
```kotlin
// SnowflakeIdGenerator.kt — 64 位唯一 ID 生成器
// 位分配: 41 位时间戳 | 10 位 Worker ID | 12 位序列号
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
        // 时钟回拨检测 — 直接抛异常 (D-13)
        if (timestamp < lastTimestamp) {
            val diff = lastTimestamp - timestamp
            throw ClockBackwardsException(
                "Clock moved backwards $diff ms from $lastTimestamp to $timestamp"
            )
        }
        // 同一毫秒: 序列号递增 (D-14)
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

---

### `server/…/NebulaServer.kt` (entrypoint, static)

**Analog:** `BizCode.kt` (Kotlin 风格 — package, imports)

**Imports pattern:**
```kotlin
package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.DataSourceProvider
import com.nebula.common.datasource.HikariDataSourceProvider
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer
```

**Core pattern — main 函数** (D-18 + RESEARCH.md 启动流程):
```kotlin
// NebulaServer.kt — 应用入口 (D-18: 日志配置先于配置加载)
fun main() {
    val env = System.getenv("ENV") ?: "dev"

    // D-18: logback 配置必须在 ConfigLoader 之前设置
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    val config: ApplicationConfig = ConfigLoader.load()

    val snowflakeIdGenerator = SnowflakeIdGenerator(config.snowflake.workerId, config.snowflake.epoch)
    val dataSourceProvider: DataSourceProvider = HikariDataSourceProvider(config.database)

    // SSL: 通过 SslConfig 扩展函数构建 (RESEARCH.md Pattern 2)
    val chatServer = ChatServer(config)
    chatServer.start()
    chatServer.blockUntilShutdown()
}
```

---

### `server/…/config/ConfigLoader.kt` (config, static)

**Analog:** `BizCode.kt` (Kotlin 风格) + Typesafe Config API

**Imports pattern** (RESEARCH.md 示例 + Pitfall 5 解决方案):
```kotlin
package com.nebula.server.config

import com.nebula.common.config.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
```

**Core pattern — HOCON 加载** (D-02/D-03/D-04 + Pitfall 5):
```kotlin
// ConfigLoader.kt — HOCON 配置加载入口 (D-02/D-03)
// 使用显式文件路径，避免 classpath 歧义 (Pitfall 5)
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

---

### `server/…/server/ChatServer.kt` (controller, request-response — gRPC)

**Analog:** 设计文档 9.2 (实现级代码)

**Imports pattern** (RESEARCH.md Pattern 4 + 设计文档 9.2):
```kotlin
package com.nebula.server.server

import com.nebula.common.config.ApplicationConfig
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
```

**Core pattern — NettyServerBuilder** (设计文档 9.2 + D-09/D-10):
```kotlin
// ChatServer.kt — gRPC Netty 服务启动入口 (INFRA-02)
// 集成 SSL/TLS、流控、keepalive
class ChatServer(
    private val config: ApplicationConfig
) {
    private var server: Server? = null

    fun start() {
        val sslContext = config.ssl.buildSslContext()
        val builder = NettyServerBuilder
            .forPort(config.server.port)
            .maxInboundMessageSize(4 * 1024 * 1024)   // 4MB
            .keepAliveTime(30, TimeUnit.SECONDS)       // 服务端 keepalive
            .keepAliveTimeout(10, TimeUnit.SECONDS)    // 等待客户端应答超时
            .permitKeepAliveWithoutCalls(false)        // 仅活跃 RPC 可 keepalive

        sslContext?.let { builder.sslContext(it) }     // SSL 可选

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

---

### Build Config Changes

#### `gradle/libs.versions.toml` (modified — 新增依赖声明)

**Analog:** `existing gradle/libs.versions.toml` — 完全按照现有格式扩展

**Existing pattern** (lines 1-17):
```toml
[versions]
kotlin = "2.1.20"
protobuf = "4.29.3"
# ... 其他版本

[libraries]
protobuf-java = { group = "com.google.protobuf", name = "protobuf-java", version.ref = "protobuf" }
# ... 其他库

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
protobuf = { id = "com.google.protobuf", version.ref = "protobuf-plugin" }
```

**New entries to add** (RESEARCH.md Standard Stack):
```toml
[versions]
typesafe-config = "1.4.9"
hikaricp = "7.0.2"
mysql-connector = "9.2.0"
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

---

#### `common/build.gradle.kts` (modified — 新增依赖)

**Analog:** `existing common/build.gradle.kts` lines 14-18

**Existing pattern:**
```kotlin
dependencies {
    implementation(project(":proto"))
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}
```

**Modified to add** (Phase 2 新增):
```kotlin
dependencies {
    implementation(project(":proto"))
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Phase 2: 基础设施
    implementation(libs.typesafe.config)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)
}
```

---

#### `server/build.gradle.kts` (modified — 新增依赖)

**Analog:** `existing server/build.gradle.kts` lines 19-22

**Existing pattern:**
```kotlin
dependencies {
    implementation(project(":gateway"))
    implementation(project(":proto"))
}
```

**Modified to add** (Phase 2 新增):
```kotlin
dependencies {
    implementation(project(":gateway"))
    implementation(project(":proto"))
    implementation(project(":common"))   // 新增：ApplicationConfig + 基础设施

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

---

### `config/application.conf` (HOCON config, static)

**Analog:** 设计文档 9.2~9.5 参数汇总

**Core pattern** (D-02/D-03 + RESEARCH.md HOCON 示例):
```hocon
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

### `common/src/main/resources/logback-dev.xml` (config, static)

**Pattern** (D-17: DEBUG + 彩色控制台):
```xml
<!-- logback-dev.xml — 开发环境: DEBUG 级别 + 彩色控制台 (D-17) -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%thread] %cyan(%logger{36}) - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

### `common/src/main/resources/logback-prod.xml` (config, static)

**Pattern** (D-17: INFO + JSON 格式):
```xml
<!-- logback-prod.xml — 生产环境: INFO + JSON 格式 (D-17) -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.classic.encoder.JsonEncoder"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

### `config/dev/ssl/generate-dev-cert.sh` (shell script)

**Pattern** (D-08 + OpenSSL 3.x 语法 — RESEARCH.md):
```bash
#!/bin/bash
# generate-dev-cert.sh — 开发环境自签证书 (D-08)
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

---

## Shared Patterns

### 1. Kotlin 包和代码风格
**Source:** `common/…/BizCode.kt` lines 1-3
**Apply to:** 所有新 Kotlin 文件

```kotlin
package com.nebula.common.xxx   // 按 D-15 定义的子包

// Kotlin 标准风格:
// - 使用 data class 而非普通 class (数据载体)
// - 属性在构造函数中声明
// - init { require() } 做参数验证
// - @Synchronized 做线程安全 (SnowflakeIdGenerator)
```

### 2. 异常体系 — 领域细分 + 直接 throw
**Source:** `common/…/BizCode.kt` lines 3-38 + D-06/D-07
**Apply to:** 所有 `exception/` 下的文件

```kotlin
// D-06: 每个领域有独立的异常子类
// D-07: 直接 throw UserException(BizCode.USER_NOT_FOUND)
// 
// BizException.kt — 基类
// UserException.kt — extends BizException
// ChatException.kt — extends BizException
// 等
//
// ClockBackwardsException — extends RuntimeException (非 BizException 体系)
// SequenceOverflowException — extends RuntimeException (非 BizException 体系)
```

### 3. HOCON 配置 — 环境变量覆盖
**Source:** RESEARCH.md `application.conf` 示例
**Apply to:** `config/application.conf` + `ConfigLoader.kt`

```hocon
# D-02: 单文件 + 环境变量覆盖模式
key = default_value
key = ${?ENV_VAR}       # 环境变量优先级最高
```

### 4. 构建配置 — Version Catalog + Kotlin DSL
**Source:** `gradle/libs.versions.toml` lines 1-17
**Apply to:** `libs.versions.toml` (修改), `common/build.gradle.kts` (修改), `server/build.gradle.kts` (修改)

```toml
[versions]
# D-13: 所有版本通过 Version Catalog 管理
lib-name = "x.y.z"

[libraries]
lib-name = { module = "group:artifact", version.ref = "lib-name" }
```

### 5. 启动顺序 — 日志先于一切
**Source:** RESEARCH.md Open Questions #3
**Apply to:** `NebulaServer.kt`

```kotlin
fun main() {
    // Step 1: 设置日志配置 (D-18)
    System.setProperty("logback.configurationFile", "logback-$env.xml")
    
    // Step 2: 加载配置
    val config = ConfigLoader.load()
    
    // Step 3: 初始化基础设施
    val snowflake = SnowflakeIdGenerator(...)
    val dataSource = HikariDataSourceProvider(...)
    
    // Step 4: 启动 gRPC 服务
    val server = ChatServer(config)
    server.start()
    server.blockUntilShutdown()
}
```

---

## No Analog Found

Files with no close match in the codebase (planner should use RESEARCH.md / design document patterns instead):

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `common/src/main/resources/logback-dev.xml` | config | static | 项目第一个 XML 配置文件 |
| `common/src/main/resources/logback-prod.xml` | config | static | 项目第一个 XML 配置文件 |
| `config/dev/ssl/generate-dev-cert.sh` | script | — | Shell 脚本，非代码 |
| `config/application.conf` | config | static | 项目第一个 HOCON 配置文件 |

这些文件使用标准格式（logback XML 标准、OpenSSL CLI、HOCON 标准），无需代码库内参考。

---

## Metadata

**Analog search scope:**
- `common/src/main/kotlin/com/nebula/common/` (仅 BizCode.kt)
- `gradle/libs.versions.toml` (Version Catalog)
- `common/build.gradle.kts`, `server/build.gradle.kts`
- 设计文档 `09-基础设施设计/9.2~9.5` 作为实现参考

**Files scanned:** 4 existing project files + 4 design documents

**Pattern extraction date:** 2026-06-11
