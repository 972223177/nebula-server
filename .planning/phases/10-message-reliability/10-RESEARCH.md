---
phase: 10
researcher: nx-researcher-1
---
# Phase 10: Message Reliability — 技术研究报告

## 研究摘要

本阶段（Phase 10）需实现端到端消息交付保证体系，涵盖四个核心子领域：消息三态跟踪（sent→delivered→read）、去重幂等下沉重构、异步落库补偿与死信表、消息序列号间隙检测。基于对现有代码库的深入分析，当前基础设施已提供良好的扩展基础：Redis Stream 异步刷写路径（`MessageRepositoryImpl`）、Step 链模式（`SendMessageStep`）、PushService 推送体系均可复用。主要技术挑战在于：Redis Hash 字段粒度设计对内存和 TTL 的影响、SETNX 去重下沉到 `MessageQueueRepository.enqueue()` 内部时的并发安全、以及补偿任务调度与现有协程模式的整合。建议优先实现 10-01（Proto 扩展 + Flyway V4）作为后续三个 Plan 的依赖基础。

---

## 1. 消息三态跟踪（sent → delivered → read）

### 状态模型

每个消息对每个接收者维护一个交付状态，存储在 Redis Hash 中。

**Redis 数据结构设计（D-70）：**

```
key: msg:{msg_id}:delivery
field: {uid}       → 状态值: 0=sent, 1=delivered, 2=read
```

**字段粒度分析：**

| 方案 | 优点 | 缺点 | 推荐 |
|------|------|------|------|
| `msg:{msg_id}:delivery` + 每个 uid 一个 field | 单 key 一次 HGETALL 即可获取所有接收者状态；内存开销可控（每个 field ~40 bytes） | 群聊 200 人场景一个 key 有 200 fields | ✅ 推荐（D-70 已锁定） |
| `delivery:{uid}:{conv_id}` 按用户聚合 | 便于查询某用户所有未交付消息 | 跨用户操作需多个 key，复杂度高 | ❌ 不推荐 |
| 直接写 MySQL 字段 | 最终一致性不便实时查询 | 写路径延迟高 | ❌ 不推荐 |

**TTL 策略：**
- Redis Hash TTL = 7 天（与去重保持一致），过期后状态只存在于 MySQL
- 已读状态（read）到达后可不更新 TTL，依赖自然过期
- 注意：TTL 在 key 级别，**不可对单个 field 设 TTL**。如果群聊中部分用户已读、部分未读，整个 key 过期会导致未读状态丢失。解决方案：
  - 方案 A：每次状态变更时 `EXPIRE` 刷新整个 key 的 TTL（推荐，简单可靠）
  - 方案 B：状态写入时设置 TTL，但过期后只从 MySQL 查询（适用于对实时性要求不高的场景）

**状态转换边界条件：**

```
sent ──(推送成功)──→ delivered ──(已读回执)──→ read
  │                     │
  └──(10 次推送失败)──→ 死信 (D-75)
```

关键边界：
1. **重复 delivered 标记**：如果某用户多设备在线，其中一个设备推送成功即标记 delivered，后续设备不重复标记 — 使用 `HSETNX` 或先 GET 判断
2. **已读 ≤ delivered**：已读回执的 msg_id 应该 ≤ 该用户已标记为 delivered 的最大 msg_id。但在异步模型中，delivered 标记可能晚于 read 到达，此时应允许直接标记 read（sent→read 的跳跃转换）
3. **断线重连场景**：pendingBuffer 投递成功后标记 delivered，投递失败不立即写死信（等待 10 次重试，D-75）

### 与现有代码的整合

**existing code references:**

| 文件 | 角色 |
|------|------|
| `PushService.kt` | 向接收者推送 ChatMessage — 推送成功后触发 delivered 标记 |
| `ReadReportHandler.kt` | 处理已读回执 — 触发 read 标记 |
| `UserStreamRegistry.kt` | 提供在线设备列表 — 判断是否可投递 |
| `ChatService.ChatStreamObserver.pendingBuffer` (D-75) | 缓存再投递 — 成功投递后触发 delivered |

**关键变更点：**
- 在 `PushService.pushMessage()` 中推送成功后，插入 `{msg_id}:{receiverUid} → delivered` 的 Redis HSET
- 在 ReadReport 处理流程中，为每个被读消息的发送者标记 read 状态
- 新增 `DeliveryTrackingService`（或扩展 `MessageService`）封装状态变更逻辑
- 发送者通过 DELIVERY_ACK PushEvent 接收通知：`PushService.pushDeliveryAck(senderUid, msgId, convId)`

### 常见陷阱

1. **内存膨胀**：每消息 × 每接收者 = 一个 field。按 10K msg/s、平均 5 接收者、7 天计算 ≈ 10K × 5 × 86400 × 7 = 30.24B fields。每个 field key="uid" + value="0/1/2" ≈ 30 bytes → ~907GB。**但在实际聊天场景中，消息峰值远低于此**，且 7 天 TTL 意味着存量可控。建议上线后监控 Redis 内存使用，必要时缩短 TTL 至 3 天。
2. **EXPIRE 与 HSET 的原子性问题**：Redis 不支持对 Hash 中单个 field 设 TTL。如果需要在状态全部 read 后清理，可使用 `HGETALL` 检查 + `DEL`，但这是非原子操作。

---

## 2. 消息去重（幂等性）— 重构方案

### 当前实现分析（D-72）

当前去重逻辑分布在两个位置：

1. **`DedupStep.kt`**：在 Step 链中使用 SETNX `chat:dedup:{client_msg_id}`，初始值 "pending"
2. **`SendMessageHandler.kt`**：使用 SETNX `dedup:msg:{clientMessageId}`，7 天 TTL

这种"双重去重"是 Phase 6 到 Phase 10 之间的过渡产物。D-72 要求将其统一下沉到 `MessageQueueRepository.enqueue()` 内部。

**重构方案：**

```
SendMessageHandler / Step 链
  └── MessageService.sendMessage()
       └── enqueueMessage()    ← 去重下沉至此
            ├── SETNX dedup:msg:{client_msg_id} (7 天 TTL)
            ├── 已存在 → 抛出重复异常
            └── 不存在 → XADD 到 Redis Stream
```

### SETNX + 唯一索引的协作机制

| 层 | 机制 | 职责 | 时效性 |
|----|------|------|--------|
| 应用层（Redis SETNX） | `SETNX key "pending"` + `EXPIRE 7d` | 快速拒绝重复，毫秒级响应 | 7 天窗口 |
| MySQL 唯一索引 | `uk_client_msg_id` | 兜底防重，防止 Redis 数据丢失导致的重复入库 | 永久 |

**边界分析：**
- Redis 正常：SETNX 拒绝重复 → MySQL 唯一索引不会命中
- Redis 丢失（重启/淘汰）：SETNX 可能通过 → MySQL 唯一索引报错 → 业务异常，由 `MessageRepositoryImpl.flushBatch()` 的 catch 捕获，消息进入 PEL（待确认列表）→ 最终进入死信表
- 建议在 flushBatch 中捕获 `DataIntegrityViolationException` 后，将消息主动标记为去重失败而不是保持重试

### TTL 策略

| 参数 | 值 | 说明 |
|------|----|------|
| TTL | 7 天 | 与 D-72 一致，覆盖客户端可能的重试窗口 |
| 重试窗口 | 建议 ≤ 24h | 客户端应在此窗口内完成重试；超过 7 天的 client_msg_id 视为新消息 |
| 唯一索引 | 永久 | 兜底，防止 Redis 重启后的重复 |

### One Pattern: SETNX 错误处理

`setnx` 返回 `Boolean?` — Lettuce coroutines 版本中 setnx 可能返回 null（连接断开等）。**必须处理 null 的情况**：

```kotlin
val isNew = redis.setnx(dedupKey, msgId.toString()) ?: false
if (!isNew) {
    // Redis 连接正常，key 已存在 → 重复
    // Redis 连接异常导致 null → 保守处理：允许通过（由 MySQL 唯一索引兜底）
    // 或：抛出异常拒绝写入（视业务容忍度）
}
```

建议：Redis 异常时**允许通过**（fail-open），由 MySQL 唯一索引做最终屏障。这比 fail-close（拒绝所有 Redis 异常时的消息）对用户体验影响更小。

---

## 3. 死信表设计（D-73）

### Schema 设计

```sql
-- V4__add_dead_letters.sql
CREATE TABLE IF NOT EXISTS dead_letters (
    id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    msg_id          BIGINT          DEFAULT NULL COMMENT '原始消息ID（Snowflake），落库前为 NULL',
    conversation_id VARCHAR(32)     NOT NULL COMMENT '会话ID',
    sender_uid      BIGINT          NOT NULL COMMENT '发送者UID',
    message_type    INT             NOT NULL DEFAULT 0 COMMENT '消息类型枚举',
    content         TEXT            NOT NULL COMMENT '消息文本内容',
    payload         BLOB            DEFAULT NULL COMMENT '原始 payload',
    client_msg_id   VARCHAR(64)     DEFAULT NULL COMMENT '客户端幂等ID',
    client_ts       BIGINT          NOT NULL COMMENT '客户端时间戳',
    fail_reason     VARCHAR(512)    NOT NULL COMMENT '失败原因',
    fail_count      INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    status          VARCHAR(20)     NOT NULL DEFAULT 'pending' COMMENT 'pending/retrying/permanent_failed/retry_success',
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_status_created (status, created_at) COMMENT '补偿任务扫描索引',
    INDEX idx_client_msg_id (client_msg_id) COMMENT '去重关联查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 补偿重试策略（D-73）

| 参数 | 值 | 决策依据 |
|------|----|----------|
| 扫描间隔 | 10 分钟 | 平衡延迟与资源消耗；死信应很少发生，不需要秒级扫描 |
| 并发模型 | 单线程串行 | 避免并发补偿导致的重复投递（D-73 明确） |
| 最大重试次数 | 5 次 | 超过后标记 `permanent_failed`，日志告警 |
| 重试间隔 | 固定 10 分钟（对比指数退避） | 由于扫描间隔固定，天然退避；指数退避会增加实现复杂度 |

**为什么不采用指数退避：**
- 10 分钟的固定扫描间隔已经足够"退避"
- 死信数量预期很小，不需要复杂的退避策略
- 如果未来死信量大，可改为：第 1 次 5 分钟 → 第 2 次 10 分钟 → 第 3 次 30 分钟…但在当前阶段不需过度设计

**补偿流程：**

```
补偿定时器（10 分钟）
  └── 查询 status='pending' AND fail_count < 5
  └── 逐个重试：
       ├── 成功: status='retry_success', 消息重新入队 Redis Stream
       └── 失败: fail_count++, updated_at=now
  └── 查询 fail_count >= 5 AND status != 'permanent_failed'
       └── status='permanent_failed', logger.error 告警
```

### 死信来源分析

| 来源 | 触发条件 | 进入死信时机 |
|------|----------|-------------|
| Redis Stream 刷新失败 | MySQL 写入异常（唯一索引冲突除外） | flushBatch 重试多次后 |
| pendingBuffer 投递失败 (D-75) | 重试 10 次后仍无法投递 | ChatService 监测 |
| 间隙检测发现不可恢复的缺口 | 消息已不可拉取 | 待定（先记录，手动处理） |

---

## 4. 消息序列号与间隙检测（D-74）

### 序列号生成

**Redis key 设计：**
```
conv:{conv_id}:next_seq:{uid}    ← INCR 自增
```

- 每个（会话，用户）一个自增计数器
- 消息发送时 INCR 获取 seq，写入 `SendMessageResp.seq`
- 客户端通过 `SendMessageResp.seq` 感知本地 seq 是否连续

### Redis INCR 可靠性分析

| 故障场景 | 影响 | 恢复方式 |
|----------|------|----------|
| Redis 重启（RDB/AOF 持久化正常） | 序列号不丢失 | 自动恢复 |
| Redis 重启（无持久化） | 序列号重置为 0 | 客户端 seq 从 0 开始，触发间隙检测 → 全量拉取 |
| Redis 主从切换 | 可能回滚少量 INCR | seq 间隙 ≤ 切换期间的 INCR 次数，客户端自动拉取补齐 |

**结论：** Redis INCR 在持久化正常时可靠。即使序列号回滚或重置，也只是"伪间隙"触发不必要的拉取，不会导致消息丢失。

### 间隙检测触发时机

```
客户端收到 SendMessageResp{seq=N}
  ├── lastSeq == null → 首次连接，记录 lastSeq = N，跳过检测
  └── lastSeq != null
       ├── seq == lastSeq + 1 → 连续，更新 lastSeq
       ├── seq > lastSeq + 1 → 间隙！触发 PullMessages(forward, cursor=lastSeq)
       └── seq <= lastSeq → 重复消息（或 seq 回绕），忽略
```

**拉取量估算：**
- 正常情况下，一个间隙通常由 1-2 条消息的 seq 丢失导致
- 建议拉取量 = `(seq - lastSeq - 1) * 2`，上限 200 条
- 如果重连场景下，间隙可能很大（数百条），此时应走完整的离线消息拉取流程而非间隙检测

### 服务端 vs 客户端检查

D-74 明确使用**服务端序列号**（Redis INCR），即 seq 由服务端分配并在响应中返回。客户端负责连续性检查。

**为什么不采用客户端序列号：**
- 客户端序列号可能受多设备影响，无法全局单调递增
- 服务端序列号天然有序，且与 Snowflake ID 不同（Snowflake ID 全局有序但不连续）

---

## 5. Kotlin 协程定时任务模式

### 现有模式分析

项目中已有两种协程定时模式：

**模式 A — `MessageRepositoryImpl.startFlushTimer()`：**
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    while (!stopped) {
        delay(500)
        flushBatch()
    }
}
```
- 直接在类内部创建 CoroutineScope
- 没有通过 Koin 注入，生命周期与对象绑定
- 优点：简单直接
- 缺点：scope 无法外部管理（取消/重启）

**模式 B — `ChatHandlerModule` 中 `single(named("sendHandlerScope"))`：**
```kotlin
single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
```
- Koin 管理 CoroutineScope 生命周期
- 通过 qualifier 注入到 Handler
- 优点：scope 统一管理，可测试

### 推荐方案：死信补偿任务

死信补偿任务应复用模式 A 的思路（与 `MessageRepositoryImpl` 一致），但在 Koin singleton 中管理：

```kotlin
class DeadLetterCompensationService(
    private val deadLetterRepository: DeadLetterRepository,
    private val messageQueueRepository: MessageQueueRepository,
    private val scope: CoroutineScope  // Koin 注入
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L)  // 10 分钟
                compensate()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun compensate() {
        // 查询 pending 死信 → 重试
    }
}
```

在 DI 模块中注册：
```kotlin
single(named("compensationScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
single { DeadLetterCompensationService(get(), get(), get(named("compensationScope"))) }
```

### 常见陷阱

1. **`while(true)` 无 `delay`**：会 busy-wait 占满线程
2. **异常导致协程退出**：使用 `SupervisorJob` 或 try-catch 包裹补偿逻辑，防止一次异常永久终止
3. **多个服务实例同时补偿**：单线程串行 + 单节点部署即可避免；如需多节点，应使用 Redis 分布式锁
4. **启动时机**：补偿任务应在所有初始化完成后启动（通过 `ModuleInitializer` 或延迟启动）

---

## 6. gRPC Admin API 集成（D-76）

### 方案分析

当前项目中，所有业务接口都通过统一的 `chat` 双向流 + `Dispatcher` 分发。Admin API 有两种实现路径：

**方案 A：复用现有双向流 + 新增 `admin/` 前缀 method（推荐）**

```
method = "admin/dead-letters"
method = "admin/retry-dead-letter"
```

优点：
- 不需要额外端口或 gRPC Service
- 复用现有 AuthInterceptor（可配置跳过或增加 admin 专用鉴权）
- 与现有 Handler 模式完全一致

缺点：
- Admin 操作通过同一连接，没有隔离
- 需要在 AuthInterceptor 中放行 admin 方法（或增加 admin token 校验）

**方案 B：独立的 gRPC Server（备用）**

优点：
- 完全隔离，可绑定不同端口
- 可在内网暴露

缺点：
- 需要额外端口、证书、Service 定义
- 开发环境复杂度增加

**推荐方案 A**（与 D-76 一致），新增 admin 方法的 Handler：

```kotlin
class DeadLetterQueryHandler(
    private val deadLetterRepository: DeadLetterRepository
) : Handler<DeadLetterQueryReq, DeadLetterQueryResp> {
    override val method = "admin/dead-letters"
    // ...
}
```

在 AdminInterceptor（或修改 AuthInterceptor）中放行 `admin/` 前缀：
```kotlin
single<Interceptor> { AuthInterceptor(
    get(),
    skipMethods = setOf("system/ping", "user/login", "user/register", "admin/dead-letters", "admin/retry-dead-letter")
) }
```

### Proto 扩展建议

**需要新增的消息（D-76）：**

```protobuf
// admin/dead-letters
message DeadLetterQueryReq {
  int32 page = 1;
  int32 page_size = 2;
  string status = 3;  // 可选过滤: pending / permanent_failed
}

message DeadLetterQueryResp {
  repeated DeadLetterItem items = 1;
  int32 total = 2;
}

message DeadLetterItem {
  int64 id = 1;
  int64 msg_id = 2;
  string conversation_id = 3;
  int64 sender_uid = 4;
  string content = 5;
  string fail_reason = 6;
  int32 fail_count = 7;
  string status = 8;
  int64 created_at = 9;
}

// admin/retry-dead-letter
message RetryDeadLetterReq {
  int64 dead_letter_id = 1;
}

message RetryDeadLetterResp {
  bool success = 1;
}
```

**需要扩展的消息（10-01）：**

```protobuf
// SendMessageResp 增加 seq 字段
message SendMessageResp {
  int64 msg_id = 1;
  int64 server_ts = 2;
  int64 seq = 3;  // D-74: 服务端序列号
}

// DeliveryAckPayload — 新增
message DeliveryAckPayload {
  int64 msg_id = 1;
  string conversation_id = 2;
}

// MessageSeqReq/Resp — 新增
message MessageSeqReq {
  string conversation_id = 1;
}

message MessageSeqResp {
  int64 seq = 1;
}
```

### 环境隔离

- Admin API 仅在开发/运维环境暴露
- 通过配置开关控制是否注册 Admin Handler：
  ```kotlin
  if (config.admin.enabled) {
      register(DeadLetterQueryHandler(...))
      register(RetryDeadLetterHandler(...))
  }
  ```

---

## 与 Phase 9 pendingBuffer 的集成（D-75）

**当前 pendingBuffer 行为（`ChatService.ChatStreamObserver`）：**

```
deliver(envelope):
  if deliveryActive → responseObserver.onNext(envelope)  // 直接投递
  else → pendingBuffer.add(envelope)                      // 缓存
```

**Phase 10 需要接管：**

```
PushService.pushMessage():
  ├── 遍历在线设备
  ├── 调用 observer.deliver(envelope)  ← 走 pendingBuffer（如果未激活）
  ├── 推送成功后 → DeliveryTrackingService.markDelivered(msgId, receiverUid)
  └── 推送失败（可捕获的异常）→ pendingBuffer 重试
      └── 重试 10 次仍失败 → DeadLetterService.create()
```

**修改点：**
1. PushService 的 `pushMessage()` 需要返回每个设备的投递结果（成功/失败）
2. 或者在 PushService 推送失败后（catch 块），将失败信息通知给 DeliveryTrackingService
3. pendingBuffer 的"10 次重试后进死信"逻辑需新增计数器（可在 ChatStreamObserver 中维护 per-msg 计数器，或使用 Redis 外部化）

---

## 实施路径参考

### 文件创建/修改清单

| Plan | 文件 | 操作 |
|------|------|------|
| 10-01 | `proto/.../chat.proto` — SendMessageResp 扩展 seq | 修改 |
| 10-01 | `proto/.../message.proto` — 新增 DeliveryAckPayload | 新增 |
| 10-01 | `proto/.../admin.proto` — DeadLetterQuery/RetryDeadLetter | 新增 |
| 10-01 | `proto/.../message_type.proto` — 新增 MessageSeqReq/Resp （可选） | 新增 |
| 10-01 | `repository/.../db/migration/V4__add_dead_letters.sql` | 新增 |
| 10-02 | `gateway/.../delivery/DeliveryTrackingService.kt` | 新增 |
| 10-02 | `gateway/.../push/PushService.kt` — 扩展 delivery ack | 修改 |
| 10-02 | `service/.../chat/MessageService.kt` — 扩展 seq 处理 | 修改 |
| 10-03 | `gateway/.../handler/SeqCheckHandler.kt` | 新增 |
| 10-04 | `repository/.../entity/DeadLetterEntity.kt` | 新增 |
| 10-04 | `repository/.../repository/DeadLetterRepository.kt` | 新增 |
| 10-04 | `service/.../admin/DeadLetterService.kt` | 新增 |
| 10-04 | `gateway/.../handler/admin/DeadLetterQueryHandler.kt` | 新增 |
| 10-04 | `gateway/.../handler/admin/RetryDeadLetterHandler.kt` | 新增 |
| 10-04 | `gateway/.../di/AdminModule.kt` | 新增 |

### 测试策略建议

| 测试目标 | 类型 | 关键场景 |
|----------|------|----------|
| DeliveryTrackingService | 单元测试 + Redis 集成 | 状态转换边界、多设备重复标记、断线重连跳跃转换 |
| 去重下沉 | 集成测试 | SETNX + 唯一索引协作、Redis 故障时的兜底行为 |
| 死信补偿 | 集成测试 | 补偿扫描、重试计数、永久失败告警 |
| 间隙检测 | 单元测试 | 正常连续、单间隙、多间隙、seq 回绕 |

---

## 参考资源

- [Redis idempotent message processing docs](https://redis.io/docs/latest/develop/data-types/streams/idempotency/) — Redis 8.6 原生幂等支持（当前项目不使用此特性，使用 SETNX 方案）
- [Redis HSET/HSETNX command docs](https://redis.io/docs/latest/commands/hset/) — Hash 字段操作
- [Kotlin CoroutineScope best practices](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html) — 协程作用域管理
- [Existing code: DedupStep.kt](/.planning/phases/10-message-reliability/../src/main/kotlin/com/nebula/gateway/handler/chat/send/DedupStep.kt) — 当前去重模式参考
- [Existing code: MessageRepositoryImpl.kt](/.planning/phases/10-message-reliability/../src/main/kotlin/com/nebula/repository/repository/impl/MessageRepositoryImpl.kt) — 定时刷写模式参考
- [Existing code: PushService.kt](/.planning/phases/10-message-reliability/../src/main/kotlin/com/nebula/gateway/push/PushService.kt) — 推送模式参考
- [Existing code: ChatService.kt ChatStreamObserver](/.planning/phases/10-message-reliability/../src/main/kotlin/com/nebula/gateway/service/ChatService.kt) — pendingBuffer 集成点

## RESEARCH COMPLETE
