# Phase 02: Common Module & Infrastructure Base - Context

**Gathered:** 2026-06-11
**Status:** Ready for planning

<domain>
## Phase Boundary

构建共享基础设施层，为所有后续阶段提供基础能力支持。具体交付：

1. **SnowflakeIdGenerator** — 64 位唯一 ID 生成器，支持自定义 Worker ID 和时钟回拨处理
2. **HikariCP 连接池** — MySQL 连接池配置，封装 DataSourceProvider 接口
3. **SSL/TLS 证书方案** — 开发环境自签名 + 生产环境 Let's Encrypt 双模式
4. **Netty Server Bootstrap** — gRPC 服务启动入口（端口、keepalive、流控、消息大小）
5. **BizException** — 统一业务异常定义，与 BizCode 枚举配合使用
6. **统一配置管理** — Typesafe Config (HOCON) 格式的配置加载和管理

**Requirements:** INFRA-02, INFRA-03, INFRA-04, INFRA-05

</domain>

<decisions>
## Implementation Decisions

### 配置管理策略

- **D-01:** 配置格式使用 **Typesafe Config (HOCON)**，类型安全、零额外重量级框架依赖
- **D-02:** 采用 **单文件 + 环境变量覆盖** 模式。一份 `application.conf`，通过 `ENV` 环境变量或 JVM 属性切换 dev/prod 环境
- **D-03:** 配置文件放在项目根 `config/` 目录下，**提交 git 版本控制**。生产敏感凭据（如数据库密码）通过环境变量注入
- **D-04:** 所有配置合并为一个 **统一的 `ApplicationConfig` 数据类**，放在 common 模块的 `com.nebula.common.config` 包下，包含子段：`ServerConfig`、`DatabaseConfig`、`SnowflakeConfig`、`SslConfig`

### BizException 异常体系

- **D-05:** BizException 放在 **common 模块** `com.nebula.common.exception` 包下，与 BizCode 同层
- **D-06:** 按领域细分异常类：`UserException` / `ChatException` / `ConversationException` / `FriendException` / `MessageException` 等，统一继承自 `BizException` 基类
- **D-07:** 使用方式：**直接 throw** `UserException(BizCode.USER_NOT_FOUND)`，不走扩展函数

### SSL/TLS 证书管理

- **D-08:** 开发环境自签证书通过 **`generate-dev-cert.sh` 脚本** 一次性生成
- **D-09:** 证书文件（`fullchain.pem` + `privkey.pem`）**提交 git**，放在 `config/dev/ssl/` 目录下
- **D-10:** 生产环境使用 **Let's Encrypt** 或云平台托管证书，证书路径通过环境变量配置

### HikariCP 连接池

- **D-11:** Phase 2 先实现 **单数据源**，不提前预留主从骨架。多数据源在 Phase 3 按需引入
- **D-12:** 封装为 **`DataSourceProvider` 接口**，提供 `getDataSource(): DataSource`，非直接暴露 `HikariDataSource`。接口预留未来扩展多数据源的能力

### Snowflake 时钟回拨

- **D-13:** 检测到时钟回拨时直接**抛异常 + 手动重启**。单节点无自动恢复机制
- **D-14:** 区分两种异常：**`ClockBackwardsException`**（系统时钟回拨，抛给上层）和 **`SequenceOverflowException`**（同一毫秒序列号耗尽，`waitNextMillis()` 自愈，不抛给调用方）

### Common 模块包结构

- **D-15:** 按功能分包：
  - `com.nebula.common.config` — ApplicationConfig、HOCON 加载
  - `com.nebula.common.exception` — BizException 及子类
  - `com.nebula.common.idgen` — SnowflakeIdGenerator
  - `com.nebula.common.util` — 工具类

### 日志配置

- **D-16:** `logback.xml` 配置文件放在 **common 模块**的 `src/main/resources/` 下
- **D-17:** 分别配置 `logback-dev.xml`（DEBUG、彩色控制台）和 `logback-prod.xml`（INFO、JSON 格式）
- **D-18:** server 模块启动时通过 `logback.configurationFile` 系统属性加载对应环境日志配置

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 架构设计文档 (Phase 2 核心实现依据)

- `设计文档/后端架构设计v1.2/09-基础设施设计/9.2-gRPC-Netty启动.md` — NettyServerBuilder 启动参数（端口、keepalive、流控、消息大小）
- `设计文档/后端架构设计v1.2/09-基础设施设计/9.3-SSL-TLS.md` — SSL SslContext 构建（SslProvider.OPENSSL、TLSv1.2/1.3、密码套件）
- `设计文档/后端架构设计v1.2/09-基础设施设计/9.4-雪花算法.md` — SnowflakeIdGenerator 完整实现（位分配、Worker ID、时钟回拨）
- `设计文档/后端架构设计v1.2/09-基础设施设计/9.5-HikariCP连接池.md` — HikariCP 参数配置（连接池大小、超时、泄漏检测）

### 前期阶段上下文

- `.planning/phases/01-project-scaffolding-proto-definitions/01-CONTEXT.md` — Phase 1 上下文
  - D-10: ErrorCode 用 Kotlin BizCode 枚举在 common 模块
  - D-13: 依赖版本通过 Gradle Version Catalog 管理
  - D-14: kotlin-logging + SLF4J

### 项目规划文档

- `.planning/PROJECT.md` — 项目概览、技术栈、约束条件
- `.planning/REQUIREMENTS.md` — 70 个 v1 需求映射与 Traceability
- `.planning/ROADMAP.md` — 11 阶段路线图与依赖关系

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- `common/src/main/kotlin/com/nebula/common/BizCode.kt` — 已有 30 个业务错误码枚举，BizException 直接关联使用
- 设计文档 9.2~9.5 提供**实现级代码**，可直接参考或按 Kotlin 风格调整后使用

### Established Patterns

- `:common` 模块已依赖 `:proto`（Phase 1 产物），构建已通过
- Kotlin DSL + Gradle Version Catalog (`libs.versions.toml`) 管理依赖版本
- 包命名 `com.nebula.common.*` 已确立

### Integration Points

- **`:common` 模块**是 `:repository`、`:service`、`:gateway`、`:server` 的底层依赖
- **`ApplicationConfig`** 在 `:server` 模块加载后注入各模块（Koin 在 Phase 4 引入，在此之前通过构造函数传参）
- **`DataSourceProvider`** 供 `:repository` 模块在 Phase 3 使用
- **NettyServerBuilder** 在 `:server` 模块的 `ChatServer.kt` 中组装
- **SnowflakeIdGenerator** 在所有需要生成唯一 ID 的模块中使用

</code_context>

<specifics>
## Specific Ideas

- ApplicationConfig 的加载路径：server 模块读取 `config/application.conf`，通过 `ConfigFactory.load()` 的 fallback 链实现
- DataSourceProvider 接口设计为 `interface DataSourceProvider { fun getDataSource(): DataSource }`，Phase 3 扩展为路由数据源
- server 模块启动流程：加载配置 → 创建 ApplicationConfig → 创建 SnowflakeIdGenerator → 创建 DataSourceProvider → 创建 NettyServer

</specifics>

<deferred>
## Deferred Ideas

- **HikariCP 主从多数据源** — Phase 3 数据库层实现时才引入读写分离
- **Snowflake Worker ID 动态分配** — 分布式部署后考虑 ZooKeeper/Redis 分配
- **生产监控指标** — Phase 11 性能与监控阶段统一处理

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 02-Common Module & Infrastructure Base*
*Context gathered: 2026-06-11*
