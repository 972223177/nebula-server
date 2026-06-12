---
status: completed
phase: 05-user-authentication
source:
  - 05-01-SUMMARY.md
  - 05-02-SUMMARY.md
  - 05-03-SUMMARY.md
  - 05-04-SUMMARY.md
started: 2026-06-12T17:05:00Z
updated: 2026-06-12T17:08:00Z
---

## Current Test

number: -1
name: (completed)
expected: |
  全部 22 项测试已完成，0 项问题
awaiting: none

## Tests

### 1. 全项目编译
expected: `./gradlew compileKotlin` 编译全部模块成功，无编译错误
result: pass

### 2. Gateway 单元测试通过
expected: `./gradlew :gateway:test` 全部 70+ 个测试通过（涵盖所有 Phase 5 Handler 单元测试 + 集成测试 + Koin 解析测试）
result: pass

### 3. Proto 定义 — Request.metadata 字段
expected: `envelope.proto` 中 `Request` 消息包含 `map<string, string> metadata = 3` 字段，用于传递 Token 和客户端 IP
result: pass

### 4. AuthInterceptor — Token 提取
expected: `AuthInterceptor.extractToken()` 从 `Request.metadata["authorization"]` 提取 Token 字符串
result: pass

### 5. AuthInterceptor — skipMethods 白名单
expected: `AuthInterceptor` 的 `skipMethods` 包含 `"system/ping"`、`"user/login"`、`"user/register"`（login/register 不需要认证）
result: pass

### 6. SessionRegistry — 设备类型互踢
expected: `SessionRegistry.registerWithDeviceType()` 方法存在，同设备类型用户登录时自动踢下旧连接（设备类型映射持久化到 Redis，重启可恢复）
result: pass

### 7. RegisterRateLimiter — IP 注册限流
expected: 每 IP 每小时最多 5 次注册请求，超过后返回限流错误。空 IP 条目自动清理防止内存泄漏。
result: pass

### 8. ChatService — gRPC 双向流
expected: `ChatService` 实现 `BindableService`，使用 `ServerCalls.asyncBidiStreamingCall` 注册自定义 BIDI_STREAMING 方法。支持 Envelope 协议分发和 LoginResp 拦截（Session 绑定 + tokenToObserver 管理）
result: pass

### 9. ChatServer — addService 修复
expected: `ChatServer.start(chatService: ChatService)` 调用 `builder.addService(chatService)`（Review 修复：Phase 4 遗漏的服务注册）
result: pass

### 10. LoginHandler — 密码验证 + Token 复用
expected: `LoginHandler` 支持密码登录（BCrypt cost 12 验证）和 Token 重连（sessionRegistry.validate(token) 有效时复用现有 Token）
result: pass

### 11. RegisterHandler — 注册
expected: `RegisterHandler` 校验用户名唯一性 + 密码长度 >= 6 + BCrypt cost 12 哈希。返回 RegisterResp.uid（不含 Token）
result: pass

### 12. SearchUserHandler — 模糊搜索 + 游标分页
expected: `SearchUserHandler` 支持 LIKE `%keyword%` 模糊搜索（参数绑定防 SQL 注入），游标分页最多 20 条。含 UserRepository.findByUsernameContaining() @Query 方法
result: pass

### 13. GetProfileHandler — 用户资料查询
expected: `GetProfileHandler`（user/getProfile）使用 `findById()` 查询用户详细资料，用户不存在返回 USER_NOT_FOUND
result: pass

### 14. BatchGetUserHandler — 批量用户查询
expected: `BatchGetUserHandler`（user/batchGet）使用 `findAllById()` 批量查询，缺失 ID 静默跳过（KDoc 已文档化）
result: pass

### 15. BatchGetStatusHandler — 批量在线状态（MGET 隐私过滤）
expected: `BatchGetStatusHandler`（user/batchGetStatus）使用 `PrivacyRepository.batchGetHideOnlineStatus()`（Redis MGET）一次批量查询隐私设置，避免 N+1
result: pass

### 16. SetPrivacyHandler — 隐私设置
expected: `SetPrivacyHandler`（user/setPrivacy）只允许修改当前登录用户的隐私设置（userId 从 Session 获取）。写 Redis 立即生效 + 异步 MySQL best-effort（KDoc 标注 Pitfall 4）
result: pass

### 17. GetPrivacyHandler — 隐私读取
expected: `GetPrivacyHandler`（user/getPrivacy）使用 `GetPrivacyReq` 空消息，userId 从 Session 获取。Redis 优先 + MySQL 回退
result: pass

### 18. GatewayModule — Koin 注册
expected: `GatewayModule.handlerModule` 包含所有 9 个 Handler 的 Koin `single { }` 声明（PingHandler + 8 个 Phase 5 Handler）
result: pass

### 19. NebulaServer — 启动流程
expected: `NebulaServer.kt` 包含 `externalModule`（注入 Repository），`startKoin` 完整初始化，ChatService 创建并传递给 ChatServer.start()
result: pass

### 20. GatewayModuleTest — Koin 解析 + 清理
expected: `GatewayModuleTest` 使用 `@AfterEach + stopKoin()` 显式清理，验证所有 8 个 Phase 5 Handler 可被 Koin 解析
result: pass

### 21. PipelineIntegrationTest — 端到端分发
expected: `PipelineIntegrationTest` 包含 login/register/search/getProfile 等 Handler 的端到端分发测试
result: pass

### 22. 预置账号 — 种子数据
expected: `V1_2__seed_users.sql` 包含 3 个预置账号（admin/admin123, testuser1/test1234, testuser2/test1234），bcrypt 预哈希
result: pass

## Summary

total: 22
passed: 22
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
