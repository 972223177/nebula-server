---
phase: 03
slug: database-schema-repository-layer
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-12
---

# Phase 03 — 安全合约

> 每阶段安全合约：威胁注册表、已接受的风险和审计轨迹。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| Application → MySQL | JDBC 查询，部分受用户输入影响 | SQL 查询（JPQL @Param 绑定）、结果集 |
| Flyway 脚本 → MySQL | Migration DDL 从 classpath 加载并执行 | DDL 语句、索引定义 |
| Application → Redis | Lettuce TCP 命令，内存数据存储 | Session Token、消息队列、在线状态 |
| Redis Stream → Flush → MySQL | 消息先入 Redis 再批量刷入 MySQL | 消息内容（中间态） |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-03-01 | 篡改 (Tampering) | MessageRepository @Query | mitigate | 所有查询使用 JPQL `@Param` 参数绑定（`MessageRepository.kt` 第 23~36 行），杜绝字符串拼接注入 | closed |
| T-03-02 | 拒绝服务 (Denial of Service) | HikariCP 连接池 | mitigate | connectionTimeout=30s + maximumPoolSize=20（继承 Phase 2 配置），限制资源耗尽影响 | closed |
| T-03-03 | 篡改 (Tampering) | Flyway 迁移脚本 | accept | 迁移文件通过 Git 版本控制，篡改需文件系统写权限 | closed |
| T-03-04 | 篡改 (Tampering) | gradle/libs.versions.toml 依赖 | mitigate | 所有 Phase 3 库为 Maven Central 高下载量包（JPA、Lettuce、Flyway、Hibernate），通过版本目录管理 | closed |
| T-03-05 | 信息泄露 (Information Disclosure) | Redis 内存数据 | accept | 开发环境 localhost-only；生产环境需配置 `requirepass` + TLS（Phases 3 范围外） | closed |
| T-03-06 | 篡改 (Tampering) | Redis 连接注入 | accept | Redis 端点通过 HOCON 文件配置，服务器启动时验证，不受用户输入影响 | closed |
| T-03-07 | 拒绝服务 (Denial of Service) | Lettuce 连接泄漏 | mitigate | 所有 Redis Repository 共享单一连接（`RedisConfig.kt` 共享 `connection` 成员），防止连接耗尽（Pitfall 4 缓解措施） | closed |
| T-03-08 | 信息泄露 (Information Disclosure) | Redis Stream 消息内容 | accept | 消息包含用户内容；生产 Redis 应启用传输加密（TLS）+ requirepass | closed |
| T-03-09 | 篡改 (Tampering) | MessageRepositoryImpl.parseToEntity | mitigate | StreamMessage body map values 使用 null-safe 解析（`?.toLongOrNull()`），无效条目被跳过并记录警告日志 | closed |
| T-03-10 | 拒绝服务 (Denial of Service) | Flush 定时器 CoroutineScope | mitigate | `CoroutineScope(Dispatchers.IO)` 使用平台默认并行度，作用域不对外暴露 | closed |
| T-03-11 | 身份伪造 (Spoofing) | docker-compose.yml 默认凭据 | accept | root/root123 + Redis 无认证仅用于本地开发；生产凭据通过环境变量配置 | closed |
| T-03-12 | 信息泄露 (Information Disclosure) | Redis Stream PEL | accept | 消息留在 PEL 直到 XACK；Flush 崩溃后重启通过 XAUTOCLAIM 重新派发（Phase 10） | closed |
| T-03-13 | 篡改 (Tampering) | incrementUnreadCount @Modifying | mitigate | JPQL 参数化绑定（`:convId`、`:senderId`）防注入 | closed |
| T-03-14 | 身份伪造 (Spoofing) | updateReadReceipt 任意 lastReadMsgId | accept | lastReadMsgId 为客户端提供；Phase 6 Service 层应验证用户对该对话有访问权限 | closed |
| T-03-15 | 抵赖 (Repudiation) | 未读计数并发竞争 | accept | Phase 11（监控阶段）应监测计数漂移；v1 版本接受最终一致性 | closed |

*状态: open · closed*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-03-01 | T-03-03 | Flyway 迁移文件通过 Git 版本控制，PR 审查可检测篡改 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-02 | T-03-05, T-03-08 | 开发环境 Redis 仅本地访问；生产需单独配置 requirepass + TLS | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-03 | T-03-06 | Redis 端点硬编码在 application.conf 中，无用户输入路径 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-04 | T-03-11 | docker-compose.yml 默认凭据仅开发环境使用，生产凭据通过环境变量注入 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-05 | T-03-12 | PEL 消息为持久化中间态，崩溃恢复后重新处理 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-06 | T-03-14 | Phase 6 应在 Service 层添加对话访问权限验证 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-03-07 | T-03-15 | 未读计数 v1 接受最终一致性，Phase 11 监控计数漂移 | plan-audit (gsd-secure-phase) | 2026-06-12 |

---

## 缓解措施验证详情

### T-03-01/T-03-13: JPQL 参数绑定防注入

- `MessageRepository.kt:23~24`: `@Param("convId")`, `@Param("cursor")` 参数绑定 ✅
- `ConversationMemberRepository.kt:33~34`: `@Param("convId")`, `@Param("senderId")` 参数绑定 ✅
- 所有 Repository 接口均使用 `@Param` 绑定，无字符串拼接 ✅

### T-03-07: Redis 连接共享

- `RedisConfig.kt`: 所有 Repository 通过构造函数注入 `StatefulRedisConnection` 共享连接 ✅

### T-03-09: Stream 消息 null-safe 解析

- `MessageRepositoryImpl.kt`: `parseToEntity` 使用 `?.toLongOrNull()` / `?.toString()` 等 null-safe 操作 ✅

### T-03-02: HikariCP 连接池限制

- 继承 Phase 2 `HikariDataSourceProvider.kt` 配置：connectionTimeout=30s, maximumPoolSize=20 ✅

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|------------|---------------|--------|------|--------|
| 2026-06-12 | 15 | 15 | 0 | gsd-secure-phase (追溯验证) |

---

## 签收

- [x] 所有威胁都有处置方案（mitigate / accept）
- [x] `threats_open: 0` 确认
- [x] `status: verified` 已设置
