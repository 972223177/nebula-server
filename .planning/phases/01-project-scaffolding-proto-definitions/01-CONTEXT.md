# Phase 01: Project Scaffolding & Proto Definitions - Context

**Gathered:** 2026-06-11
**Status:** Ready for planning

<domain>
## Phase Boundary

搭建 Nebula Chat Server 的 Gradle 多模块工程骨架，定义完整的 Protobuf 通信协议契约，并配置工程基础设施（版本管理、日志、IDE 规范等）。

具体交付：
1. 6 个子模块的 Gradle 构建系统（Kotlin DSL）
2. 独立的 `nebula-proto` 仓库（纯 .proto 源文件），通过 git submodule 集成到 server 的 `:proto` 模块
3. 8 个 `.proto` 文件 + `message_type.proto`，覆盖全部 23 个接口方法的协议定义
4. 工程脚手架：Gradle wrapper、.gitignore、README.md、.editorconfig、.gitattributes、LICENSE(MIT)
</domain>

<decisions>
## Implementation Decisions

### Proto 文件组织

- **D-01:** Proto package 命名为 `com.nebula.chat`，子目录对应子包名
- **D-02:** `envelope.proto` 放在根包 `com.nebula.chat`，`common.proto` 在 `com.nebula.chat.common`
- **D-03:** 领域目录拆分：`auth/`、`user/`、`chat/`、`conversation/`、`group/`（独立）、`message/`、`friend/`
- **D-04:** MessageType 单独成文件 `message_type.proto`，后续扩展 Type 不碰信封结构
- **D-05:** `UserBrief`/`UserOnlineStatus` → `user/user.proto`，`ConversationBrief` → `conversation/conversation.proto`，`GroupMember` → `group/group.proto`。`DeviceType`/`ErrorCode` 留在 `common.proto`

### Protobuf 构建策略

- **D-06:** Proto 版本 protobuf 4.x，Gradle plugin 版本 0.10.x
- **D-07:** Java + Kotlin 双生成代码
- **D-08:** nebula_server 建 `:proto` 模块，git submodule 挂载 `nebula-proto`（与 9.1 架构文档一致）
- **D-09:** `params`/`result` 保持 `bytes` 类型，运行时由 Dispatcher 按 method 路由解析（8.1 架构文档已定义 Handler 接口契约）

### 错误码

- **D-10:** ErrorCode 不在 Proto 中定义，按 6.2 架构文档用 Kotlin `BizCode` 枚举，放在 `:common` 模块

### 工程脚手架

- **D-11:** 标准初始化：Gradle wrapper、.gitignore、根 README.md、.editorconfig、.gitattributes
- **D-12:** LICENSE 协议选 MIT

### Gradle 配置

- **D-13:** 依赖版本通过 `libs.versions.toml`（Gradle Version Catalog）集中管理

### 日志框架

- **D-14:** 使用 `kotlin-logging` + SLF4J，保持整体 Kotlin 风格

### IDE 配置

- **D-15:** `.idea/` 选择性提交：`codeStyles/`、`runConfigurations/`、`vcs.xml`、`misc.xml`、`compiler.xml` 提交；`workspace.xml`、`tasks.xml` 忽略

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 架构设计文档
- `设计文档/后端架构设计v1.2/02-项目目录结构.md` — 6 模块目录结构定义
- `设计文档/后端架构设计v1.2/09-基础设施设计/9.1-Gradle多模块.md` — 模块依赖关系和 Gradle 配置骨架
- `设计文档/后端架构设计v1.2/03-通信协议/3.3-内层消息与Payload/` — Envelope、MessageType、Payload 结构体定义
- `设计文档/后端架构设计v1.2/06-接口方法列表/6.1-方法路由表.md` — 23 个方法的请求/响应消息对照表
- `设计文档/后端架构设计v1.2/06-接口方法列表/6.2-错误码.md` — 30 个错误码 + Kotlin BizCode 枚举
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.1-接口契约.md` — Handler 泛型接口 + Dispatcher 编解码约定
- `设计文档/后端架构设计v1.2/13-编码与规范.md` — Kotlin/Proto 编码规范

### 项目规划
- `.planning/PROJECT.md` — 项目概览和核心价值
- `.planning/REQUIREMENTS.md` — 70 个 v1 需求映射
- `.planning/STATE.md` — 项目状态

</canonical_refs>

<code_context>
## Existing Code Insights

绿色项目，无现有代码。所有代码从零构建。

### Reusable Assets
- 无现有可复用资产

### Established Patterns
- 设计文档已有完整的 Proto 结构定义和模块架构，直接作为实现依据

### Integration Points
- `:proto` 模块是所有模块的底层依赖（9.1 架构文档依赖图）
- `nebula-proto` 需先创建独立 git 仓库，然后在 `nebula_server` 中作为 submodule 添加

</code_context>

<specifics>
## Specific Ideas

- Proto 目录命名为 `nebula/`（非 `com/nebula/chat/`），与设计文档的 Nebula 品牌名一致
- 每个 `.proto` 文件的 `package` 声明用 `com.nebula.chat.{domain}` 格式
- Gradle 用 Kotlin DSL，settings.gradle.kts 定义 6 个模块

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 01-Project Scaffolding & Proto Definitions*
*Context gathered: 2026-06-11*
