# Phase 10 执行摘要 — Message Reliability

## 执行信息
- **阶段**: 10 — Message Reliability（消息可靠性）
- **状态**: ✅ COMPLETED
- **日期**: 2026-06-13 ~ 2026-06-14
- **总计划**: 4（10-01 ~ 10-04）
- **总提交**: 3（96ec3b9, ef25f70, 96aaa46）+ 1 收尾（e2aaaba）

## 需求覆盖

| 需求 | 状态 | 说明 |
|------|------|------|
| REL-01 三态跟踪 | ✅ | sent → delivered → read，Redis Hash `msg:{msg_id}:delivery` |
| REL-02 去重幂等 | ✅ | SETNX 下沉到 MessageQueueRepository.enqueue() + MySQL 唯一索引兜底 |
| REL-03 死信补偿 | ✅ | 死信表 + DeadLetterService + 10 分钟 Compensator，最多 5 次重试 |
| REL-04 间隙检测 | ✅ | SeqService (Redis INCR) + MessageSeqHandler |

## 计划执行详情

| Plan | Wave | Commits | 文件变更 |
|------|------|---------|---------|
| 10-01 Proto + Flyway | 1 (无依赖) | 96ec3b9 | 5 files, +86 lines |
| 10-02 三态跟踪 | 2 (并行) | ef25f70 | 14 files, +457/-69 lines |
| 10-03 间隙检测 | 2 (并行) | ef25f70 | 同 10-02 提交 |
| 10-04 死信+Admin+DI | 3 (依赖全) | 96aaa46 | 22 files, +837/-44 lines |

## 新建文件清单

### Proto 定义 (3 files)
- `proto/.../admin.proto` — DeadLetterQueryReq/Resp、RetryDeadLetterReq/Resp
- `chat.proto` SendMessageResp.seq=3（追加字段）
- `message.proto` DeliveryAckPayload、MessageSeqReq/Resp

### Flyway 迁移 (1 file)
- `V4__add_dead_letters.sql` 死信表 DDL（含 @Version 乐观锁列）

### 服务层 (6 files)
- `service/.../sequence/SeqService.kt`
- `service/.../admin/DeadLetterService.kt`
- `repository/.../entity/DeadLetterEntity.kt`
- `repository/.../repository/DeadLetterRepository.kt`

### 网关层 (8 files)
- `gateway/.../delivery/RedisDeliveryTracker.kt`
- `gateway/.../delivery/DeliveryTrackingService.kt`
- `gateway/.../delivery/DeliveryHandlerCollector.kt`
- `gateway/.../admin/DeadLetterCompensator.kt`
- `gateway/.../handler/admin/DeadLetterQueryHandler.kt`
- `gateway/.../handler/admin/RetryDeadLetterHandler.kt`
- `gateway/.../handler/admin/AdminHandlerCollector.kt`
- `gateway/.../handler/message/MessageSeqHandler.kt`
- `gateway/.../di/MessageReliabilityModule.kt`

### 修改文件清单
- `gateway/.../push/PushService.kt` — pushDeliveryAck + markSent
- `gateway/.../interceptor/AuthInterceptor.kt` — 前缀匹配 + admin/ 放行
- `gateway/.../service/ChatService.kt` — D-75 pendingBuffer 死信
- `gateway/.../handler/chat/ChatHandlerCollector.kt` — MessageSeqHandler
- `gateway/.../handler/chat/send/DedupStep.kt` — no-op
- `gateway/.../handler/chat/send/SendMessageHandler.kt` — 移除 SETNX + 追加 seq
- `gateway/.../di/ChatHandlerModule.kt` — 注册/移除组件
- `gateway/.../di/GatewayModule.kt` — messageReliabilityModule
- `gateway/.../di/ServiceModule.kt` — MessageService 构造参数
- `service/.../chat/MessageService.kt` — seq 内联生成 → 统一 SeqService
- `repository/.../init/RepositoryModuleInitializer.kt` — DeadLetterRepository
- `repository/.../redis/MessageQueueRepository.kt` — checkAndSetDedup
- `repository/.../impl/MessageRepositoryImpl.kt` — DataIntegrityViolationException
- `server/.../NebulaServer.kt` — DeadLetterService 注入
- `server/build.gradle.kts` — service 模块依赖
- `service/build.gradle.kts` — jakarta.persistence.api 依赖

## 偏差记录
- SeqService 从 `gateway` 模块移至 `service` 模块（避免 service→gateway 循环依赖）

## 遗留风险
- AuthInterceptor admin/ 放行采用简化方案（前缀匹配），生产部署前应评估增加 Admin Token 校验
- pendingBuffer 重试计数器（ConcurrentLinkedQueue + ConcurrentHashMap）在极端高并发下可能内存紧张
- 补偿任务（10 分钟间隔）在单节点生产环境可用，多节点部署时需确认乐观锁机制有效防重复
