---
phase: 9
status: contexted
---

# Phase 9: Reconnection — 上下文

## 阶段目标

实现客户端断线重连的完整流程：重连状态机（纯客户端）、指数退避策略、心跳恢复、断连期间消息积压、原子连接清理。

**核心价值**: 客户端断连后自动重连，消息不丢失、不重复。

## 关联需求

| 需求 ID | 描述 |
|---------|------|
| RECON-01 | 重连状态机：INITIAL → BACKOFF → CONNECTING → CONNECTED，指数退避 |
| RECON-02 | 最大重试次数和心跳恢复 |
| RECON-03 | P0 消息重连重试 vs P1 延后 |
| RECON-04 | 原子旧连接清理：Redis pipeline + 旧 Channel 关闭 |
| RECON-05 | 标准重连流程，v1 不引入 QUIC |

## 技术决策

| 编号 | 决策 | 说明 |
|------|------|------|
| D-61 | 纯客户端重连状态机 | 服务端不维护重连状态，复用 login Token 验证。INITIAL → BACKOFF → CONNECTING → CONNECTED 由客户端管理 |
| D-62 | 等待首次 PONG 恢复心跳 | 重连后客户端发起 PING，服务端响应 PONG，确认双向通信正常后恢复标准心跳间隔 |
| D-63 | 客户端指定消息优先级 | P0/P1 由客户端本地维护重试队列，服务端无需感知，不修改 proto |
| D-64 | 复用 Redis 离线消息队列 | 断连期间消息写入 Redis 队列，重连后 `message/pull` 拉取，零新开发量 |
| D-65 | Redis pipeline 清理旧连接 | 非事务批量操作，最终一致性，性能最佳 |
| D-66 | 滑动窗口重试限制 | 1 小时内最多 10 次重试，每次间隔指数增长（2s→4s→8s→…→上限 60s） |
| D-67 | 缓存再投递 | 新连接建立后缓存消息，旧连接清理完成后投递到新连接，确保不丢消息 |
| D-68 | 推送 DISCONNECT 通知 | 关闭旧 Channel 前推送 DISCONNECT，客户端即时感知进入重连流程 |
| D-69 | 伪在线 = BACKOFF | 伪在线 60s 即为状态机的 BACKOFF 阶段，超时后标记离线 |

## 实现约束

- **纯客户端重连状态机**：服务端仅提供无状态 Token 验证（复用 login），无需新增 RPC
- **与现有机制集成**：
  - 复用 Phase 3/6 的 Redis 离线消息队列和 `message/pull` 接口
  - 复用 Phase 5 的 login Token 验证
  - 复用 Phase 8 的 60s 伪在线逻辑
  - 复用 Phase 6 的 `PushService.pushEventToUser()` 推送 DISCONNECT
- **Proto 扩展**：可能需新增 DISCONNECT 相关的 PushEventType（可选，也可复用现有通知机制）
- **无需新增 Entity**：连接清理仅涉及 Redis + Channel 管理，不涉及数据库

## 依赖

- Phase 3 (Database): Redis 离线消息队列已就绪
- Phase 5 (User & Auth): login Token 验证已就绪
- Phase 6 (Chat & Message): PushService 已就绪
- Phase 8 (Friend & Online Status): 伪在线逻辑已就绪
- 本阶段完成后 block Phase 10 (Message Reliability)

## 灰区已解决

全部 9 个灰区已讨论并锁定决策（D-61~D-69）。

## 灰区遗留

无。
