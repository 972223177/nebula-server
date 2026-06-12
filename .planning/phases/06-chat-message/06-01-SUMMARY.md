# 06-01-SUMMARY.md — Chat 消息推送基础设施

## 完成情况

所有 4 个任务已完成：

- **Task 1 ✅** — protobuf 重新生成通过，D-22 变更已验证（sender_username/sender_avatar 已移除，receiver_uid 已添加）
- **Task 2 ✅** — UserStreamRegistry（ConcurrentHashMap<Long, CopyOnWriteArrayList> 模式）和 SendMessageException（继承 BizException）创建，编译通过
- **Task 3 ✅** — PushService（pushMessage + pushReadReceipt）创建，调用 ConversationMemberRepository.findByConversationId，单 observer 异常容错
- **Task 4 ✅** — 15 个单元测试全部通过（UserStreamRegistryTest 9 个 + PushServiceTest 6 个）

## 新建文件

- `gateway/.../session/UserStreamRegistry.kt` — userId→StreamObserver 注册中心
- `gateway/.../handler/chat/send/SendMessageException.kt` — 消息领域异常
- `gateway/.../push/PushService.kt` — 推送服务（pushMessage/pushReadReceipt）
- `gateway/.../push/PushServiceTest.kt` — PushService 单元测试
- `gateway/.../session/UserStreamRegistryTest.kt` — UserStreamRegistry 单元测试

## 修改文件

- `repository/.../ConversationMemberRepository.kt` — 新增 findByConversationId 方法

## 设计决策

- D-01, D-02: UserStreamRegistry ConcurrentHashMap + CopyOnWriteArrayList
- D-05: 单 observer 异常 try-catch 容错
- D-09: pushMessage 排除发送者
- D-11, D-12: ChatMessage 推送逻辑
- D-15: ReadReceipt 推送逻辑
- D-22: message.proto ChatMessage 字段变更

## 风险

- REVIEW-MEDIUM-4: PushService.pushMessage 调用 blocking JPA findById，已添加 TODO 注释
- PushService 的 pushMessage 暂未设置 ChatMessage payload 序列化到 Envelope（由后续 Plan 02 的 SendMessageHandler 完成）
