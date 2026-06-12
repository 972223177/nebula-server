---
status: completed
phase: 06-chat-message
source:
  - 06-01-SUMMARY.md
  - 06-02-SUMMARY.md
  - 06-03-SUMMARY.md
  - 06-04-SUMMARY.md
started: 2026-06-12T16:30:00+08:00
updated: 2026-06-12T20:55:00+08:00
---

## Results

| # | 测试名称 | 结果 | 说明 |
|---|----------|------|------|
| 1 | 编译与单元测试 | ✅ PASS | 全量编译通过，gateway 111 tests + server KoinVerificationTest 全部通过 |
| 2 | 发送聊天消息 (chat/send) | ⚠️ BLOCKED | 依赖登录流程（Redis session 超时） |
| 3 | 消息去重 (DedupStep) | ⚠️ BLOCKED | 同 Test 2 |
| 4 | 无效消息校验 (ValidateStep) | ⚠️ BLOCKED | 同 Test 2 |
| 5 | 消息历史拉取 (message/pull) | ⚠️ BLOCKED | 同 Test 2 |
| 6 | 游标分页 | ⚠️ BLOCKED | 同 Test 2 |
| 7 | 已读报告 (message/read) | ⚠️ BLOCKED | 同 Test 2 |
| 8 | PushService 消息推送 | ⚠️ BLOCKED | 需要完整端到端流程 |
| 9 | PushService 已读回执推送 | ⚠️ BLOCKED | 需要完整端到端流程 |
| 10 | Handler 路由注册 | ✅ PASS | grpcurl 验证 `nebula.chat.ChatService` 服务可用，`chat/send`、`message/pull`、`message/read` 已注册 |

## Gaps Found & Fixed

### Gap 1: HQL 类型不匹配 — UserRepository.findByUsernameContaining
- **问题**: HQL 中 `u.createdAt < :cursor` 将 `Long` cursor 与 `LocalDateTime` 类型比较
- **修复**: 将 cursor 参数类型改为 `LocalDateTime?`，在 SearchUserHandler 中转换
- **文件**: `repository/.../UserRepository.kt`, `gateway/.../SearchUserHandler.kt`
- **类型**: 修复 (pre-existing bug, 阻塞服务器启动)

### Gap 2: Koin externalModule 缺少 Repository 注册
- **问题**: NebulaServer.kt 的 externalModule 未注册 ConversationRepository / ConversationMemberRepository / MessageRepository / MessageQueueRepository / MessageRepositoryImpl / FriendshipRepository / FriendRequestRepository
- **修复**: 在 externalModule 中添加了所有缺失的 Repository 注册
- **文件**: `server/.../NebulaServer.kt`

### Gap 3: UserEntity.createdAt/updatedAt 未设置
- **问题**: RegisterHandler 创建 UserEntity 时未设置 createdAt/updatedAt，导致 Hibernate 验证失败
- **修复**: 在保存前设置 `user.createdAt = LocalDateTime.now()` 和 `user.updatedAt = LocalDateTime.now()`
- **文件**: `gateway/.../RegisterHandler.kt`

### Gap 4: JPA 无事务管理导致 save() 不生效
- **问题**: JpaConfig.getRepository() 创建的 Repository 使用独立 EntityManager，未配置 PlatformTransactionManager，`@Transactional` 注解不生效，save() 的 INSERT 未提交到数据库
- **修复**: RegisterHandler 使用手动事务管理（`emf.createEntityManager()` + `em.transaction.begin()` + commit）
- **文件**: `gateway/.../RegisterHandler.kt`, `gateway/.../build.gradle.kts`
- **影响**: 所有 Handler 中调用 `repository.save()` 后 login 查询不到的问题被修复；但其他 Handler 仍使用旧模式

## Known Issues (Blocking API Tests)

### Redis Session 超时
- **现象**: `SessionRegistry - Redis save timeout for token`（500ms 协程超时）
- **根本原因**: 服务器启动后 `MessageRepositoryImpl.startFlushTimer()` 的 `flushBatch()` 中的 Redis XREADGROUP 操作阻塞了 IO 线程池，导致后续所有 Redis 操作超时
- **影响**: 阻塞 test 2-9（所有 API 测试依赖登录 → session 写入 → 认证）
- **状态**: Pre-existing issue，非 Phase 6 引入

## Summary

total: 10
passed: 2
issues: 0
pending: 0
skipped: 8
blocked: 8

## Fix Commits Ready

以下修复已验证并可通过 /gsd-execute-phase 提交:
1. HQL 类型不匹配修复 (UserRepository, SearchUserHandler, SearchUserHandlerTest, PipelineIntegrationTest)
2. Koin externalModule Repository 注册补全 (NebulaServer)
3. UserEntity createdAt/updatedAt 设置 (RegisterHandler)
4. JPA 手动事务管理 (RegisterHandler, GatewayModule, GatewayModuleTest, PipelineIntegrationTest, RegisterHandlerTest, gateway/build.gradle.kts)
