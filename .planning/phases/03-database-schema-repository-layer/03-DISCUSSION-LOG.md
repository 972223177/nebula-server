# Phase 03: Database Schema & Repository Layer - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-11
**Phase:** 03-Database Schema & Repository Layer
**Areas discussed:** ORM 框架选择, Redis 客户端, 实体设计风格, Repository 组织结构, 异步刷写策略, 触发参数, 分页方案, 消息 ID 策略, 事务边界, Redis 命名规范, Redis 队列数据结构, DB 迁移工具, Session TTL 策略, 在线状态缓存策略

---

## ORM / 数据访问框架

| Option | Description | Selected |
|--------|-------------|----------|
| JPA + Hibernate | 标准 JPA 注解 + Hibernate 实现。已有 javax.sql.DataSource | ✓ |
| Kotlin Exposed | JetBrains 出品的 Kotlin 原生 SQL 框架，类型安全 DSL | |
| 纯 JDBC | 完全控制 SQL，直接使用 DataSource 写 JDBC | |

**User's choice:** JPA + Hibernate

---

## Redis 客户端

| Option | Description | Selected |
|--------|-------------|----------|
| Lettuce | 异步/非阻塞客户端，与 Netty/gRPC 异步模型天然契合 | ✓ |
| Jedis | 同步阻塞客户端，最简单直接 | |
| Redisson | 高级抽象客户端，提供分布式锁、RMap 等 | |

**User's choice:** Lettuce

---

## 实体设计风格

| Option | Description | Selected |
|--------|-------------|----------|
| data class + JPA 注解 | 简洁但 copy() 会丢失 JPA 延迟加载代理状态 | |
| 常规 class + JPA | 完全掌控 equals/hashCode，避免 data class 的代理问题 | ✓ |
| Entity/DTO 分离 | Entity 层与 DTO 层分离，最解耦但文件翻倍 | |

**User's choice:** 常规 class + JPA

**Notes:** 字段初始化用构造参数 + nullable 默认值，DB 自增/自动生成字段用 `Type? = null`

---

## Repository 组织结构

| Option | Description | Selected |
|--------|-------------|----------|
| 按实体拆分 | 6 张表对应 6 个 Repository + MessageRepository | ✓ |
| 单体 Repository | 所有操作放在一个 Repository 中 | |

**User's choice:** 按实体拆分

---

## 异步刷写策略

| Option | Description | Selected |
|--------|-------------|----------|
| 定时触发 | 500ms 定时或积压 30 条触发 | ✓ |
| 每次写入都刷 | 延迟最低但 MySQL 写压力大 | |
| 定时+定量组合 | 积攒到一定时间或数量再批量刷写 | |

**User's choice:** 定时触发

---

## 触发参数

| Option | Description | Selected |
|--------|-------------|----------|
| 500ms / 30条 | 聊天消息延迟可接受，MySQL 写压力适中 | ✓ |
| 200ms / 20条 | 延迟更低但 MySQL 写更频繁 | |
| 1s / 50条 | 吞吐最高但极端情况有 1s 延迟 | |

**User's choice:** 500ms / 30条

---

## 分页方案

| Option | Description | Selected |
|--------|-------------|----------|
| Cursor 游标分页 | 基于消息 ID 作为 cursor，高性能、无数据偏移 | ✓ |
| Offset 偏移分页 | 实现简单但大数据量性能下降 | |

**User's choice:** Cursor 游标分页

---

## 消息 ID 策略

| Option | Description | Selected |
|--------|-------------|----------|
| 雪花 ID | 利用 SnowflakeIdGenerator，全局有序 | ✓ |
| 数据库自增 ID | auto_increment，简单但跨库迁移麻烦 | |

**User's choice:** 雪花 ID

---

## 事务边界

| Option | Description | Selected |
|--------|-------------|----------|
| Service 层事务 | Service 层打开事务，Repository 层不管理 | ✓ |
| Repository 层事务 | 每个 Repository 方法自包含事务 | |

**User's choice:** Service 层事务

---

## Redis 命名规范

| Option | Description | Selected |
|--------|-------------|----------|
| 前缀:层级 | `session:token:<value>`、`queue:session:<id>:<seq>` | ✓ |
| 下划线平铺 | `session_token_<value>` | |

**User's choice:** 前缀:层级

---

## Redis 队列数据结构

| Option | Description | Selected |
|--------|-------------|----------|
| Redis Stream | 支持消费者组、消息确认 ACK、消息回溯 | ✓ |
| Redis List | 简单 LPUSH/BRPOP，无消息确认机制 | |
| Redisson RQueue | Redisson 封装，已选 Lettuce 不太合适 | |

**User's choice:** Redis Stream

---

## DB 迁移工具

| Option | Description | Selected |
|--------|-------------|----------|
| Flyway | 版本化 SQL 文件，自动按版本顺序执行 | ✓ |
| Hibernate auto DDL | 开发期方便但生产不推荐 | |
| 纯 SQL 脚本 | 最简但团队协作难 | |

**User's choice:** Flyway

---

## Session TTL 策略

| Option | Description | Selected |
|--------|-------------|----------|
| 滑动 TTL | 每次请求刷新 TTL，活跃用户持续有效 | ✓ |
| 固定 TTL | TTL 固定不做刷新，到期强制重新登录 | |

**User's choice:** 滑动 TTL

---

## 在线状态缓存策略

| Option | Description | Selected |
|--------|-------------|----------|
| 断连即离线+短 TTL | 断连立即置离线，30s 内重连不触发通知，TTL 60s | ✓ |
| 长 TTL + 心跳刷新 | 2 分钟 TTL，心跳 30s 持续刷新 | |
| 支持隐私模式 | 额外字段处理隐藏/离线 | |

**User's choice:** 断连即离线+短 TTL

## Claude's Discretion

- DB 表具体字段设计、索引策略、存储引擎和字符集选择
- Redis 操作的具体 API 封装（Lettuce 异步 API 的包装层）
- Flyway 版本化脚本组织方式
- 消息表的分表策略（单表起步）
- 并发控制策略（乐观锁 vs 悲观锁）

## Deferred Ideas

- **HikariCP 主从多数据源（读写分离）** — 不在 Phase 3 引入
- **消息表按时间/会话分表** — 单表起步
- **生产监控指标** — Phase 11 统一处理
