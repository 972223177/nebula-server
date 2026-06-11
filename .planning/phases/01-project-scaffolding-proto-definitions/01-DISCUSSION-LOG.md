# Phase 01: Project Scaffolding & Proto Definitions - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-11
**Phase:** 01-Project Scaffolding & Proto Definitions
**Areas discussed:** Proto package naming, Protobuf codegen strategy, Error code definitions, Dev scaffolding extent, Gradle 版本目录策略, 日志框架选择, IDE 配置管理

---

## Proto Package Naming

| Option | Description | Selected |
|--------|-------------|----------|
| nebula.chat.v1 | 简洁，与 Nebula 品牌一致 | |
| com.nebula.chat | 标准反向域名格式，适合长期维护 | ✓ |
| io.github.nebula.chat | 含 GitHub 域名 | |

**User's choice:** com.nebula.chat
**Notes:** 子目录对应子包名。envelope.proto 放在根包 com.nebula.chat，common 在 com.nebula.chat.common，领域目录对应 com.nebula.chat.{domain}。

Proto 为独立仓库 nebula-proto，纯 .proto 源文件，server 通过 git submodule 引入。

Proto 目录布局按领域拆分：envelope.proto、common.proto、message_type.proto（独立）、auth/、user/、chat/、conversation/、group/（独立）、message/、friend/。

UserBrief/UserOnlineStatus → user/，ConversationBrief → conversation/，GroupMember → group/，DeviceType/ErrorCode 留在 common.proto。

---

## Protobuf Codegen Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| protobuf 4.x + plugin 0.9.x | 成熟稳定 | |
| protobuf 4.x + plugin 0.10.x | 更新的版本，更好的 Kotlin 支持 | ✓ |
| 仅 Java 生成 | 只用 protobuf-java | |
| Java + Kotlin 双生成 | protobuf-java + protobuf-kotlin | ✓ |
| 发布独立 artifact | nebula-proto 发布为 Maven artifact | |
| server 内 proto 模块 + submodule | 符合 9.1 架构文档的设计 | ✓ |

**User's choice:** protobuf 4.x + plugin 0.10.x, Java+Kotlin 双生成, server 内 proto 模块 + git submodule

---

## Error Code Definitions

| Option | Description | Selected |
|--------|-------------|----------|
| Proto 枚举 | 在 common.proto 中加 ErrorCode 枚举 | |
| Kotlin 枚举 | 按 6.2 架构文档用 Kotlin BizCode 枚举 | ✓ |

**User's choice:** Kotlin BizCode 枚举，放在 :common 模块下
**Notes:** 架构文档 6.2 已定义完整 30 个错误码和 BizCode 枚举类。proto 编译自动化无需手动触发，但仍决定保持 Kotlin 枚举。

---

## Dev Scaffolding Extent

| Option | Description | Selected |
|--------|-------------|----------|
| 选项一：核心仅 Gradle 模块 | 最小范围 | |
| 选项二：标准工程初始化 | 一次配好 | ✓ |

**User's choice:** 选项二 — Gradle wrapper、.gitignore、README.md、.editorconfig、.gitattributes、LICENSE(MIT)

---

## Gradle 版本目录策略

| Option | Description | Selected |
|--------|-------------|----------|
| libs.versions.toml | 版本目录集中管理 | ✓ |
| 直接写 build.gradle.kts | 架构文档示例写法 | |
| gradle.properties + 变量 | 中间方案 | |

**User's choice:** libs.versions.toml

---

## 日志框架选择

| Option | Description | Selected |
|--------|-------------|----------|
| SLF4J + Logback | JVM 生态标准，支持广泛 | |
| kotlin-logging + SLF4J | Kotlin 语法友好，惰性求值 | ✓ |

**User's choice:** kotlin-logging + SLF4J，保持整体 Kotlin 风格

---

## IDE 配置管理

| Option | Description | Selected |
|--------|-------------|----------|
| 完全忽略 | .idea/* 全部忽略 | |
| 选择性提交 | 稳定文件提交，workspace.xml/tasks.xml 忽略 | ✓ |
| 全部提交 | 包括 workspace.xml | |

**User's choice:** 选择性提交 — codeStyles/、runConfigurations/、vcs.xml、misc.xml、compiler.xml 提交；workspace.xml、tasks.xml 忽略

---

## Claude's Discretion

None — all decisions were discussed and explicitly decided by user.

## Deferred Ideas

None.
