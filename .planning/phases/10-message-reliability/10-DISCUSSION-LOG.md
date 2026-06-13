---
discussion_status: completed
phase: 10
started_at: 2026-06-13
completed_at: 2026-06-13
---

# Phase 10: Message Reliability — 讨论日志

## 灰区议题及决策

### 1. 消息三态跟踪模型（REL-01）
**决策（D-70）**: 混合模型 — 实时状态存 Redis（Hash + TTL 7 天），最终一致写到 MySQL。
- Redis Hash: `msg:{msg_id}:delivery`，字段 `{uid}:status`
- MySQL MessageEntity 扩展 `deliveryStatus` 字段（最终一致状态）
- 状态转换：sent（Redis 写入）→ delivered（推送成功）→ read（已读回执）

### 2. DeliveryAck 协议（REL-01）
**决策（D-71）**: 双模式 — 服务端推送即 delivered + 已读回执 read。
- 连接层推送成功后自动标记 delivered，无需客户端额外 ACK
- 已读回执复用现有 `message/read` 流程
- 两种事件：`DELIVERY_ACK` 推送发送者（告知已交付）+ 现有 `READ_RECEIPT`

### 3. 去重机制增强（REL-02）
**决策（D-72）**: 下沉至 MessageService。
- SETNX 去重从 Handler 层移至 `MessageQueueRepository.enqueue()` 内部
- 配合 `uk_client_msg_id` 唯一索引最终一致兜底
- TTL 保持 7 天

### 4. 异步落库补偿 + 死信表（REL-03）
**决策（D-73）**: MySQL 独立死信表。
- 新增 `dead_letters` 表：dead_letter_id、msg_id、conversation_id、content、fail_reason、fail_count、status、created_at、updated_at
- 补偿任务每 10 分钟扫描，单线程串行，最多 5 次重试
- 5 次失败后标记 `permanent_failed`，日志告警

### 5. 间隙检测策略（REL-04）
**决策（D-74）**: 服务端序列号（Redis INCR）。
- Redis key: `conv:{conv_id}:next_seq:{uid}`，消息发送时 INCR 获取 seq
- `SendMessageResp` 扩展 `seq` 字段
- 拉取时检查 last_seen_seq 与当前 seq 差值，触发 forward 方向拉取

### 6. 与 Phase 9 重连集成
**决策（D-75）**: Phase 10 接管 pendingBuffer 生命周期。
- pendingBuffer 投递成功 → 触发 delivered 状态标记
- pendingBuffer 投递失败（10 次后）→ 进入死信表
- 三态跟踪覆盖消息全生命周期

### 7. 性能与存储开销
**决策（D-76）**: 轻量 Admin API。
- 新增 `admin/dead-letters` 分页查询
- 新增 `admin/retry-dead-letter` 手动重试
- 仅开发/运维环境暴露

### 额外议题

#### Proto 扩展范围
- DeliveryAckPayload：`{msg_id, conversation_id, server_acked}`
- DeadLetterQueryReq/Resp：分页查询
- RetryDeadLetterReq/Resp：手动重试
- ChatMessage 不新增字段（三态是 per-user 元数据）
- SendMessageResp 扩展 `seq` 字段
- 新增 MessageSeq 消息

#### 死信补偿调度策略
- 10 分钟间隔、单线程串行、最多 5 次重试
- 永久失败日志告警

#### Proto 兼容性
- ChatMessage 不变
- SendMessageResp 扩展 seq
- 新增 MessageSeqReq/Resp

#### 实现拆分策略（4 个 Plan）
- 10-01: Proto 扩展
- 10-02: 三态跟踪 + DeliveryAck
- 10-03: 间隙检测
- 10-04: 死信表 + 补偿 + Admin API
