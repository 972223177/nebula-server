# Nebula Chat Server

## What This Is

Nebula 是一个基于 gRPC 双向流的高性能聊天服务后端。它通过单条长连接承载登录、消息收发、好友管理、群聊、在线状态等全部通信，为即时通讯客户端提供可靠、低延迟的实时消息能力。采用 Kotlin + Netty + Protobuf 技术栈架构。

## Core Value

用户通过一条 gRPC 双向流长连接，能够实时收发消息、感知好友在线状态，且消息不丢失、不重复。

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] GRP-01: 搭建 Gradle 6 子模块项目（proto/server/gateway/service/repository/common）
- [ ] PRO-01: 定义 Protobuf 协议栈 — Envelope、MessageType、23 个 Method 的 Request/Response 消息
- [ ] AUTH-01: 用户可通过 user/login 登录获取 Token，支持密码验证和重连幂等
- [ ] AUTH-02: Redis + 本地内存双缓存会话映射，Token 有效期管理
- [ ] AUTH-03: 同端多设备互踢策略（同类型设备只保留最新连接）
- [ ] INFRA-01: gRPC Netty Server 启动配置（端口、SSL/TLS、流控参数）
- [ ] INFRA-02: 雪花算法 ID 生成器（64 位、Worker ID 分配、时钟回拨处理）
- [ ] INFRA-03: HikariCP 连接池配置 + MySQL 主从多数据源
- [ ] INFRA-04: SSL/TLS 证书方案（开发自签名 + 生产 Let's Encrypt）
- [ ] HNDL-01: Handler 泛型接口框架 + Dispatcher 路由分发
- [ ] HNDL-02: Koin 依赖注入模块，Handler 与 method 显式注册
- [ ] HNDL-03: 拦截器 Pipeline（鉴权/日志/异常兜底）
- [ ] HNDL-04: BizException + ExceptionInterceptor 统一异常处理
- [ ] DB-01: MySQL 6 张核心表 DDL（users/conversations/conversation_members/messages/friendships/friend_requests）
- [ ] DB-02: Redis 3 种缓存结构（Session/消息队列/在线状态）
- [ ] DB-03: 消息"先写 Redis → 异步刷 MySQL"读写模型 + 消息拉取/离线消息/未读数/已读上报
- [ ] BIZ-01: 用户模块 Handler（login/search/getProfile/batchGet/batchGetStatus/setPrivacy/getPrivacy）
- [ ] BIZ-02: 聊天模块 Handler（chat/send 消息发送）
- [ ] BIZ-03: 会话模块 Handler（list/createGroup/inviteMember/leaveGroup/kickMember/editGroupInfo/groupMembers）
- [ ] BIZ-04: 消息模块 Handler（pull/readReport）
- [ ] BIZ-05: 好友模块 Handler（add/accept/reject/delete/list/requests）+ 回向好友申请
- [ ] BIZ-06: 群聊管理权限模型（唯一群主、邀请直接加入、群主退群解散、人数上限）
- [ ] BIZ-07: 删除好友后会话保留但不发送、重新添加后恢复
- [ ] BIZ-08: 好友通过时自动创建私聊会话
- [ ] BIZ-09: 在线状态管理（三值：在线/离线/隐藏、懒加载、伪在线）
- [ ] RECON-01: 断线重连状态机（指数退避、心跳恢复、最大重试次数）
- [ ] RECON-02: 断连期间消息 P0/P1 权重分级策略
- [ ] RECON-03: 旧连接原子清理（Redis pipeline + 旧 Channel 关闭）
- [ ] REL-01: 消息三态递进（sent → delivered → read）与推送通知
- [ ] REL-02: 客户端重试幂等（client_message_id 防重、Redis 缓存）
- [ ] REL-03: 异步落库补偿 + 死信表机制
- [ ] REL-04: 客户端消息 ID 连续性检查与空洞拉取
- [ ] PERF-01: 5 类压测场景定义与执行
- [ ] PERF-02: 单节点 8 项性能指标目标
- [ ] PERF-03: Netty/gRPC/业务/JVM 4 层优化
- [ ] PERF-04: 8 项生产监控指标与告警阈值

### Out of Scope

- 群聊扩展（多管理员、群公告、系统消息）— 后续版本计划
- 黑名单功能 — 后续版本计划
- 客户端 SDK / 前端 — 仅服务端后端
- QUIC 协议迁移 — v1 走标准 gRPC 重连流程
- 微服务拆分 / Kubernetes 部署 — 单节点起步
- WebSocket 支持 — 仅 gRPC

## Context

- **技术栈**：Kotlin / gRPC 双向流 / Netty / Protobuf / Gradle Kotlin DSL / MySQL / Redis
- **部署策略**：本地开发环境运行，后期迁移至云服务器。开发用自签名证书，生产用 Let's Encrypt
- **设计文档**：详见 `/Users/linyu/project/personal/Nebula/设计文档/后端架构设计v1.2/`，涵盖 14 章完整设计
- **开发模式**：从零新建 Greenfield 项目
- **性能基线**：10K 连接 / 10K msg/s / 200ms 群发扇出作为优化参考方向，非硬性验收指标

## Constraints

- **语言**: Kotlin — 全项目使用 Kotlin，不使用 Java
- **传输**: gRPC 双向流 — 唯一通信方式，不走 WebSocket 或 HTTP 轮询
- **序列化**: Protobuf — 所有消息结构使用 .proto 定义
- **构建**: Gradle Kotlin DSL — 6 个子模块，依赖方向单向（proto ← common ← repository ← service ← gateway ← server）
- **数据库**: MySQL 6 张核心表 + Redis 3 种缓存结构
- **部署**: 单节点起步，不引入微服务

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| gRPC 双向流单连接 | 简化连接管理、心跳复用同一条流、避免端口爆炸 | — Pending |
| 异步写消息（Redis → MySQL） | 降低写路径延迟，Redis ack 即返回，MySQL 异步刷盘 | — Pending |
| 雪花算法 Worker ID 硬配置 | 单节点无协调需求，分布式后改为 ZooKeeper/Redis 分配 | — Pending |
| 同端互踢（同类设备） | 用户一侧手机 + PC 各一条连接互不干扰 | — Pending |
| 本地开发 + 云端生产双模式 | SSL 证书、连接池参数通过环境配置切换 | — Pending |
| Handler 框架基于 suspend 协程 | 统一泛型接口 + Dispatcher 路由 + Pipeline 编排，与 gRPC StreamObserver 解耦 | Validated in Phase 4 (D-01~D-15) |
| 拦截器基于 Koin List<Interceptor> 注入 | 顺序执行 Auth→Log→RateLimit→Exception，支持扩展 | Validated in Phase 4 (D-06/D-07) |
| Session L1/L2 双级缓存 | ConcurrentHashMap + Redis，500ms 超时降级 | Validated in Phase 4 (D-17~D-20) |
| 双重心跳策略 | gRPC keepalive(连接存活) + 应用层 PING(服务健康)，时间参数随机 Jitter | Validated in Phase 4 (D-27~D-32) |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:discuss-phase`/`/gsd:plan-phase`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-06-12 after Phase 4 (handler-framework) execution*
