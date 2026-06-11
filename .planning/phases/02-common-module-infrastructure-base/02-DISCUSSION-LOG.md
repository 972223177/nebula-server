# Phase 02: Common Module & Infrastructure Base - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-11
**Phase:** 02-Common Module & Infrastructure Base
**Areas discussed:** 配置管理策略, BizException 位置, 开发环境 SSL 证书, HikariCP 多数据源, Snowflake 时钟回拨, Common 模块包结构, 日志配置文件位置

---

## 配置管理策略

| Option | Description | Selected |
|--------|-------------|----------|
| Typesafe Config (HOCON) | 轻量级，纯 Kotlin 实现，类型安全，支持环境变量插值、fallback 链 | ✓ |
| application.yml (Spring) | Spring Boot 风格，但引入 spring-boot-starter 增加依赖重量 | |
| 纯 properties 文件 | 最简单，零依赖，但嵌套和类型转换需手动处理 | |

**User's choice:** Typesafe Config (HOCON)

| Option | Description | Selected |
|--------|-------------|----------|
| 单文件 + 环境变量覆盖 | 一个 application.conf，运行时通过 ENV 或 JVM 属性切换环境 | ✓ |
| 多文件，环境分离 | dev.conf / prod.conf 分离，启动时通过参数指定加载 | |

**User's choice:** 单文件 + 环境变量覆盖

| Option | Description | Selected |
|--------|-------------|----------|
| config/ 目录下提交 git | 配置与应用代码同仓库，开发友好 | ✓ |
| 应用外路径不提交 git | 配置与代码分离，更安全但调试不便 | |

**User's choice:** config/ 目录下提交 git

| Option | Description | Selected |
|--------|-------------|----------|
| 统一配置对象 | ApplicationConfig 合并所有配置 | ✓ |
| 按模块分散 | 各模块独立 Config 类 | |

**User's choice:** 统一配置对象

**Notes:** 用户明确选择轻量级方案，避免引入 Spring 依赖。配置提交 git 但敏感信息通过环境变量注入。

---

## BizException 位置

| Option | Description | Selected |
|--------|-------------|----------|
| common 模块 | BizCode 已在 common，Exception 放一起，无反向依赖 | ✓ |
| gateway 模块 | 靠近 Handler 框架，但产生依赖交叉 | |

**User's choice:** common 模块

| Option | Description | Selected |
|--------|-------------|----------|
| 单一 BizException | 一种异常带 BizCode，统一处理 | |
| 按领域细分异常类 | UserException / ChatException / FriendException 等继承 BizException | ✓ |

**User's choice:** 按领域细分异常类

| Option | Description | Selected |
|--------|-------------|----------|
| 直接 throw | throw BizException(BizCode.xxx) — 显式清晰 | ✓ |
| 扩展函数 | BizCode.toException() — 多一层间接 | |

**User's choice:** 直接 throw

**Notes:** 用户倾向完整的领域异常体系，与 BizCode 的领域分类一致。

---

## 开发环境 SSL 证书

| Option | Description | Selected |
|--------|-------------|----------|
| 脚本生成 + 提交 git | generate-dev-cert.sh，run 一次即可 | ✓ |
| build 时自动生成 | 零干预但变慢且证书频繁变化 | |
| 开发者自行生成 | 流程最重 | |

**User's choice:** 脚本生成 + 提交 git

| Option | Description | Selected |
|--------|-------------|----------|
| config/dev/ssl/ | 与配置文件平级 | ✓ |
| 项目根 ssl/ 目录 | 独立于 config | |

**User's choice:** config/dev/ssl/

**Notes:** 简单明确，与配置管理策略的 config/ 目录一致。

---

## HikariCP 多数据源

| Option | Description | Selected |
|--------|-------------|----------|
| Phase 2 先单数据源 | 减少 Phase 2 交付范围 | ✓ |
| 提前预留主从骨架 | 指向同一 MySQL，方便后期过渡 | |

**User's choice:** Phase 2 先单数据源

| Option | Description | Selected |
|--------|-------------|----------|
| 直接 HikariDataSource | 简单直接 | |
| 封装 DataSourceProvider 接口 | 预留扩展能力 | ✓ |

**User's choice:** 封装 DataSourceProvider 接口

**Notes:** 虽然选单数据源，但通过接口封装预留主从扩展。

---

## Snowflake 时钟回拨

| Option | Description | Selected |
|--------|-------------|----------|
| 抛异常 + 手动重启 | 设计文档方案，简单可靠 | ✓ |
| 等待回退不抛异常 | 容忍轻微回拨 | |

**User's choice:** 抛异常 + 手动重启

| Option | Description | Selected |
|--------|-------------|----------|
| 区分回拨和回卷异常类 | ClockBackwardsException + SequenceOverflowException | ✓ |
| 单一异常类 | 不细分 | |

**User's choice:** 区分回拨异常类和回卷异常类

**Notes:** 保持设计文档的简单策略，但增加异常类型的区分使调试更清晰。

---

## Common 模块包结构

| Option | Description | Selected |
|--------|-------------|----------|
| 按功能分包 | config / exception / idgen / util | ✓ |
| 按组件分包 | snowflake / hikari / ... | |

**User's choice:** 按功能分包

**Notes:** 标准 Kotlin 项目结构，易于扩展。

---

## 日志配置文件位置

| Option | Description | Selected |
|--------|-------------|----------|
| server 模块 | 启动入口模块 | |
| common 模块 | 下沉到低层模块 | ✓ |

**User's choice:** common 模块

| Option | Description | Selected |
|--------|-------------|----------|
| logback-dev.xml + logback-prod.xml | 按环境分别配置 | ✓ |
| 一个 logback.xml 动态判断 | 运行时切换 appender | |

**User's choice:** logback-dev.xml + logback-prod.xml 分别配置

**Notes:** logback.xml 在 common 模块，启动时通过 server 模块设定系统属性加载。

---

## Claude's Discretion

无 — 所有灰色区域用户都做了明确选择。

## Deferred Ideas

- HikariCP 主从多数据源 — Phase 3 实现
- Snowflake Worker ID 动态分配 — 分布式后考虑
