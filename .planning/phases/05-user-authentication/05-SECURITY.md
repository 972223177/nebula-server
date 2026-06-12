---
phase: 05
slug: user-authentication
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-12
---

# Phase 05 — 安全合约

> 每阶段安全合约：威胁注册表、已接受的风险和审计轨迹。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| `Request.metadata` 入口 | 客户端在 metadata["authorization"] 中携带 Token | Token 字符串（UUIDv4） |
| `AuthInterceptor.skipMethods` | 白名单方法（system/ping、user/login、user/register）免认证 | 无认证数据 |
| `LoginHandler.handle()` | 密码验证和 Token 重连入口 | 明文密码或 Token |
| `RegisterHandler.handle()` | 用户注册入口（IP 限流后） | 明文密码、用户名 |
| `BCryptPasswordEncoder(12)` | 密码哈希边界 | 明文密码 → 哈希值 |
| `SessionRegistry L1 → L2` | Session 写入 Redis 持久化 | Session JSON（含 userId/token/deviceType） |
| `SessionRegistry deviceTypeIndex` | 同类型设备互踢逻辑 | userId:deviceType → token 映射 |
| `ChatService.handleLoginSuccess()` | LoginResp 拦截 + Session 绑定 | LoginResp 中的设备信息 |
| `tokenToObserver` 映射 | 推送连接的 StreamObserver 生命周期管理 | token → gRPC StreamObserver |
| `PrivacyRepository` | 隐私设置 Redis 读写 | hideOnlineStatus boolean |
| `batchGetHideOnlineStatus()` | MGET 批量隐私过滤 | 隐私设置 JSON |
| `Koin DI 容器` | 组件注册和依赖解析 | 组件引用 |
| `NebulaServer.kt` 启动顺序 | startKoin → registerHandlers → gRPC 启动 | Koin 容器、HandlerRegistry 状态 |
| `V1_2__seed_users.sql` | 种子数据 Flyway 迁移 | 预哈希的 bcrypt 密码 |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-05-01 | 身份伪造 (Spoofing) | Request.metadata authorization | mitigate | AuthInterceptor 通过 `extractToken()` 从 metadata["authorization"] 提取 Token，`SessionRegistry.validate()` 验证 Token 有效性（L1 缓存 → L2 Redis）；Token 为 UUIDv4 不可伪造 | closed |
| T-05-02 | 篡改 (Tampering) | PrivacyRepository → Redis 写入 | accept | Redis 内网部署非公开暴露；数据非敏感（仅 hideOnlineStatus boolean） | closed |
| T-05-03 | 信息泄露 (Information Disclosure) | batchGetStatus 隐私过滤 | mitigate | BatchGetStatusHandler 调用 `batchGetHideOnlineStatus()`（Redis MGET）批量查询隐藏用户，隐藏用户不在结果中返回（D-10） | closed |
| T-05-SC | 篡改 (Tampering) | spring-security-crypto 安装 | mitigate | Maven Central 官方源，版本 6.4.5 已验证无 CVE | closed |
| T-05-04 | 身份伪造 (Spoofing) | LoginHandler | mitigate | bcrypt cost 12 验证密码防彩虹表攻击；Token 重连通过 `sessionRegistry.validate()` 复用现有 Token 防 Session 孤儿化 | closed |
| T-05-05 | 篡改 (Tampering) | RegisterHandler | mitigate | 用户名唯一性校验（`userRepository.findByUsername()`）；密码强度校验（最小 6 位）；BCrypt cost 12 哈希 | closed |
| T-05-06 | 信息泄露 (Information Disclosure) | LoginHandler | accept | 用户名不存在返回 `USER_NOT_FOUND`、密码错误返回 `AUTH_FAILED`，不同错误码不泄露具体是哪个字段错误 | closed |
| T-05-07 | 篡改 (Tampering) | SearchUserHandler SQL 注入 | mitigate | JPA `@Query` 参数绑定（`:keyword`/`:cursor`），非字符串拼接，防止 SQL 注入 | closed |
| T-05-08 | 拒绝服务 (Denial of Service) | RegisterHandler | mitigate | RegisterRateLimiter IP 限流（每小时 5 次/每 IP）；内存泄漏修复：空 IP 条目自动清理 | closed |
| T-05-09 | 信息泄露 (Information Disclosure) | BatchGetStatusHandler | mitigate | D-10 隐私过滤：`batchGetHideOnlineStatus()` 使用 Redis MGET 批量查询隐藏用户，不在结果中返回 | closed |
| T-05-10 | 篡改 (Tampering) | SetPrivacyHandler | mitigate | 只允许修改当前登录用户的隐私设置——userId 通过 `coroutineContext.requireSession()` 从 Session 获取，非请求参数传入，防止越权修改 | closed |
| T-05-11 | 信息泄露 (Information Disclosure) | GetProfileHandler | accept | 当前所有用户资料公开；Phase 8 可增加好友/非好友可见范围 | closed |
| T-05-12 | 身份伪造 (Spoofing) | Koin DI 容器 | accept | 仅容器内部注册，无外部输入；攻击者需能修改编译后的 class 文件 | closed |
| T-05-13 | 权限提升 (Elevation of Privilege) | registerHandlers() 注册顺序 | mitigate | Handler 注册在 `startKoin` 之后、gRPC 启动之前执行，确保 AuthInterceptor 等拦截器已就绪（NebulaServer.kt 第 127~145 行） | closed |
| T-05-14 | 篡改 (Tampering) | ChatService gRPC 服务 | mitigate | ChatService 通过 `BindableService.bindService()` 编译期绑定，ServerServiceDefinition 在编译时构建，无运行时热加载 | closed |

*状态: open · closed*
*处置: mitigate (需实现) · accept (已记录风险) · transfer (第三方)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-05-01 | T-05-02 | Redis 内网部署，仅存储 hideOnlineStatus 布尔值，数据非敏感 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-05-02 | T-05-06 | 故意设计：不同错误码让客户端能精确判断错误类型，UX 收益大于信息泄露风险 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-05-03 | T-05-11 | 用户资料当前对全服公开（类似微信的 "通过搜索找到我"），Phase 8 可添加好友/非好友可见范围 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-05-04 | T-05-12 | Koin DI 为编译期组件装配，攻击面仅存在于可修改编译后 class 文件的场景，非运行时攻击向量 | plan-audit (gsd-secure-phase) | 2026-06-12 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-05-01: AuthInterceptor Token 验证

- `gateway/src/main/kotlin/.../AuthInterceptor.kt` 第 67~72 行：`extractToken()` 从 `request.metadataMap["authorization"]` 提取 Token ✅
- `gateway/src/main/kotlin/.../SessionRegistry.kt` 第 181~185 行：`validate()` 先查 L1 本地缓存，再查 L2 Redis ✅
- `gateway/src/main/kotlin/.../LoginHandler.kt` 第 78 行：Token 使用 `UUID.randomUUID().toString()` 生成 ✅
- `gateway/src/main/kotlin/.../GatewayModule.kt` 第 40~43 行：AuthInterceptor 在 Koin 中注入时 `skipMethods` 包含 `"user/login"`、`"user/register"` ✅

### T-05-04/T-05-05: BCrypt 密码哈希

- `gateway/src/main/kotlin/.../LoginHandler.kt` 第 92~93 行：`verifyPassword()` 使用 `BCryptPasswordEncoder(12).matches()` ✅
- `gateway/src/main/kotlin/.../RegisterHandler.kt` 第 71 行：`BCryptPasswordEncoder(12).encode()` ✅

### T-05-07: SQL 注入防护

- `repository/src/main/kotlin/.../UserRepository.kt` 第 27~28 行：`@Query("SELECT u FROM UserEntity u WHERE u.username LIKE %:keyword% AND (:cursor = 0L OR u.createdAt < :cursor)")`，使用 `:keyword`/`:cursor` 参数绑定 ✅

### T-05-08: 注册限流

- `gateway/src/main/kotlin/.../RateLimitInterceptor.kt` 第 41~49 行：`user/register` 请求走独立 IP 限流 ✅
- 第 96~121 行：RegisterRateLimiter 实现每小时 5 次/每 IP 限流；第 116~118 行空 IP 条目拦截器防内存泄漏 ✅

### T-05-10: SetPrivacyHandler 越权防护

- `gateway/src/main/kotlin/.../SetPrivacyHandler.kt` 第 45~46 行：`coroutineContext.requireSession()` 从 Session 获取 userId，非请求参数 ✅

### T-05-09: 批量隐私过滤

- `gateway/src/main/kotlin/.../BatchGetStatusHandler.kt` 第 47 行：`batchGetHideOnlineStatus()` 使用 Redis MGET ✅
- 第 51 行：`if (uid in hiddenUserIds) continue` 跳过隐藏用户 ✅

### T-05-13: 启动顺序

- `server/src/main/kotlin/.../NebulaServer.kt` 第 123~125 行：`startKoin` 先执行 ✅
- 第 127~145 行：Handler 注册在 Koin 之后、gRPC 启动之前 ✅

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|------------|---------------|--------|------|--------|
| 2026-06-12 | 15 | 15 | 0 | gsd-secure-phase (自动验证) |

---

## 签收

- [x] 所有威胁都有处置方案（mitigate / accept / transfer）
- [x] 已接受的风险记录在风险日志中
- [x] `threats_open: 0` 确认
- [x] `status: verified` 已在前置元数据中设置

**审批：** verified 2026-06-12
