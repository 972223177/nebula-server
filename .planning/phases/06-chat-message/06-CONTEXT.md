# Phase 6: Chat & Message — Context

**Gathered:** 2026-06-12
**Status:** Ready for planning

<domain>
## Phase Boundary

实现消息发送、扇出投递、消息拉取和已读回执。包含 4 个业务入口：

- **chat/send** — 消息写入、去重、成员验证、扇出推送至在线成员
- **message/pull** — 按会话游标分页拉取历史消息（MySQL 游标查询）
- **message/read** — 更新已读进度、重置未读计数、私聊已读回执推送

共 4 个需求（BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02）。

</domain>

<decisions>
## Implementation Decisions

### 在线推送架构
- **D-01:** **UserStreamRegistry** — 独立组件管理 userId→StreamObserver 映射，与 ChatService 解耦。ChatService 仅负责 gRPC 流管理。
- **D-02:** **多设备全推** — 消息推送给用户的所有在线设备。
- **D-03:** **推送完整 ChatMessage** — 含 content、message_type、payload（图片为 OSS URL，无二进制大包体）。不同 messageType 统一推送，客户端按类型渲染。

### 消息扇出编排（chat/send）
- **D-04:** **ACK 时机** — 写入 Redis Stream 后立即返回 SendMessageResp（含 msg_id + server_ts）。推送和未读计数异步执行。
- **D-05:** **推送容错** — 推送失败不影响消息落盘和未读计数。推送是秒级可重试的优化行为。
- **D-06:** **未读计数** — `INCR conversation:{conv_id}:unread:{uid}` 逐个成员递增。成员数通常几十人以内，性能可承受。
- **D-07:** **消息去重** — `SETNX chat:dedup:{client_msg_id} {msg_id}` + TTL 7 天。重复请求忽略。
- **D-08:** **成员验证** — 仅检查 sender_uid 是否为 conversation_members 成员（SISMEMBER）。好友/黑名单验证交由 Phase 8。
- **D-09:** **自消息排除** — 推送时排除 sender_uid。发送者已从 SendMessageResp 获得确认。
- **D-10:** **会话元更新** — SendHandler 同步更新 Redis 中会话的 last_message_id、last_message_preview、last_updated_at。
- **D-11:** **推送触发** — Handler（PushStep）直接调用 PushService，不走 ChatService 拦截。零额外 I/O（消息内容已在 Handler 内存）。
- **D-12:** **逐个单推** — 不批量推送。50 人以下群聊单推耗时 ~5ms 可接受。
- **D-13:** **Step 链模式** — SendMessageHandler 不承担全部逻辑，拆分为 SendMessageStep 接口：
  - `ValidateStep` — 成员验证 + 内容非空 + client_message_id 非空
  - `DedupStep` — Redis SETNX 去重
  - `WriteStep` — Snowflake 生成 msg_id → Redis Stream 写入 → 更新会话元
  - `PushStep` — 调用 PushService 推送给在线成员（排除发送者）
  
  各 Step 通过 `SendContext` 传递共享状态。编排顺序在注册处显式声明。
- **D-14:** **client_message_id 容错** — 强制客户端传入。空值直接返回 INVALID_PARAM，不做服务端自动生成。
- **D-15:** **Push Envelope 构建** — PushService 内部构建完整 Envelope(Direction.PUSH, Message, ChatMessage)。对外暴露 `pushMessage(convId, msg, excludeUid)` 方法。
- **D-16:** **离线成员处理** — Phase 6 仅递增未读计数。离线消息恢复由 Phase 3 的 Redis Stream PEL 机制负责。

### 消息拉取策略（message/pull）
- **D-17:** **数据源** — MySQL 游标查询，利用 `idx_conv_messages(conversation_id, id)` 索引。
- **D-18:** **翻页方向** — tail 优先 + 往前翻（cursor=0 → 最新 limit 条，cursor>0 → 比 cursor 更旧的 limit 条）。
- **D-19:** **分页大小** — 默认 20 条，最大 100 条。
- **D-20:** **direction 字段保留** — PullMessagesReq.direction 字段保留不删，Phase 10 间隙检测时使用 forward 方向。
- **D-21:** **不存冗余用户信息** — ChatMessage 不存 sender_username/sender_avatar。客户端通过 user/batchGet 按 sender_uid 批量获取。
- **D-22:** **ChatMessage proto 变更** — 移除 sender_username(4)、sender_avatar(5)，新增 receiver_uid(11)。私聊场景填充接收方 UID，群聊填 0。

### 已读回执推送（message/read）
- **D-23:** **推送范围** — 私聊场景推送 READ_RECEIPT 给原发送者。群聊不推。
- **D-24:** **已读回执 Payload** — `ReadReceiptPayload{conversation_id, reader_uid, msg_id}`，不含时间戳。
- **D-25:** **ReadReceiptProto 已定义** — 见 `message.proto`，对应 PushEventType.READ_RECEIPT。
- **D-26:** **单个 Handler** — ReadReportHandler 内完成全部逻辑（不拆 Step 链）。
- **D-27:** **会话类型判定** — 通过 `ConversationEntity.type` 字段（已存在）判断私聊/群聊。
- **D-28:** **未读计数重置** — `DEL conversation:{conv_id}:unread:{uid}` 删除 Redis 键。接受极低概率的竞态（DEL 后新消息 INCR 覆盖），下次新消息自动修复。

### Claude's Discretion
- Step 链接口和 PushService 的具体包路径由规划者决定
- PushService.pushMessage() 的签名细节（是否挂起、超时等）由实现者决定
- `client_message_id` 去重 TTL 的 7 天值可在实现时根据实际调整

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Proto 定义
- `proto/src/main/proto/nebula/chat/chat.proto` — SendMessageReq、SendMessageResp 定义
- `proto/src/main/proto/nebula/message/message.proto` — PullMessagesReq/Resp、ChatMessage、ReadReportReq、ReadReceiptPayload 定义（Phase 6 已修改：移除 sender_username/avatar，新增 receiver_uid/ReadReceiptPayload）
- `proto/src/main/proto/nebula/message_type.proto` — ChatContentType、PushEventType 枚举（含 CHAT_MESSAGE、READ_RECEIPT）
- `proto/src/main/proto/nebula/envelope.proto` — Direction、Envelope、Message 消息定义

### 设计文档
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.1-接口契约.md` — Handler<ReqT, RespT> 接口定义
- `设计文档/后端架构设计v1.2/08-Handler层设计/8.5-分文件组织.md` — 模块目录和包结构

### 项目配置
- `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt` — Handler 注册模式参考（registerHandlers 模式）
- `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt` — gRPC 双向流，tokenToObserver 映射，现有连接管理
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/Dispatcher.kt` — 请求分发器，Pipeline 编排
- `repository/src/main/kotlin/com/nebula/repository/entity/MessageEntity.kt` — 消息实体（含 idx_conv_messages、uk_client_msg_id 索引）
- `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt` — 会话实体（含 type 字段判定私聊/群聊）

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **MessageRepository** — 已实现 `findForward()/findBackward()` 游标分页查询
- **ConversationMemberRepository** — 已实现成员查询和未读计数操作
- **MessageQueueRepository** — Redis Stream 写入封装，异步刷 MySQL
- **SessionRegistry** — 已实现 userIdIndex 用于按用户查找 Session 列表

### Established Patterns
- **Handler 模式** — `Handler<ReqT, RespT>` + `ProtoCodec` 零反射序列化
- **Step 链模式**（新增）— `SendMessageStep` 接口，`SendContext` 共享上下文
- **ChatService 拦截模式** — 现有 ChatService 拦截 user/login 200 响应，Phase 6 不再扩展拦截方式（改用 Handler 直接调 PushService）

### Integration Points
- PushService 需要依赖 UserStreamRegistry（新增组件）查找在线 StreamObserver
- PushService 需要依赖 ConversationMemberRepository 查询会话成员列表
- ReadReportHandler 需要依赖 ConversationRepository 获取会话类型
- 4 个新 Handler（SendMessageHandler + 3 个 Step / PullMessagesHandler / ReadReportHandler）需注册到 Koin 和 HandlerRegistry
- Protobuf 需重新生成（message.proto 已修改）
- MessageEntity 无需修改（用户信息不冗余存储）

</code_context>

<specifics>
## Specific Ideas

- 推送 Envelope 结构：Direction.PUSH → Message(event_type=CHAT_MESSAGE) → ChatMessage(bytes)
- Step 链错误处理：ValidateStep/DedupStep 失败时抛出 BizException，由 ExceptionInterceptor 兜底。WriteStep/PushStep 失败不影响已写入状态，但需记录日志。

</specifics>

<deferred>
## Deferred Ideas

- **Segment 富文本结构** — 用户期望消息体由 `[text_segment + file_url_segment + text_segment]` 组成而非单一 `content + payload`。需定义 Segment proto 和重构消息结构，属于后续迭代。
- **群聊已读详情** — 群聊场景展示"已读人数/N"，不属于 Phase 6 范围。

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 6-Chat & Message*
*Context gathered: 2026-06-12*
