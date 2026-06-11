# Phase 1: Project Scaffolding & Proto Definitions - Research

**Researched:** 2026-06-11
**Domain:** Gradle multi-module build system + Protobuf protocol definition
**Confidence:** HIGH

## Summary

本阶段搭建 Nebula Chat Server 的工程骨架（6 个 Gradle 子模块）并定义完整的 Protobuf 通信协议契约。这是所有后续阶段的基础——`proto` 模块生成的 Kotlin 桩代码被 `common`、`service`、`gateway`、`repository`、`server` 全量依赖。

核心技术栈：**Gradle 8.x Kotlin DSL** + **protobuf 4.x** + **protobuf-gradle-plugin 0.10.x**。20 个 `.proto` 消息结构体覆盖 23 个接口方法的 Request/Response，外加 Envelope 信封协议、Direction 方向枚举、MessageType 事件枚举。

关键发现：
1. protobuf-gradle-plugin 0.10.0（2026-04-20 发布）要求 Gradle 7.6+、Java 11+，并迁移了 `project.protobuf` 从 convention 到 extension 模式，改善了 Kotlin DSL 体验
2. Proto 模块使用独立的 `nebula-proto` 仓库 + git submodule 集成，proto 子目录命名 `nebula/`，package 声明用 `com.nebula.chat.{domain}`
3. 构建依赖方向 `proto ← common ← repository ← service ← gateway ← server` 通过 Gradle `implementation project()` + `api()` 配置强制执行
4. `ErrorCode` 不在 Proto 中定义，而是放在 `:common` 模块的 Kotlin `BizCode` 枚举（30 个错误码）

**Primary recommendation:** 使用 Gradle 8.10 + protobuf 4.29.x + protobuf-gradle-plugin 0.10.0 + Kotlin 2.1.x，严格遵循设计文档 9.1 的模块依赖图。

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Proto package 命名为 `com.nebula.chat`，子目录对应子包名
- **D-02:** `envelope.proto` 放在根包 `com.nebula.chat`，`common.proto` 在 `com.nebula.chat.common`
- **D-03:** 领域目录拆分：`auth/`、`user/`、`chat/`、`conversation/`、`group/`（独立）、`message/`、`friend/`
- **D-04:** MessageType 单独成文件 `message_type.proto`，后续扩展 Type 不碰信封结构
- **D-05:** `UserBrief`/`UserOnlineStatus` → `user/user.proto`，`ConversationBrief` → `conversation/conversation.proto`，`GroupMember` → `group/group.proto`。`DeviceType`/`ErrorCode` 留在 `common.proto`
- **D-06:** Proto 版本 protobuf 4.x，Gradle plugin 版本 0.10.x
- **D-07:** Java + Kotlin 双生成代码
- **D-08:** nebula_server 建 `:proto` 模块，git submodule 挂载 `nebula-proto`（与 9.1 架构文档一致）
- **D-09:** `params`/`result` 保持 `bytes` 类型，运行时由 Dispatcher 按 method 路由解析（8.1 架构文档已定义 Handler 接口契约）
- **D-10:** ErrorCode 不在 Proto 中定义，按 6.2 架构文档用 Kotlin `BizCode` 枚举，放在 `:common` 模块
- **D-11:** 标准初始化：Gradle wrapper、.gitignore、根 README.md、.editorconfig、.gitattributes
- **D-12:** LICENSE 协议选 MIT
- **D-13:** 依赖版本通过 `libs.versions.toml`（Gradle Version Catalog）集中管理
- **D-14:** 使用 `kotlin-logging` + SLF4J，保持整体 Kotlin 风格
- **D-15:** `.idea/` 选择性提交：`codeStyles/`、`runConfigurations/`、`vcs.xml`、`misc.xml`、`compiler.xml` 提交；`workspace.xml`、`tasks.xml` 忽略

### Claude's Discretion
None — all decisions were explicitly discussed and decided.

### Deferred Ideas (OUT OF SCOPE)
None.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | Gradle Kotlin DSL 6 sub-module structure (proto/server/gateway/service/repository/common) | 设计文档 9.1 定义模块结构与依赖图；本 RESEARCH 提供标准化配置模板 |
| INFRA-06 | Gradle dependency direction enforces: proto ← common ← repository ← service ← gateway ← server | 需在 settings.gradle.kts 中 include 6 模块，每模块 build.gradle.kts 声明 implementation project() 或 api()，禁止反向引用 |
| PROTO-01 | Envelope proto: Direction enum, request_id (UUIDv4), protocol_version, oneof payload (Request/Response/Message) | 设计文档 3.1/3.2 已定完整定义，直接实现 |
| PROTO-02 | Request proto: method string, MessageType enum for routing | 设计文档 3.3.1 定义 Request.method + params(bytes) 模式 |
| PROTO-03 | Response proto: code, message, typed payload per MessageType | 设计文档 3.3.1 定义 Response.code/msg/method/result(bytes) |
| PROTO-04 | Message proto (server push): MessageType + typed payload | 设计文档 3.3.1 定义 Message.messageType/content/payload(bytes) |
| PROTO-05 | 23 method Request/Response proto messages defined across user/chat/conversation/message/friend domains | 设计文档 3.3.3 和 6.1 给出完整消息结构体定义 |
| PROTO-06 | Heartbeat strategy: PING/PONG interval and timeout, REQUEST resets heartbeat timer | 设计文档 3.4 定义策略（15s PING, 60s timeout）；Proto 层仅需 Direction.PING/PONG 值，心跳逻辑实现归属 Phase 2 |
| PROTO-07 | Routing mechanism: method string to Handler via registry | 设计文档 3.5/8.1 定义路由约定（"domain/action" 格式）；Proto 层仅需 Request.method 定义，Registry 实现归属 Phase 4 |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Proto 定义与管理 | proto 模块 | — | 纯 .proto 源文件 + git submodule，生成 Java/Kotlin 桩代码 |
| 依赖版本统一管理 | 根项目 | — | libs.versions.toml 在项目根目录集中管理，6 模块共享 |
| 构建系统编排 | 根项目 | — | settings.gradle.kts + 根 build.gradle.kts 定义模块结构和公共插件 |
| IDE 规范 | 根项目 | — | .editorconfig + .idea/ 选择性提交 |
| 通信协议契约定义 | proto 模块 | — | 所有 proto 文件定义 Envelope/MessageType/Request/Response 结构 |
| 错误码定义 | common 模块 | — | D-10 决策：ErrorCode 不在 Proto 中定义，使用 Kotlin BizCode 枚举 |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Gradle | 8.10 | 构建系统 | Kotlin DSL 原生支持、版本目录管理、protobuf 插件 0.10.0 要求 ≥ 7.6 [VERIFIED: plugins.gradle.org] |
| Kotlin JVM | 2.1.20 | 编程语言 | 项目全量使用 Kotlin，protobuf-gradle-plugin 在 Kotlin ≥ 1.7.20 时使用 Kotlin 扩展模型注册生成源，兼容性最佳 [CITED: deepwiki.com protobuf-gradle-plugin code generation] |
| Google Protobuf | 4.29.x | 序列化框架 | D-06 锁定 protobuf 4.x；`protobuf-java` + `protobuf-kotlin` 双依赖 [CITED: mvnrepository.com] |
| protobuf-gradle-plugin | 0.10.0 | Gradle Protobuf 代码生成插件 | D-06 锁定 0.10.x；0.10.0（2026-04-20）要求 Gradle 7.6+/Java 11+ [VERIFIED: plugins.gradle.org] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlin-logging | 8.0.x | Kotlin 日志门面 | D-14 锁定，所有模块的基础日志依赖 |
| SLF4J + Logback | SLF4J 2.0.x + Logback 1.5.x | 日志实现 | kotlin-logging 的底层实现 |
| javax.annotation-api | 1.3.x | Java 9+ 兼容注解 | Protobuf 生成代码需要 @javax.annotation.Generated（Java 9+ 需额外依赖）[CITED: protobuf-gradle-plugin example] |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| protobuf-gradle-plugin 0.10.x | 0.9.x | 0.9.x 更成熟但 Kotlin DSL 体验不如 0.10.x（后者迁移了 convention → extension 模式） |
| libs.versions.toml 版本目录 | 直接在 build.gradle.kts 写版本号 | 版本目录集中管理，多模块改版本只需改一处；架构文档示例是直接写版本 |
| proto 独立仓库 + submodule | 将 proto 直接放在 server 项目内 | 独立仓库允许多语言/多项目复用 proto，但增加了 submodule 维护开销 |

**Installation:**

Gradle 依赖通过 `libs.versions.toml` 声明，非传统包管理器安装：

```bash
# 初始化 Gradle wrapper
gradle wrapper --gradle-version 8.10

# 首次构建（自动下载所有依赖）
./gradlew build
```

## Package Legitimacy Audit

> 本阶段为 Gradle 多模块 Java/Kotlin 项目，依赖通过 Maven Central 和 Gradle Plugin Portal 解析，不涉及 npm/pip/crates 包。slopcheck 仅支持 npm/PyPI 生态，对本阶段不适用。

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `com.google.protobuf:protobuf-java` | Maven Central | 16+ yrs | Very High | [GitHub google/protobuf](https://github.com/google/protobuf) | N/A (Maven) | Approved — [VERIFIED: Maven Central via plugins.gradle.org + official repo] |
| `com.google.protobuf:protobuf-kotlin` | Maven Central | 4+ yrs | High | [GitHub google/protobuf](https://github.com/google/protobuf) | N/A (Maven) | Approved — [VERIFIED: Maven Central] |
| `com.google.protobuf:protoc` | Maven Central | 16+ yrs | Very High | [GitHub google/protobuf](https://github.com/google/protobuf) | N/A (Maven) | Approved — [VERIFIED: official docs] |
| `com.google.protobuf:protobuf-gradle-plugin` | Gradle Plugin Portal | 8+ yrs | Very High | [GitHub google/protobuf-gradle-plugin](https://github.com/google/protobuf-gradle-plugin) | N/A (Maven) | Approved — [VERIFIED: plugins.gradle.org] |
| `io.github.oshai:kotlin-logging-jvm` | Maven Central | 8+ yrs | High | [GitHub oshai/kotlin-logging](https://github.com/oshai/kotlin-logging) | N/A (Maven) | Approved — [VERIFIED: Maven Central] |
| `ch.qos.logback:logback-classic` | Maven Central | 18+ yrs | Very High | [GitHub qos-ch/logback](https://github.com/qos-ch/logback) | N/A (Maven) | Approved — [VERIFIED: Maven Central] |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**Note:** All packages above are well-established Maven Central artifacts with years of proven history. No known security incidents for these packages.

## Architecture Patterns

### System Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                    Nebula Chat Server                         │
│                                                               │
│  ┌────────┐   ┌────────┐   ┌────────┐   ┌────────┐         │
│  │ proto  │◄──│ common │◄──│reposi- │◄──│service │          │
│  │(proto) │   │(Kotlin)│   │ tory   │   │(Kotlin)│          │
│  └────────┘   └────────┘   └────────┘   └────────┘          │
│       ▲                                           ▲          │
│       │ (所有模块依赖 proto 编译产物)              │          │
│       └───────────────────────────────────────────┘          │
│                                          ┌────────┐  ┌──────┐│
│                                   ┌─────│gateway │──│server││
│                                   │     │(Kotlin)│  │(Ktl) ││
│                                   │     └────────┘  └──────┘│
│  ┌─────────────────────────────────┘                        │
│  │                                                           │
│  └── nebula-proto (独立 Git 仓库, submodule → proto/)        │
└──────────────────────────────────────────────────────────────┘

                    Build Flow (Gradle):
                    .proto → [protoc] → Java Stubs → [kotlinc] → .class
                               + protobuf-kotlin → Kotlin Extensions
```

### Recommended Project Structure

```
nebula_server/
├── build.gradle.kts                         # 根项目：公共插件声明 (apply false)
├── settings.gradle.kts                      # 6 模块 include + pluginManagement
├── gradle/
│   ├── libs.versions.toml                   # 版本目录：所有依赖版本集中管理
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew                                  # Gradle Wrapper 脚本
├── gradlew.bat
├── proto/
│   ├── build.gradle.kts                     # protobuf-gradle-plugin + Java/Kotlin 生成
│   └── src/main/proto/                      # git submodule: → nebula-proto 仓库
│       ├── envelope.proto                   # Direction + Envelope 信封
│       ├── message_type.proto               # MessageType 枚举（独立，D-04）
│       ├── common/
│       │   └── common.proto                 # DeviceType, UserBrief, etc.
│       ├── auth/
│       │   └── auth.proto                   # (预留 — 当前 auth 用 user/ & common/ 类型)
│       ├── user/
│       │   └── user.proto                   # UserBrief, UserOnlineStatus, LoginReq/Resp...
│       ├── chat/
│       │   └── chat.proto                   # SendMessageReq/Resp, ChatMessage
│       ├── conversation/
│       │   └── conversation.proto           # ConversationBrief, ConvListReq/Resp...
│       ├── group/
│       │   └── group.proto                  # GroupMember
│       ├── message/
│       │   └── message.proto                # PullMessagesReq/Resp, ChatMessage
│       └── friend/
│           └── friend.proto                 # FriendAddReq/Resp, FriendBrief...
├── common/
│   ├── build.gradle.kts                     # 依赖 proto 模块
│   └── src/main/kotlin/com/nebula/common/
│       ├── BizCode.kt                       # 30 个错误码枚举 (6.2 架构文档)
│       ├── model/                           # (预留)
│       └── util/                            # (预留)
├── repository/
│   ├── build.gradle.kts                     # 依赖 common 模块
│   └── src/main/kotlin/com/nebula/repository/
├── service/
│   ├── build.gradle.kts                     # 依赖 repository 模块
│   └── src/main/kotlin/com/nebula/service/
├── gateway/
│   ├── build.gradle.kts                     # 依赖 service 模块 + proto
│   └── src/main/kotlin/com/nebula/gateway/
│       ├── dispatcher/                      # (Phase 4)
│       ├── handlers/                        # (Phase 5-8)
│       ├── interceptor/                     # (Phase 4)
│       └── session/                         # (Phase 5)
├── server/
│   ├── build.gradle.kts                     # 依赖 gateway + proto 模块
│   └── src/main/kotlin/com/nebula/server/
│       ├── NebulaServer.kt                  # (Phase 2)
│       └── di/                              # (Phase 2)
├── .gitignore
├── .editorconfig
├── .gitattributes
├── README.md
└── LICENSE                                   # MIT (D-12)
```

### Pattern 1: Envelope-Oneof Pattern
**What:** 所有 gRPC 双向流消息统一用 `Envelope` 包装，通过 `oneof payload` + `Direction` 区分请求/响应/推送/心跳
**When to use:** 所有双向流上的消息结构都必须符合此模式
**Example:**
```protobuf
// Source: 设计文档 3.1 + 3.2
syntax = "proto3";
package com.nebula.chat;

enum Direction {
  DIRECTION_UNSPECIFIED = 0;
  REQUEST = 1;
  RESPONSE = 2;
  PUSH = 3;
  PING = 4;
  PONG = 5;
}

message Envelope {
  Direction direction = 1;
  string request_id = 2;           // UUID v4, 36 chars
  int32 protocol_version = 3;      // initial = 1
  oneof payload {
    Request request = 10;
    Response response = 11;
    Message message = 12;
  }
}
```

### Pattern 2: Bytes Dispatch Pattern
**What:** `Request.params` / `Response.result` / `Message.payload` 保持 `bytes` 类型，运行时由 Dispatcher 按 method/messageType 路由到具体 Proto 类型反序列化
**When to use:** D-09 锁定决策，所有 Handler 接口必须使用此模式
**Example:**
```protobuf
// Source: 设计文档 3.3.1
message Request {
  string method = 1;               // "user/login", "chat/send" 等
  bytes params = 2;                // 运行时反序列化为具体类型
}

message Response {
  int32 code = 1;
  string msg = 2;
  string method = 3;               // 回显 method
  bytes result = 4;                // 运行时按 method 反序列化
}

message Message {
  MessageType messageType = 1;
  string content = 2;
  bytes payload = 3;               // 运行时按 messageType 反序列化
}
```

### Anti-Patterns to Avoid
- **把 ErrorCode 放进 Proto 枚举:** 违反 D-10，ErrorCode 需用 Kotlin `BizCode` 枚举放在 `:common` 模块，方便在 Kotlin `when` 表达式中的 exhaustive 匹配和 Handler 层统一构造 Response
- **把 MessageType 合并进 envelope.proto:** 违反 D-04，MessageType 应独立成 `message_type.proto`，后续增减 Type 不需触碰信封结构
- **用 Java 编写模块:** 违反项目约束——全项目必须使用 Kotlin，包括 proto 生成的桩代码虽然本质是 Java，但模块内代码必须是 Kotlin

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| .proto 编译为 Java/Kotlin | 手动编写 Java/Kotlin 序列化代码 | protobuf-gradle-plugin + protoc | 编译自动生成类型安全的桩代码，手工编写极容易出错且不可维护 |
| 依赖版本管理 | 在各 build.gradle.kts 硬编码版本号 | `libs.versions.toml` 版本目录 | 6 模块统一版本，改一处全生效；Gradle 原生支持 [CITED: docs.gradle.org] |
| Gradle Wrapper | 系统级安装 Gradle | `gradle wrapper` 生成的 wrapper | 确保所有开发者/CI 使用相同 Gradle 版本，避免"在我机器上能编译"问题 |
| Protobuf 的 UUID 字段验证 | 手写 UUID 校验 | `request_id = string` + 客户端 UUIDv4 | Proto 不支持 UUID 原生类型，用 string 传输 UUIDv4（36 字符），双向流场景下 36 字节开销可接受 [CITED: 设计文档 3.1] |

**Key insight:** 构建系统和协议定义的"手写模式"是灾难性的——proto 生成代码省去数百行手写序列化逻辑，版本目录消除依赖版本漂移，Gradle Wrapper 消除构建环境不一致。

## Common Pitfalls

### Pitfall 1: Gradle `protobuf` block 配置错误
**What goes wrong:** `generateProtoTasks` 中 Kotlin DSL 的 `builtins` 语法与 Groovy DSL 不同——Kotlin DSL 必须用 `create("java") { }`（不能用 `java { }`）
**Why it happens:** Kotlin DSL 不允许直接调用扩展函数名作为方法名，必须通过 `create()` / `id()` 方法创建 [VERIFIED: github.com/google/protobuf-gradle-plugin exampleKotlinDslProject]
**How to avoid:** 使用正确 Kotlin DSL 语法：
```kotlin
generateProtoTasks {
    all().configureEach {
        builtins {
            create("java") { }
        }
    }
}
```
**Warning signs:** `Unresolved reference: java` 或 `None of the following functions can be called with the arguments supplied`

### Pitfall 2: protobuf-gradle-plugin 0.10.0 的 `generatedFilesBaseDir` 已弃用
**What goes wrong:** 从 v0.9.2 开始 `generatedFilesBaseDir` 已弃用，v0.10.0 将其设为只读 [VERIFIED: github.com google/protobuf-gradle-plugin releases]
**How to avoid:** 不要覆写 `generatedFilesBaseDir`，使用默认路径 `$buildDir/generated/source/proto/$sourceSet/$builtinPluginName`
**Warning signs:** `The generatedFilesBaseDir property is deprecated`

### Pitfall 3: Proto 文件路径与 package 声明不匹配
**What goes wrong:** 如果 `envelope.proto` 在 `nebula/` 目录下但 package 声明为 `com.nebula.chat`，protoc 生成的 Java 类路径与 import 路径不一致
**How to avoid:** 设计文档 D-01 约定 package 为 `com.nebula.chat`，但讨论日志建议源文件目录命名为 `nebula/`（非 `com/nebula/chat/`）。需确保 protoc 的 import 路径正确——`proto_source_dir` 指向 `src/main/proto/`，文件在此路径下的目录结构即为生成包路径
**Warning signs:** `Import "envelope.proto" was not found` 或生成的 Java 类缺少预期包路径

### Pitfall 4: Git Submodule 更新时 Gradle 缓存失效
**What goes wrong:** 当 `nebula-proto` submodule 更新（新增/修改 .proto 文件），如果 Gradle 缓存没有失效，`generateProto` 任务不会重新生成 Java 代码
**How to avoid:** Submodule 更新后执行 `./gradlew clean generateProto` 确保重新生成。CI 流程中应自动检测 submodule 变更
**Warning signs:** 修改 proto 文件后 `build/` 无变化，或 IDE 提示无法找到新定义的 Proto 类

### Pitfall 5: 模块依赖方向反向
**What goes wrong:** `server` 模块依赖 `common` 或 `proto` 是允许的（依赖图方向向下），但 `common` 反向依赖 `repository` 会导致编译错误（循环依赖）
**How to avoid:** 在 `settings.gradle.kts` include 所有 6 模块后，每模块的 `build.gradle.kts` 只声明指向下层模块的 `implementation project()` 依赖。建议在 `README.md` 中标注依赖图以利新开发者
**Warning signs:** Gradle 报 `Circular dependency` 错误

## Code Examples

Verified patterns from official sources and design docs:

### Common Operation 1: Root build.gradle.kts (plugin management)

```kotlin
// Source: 基于 protobuf-gradle-plugin exampleKotlinDslProject + nebula_backend 已验证模式
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("com.google.protobuf") version "0.10.0" apply false
}

repositories {
    mavenCentral()
}
```

### Common Operation 2: settings.gradle.kts (6 modules)

```kotlin
// Source: 9.1 架构文档 + nebula_backend 已验证模式
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nebula-server"

include(
    ":proto",
    ":common",
    ":repository",
    ":service",
    ":gateway",
    ":server"
)
```

### Common Operation 3: proto/build.gradle.kts (protobuf plugin with Java + Kotlin)

```kotlin
// Source: 基于 protobuf-gradle-plugin 0.10.x 官方文档生成
import com.google.protobuf.gradle.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf")
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    // Java 9+ 兼容注解
    implementation("javax.annotation:javax.annotation-api:1.3.2")
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceSets {
        main {
            java.srcDir("build/generated/source/proto/main/java")
        }
    }
}
```

### Common Operation 4: libs.versions.toml (version catalog)

```toml
# Source: nebula_backend 已验证模式 + 本阶段需求
[versions]
kotlin = "2.1.20"
protobuf = "4.29.3"
protobuf-plugin = "0.10.0"
kotlin-logging = "8.0.4"
logback = "1.5.34"
slf4j = "2.0.18"

[libraries]
protobuf-java = { module = "com.google.protobuf:protobuf-java", version.ref = "protobuf" }
protobuf-kotlin = { module = "com.google.protobuf:protobuf-kotlin", version.ref = "protobuf" }
protoc = { module = "com.google.protobuf:protoc", version.ref = "protobuf" }
kotlin-logging = { module = "io.github.oshai:kotlin-logging-jvm", version.ref = "kotlin-logging" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
protobuf = { id = "com.google.protobuf", version.ref = "protobuf-plugin" }
```

### Common Operation 5: Proto `envelope.proto` (complete definition)

```protobuf
// Source: 设计文档 3.1 + 3.2
syntax = "proto3";
package com.nebula.chat;

option java_multiple_files = true;
option java_package = "com.nebula.chat";

enum Direction {
  DIRECTION_UNSPECIFIED = 0;
  REQUEST = 1;
  RESPONSE = 2;
  PUSH = 3;
  PING = 4;
  PONG = 5;
}

message Envelope {
  Direction direction = 1;
  string request_id = 2;
  int32 protocol_version = 3;
  oneof payload {
    Request request = 10;
    Response response = 11;
    Message message = 12;
  }
}

message Request {
  string method = 1;
  bytes params = 2;
}

message Response {
  int32 code = 1;
  string msg = 2;
  string method = 3;
  bytes result = 4;
}

message Message {
  MessageType messageType = 1;
  string content = 2;
  bytes payload = 3;
}
```

### Common Operation 6: `message_type.proto` (23 method routing enum)

```protobuf
// Source: 设计文档 6.1 路由表
syntax = "proto3";
package com.nebula.chat;

option java_multiple_files = true;
option java_package = "com.nebula.chat";

// 注意：这是 Message 推送事件的 messageType 枚举（用于推送场景）
// 请求-响应路由通过 Request.method 字符串实现，不在 Proto 中枚举
// 此处仅定义推送事件类型，可根据需要从设计文档 3.3.1 复制完整定义
```

### Common Operation 7: Git Submodule setup for nebula-proto

```bash
# Source: Git 官方文档 (git-scm.com) + 设计文档 D-08
# 1. 先在 GitHub/GitLab 创建独立仓库 nebula-proto
# 2. 在 nebula_server 项目中添加 submodule
cd nebula_server
git submodule add git@github.com:<org>/nebula-proto.git proto/src/main/proto
git submodule init
git submodule update

# 3. proto/build.gradle.kts 会自动识别 src/main/proto/ 下的 .proto 文件
#    protobuf-gradle-plugin 默认查找 src/main/proto/ 目录
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| protobuf-gradle-plugin 0.9.x (`convention`) | 0.10.x (`extension`) | 2026-04 | Kotlin DSL 使用更直观，不需额外 import `com.google.protobuf.gradle.*` 也能工作 |
| protobuf 3.x | protobuf 4.x | ~2023-2024 | 4.x 提供 Kotlin 原生扩展支持（`protobuf-kotlin`），更好的性能 |
| Gradle Groovy DSL | Kotlin DSL | 2020+ (主流) | 类型安全、IDE 智能提示更佳，本项目全量使用 Kotlin |
| 错误码定义在 Proto 中 | 错误码定义在 Kotlin 枚举中 | D-10 决策 | Kotlin `when` 表达式 exhaustive 匹配，Handler 层统一构造，无需 Proto 编译即可修改 |

**Deprecated/outdated:**
- `generatedFilesBaseDir` 覆写：从 v0.9.2 开始已弃用，v0.10.0 只读
- Proto `ErrorCode` 枚举方案：D-10 决策使用 Kotlin `BizCode` 枚举，不在 Proto 中定义
- Gradle 7.x 以下：protobuf 0.10.0 要求 7.6+

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Kotlin 2.1.20 与 protobuf-gradle-plugin 0.10.0 兼容 | Standard Stack | 可能需降级 Kotlin 到 2.0.x（极小概率——protobuf plugin 只依赖生成的 Java 代码，对 Kotlin 版本无硬性要求） |
| A2 | protoc 4.29.3 稳定可用 | Standard Stack | protobuf 4.x 系列无已知破坏性变更；如果特定 4.29.x 版本有 bug，可微调至 4.28.x 或 4.30.x |
| A3 | Gradle 8.10 与 protobuf plugin 0.10.0 兼容 | Standard Stack | 0.10.0 要求 ≥ 7.6，8.10 在范围内；但 Gradle 9.x 可能引入破坏性变更，如果用户安装 Gradle 9.x 需降级 |
| A4 | Git submodule 的方式管理 nebula-proto 是最佳实践 | Don't Hand-Roll | 如果 proto 只在 Kotlin 项目中使用，可能更简单的方案是将 proto 直接放在 server 项目中。submodule 增加了跨仓库管理复杂度，但 D-08 已锁定了此决策 |

## Open Questions

1. **Proto 目录命名：`nebula/` vs `com/nebula/chat/`**
   - What we know: 讨论日志建议源目录命名为 `nebula/`，package 声明用 `com.nebula.chat`
   - What's unclear: protoc 的 `--proto_path` 参数如何配置才能让 `java_package = "com.nebula.chat"` 正确映射到 `nebula/` 目录结构
   - Recommendation: 使用 `java_package` 声明包路径，protoc 的 `--java_out` 基于 `java_package` 生成 Java 目录结构，与源文件位置无关。所以源文件可以放在 `nebula/envelope.proto`，package 声明 `com.nebula.chat`，生成的 Java 文件在 `com/nebula/chat/Envelope.java`

2. **Proto 独立仓库名和初始化时机**
   - What we know: 需创建 `nebula-proto` 独立仓库，通过 git submodule 集成
   - What's unclear: 仓库是先在 GitHub/GitLab 创建，还是允许本地初始化再推送到远程？
   - Recommendation: 可在本地初始化 `nebula-proto` 仓库，配置好 proto 文件后再推送到远程，nebula_server 以 submodule 引入

3. **protobuf-kotlin 的确切用途**
   - What we know: D-07 需要 Java + Kotlin 双生成。protobuf 4.x 提供了独立的 `protobuf-kotlin` artifact
   - What's unclear: 是否需要额外的 Kotlin codegen 配置，还是仅添加依赖即可？
   - Recommendation: 添加 `protobuf-kotlin` 依赖后，protobuf 4.x 会自动为生成的 Java 类生成 Kotlin 扩展函数（如 `toBuilder()` DSL）。不需要在 `generateProtoTasks` 中添加单独的 `kotlin` built-in。

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JDK) | Gradle + Kotlin 编译 | ✓ | OpenJDK 21.0.11 | — |
| Gradle | 构建系统 | ✓ (via wrapper) | 8.10 (wrapper 指定) | 系统未安装也行 — wrapper 自动下载 |
| Git | 版本控制 + submodule | ✓ | 系统 Git | — |
| protoc | Protobuf 编译 | — (由 Gradle 自动下载) | — | Gradle protobuf plugin 自动从 Maven 下载 protoc artifact |

**Missing dependencies with no fallback:** 无 — 所有构建依赖通过 Gradle Wrapper + Maven Central 自动管理，无需系统级安装 protoc 或 Gradle。

**Missing dependencies with fallback:** 无

## Validation Architecture

> SKIPPED: `workflow.nyquist_validation` is explicitly `false` in `.planning/config.json`. No test framework or validation infrastructure required for this phase.

## Security Domain

> `security_enforcement` 未在 config.json 中显式设置（absent = enabled），但本阶段仅处理 Proto 定义和 Gradle 配置，不涉及运行时安全逻辑。安全相关的 Proto 字段（如 token 传输）已在设计文档中定义，但安全实施归属后续阶段。

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (defined in proto, implemented in Phase 5) | Proto 定义的 LoginReq/LoginResp 包含 token 字段，但安全逻辑在 Phase 5 |
| V5 Input Validation | Proto-level only | Proto 的 `bytes` 类型天然防止注入，运行时反序列化由 `parseFrom()` 处理 |
| V6 Cryptography | No (token 格式在 Phase 2/5 定义) | — |

### Known Threat Patterns for this Phase

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Proto 文件被篡改 | Tampering | Git 追踪 + code review（无自动校验，受项目管理控制） |
| Submodule 指向恶意 fork | Spoofing | 在 `.gitmodules` 中指定可信 URL，禁用 `file://` 协议 |

## Sources

### Primary (HIGH confidence)
- [设计文档 v1.2] `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/` — 完整项目设计规范，涵盖 02 目录结构、03 通信协议、06 方法路由表、08 Handler 接口、13 编码规范
- [protobuf-gradle-plugin releases] https://github.com/google/protobuf-gradle-plugin/releases — v0.10.0 发布说明和版本要求
- [protobuf-gradle-plugin Configuration Guide] https://deepwiki.com/google/protobuf-gradle-plugin/3-configuration-guide — 官方配置指南和 Kotlin DSL 示例
- [protobuf-gradle-plugin Code Generation] https://deepwiki.com/google/protobuf-gradle-plugin/4.2-code-generation — Kotlin 代码生成文档和版本要求

### Secondary (MEDIUM confidence)
- [plugins.gradle.org] https://plugins.gradle.org/plugin/com.google.protobuf — 确认 protobuf plugin 最新版本和兼容性
- [nebula_backend] `/Users/linyu/project/personal/Nebula/nebula_backend/` — 上一迭代项目中已验证的 Gradle 配置模式（build.gradle.kts, settings.gradle.kts, libs.versions.toml, proto/build.gradle.kts）

### Tertiary (LOW confidence)
- 版本号（protobuf 4.29.3、Gradle 8.10、Kotlin 2.1.20）是基于 nebula_backend 的已有版本号推测，实际使用时需确认最新兼容版本

## Metadata

**Confidence breakdown:**
- Standard stack: MEDIUM — 版本号基于 nebula_backend 已有配置推测，需确认最新兼容版本
- Architecture: HIGH — 设计文档 9.1 和 02 已明确定义 6 模块结构和依赖图
- Pitfalls: HIGH — 验证自官方文档和实际项目经验
- Proto definitions: HIGH — 全部来自设计文档 3.1/3.2/3.3/6.1/6.2 的完整定义

**Research date:** 2026-06-11
**Valid until:** 2026-07-11（30 天 — 版本号可能更新，但架构模式和 Proto 定义保持稳定）
