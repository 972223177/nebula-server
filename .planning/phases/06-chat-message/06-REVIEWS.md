---
phase: 6
reviewers: [claude]
reviewed_at: 2026-06-12T00:00:00+08:00
plans_reviewed:
  - 06-01-PLAN.md
  - 06-02-PLAN.md
  - 06-03-PLAN.md
  - 06-04-PLAN.md
---

# Cross-AI Plan Review — Phase 6

## Claude Review

### Plan 06-01: Foundation Components (Wave 1)

#### 1. Summary

该计划创建了推送基础设施层——UserStreamRegistry（userId→StreamObserver 映射）、PushService（Envelope 构建和投递）、SendMessageException（领域异常）以及 Proto 重新生成。为所有后续的发送/推送/读取流程奠定基础。

#### 2. Strengths

- **关注点分离清晰：** UserStreamRegistry 与 SessionRegistry 职责明确分离，遵循 D-01 决策
- **数据结构选择得当：** ConcurrentHashMap<Long, CopyOnWriteArrayList<StreamObserver<Envelope>>> 并发安全、读多写少场景优化、天然支持多设备全推
- **每个 observer 异常隔离：** pushMessage 和 pushReadReceipt 各自包裹 try-catch，正确实现 D-05
- **过期流清理：** 推送失败时主动调用 removeStream()，主动解决连接泄漏问题
- **Proto 重新生成包含 gateway 编译检查：** 在 Wave 2 修改之前捕获下游编译问题

#### 3. Concerns

- **MEDIUM — pushMessage 是 suspend 但使用了阻塞型 DB 调用：** 声明为 `suspend fun` 但调用 blocking JPA 的 `findByConversationId()`，没有 `withContext(Dispatchers.IO)` 包裹
- **MEDIUM — pushReadReceipt 非 suspend 可能丢失线程安全：** Marked as `fun`，调用 getStreams() 返回快照列表，流可能在快照和 onNext() 之间断开
- **LOW — CopyOnWriteArrayList 迭代与 removeStream 的交互：** 实现需避免在 compute lambda 内调用 removeStream
- **LOW — 无反压考虑：** 慢消费者可能阻塞 onNext()，50 人群聊中一个慢消费者可能延迟整个推送循环

#### 4. Suggestions

- PushService.pushMessage 添加 TODO 注释，提示 findByConversationId 应使用 withContext(Dispatchers.IO)
- 考虑在 pushMessage 中添加每个 observer 的写入超时
- 为 UserStreamRegistry 添加 getOnlineCount() 便利方法方便未来监控

#### 5. Risk Assessment: LOW

组件简单、接口清晰、异常处理完整。唯一的风险是 suspend 函数的阻塞 I/O，实现时可轻松解决。

---

### Plan 06-02: Send Message Step Chain (Wave 2)

#### 1. Summary

实现了核心的 chat/send 流程，采用 Step 链模式（D-13）：ValidateStep → DedupStep → WriteStep → PushStep，由 SendMessageHandler 编排。同时修改 ChatService 集成 UserStreamRegistry 的连接生命周期管理。

#### 2. Strengths

- **Step 链接口设计良好：** SendMessageStep 含 `suspend fun execute(context: SendContext): Boolean`，`false` 用于链终止 vs. 异常用于错误中止，区分清晰
- **SendContext 可变状态天然单线程安全：** Step 链在单个协程中顺序执行
- **DedupStep 的 SETNX + TTL 完整：** 识别了 Lettuce coroutines API 的歧义，提供 setnx + expire 组合方案
- **WriteStep 正确延迟 receiver_uid 设置：** 设计注释说明该字段主要用于拉取输出场景
- **ChatService 集成解决了 userId 可用性问题：** 向 ChatStreamObserver 添加 userId 字段是 inner class 的正确方法
- **PushStep 双重异常包裹：** PushService 的每 observer try-catch + PushStep 的顶层 try-catch 提供深度防御

#### 3. Concerns

- **HIGH — D-04 矛盾被承认但未解决：** PushStep 是同步执行的，响应在推送完成后才返回。250ms 的延迟对 50 人群聊是显著延迟增加
- **HIGH — 非预期异常处理缺失：** Step 实现抛出非预期异常时（如 Redis 连接超时），处理器中无 catch-all，异常未经上下文填充直接传播至 ExceptionInterceptor
- **MEDIUM — 去重键竞争窗口：** 去重键值为 "pending" 而非实际 msg_id。后续查询去重键时 "pending" 无用
- **MEDIUM — ChatStreamObserver.userId 可空且无 null 安全检查：** `as?` 转换可能静默失败，导致 cleanupConnection 跳过 UserStreamRegistry 清理
- **MEDIUM — WriteStep 职责过多：** 同时处理 Snowflake ID 生成、ChatMessage 构建、Redis Stream 写入、会话元更新、未读计数递增。违背了 Step 链的可分离、可测试的意图
- **LOW — PushStep 的 `chatMessage!!` 是潜在 NPE：** 如果 Step 链乱序执行，`!!` 会 NPE
- **LOW — registerHandlers 函数签名迁移风险：** 在 06-04 中添加 3 个参数，将破坏 NebulaServer.kt 调用点。跨计划依赖应在此处明确标注

#### 4. Suggestions

- **强烈建议：** 将未读计数 INCR 从 WriteStep 提取为独立的 UnreadStep（或并入 PushStep）
- 在 SendMessageHandler 中添加 StepException 包裹，将非预期异常转化为带上下文的 SendMessageException
- 在 WriteStep 中将去重键值更新为实际 msg_id
- 使用 `requireNotNull(context.chatMessage) { "WriteStep must execute before PushStep" }` 替代 `!!`
- 创建后续 ticket 将 PushStep 改为异步执行

#### 5. Risk Assessment: MEDIUM

Step 链模式架构合理，Step 实现规范明确。但同步推送延迟问题和 WriteStep 职责扩张是实质性问题。可空 userId 在 ChatStreamObserver 中是潜在的正确性 bug。

---

### Plan 06-03: Message Pull & Read (Wave 2)

#### 1. Summary

实现两个标准 Handler：PullMessagesHandler（游标分页消息历史拉取）和 ReadReportHandler（已读回执处理、未读计数重置、私聊推送通知）。

#### 2. Strengths

- **cursor=0 边界情况优雅处理：** 用 Long.MAX_VALUE 作为有效游标是零分支的简洁方案
- **Limit 约束正确：** .coerceIn(1, 100) 防止零大小查询和过大结果集
- **receiver_uid 处理诚实：** 明确说明由于 MessageEntity 缺少该字段，拉取消息时统一填 0
- **会话类型判断正确：** 通过 ConversationEntity.type 字段判断私聊/群聊
- **D-28 竞态条件被文档化为可接受权衡：** 防止了未来的调试困惑
- **已读回执发送者发现逻辑经过深思：** 通过 ConversationMemberRepository 查找私聊的另一方成员

#### 3. Concerns

- **HIGH — T-06-10 被接受但无缓解措施：** PullMessagesHandler 不验证请求者是否为会话成员。任何认证用户可拉取任何会话的消息
- **MEDIUM — 不检查 conversationId 是否存在：** 不存在的会话返回空列表，与"无消息的会话"无法区分
- **MEDIUM — 更新已读回执前不验证成员身份：** 非成员可调用 updateReadReceipt 和 Redis DEL
- **MEDIUM — toChatMessage() 不处理 null messageType：** forNumber 可能返回 null，导致 NPE
- **LOW — 推送已读回执前不检查目标是否在线：** 应记录日志用于可观测性
- **LOW — 无分页边界文档：** cursor=0 → Long.MAX_VALUE 依赖 Snowflake ID < MAX_VALUE，应添加注释记录此假设

#### 4. Suggestions

- 在 PullMessagesHandler 中添加显眼的 `// SECURITY(FIXME Phase 7): 添加会话成员检查` 注释
- 在 PullMessagesHandler 中添加 ConversationRepository.findById() 检查，不存在的会话返回 CONV_NOT_FOUND
- 在 ReadReportHandler 开始时添加成员身份检查
- 在 forNumber 调用中使用 `?: ChatContentType.UNRECOGNIZED`
- 在向离线用户推送已读回执时记录 debug 日志

#### 5. Risk Assessment: MEDIUM

功能正确且规格明确。主要的担忧是已接受的信息泄露（T-06-10）——计划在 Phase 7 补充成员检查。缺失的会话存在性检查和读路径的成员验证是中等严重性的正确性问题。

---

### Plan 06-04: DI Wiring (Wave 3)

#### 1. Summary

将所有 Phase 6 组件连接到 Koin 容器，向 HandlerRegistry 注册三个新 Handler，更新 NebulaServer 调用点。

#### 2. Strengths

- **Koin 泛型类型注册正确：** 使用 `single<SendMessageStep> { ... }` 确保 Koin 可通过 get() 解析 List<SendMessageStep>
- **标记为 checkpoint:human-verify：** 适当的 DI 写入门控
- **依赖顺序明确：** 声明依赖 06-02 和 06-03，确保所有组件在写前存在
- **NebulaServer 修改谨慎指定：** 承认不确定性，要求执行者先确认实际代码模式

#### 3. Concerns

- **MEDIUM — ChatService 构造函数变更处理缺失：** 如果 ChatService 在 NebulaServer.kt 中实例化且需要 UserStreamRegistry 参数，计划必须更新该实例化点。Plan 的任务动作中未明确列出此项
- **MEDIUM — 导入爆炸风险：** 向 GatewayModule.kt 添加 8 个新 import，其中任何一个新文件编译失败都会连带整个模块
- **LOW — registerHandlers 参数列表增长：** 每阶段添加 2-4 个参数，到 Phase 11 将有 20+ 参数
- **LOW — 无集成/E2E 测试任务：** 仅验证编译，未验证运行时组件正确解析

#### 4. Suggestions

- 明确包含更新 ChatService 实例化的任务动作
- 添加简单的 Koin 验证测试：`koinApplication.checkModules()`
- 考虑将 Phase 6+ 的 Handler 模块声明提取到单独的 ChatHandlerModule.kt
- 记录 registerHandlers 参数可扩展性问题作为延期重构项

#### 5. Risk Assessment: LOW

DI 写入门机械，模式已在前阶段建立。主要风险是 ChatService 构造函数变更遗漏，server 编译会捕获。修复后应正确工作。

---

## Consensus Summary

### 唯一审阅者：Claude

由于本次仅 Claude CLI 可用，以下为 Claude 对 Phase 6 的整体评估总结。

### Main Findings

1. **D-04 同步推送偏差（HIGH 关注度）：** 用户决策 D-04 明确要求"写入 Redis Stream 后立即返回"，推送异步执行。但 06-02 计划将 PushStep 作为同步 Step 执行。建议在实现前讨论是否接受 ~250ms 延迟增加，或立即实现 fire-and-forget 异步推送。

2. **T-06-10 信息泄露（HIGH 关注度）：** PullMessagesHandler 不验证请求者是否为会话成员。任何认证用户可拉取任何会话的消息。需在 Phase 7 补充前添加显眼的 FIXME 注释。

3. **WriteStep 职责过重（MEDIUM 关注度）：** WriteStep 同时处理 ID 生成、消息构建、Redis Stream 写入、会话元更新、未读计数递增。建议将未读计数操作提取为独立 Step。

4. **ChatService 构造函数变更跨计划协调风险（MEDIUM 关注度）：** 06-02 添加参数，06-04 需更新实例化点。需在 06-04 中明确包含此项任务。

5. **测试覆盖缺失（MEDIUM 关注度）：** 4 个计划均未包含单元测试或集成测试任务。Step 链接口设计为可独立测试，但未计划创建测试。

### Phase Goal Achievement

| Success Criterion | Covered? | Notes |
|---|---|---|
| 1. chat/send validates, generates client_message_id | ✅ | ValidateStep + DedupStep |
| 2. Online members receive push via gRPC stream | ✅ | PushService + UserStreamRegistry |
| 3. Offline members stored for pull-on-reconnect | ⚠️ | 仅未读 INCR；PEL 恢复在 Phase 3 |
| 4. message/pull with cursor pagination | ✅ | PullMessagesHandler |
| 5. message/read updates and unread count | ✅ | ReadReportHandler |
| 6. Fan-out latency within acceptable bounds | ⚠️ | 同步推送增加 ~250ms 延迟 |

### Overall Risk Assessment: MEDIUM

架构设计良好，计划详尽。核心关注点：D-04 偏差、T-06-10 信息泄露、WriteStep 职责过重。这些问题可修复，建议在实现前处理。
