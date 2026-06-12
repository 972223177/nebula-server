---
status: complete
phase: 03-database-schema-repository-layer
source: 03-01-SUMMARY.md, 03-02-SUMMARY.md, 03-03-SUMMARY.md, 03-04-SUMMARY.md
started: 2026-06-11T18:30:00+08:00
updated: 2026-06-12T09:00:00+08:00
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Kill running services。启动 docker-compose up -d，运行 `gradle :server:compileKotlin`，server 启动成功（JPA→Redis→gRPC），Flyway 迁移执行完毕，无错误日志
result: pass
note: "修复了 4 个 bug 后启动成功：characterEncoding password 默认值 Hibernate 命名策略 TINYINT→INT kotlin-reflect 依赖 MKSTREAM 选项"

### 2. 构建编译验证
expected: `gradle :repository:compileKotlin` 和 `gradle :server:compileKotlin` 均编译通过，无依赖冲突或注解错误
result: issue → pass
reported: "未通过，报错了，还有根 build.gradle 中 kotlin(\"plugin.jpa\") version \"2.1.21\" 和 kotlin(\"plugin.allopen\") version \"2.1.21\" 未写入 toml 中管理"
severity: major
fix: |
  - 将 kotlin-jpa 和 kotlin-allopen 插件纳入 gradle/libs.versions.toml 的 [plugins] 段（使用 ref kotlin = "2.1.20"）
  - 根 build.gradle.kts: 改用 alias(libs.plugins.kotlin.jpa) apply false / alias(libs.plugins.kotlin.allopen) apply false，移除残留的 implementation(kotlin("stdlib")) 和根级 allOpen{} 块
  - repository/build.gradle.kts: 改用 alias(libs.plugins.kotlin.jpa) / alias(libs.plugins.kotlin.allopen)

### 3. 数据库迁移脚本
expected: V1__init_schema.sql 包含 6 张表（users, conversations, conversation_members, messages, friendships, friend_requests）及所有索引，Flyway migrate 执行无错误
result: pass
note: "表结构与 5.1-MySQL表.md 设计有差异（移除 uid/role/created_by/last_message, 新增 unread_count/deleted/privacy_status/client_message_id, ENUM→INT, 代理主键替代复合主键, 移除 FK）。差异均有 Phase 3 设计决策依据（D-06/D-07），且 Roadmap 中已规划后续阶段承接完整数据流。需同步更新 v1.2 设计文档"

### 4. JPA 实体映射
expected: 6 个实体类（UserEntity, ConversationEntity 等）使用 jakarta.persistence 注解，Snowflake ID（UserEntity, MessageEntity）和 UUID（ConversationEntity）主键映射正确。Hibernate validate 模式启动无校验错误
result: pass
note: "属性位置（构造 vs 类体）存在 cosmetic 不一致（FriendshipEntity.deleted），但不影响 Hibernate 校验"

### 5. Repository 接口方法
expected: 7 个 Repository 接口的 CRUD 及查询方法签名正确。MessageRepository 支持游标分页（findMessagesBackward/findMessagesForward），ConversationMemberRepository 含 @Modifying 更新查询
result: pass
note: "编译通过。ConversationRepository 目前仅基础 CRUD，后续阶段补充"

### 6. Redis 配置与连接
expected: RedisConfig 使用 Lettuce + 协程 API，懒加载初始化。SessionRepository/OnlineStatusRepository/MessageQueueRepository 通过构造函数注入共享连接，基本 set/get/delete 操作正常
result: pass

### 7. Docker Compose 服务
expected: `docker-compose up -d` 启动 MySQL 8.0 和 Redis 7-alpine，healthcheck 通过。server 连接数据库和 Redis 成功
result: pass
note: "HikariCP → Flyway → Hibernate validate → Redis → gRPC Server 全部正常启动"

### 8. 异步消息写入路径
expected: MessageRepositoryImpl 的 enqueueMessage 写入 Redis Stream，flushBatch 按 500ms 间隔 + 30 条阈值批量写入 MySQL 并 XACK。startFlushTimer 协程定时器正常工作
result: pass
note: |
  - `enqueueMessage()` 将 MessageEntity 序列化为 Map<String,String> 调用 messageQueue.enqueue()（XADD 到 Redis Stream）✓
  - `flushBatch()` 从 Stream 消费 30 条 → 检查 >=30 阈值 → 批量 JPA INSERT → XACK 成功消息 ✓
  - `startFlushTimer()` 启动协程定时器，500ms 间隔循环调用 flushBatch() ✓
  - 编译通过 ✓

### 9. 服务器启动顺序
expected: NebulaServer.kt 按顺序初始化：HikariCP → Flyway → JPA → Redis → Repository 代理 → MessageWriter → gRPC 服务。server 启动日志确认各组件初始化成功
result: pass
note: |
  - logback 配置 → ConfigLoader → SnowflakeIdGenerator → HikariCP → JpaConfig(Flyway+JPA+EntityManagerFactory) → RedisConfig(共享连接) → Repository 代理(6个 JPA + 3个 Redis) → initializeRedisInfra → MessageRepositoryImpl(startFlushTimer) → ChatServer.start() → blockUntilShutdown() ✓
  - 编译通过 ✓

### 10. 未读计数与已读回执
expected: ConversationMemberRepository 的 incrementUnreadCount 和 updateReadReceipt @Modifying 查询正确使用实体字段名（lastReadMessageId, unreadCount），编译通过
result: pass
note: |
  - `incrementUnreadCount`: `SET cm.unreadCount = cm.unreadCount + 1 WHERE cm.conversationId = :convId AND cm.userId <> :senderId` ✓
  - `updateReadReceipt`: `SET cm.lastReadMessageId = :lastReadMsgId, cm.unreadCount = 0 WHERE cm.conversationId = :convId AND cm.userId = :userId` ✓
  - 均使用 JPQL 实体字段名，非数据库列名 ✓
  - 编译通过 ✓

### 11. PEL 离线消息支持
expected: MessageQueueRepository 的 getPendingCount（XPENDING 统计）、getPendingMessages（XPENDING 范围）、readMessagesById（XRANGE）方法签名正确，使用 Lettuce 协程 Flow API
result: pass
note: |
  - `getPendingCount()` → `PendingMessages?` 通过 `redis.xpending()` 返回 PEL 统计 ✓
  - `getPendingMessages(start, end, count)` → `Flow<PendingMessage>` 通过 `redis.xpending()` 范围查询返回 PEL 详情 ✓
  - `readMessagesById(startId, endId, count)` → `Flow<StreamMessage<String, String>>` 通过 `redis.xrange()` 按 ID 范围读取消息 ✓
  - 全部使用 Lettuce 协程 Flow API ✓
  - 编译通过 ✓

## Summary

total: 11
passed: 11
issues: 1 (已修复)
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Server 启动成功，Flyway 迁移完成，无错误日志"
  status: failed → fixed
  reason: "User reported: HikariCP 连接失败: Unsupported character encoding 'utf8mb4'"
  severity: blocker
  test: 1
  root_cause: "HikariDataSourceProvider.kt 第 68 行 JDBC URL 使用 characterEncoding=utf8mb4，该值是 MySQL 服务端字符集名而非 Java 字符集名。Java 的 String.lookupCharset() 抛出 UnsupportedEncodingException，导致 JDBC 驱动无法创建连接。后一个启动尝试报 Access denied (using password: NO)，原因是 application.conf 中 database.password 默认值为空字符串，与 docker-compose.yml 设置的 MYSQL_ROOT_PASSWORD=root123 不匹配。第三个启动失败报 Hibernate validate 模式 missing column [conversationId]，原因是未配置物理命名策略，Hibernate 默认使用实体字段名（camelCase）作为列名，而 SQL 迁移脚本中列名使用 snake_case"
  artifacts:
    - path: "common/src/main/kotlin/com/nebula/common/datasource/HikariDataSourceProvider.kt"
      issue: "characterEncoding=utf8mb4 改为 characterEncoding=UTF-8"
    - path: "config/application.conf"
      issue: "database.password 默认值 \"\" 改为 \"root123\""
    - path: "repository/src/main/kotlin/com/nebula/repository/config/JpaConfig.kt"
      issue: "添加 CamelCaseToUnderscoresNamingStrategy"
    - path: "repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt"
      issue: "@Index columnList 改为 snake_case"
    - path: "repository/src/main/kotlin/com/nebula/repository/entity/MessageEntity.kt"
      issue: "@Index columnList 改为 snake_case"
    - path: "repository/src/main/kotlin/com/nebula/repository/entity/FriendshipEntity.kt"
      issue: "@Index columnList 改为 snake_case"
    - path: "repository/src/main/kotlin/com/nebula/repository/entity/FriendRequestEntity.kt"
      issue: "@Index columnList 改为 snake_case"
  missing:
    - "JDBC URL 中 characterEncoding 参数应使用 Java 标准字符集名 UTF-8，而非 MySQL 服务端字符集名 utf8mb4"
    - "application.conf 的 database.password 默认值需与 docker-compose.yml 的 MYSQL_ROOT_PASSWORD 一致"
    - "需要配置 Hibernate 物理命名策略 CamelCaseToUnderscoresNamingStrategy，使实体字段名（camelCase）自动映射到 snake_case 列名"
    - "@Index 注解中的 columnList 需要与物理列名一致，而非实体字段名"
  debug_session: ""

- truth: "gradle :repository:compileKotlin 和 :server:compileKotlin 编译通过"
  status: failed → fixed
  reason: "User reported: 未通过，报错了；kotlin(\"plugin.jpa\") 和 kotlin(\"plugin.allopen\") 未写入 toml 管理"
  severity: major
  test: 2
  root_cause: "1) 根 build.gradle.kts 第 15 行 implementation(kotlin(\"stdlib\")) 因未应用 java/kotlin-jvm 插件导致 implementation 配置不存在。2) kotlin-allopen 和 kotlin-jpa 插件使用硬编码版本 \"2.1.21\"，未在 TOML 中声明；改用 TOML 引用后根项目的 allOpen{} 块因 apply false 无法解析"
  artifacts:
    - path: "build.gradle.kts"
      issue: "移除 implementation(kotlin(\"stdlib\")) 和根级 allOpen{} 块；插件改用 alias(libs.plugins.*)"
    - path: "repository/build.gradle.kts"
      issue: "插件改用 alias(libs.plugins.kotlin.jpa / kotlin.allopen)"
    - path: "gradle/libs.versions.toml"
      issue: "添加 kotlin-jpa 和 kotlin-allopen 插件声明"
  missing:
    - "gradle/libs.versions.toml [plugins] 段添加 kotlin-jpa 和 kotlin-allopen"
    - "根 build.gradle.kts 移除不兼容的 implementation 声明和根级 allOpen 配置"
  debug_session: ""
