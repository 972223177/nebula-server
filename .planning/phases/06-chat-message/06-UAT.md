---
status: partial
phase: 06-chat-message
source:
  - 06-01-SUMMARY.md
  - 06-02-SUMMARY.md
  - 06-03-SUMMARY.md
  - 06-04-SUMMARY.md
started: 2026-06-12T16:30:00+08:00
updated: 2026-06-13T10:30:00+08:00
---

## Current Test

[testing paused — 8 items blocked by pre-existing JPA EntityManager lifecycle issue]

## Results

| # | 测试名称 | 结果 | 说明 |
|---|----------|------|------|
| 1 | 编译与单元测试 | ✅ PASS | 全量编译通过，gateway 111 tests + server KoinVerificationTest 全部通过 |
| 2 | 发送聊天消息 (chat/send) | ⚠️ BLOCKED | 依赖 user/login 获取 token，但 LoginHandler 在 Phase 5 的 JPA EntityManager 生命周期问题未解决 |
| 3 | 消息去重 (DedupStep) | ⚠️ BLOCKED | 同 Test 2 |
| 4 | 无效消息校验 (ValidateStep) | ⚠️ BLOCKED | 同 Test 2 |
| 5 | 消息历史拉取 (message/pull) | ⚠️ BLOCKED | 同 Test 2 |
| 6 | 游标分页 | ⚠️ BLOCKED | 同 Test 2 |
| 7 | 已读报告 (message/read) | ⚠️ BLOCKED | 同 Test 2 |
| 8 | PushService 消息推送 | ⚠️ BLOCKED | 需要完整端到端流程（含登录） |
| 9 | PushService 已读回执推送 | ⚠️ BLOCKED | 需要完整端到端流程（含登录） |
| 10 | Handler 路由注册 | ✅ PASS | E2E 验证：grpcurl 连接成功，`user/register` 路由正常返回 200 |

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

## Known Issues

### Resolved: Redis Session 超时（D-29）
- **现象**: `SessionRegistry - Redis save timeout for token`（500ms 协程超时）
- **根本原因**: `MessageRepositoryImpl.startFlushTimer()` 的 `flushBatch()` 中的 Redis XREADGROUP 操作与 SessionRegistry 的 Redis 操作共享同一 `StatefulRedisConnection`，导致连接争用
- **修复**: `RedisConfig` 新增 `messageQueueConnection` 独立连接，`MessageQueueRepository` 使用专用连接
- **验证**: 服务器启动后无 Redis timeout 警告，XREADGROUP 运行在独立连接上
- **文件**: `repository/.../config/RedisConfig.kt`, `server/.../NebulaServer.kt`

### Unresolved: JPA EntityManager 生命周期问题（Phase 5 遗留）
- **现象**: `user/login` 请求发送后无响应返回（server 端 handleLogin 不返回）
- **根本原因**: `JpaConfig.getRepository()` 创建的 Repository 使用单个 `EntityManager` 实例，`LoginHandler` 的 `userRepo.findByUsername()` 在此 EntityManager 上执行查询时挂起
- **关联**: `RegisterHandler.kt` KDoc 已有说明（第 29-30 行）："由于使用独立 EntityManager，无法看到本 Handler 写入的数据。该问题将在 Phase 6 修复（TODO: 全局事务管理）"
- **影响**: 阻塞所有需要登录认证的 API 测试（test 2-9）
- **修复建议**: 需引入 `PlatformTransactionManager` + 全局事务管理，或用 `TransactionTemplate` 包装所有 JPA 操作
- **状态**: Pre-existing issue，Phase 5 引入，当前 Phase 6 尚未触及

## Summary

total: 10
passed: 2
blocked: 8
issues: 0
pending: 0
skipped: 0

## Fix Commits Ready

以下修复已验证并可通过 /gsd-execute-phase 提交:
1. HQL 类型不匹配修复 (UserRepository, SearchUserHandler, SearchUserHandlerTest, PipelineIntegrationTest)
2. Koin externalModule Repository 注册补全 (NebulaServer)
3. UserEntity createdAt/updatedAt 设置 (RegisterHandler)
4. JPA 手动事务管理 (RegisterHandler, GatewayModule, GatewayModuleTest, PipelineIntegrationTest, RegisterHandlerTest, gateway/build.gradle.kts)
5. 【新增】Redis 独立连接 D-29 (RedisConfig, NebulaServer)
