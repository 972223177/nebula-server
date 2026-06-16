---
phase: 12-module-dependency-isolation
status: contexted
---

# Phase 12: Module Dependency Isolation — Context

## 阶段目标

将所有 Gradle 模块间的项目依赖从 `api` 改为 `implementation`，消除传递依赖，强制每个模块显式声明其直接依赖。同时消除 gateway → repository 的跨层引用，确保编译隔离符合分层架构。

### 目标依赖图

```
proto           — 无项目依赖
common          — implementation(project(":proto"))
repository      — implementation(project(":proto")) implementation(project(":common"))
service         — implementation(project(":proto")) implementation(project(":common")) implementation(project(":repository"))
gateway         — implementation(project(":proto")) implementation(project(":common")) implementation(project(":service"))
server          — implementation(project(":proto")) implementation(project(":common")) implementation(project(":gateway"))
```

## 关联需求

| 需求编号 | 描述 | 优先级 |
|---------|------|--------|
| D-28 | 分层架构依赖隔离：gateway 不应直接依赖 repository | P0 |
| INFRA-07 | 显式依赖声明优于 api 传递依赖 | P1 |

## 技术决策

| 编号 | 决策 | 理由 |
|------|------|------|
| D-101 | 所有项目模块间依赖使用 `implementation` | 显式依赖声明比 api 传递更清晰，编译隔离更好 |
| D-102 | SessionStore 接口放在 common 模块 | 避免 repository → service 循环依赖 |
| D-103 | DeadLetterCallback 接口放在 common 模块 | repository 和 service 均可独立引用，支持跨层回调桥接 |
| D-104 | 服务层返回 DTO 而非实体类型 | gateway 通过 implementation 依赖 service 后无法访问 repository 实体类型 |
| D-105 | ServiceModule（Koin）从 gateway 移至 service 模块 | service 构造函数依赖 repository 类型，gateway 不应可见 |

## 实现约束

- gateway 生产代码中不得存在任何 `com.nebula.repository.*` import
- repository 模块的 entity 类型不得在 gateway 中使用（通过 DTO 隔离）
- 所有新抽象（接口、DTO）需带中文 KDoc 注释
- 不新增测试，仅确保现有测试可编译通过

## 关键产出物

### 新建文件 (service 层抽象)

| 文件 | 说明 |
|------|------|
| `service/.../user/OnlineStatusService.kt` | 包装 OnlineStatusRepository |
| `service/.../user/OnlineStatusInfo.kt` | 在线状态 DTO |
| `service/.../session/SessionStore.kt` → 移至 `common/.../session/SessionStore.kt` | Session 持久化接口 |
| `service/.../init/ServiceInitModule.kt` | 三层 Koin 模块聚合 |
| `service/.../init/ServiceKoinModule.kt` | Service 实例注册 |
| `service/.../conversation/ConversationMemberInfo.kt` | 会话成员 DTO |
| `service/.../conversation/ConversationInfo.kt` | 会话 DTO |
| `service/.../friend/FriendshipInfo.kt` | 好友关系 DTO |
| `service/.../admin/DeadLetterDTO.kt` | 死信 DTO |
| `common/.../init/DeadLetterCallback.kt` | 死信回调接口 |

### 修改文件 (核心变更)

| 文件 | 变更 |
|------|------|
| `repository/build.gradle.kts` | `api(:common)` → `implementation(:common)` |
| `service/build.gradle.kts` | `api(:repository)`, `api(:proto)` → `implementation`；新增 koin 依赖 |
| `repository/.../SessionRepository.kt` | 实现 SessionStore 接口 |
| `repository/.../MessageRepositoryImpl.kt` | onDeadLetter lambda → DeadLetterCallback 接口 |
| `repository/.../RepositoryModuleInitializer.kt` | 注册 SessionStore + 死信桥接 |
| `service/.../DeadLetterService.kt` | 实现 DeadLetterCallback；query() 返回 DTO |
| `service/.../SeqService.kt` | 新增 recoverSequences() |
| `service/.../UserPrivacyService.kt` | 新增 batchGetHideOnlineStatus() |
| `service/.../ConversationService.kt` | 新增 3 个查询方法，返回 DTO |
| `service/.../FriendService.kt` | 新增 2 个查询方法，返回 DTO |
| `service/.../MessageService.kt` | 新增 3 个方法 |
| `gateway/.../ServerBootstrap.kt` | 移除所有 repository import，改用 service |
| `gateway/.../ChatService.kt` | 注入改为 service 类型 |
| `gateway/.../PushService.kt` | 注入改为 service 类型 |
| `gateway/.../SessionRegistry.kt` | SessionRepository → SessionStore |
| 10 个 Handler 文件 | 注入改为 service 类型 |
| 5 个 DI 模块文件 | 更新构造函数参数 |

## 状态

- **执行日期**: 2026-06-16
- **状态**: complete — 生产代码编译通过
- **子计划**: 1/1 (12-01-PLAN.md)
