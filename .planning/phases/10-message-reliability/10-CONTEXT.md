---
phase: 10
status: contexted
---

# Phase 10: Message Reliability — 上下文

## 阶段目标

实现端到端消息交付保证 — 三态跟踪（sent → delivered → read）、幂等重试（客户端重试已实现，此处强化服务端）、死信处理、消息间隙检测。

## 关联需求

| 需求 ID | 描述 |
|---------|------|
| REL-01 | 三态递进 sent → delivered → read，状态变更时服务端推送给发送者 |
| REL-02 | 客户端重试幂等，client_message_id 去重（Redis SETNX + TTL） |
| REL-03 | 异步落库补偿 + 死信表机制 |
| REL-04 | 客户端消息 ID 连续性检查，检测间隙并触发重新拉取 |

## 技术决策

| 编号 | 决策 | 说明 |
|------|------|------|
| D-70 | 混合状态存储 | Redis Hash（`msg:{msg_id}:delivery`，字段 `{uid}:status`）+ 最终一致写到 MySQL MessageEntity.deliveryStatus |
| D-71 | 双模式 DeliveryAck | 连接层推送成功 → delivered（推送 DELIVERY_ACK 给发送者）；已读回执 → read（现有流程）。无需客户端显式送达 ACK |
| D-72 | 去重下沉 | SETNX 去重从 Handler 层移至 `MessageQueueRepository.enqueue()` 内部，`uk_client_msg_id` 唯一索引兜底，TTL 7 天 |
| D-73 | MySQL 独立死信表 | 新增 `dead_letters` 表，补偿任务 10 分钟间隔/单线程/最多 5 次重试，永久失败日志告警 |
| D-74 | 服务端序列号间隙检测 | Redis `conv:{conv_id}:next_seq:{uid}` INCR 自增，检测到间隙触发 forward 拉取 |
| D-75 | 接管 Phase 9 pendingBuffer | pendingBuffer 投递成功 → delivered，投递失败 10 次 → 死信 |
| D-76 | 轻量 Admin API | `admin/dead-letters` 分页查询 + `admin/retry-dead-letter` 手动重试，仅运维环境暴露 |

## 实现约束

- **Proto 扩展**：DeliveryAckPayload、DeadLetterQueryReq/Resp、RetryDeadLetterReq/Resp、SendMessageResp.seq 新增、MessageSeqReq/Resp
- **ChatMessage 不变**：三态跟踪是 per-user 元数据，不嵌入消息体
- **无需新增 Handler 框架**：复用现有 Handler<ReqT, RespT> 模式和 Dispatcher 路由
- **死信表需要 Flyway 迁移**：新增 V3 迁移文件
- **补偿任务使用协程定时调度**：复用 Koin 单例 + CoroutineScope

## 依赖

- Phase 6 (Chat & Message): 现有消息发送路径、去重、已读回执
- Phase 9 (Reconnection): 缓存再投递 pendingBuffer、重连清理

## 实现拆分（4 个 Plan）

| Plan | 内容 | Wave |
|------|------|------|
| 10-01 | Proto 扩展 + Flyway V3 | 1（无依赖） |
| 10-02 | 三态跟踪 + DeliveryAck | 2（依赖 10-01） |
| 10-03 | 间隙检测 | 2（并行，依赖 10-01） |
| 10-04 | 死信表 + 补偿 + Admin API + DI | 3（依赖 10-01, 10-02, 10-03） |

## 灰区已解决

全部 7 个灰区 + 4 个额外议题已讨论并锁定决策（D-70~D-76）。

## 灰区遗留

无。
