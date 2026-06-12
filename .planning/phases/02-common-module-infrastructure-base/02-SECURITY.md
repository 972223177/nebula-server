---
phase: 02
slug: common-module-infrastructure-base
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-12
---

# Phase 02 — 安全合约

> 每阶段安全合约：威胁注册表、已接受的风险和审计轨迹。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| application.conf → 进程 | HOCON 配置文件读取，非用户输入，无外部写入路径 | 数据库密码（环境变量）、服务器配置参数 |
| gRPC 客户端 → NettyServer | 不受信的外部输入，网络边界 | APEX proto 消息、metadata |
| 数据库连接 → MySQL | JDBC 连接，凭据通过环境变量注入 | SQL 查询、结果集 |
| SnowflakeIdGenerator → 调用方 | 内部生成唯一 ID，无用户输入 | 64-bit 数值 ID |
| generate-dev-cert.sh → shell 执行 | 开发环境工具脚本，仅开发环境运行 | SSL 证书文件 |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-02-01 | 信息泄露 (Information Disclosure) | application.conf | mitigate | 数据库密码通过 `${DB_PASSWORD}` 环境变量注入，不在配置文件中硬编码；密码字段仅 key 在 git 中，value 从环境变量读取 | closed |
| T-02-02 | 篡改 (Tampering) | application.conf | accept | 配置文件提交 git 版本控制，修改可追踪。单节点部署，篡改需文件系统写权限 | closed |
| T-02-03 | 拒绝服务 (Denial of Service) | grpc-netty-shaded / netty-tcnative | accept | Maven Central 官方 artifact，已在 PHASE-02-RESEARCH.md Package Legitimacy Audit 中验证 | closed |
| T-02-SC | 篡改 (Tampering) | 第三方包安装 | mitigate | 仅 Maven 依赖，已验证所有包来自 Maven Central，npm/pip/cargo 不适用 | closed |
| T-02-04 | 篡改 (Tampering) | SnowflakeIdGenerator | mitigate | Worker ID 超出 0~1023 时 `init { require() }` 抛出 IllegalArgumentException 立即失败 | closed |
| T-02-05 | 拒绝服务 (Denial of Service) | SnowflakeIdGenerator | accept | `@Synchronized` 在 10K msg/s 级别可能成为瓶颈，Phase 11 优化。D-14 waitNextMillis 自愈不会阻塞系统 | closed |
| T-02-06 | 信息泄露 (Information Disclosure) | BizException.bizCode | accept | bizCode 是预定义的错误码枚举（code+msg），不携带敏感信息。ExceptionInterceptor 在 Phase 4 统一脱敏处理 | closed |
| T-02-07 | 拒绝服务 (Denial of Service) | NettyServerBuilder | mitigate | `maxInboundMessageSize(4MB)` 限制消息大小；`maxConnectionAge(30min)` + `maxConnectionAgeGrace(10s)` 强制刷新连接防老化。注意：`permitKeepAliveWithoutCalls(true)` 因 D-27 双重心跳策略开启，已通过 keepaliveTime/Jitter 和 maxConnectionIdle 互补限制 | closed |
| T-02-08 | 信息泄露 (Information Disclosure) | SSL SslContext | mitigate | 生产环境使用 Let's Encrypt（路径环境变量配置）；密码套件限制为 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` 和 `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` | closed |
| T-02-09 | 篡改 (Tampering) | HikariDataSourceProvider | mitigate | 数据库凭据通过 `${DB_PASSWORD}` 环境变量注入（application.conf 第 24 行），不在文件或代码中硬编码 | closed |
| T-02-10 | 信息泄露 (Information Disclosure) | JDBC SSL | mitigate | mysql-connector-j 9.x 使用 `sslMode` 参数（PREFERRED / VERIFY_IDENTITY），替代已废弃的 `useSSL` | closed |
| T-02-11 | 拒绝服务 (Denial of Service) | HikariCP 泄漏检测 | mitigate | `leakDetectionThreshold=10000ms` 启用连接泄漏检测，HikariCP 超时后打印警告日志；`DatabaseConfig.leakDetectionThreshold` 配置项 + `HikariDataSourceProvider` 应用该值 | closed |
| T-02-12 | 身份伪造 (Spoofing) | ConfigFactory.parseFile | accept | 配置文件提交 git 版本控制，单节点部署需文件系统写权限才能替换配置 | closed |

*状态: open · closed*
*处置: mitigate (需实现) · accept (已记录风险) · transfer (第三方)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-02-01 | T-02-02 | application.conf 提交 Git，修改可追踪。需文件系统写入权限才能篡改 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-02-02 | T-02-03 | grpc-netty-shaded、netty-tcnative 为 Maven Central 高下载量包，供应链风险极低 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-02-03 | T-02-05 | `@Synchronized` 在 10K msg/s 以下无瓶颈压力。等待 Phase 11 优化（不可变序列缓冲区方案） | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-02-04 | T-02-06 | bizCode 是预定义错误码枚举，不泄露栈信息或敏感字段 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-02-05 | T-02-12 | ConfigLoader 仅启动时解析一次，无运行时重新加载路径。需文件系统写权限才能篡改 | plan-audit (gsd-secure-phase) | 2026-06-12 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-02-01/T-02-09: 密码安全 (环境变量 + 配置结构)

- `config/application.conf:24`: `password = ${?DB_PASSWORD}` — 密码仅通过环境变量注入 ✅
- `HikariDataSourceProvider.kt`: 从 `DatabaseConfig` 读取密码并创建 DataSource ✅

### T-02-07: Netty DoS 防护

- `ChatServer.kt:48`: `maxInboundMessageSize(4 * 1024 * 1024)` — 4MB 消息大小上限 ✅
- `ChatServer.kt:64`: `maxConnectionAge(1800, SECONDS)` — 每次连接 30 分钟强制刷新 ✅
- 注：`permitKeepAliveWithoutCalls(true)` 是 D-27 双重心跳策略的主动选择，与威胁模型原始缓解措施不同（原计划 `false`）。已通过 keepaliveTime(30s)+Jitter 和 maxConnectionIdle(10min) 提供互补 DoS 防护 ✅

### T-02-08: SSL 安全配置

- `SslConfig.kt:40~41`: 密码套件限定为 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` 和 `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` ✅

### T-02-10: JDBC SSL

- 使用 mysql-connector-j 9.x 的 `sslMode` 参数（PREFERRED / VERIFY_IDENTITY），代码中通过 `HikariDataSourceProvider` 配置 JDBC URL ✅

### T-02-11: HikariCP 泄漏检测

- `DatabaseConfig.kt:30`: `leakDetectionThreshold: Long` 配置项 ✅
- `HikariDataSourceProvider.kt:41`: `leakDetectionThreshold = config.leakDetectionThreshold` ✅
- `ConfigLoader.kt:88`: 从 `application.conf` 读取 `database.leak-detection-threshold` ✅

### T-02-04: Snowflake workerId 参数校验

- `SnowflakeIdGenerator.kt`: `init { require(workerId in 0..1023) }` — 越界立即失败 ✅

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|------------|---------------|--------|------|--------|
| 2026-06-12 | 12 | 12 | 0 | gsd-secure-phase (追溯验证) |

---

## 签收

- [x] 所有威胁都有处置方案（mitigate / accept）
- [x] 已接受的风险记录在风险日志中
- [x] `threats_open: 0` 确认
- [x] `status: verified` 已在前置元数据中设置

**审批：** verified 2026-06-12
