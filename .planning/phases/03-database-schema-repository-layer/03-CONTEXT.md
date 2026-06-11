# Phase 03: Database Schema & Repository Layer - Context

**Gathered:** 2026-06-11
**Status:** Ready for planning

<domain>
## Phase Boundary

构建 Nebula Chat Server 的完整持久化层。具体交付：

1. **MySQL 6 张核心表** — users、conversations、conversation_members、messages、friendships、friend_requests
2. **Redis 3 种缓存结构** — Session Token、消息待发送队列（Pending Queue）、在线状态
3. **Repository 层** — 基于 JPA + Hibernate 的实体和仓库接口
4. **消息写路径** — 接收 → Redis Stream ACK → 异步批量刷入 MySQL
5. **消息拉取** — 基于 cursor 游标分页
6. **未读计数维护** — 每个会话跟踪未读消息数
7. **已读回执** — 更新 last_read_message_id
8. **离线消息存储与重连推送**

**Requirements:** DB-01, DB-02, DB-03, DB-04, DB-05, DB-06, DB-07

</domain>

<decisions>
## Implementation Decisions

### ORM / 数据访问框架

- **D-01:** 使用 **JPA + Hibernate** 作为 ORM 框架。与设计文档 8.5 节一致，充分利用现有 DataSource 集成
- **D-02:** 数据库迁移工具使用 **Flyway**，版本化 SQL 脚本（`V1__create_users.sql` 格式）

### Redis 客户端

- **D-03:** Redis 客户端使用 **Lettuce**（异步/非阻塞），与 Netty/gRPC 异步模型契合
- **D-04:** 消息待发送队列使用 **Redis Stream**（支持消费者组、消息确认 ACK、消息回溯）
- **D-05:** Redis key 命名规范：**前缀:层级** 格式，即 `session:token:<value>`、`queue:session:<sessionId>:<seq>`、`online:user:<userId>`

### 实体设计风格

- **D-06:** 实体使用 **常规 class + JPA 注解**（非 data class），避免 data class 的 copy() 破坏 JPA 延迟加载代理
- **D-07:** 字段初始化策略：**构造参数 + nullable 默认值**。必填字段（username、password_hash 等）写在构造参数中；DB 自动生成字段（id、created_at、updated_at）用 `Type? = null` 声明

### Repository 层结构

- **D-08:** 按实体粒度拆分 Repository，每张表对应一个 Repository 接口。共 6 个基础 Repository + 1 个 MessageRepository 整合消息读写路径
- **D-09:** 事务边界控制在 **Service 层**（Service 层打开事务，Repository 层不管理事务）

### 消息 ID 生成

- **D-10:** 消息 ID 使用 **雪花算法**，利用 Phase 2 已有的 SnowflakeIdGenerator。全局唯一、时间有序，天然适合 cursor 分页排序

### MessageType 枚举重构（Proto 协议级变更）

- **D-15:** 将原 `MessageType` 枚举拆分为两个独立枚举，职责分离：
  - **`PushEventType`** — 描述 `envelope.Message.eventType`，指示 payload 结构是 ChatMessage、FriendRequestPayload 等。值从 1 开始（0 = PUSH_EVENT_UNSPECIFIED）
  - **`ChatContentType`** — 描述 `ChatMessage.message_type` 和 `SendMessageReq.message_type`，表示聊天消息正文的格式（TEXT = 0, TEXT_AND_IMAGE = 1）
- **D-16:** `envelope.Message.messageType` 改名为 `eventType`，类型从 `MessageType` 改为 `PushEventType`
- **D-17:** Push `CHAT_MESSAGE` 时，`envelope.Message.payload` 包含完整的 `ChatMessage` 序列化字节；`content` 字段作为通知栏预览文本（免反序列化）
- **理由：** 原有 `MessageType` 同时承担 Push 事件路由和聊天内容格式两个职责，导致 `envelope.Message.messageType` 找不到合适的值指示"payload 是 ChatMessage"

### 消息写入路径

- **D-11:** 异步刷写策略：**定时触发**，间隔 **500ms**，单次批处理阈值 **30 条**
  - 消息先写入 Redis Stream（客户端即刻收到 ACK）
  - 后台定时任务每 500ms 检查积压消息，>= 30 条时触发批量刷入 MySQL
  - 刷写失败的消息保留在 Redis Stream 中，下次定时任务重试

### 消息拉取

- **D-12:** 消息拉取使用 **Cursor 游标分页**（基于消息 ID/时间），避免大数据量下 OFFSET 的性能问题

### 会话 / Token 管理

- **D-13:** Redis Session Token TTL 采用 **滑动刷新** 策略，活跃用户每次请求自动续期 TTL

### 在线状态

- **D-14:** 在线状态缓存策略：**断连即标记离线 + 短 TTL（60s）**。断连时立即置为离线，如果 30s 内有重连则不触发状态变更通知

### Claude's Discretion

- DB 表具体字段设计、索引策略、存储引擎和字符集选择
- Redis 操作的具体 API 封装（Lettuce 异步 API 的包装层）
- Flyway 版本化脚本组织方式
- 消息表的分表策略（单表起步，后续按需）
- 并发控制策略（乐观锁 vs 悲观锁）

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 前期阶段上下文

- `.planning/phases/02-common-module-infrastructure-base/02-CONTEXT.md` — Phase 2 基础设施上下文
  - D-11: 单数据源实现，多数据源 Phase 3 按需引入
  - D-12: DataSourceProvider 接口封装
  - DataSourceProvider 供 `:repository` 模块使用

### 设计文档

- `设计文档/后端架构设计v1.2/09-基础设施设计/9.5-HikariCP连接池.md` — HikariCP 参数配置和 DataSource 初始化
- `设计文档/后端架构设计v1.2/05-数据结构设计/5.1-数据库设计.md` — MySQL 6 张核心表结构定义
- `设计文档/后端架构设计v1.2/05-数据结构设计/5.2-Redis设计.md` — Redis 缓存结构和 key 设计

### Proto 协议定义

- `proto/src/main/proto/nebula/message_type.proto` — `ChatContentType` + `PushEventType` 枚举定义（D-15）
- `proto/src/main/proto/nebula/envelope.proto` — `Message.eventType` 使用 `PushEventType`（D-16）
- `proto/src/main/proto/nebula/chat/chat.proto` — `SendMessageReq.message_type` 使用 `ChatContentType`
- `proto/src/main/proto/nebula/message/message.proto` — `ChatMessage.message_type` 使用 `ChatContentType`

### 项目规划

- `.planning/PROJECT.md` — 项目概览、技术栈、约束条件
- `.planning/REQUIREMENTS.md` — DB-01~DB-07 需求明细
- `.planning/STATE.md` — 项目当前状态

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- `common/src/main/kotlin/com/nebula/common/datasource/DataSourceProvider.kt` — 数据源提供者，Phase 2 已就绪
- `common/src/main/kotlin/com/nebula/common/datasource/HikariDataSourceProvider.kt` — HikariCP 实现
- `common/src/main/kotlin/com/nebula/common/idgen/SnowflakeIdGenerator.kt` — 雪花算法 ID 生成器
- `common/src/main/kotlin/com/nebula/common/config/DatabaseConfig.kt` — 数据库连接配置数据类

### Established Patterns

- `:common` 模块包结构 `com.nebula.common.*` 已确立
- Kotlin DSL + Gradle Version Catalog (`libs.versions.toml`) 管理依赖
- ApplicationConfig 在 `:server` 模块加载后通过构造函数传参注入各模块

### Integration Points

- **`:repository` 模块**是 `:service` 模块的底层依赖（新模块 Phase 3 需要创建）
- **`DataSourceProvider`** 由 `:server` 模块初始化后注入 `:repository` 模块
- **`SnowflakeIdGenerator`** 在消息实体 ID 生成和业务逻辑中使用
- **`ApplicationConfig.DatabaseConfig`** 包含数据库连接参数

</code_context>

<specifics>
## Specific Ideas

- Phase 3 拆分为 4 个子计划执行：
  1. **03-01:** MySQL DDL + Entity 层（Flyway 脚本、JPA Entity、Repository 接口）— DB-01
  2. **03-02:** Redis 存储结构（Session/队列/在线状态的 Lettuce 操作封装）— DB-02
  3. **03-03:** 消息核心路径（写路径：Redis Stream → 异步刷 MySQL；拉取：cursor 分页）— DB-03, DB-04
  4. **03-04:** 消息周边能力（离线消息、未读计数、已读回执）— DB-05, DB-06, DB-07
- `:repository` 模块需要在 Gradle settings 中注册，依赖 `:common` 和 `:proto` 模块
- HikariCP 连接池已在 Phase 2 配置，Phase 3 先使用单数据源，后续按需扩展为读写分离

</specifics>

<deferred>
## Deferred Ideas

- **HikariCP 主从多数据源（读写分离）** — 不在 Phase 3 引入，后续按需扩展
- **消息表按时间/会话分表** — 单表起步，数据量大后再考虑
- **生产监控指标（数据库连接池、Redis 命中率等）** — Phase 11 统一处理

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 03-Database Schema & Repository Layer*
*Context gathered: 2026-06-11*
