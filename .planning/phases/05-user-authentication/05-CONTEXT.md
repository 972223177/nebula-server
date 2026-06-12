# Phase 5: User & Authentication - Context

**Gathered:** 2026-06-12
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement login flow, session management, multi-device kick, and all user-related CRUD APIs. Covers AUTH-01~06 and BIZ-USER-01~06.

- **Login (AUTH-01):** password-based login returning token, reconnect with existing token
- **Token management (AUTH-03):** UUID format, 7-day TTL, stored in Redis
- **Session management (AUTH-04):** local in-memory session map (L1) + Redis (L2) — Session/ SessionRegistry already built in Phase 4
- **Multi-device kick (AUTH-05/06):** same-device-type kick with LOGOUT notification
- **User APIs (BIZ-USER-01~06):** search, get profile, batch get users, batch online status, set/get privacy
</domain>

<decisions>
## Implementation Decisions

### 用户来源与注册
- **D-01:** 同时支持预置账号导入（SQL 初始化脚本/管理员批量创建）和开放注册 API (`user/register`)
- **D-02:** 注册防护措施：IP 频率限制（每小时 5 次，复用 RateLimitInterceptor）、用户名唯一性校验、密码强度校验（最低 6 位），不额外添加验证码
- **D-03:** 注册时密码使用 bcrypt 加密（cost 12，与设计规范一致）

### 登录后连接绑定
- **D-04:** Gateway 层（ChatService/ChatGatewayImpl）在发送 LoginResp 前拦截响应，自动完成 Session 注册和 StreamObserver 绑定。**Handler 层不感知 StreamObserver**，保持职责链完整。
- **D-05:** 绑定流程：LoginHandler 验证密码 → 返回 LoginResp（含 token）→ ChatService 检测到 LoginResp 类型 → 调用 `SessionRegistry.register(session, observer)` → 同类型设备踢下线 → 发送 Logout 通知 → 发送 LoginResp 给客户端
- **D-06:** 无需额外的 system/bind 客户端请求，消除网络往返引入的不一致风险

### 用户搜索
- **D-07:** 搜索范围：仅 `username` 字段，LIKE 模糊匹配（%keyword%）
- **D-08:** 游标分页，每页最多 20 条，按注册时间倒序排列

### 隐私控制
- **D-09:** `hide_online_status` 优先存入 Redis，后续异步刷 MySQL 做持久化
- **D-10:** 在 Phase 5 的 `batchGetOnlineStatus` 中立即生效：跳过 `hide_online_status=true` 的用户
- **D-11:** `getPrivacy` 读 Redis，`setPrivacy` 写 Redis + 异步写 MySQL

### Claude's Discretion
- 预置账号的具体字段和初始数据内容（部分由 DB schema 决定）
- register API 的请求/响应 proto 定义细节（新增 `user/register` 方法）
- LoginResp 在 ChatService 层的拦截点具体实现方式
- Redis 隐私 key 的命名结构

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 认证与会话设计
- `设计文档/后端架构设计v1.2/04-认证与会话/4.1-密码存储与验证.md` — bcrypt cost 12, TLS 传输
- `设计文档/后端架构设计v1.2/04-认证与会话/4.2-Token结构与有效时间.md` — UUID token, 7 天 TTL
- `设计文档/后端架构设计v1.2/04-认证与会话/4.3-Redis存储结构.md` — session/token/online key 设计
- `设计文档/后端架构设计v1.2/04-认证与会话/4.4-本地内存映射.md` — 本地内存映射结构
- `设计文档/后端架构设计v1.2/04-认证与会话/4.5-多设备策略.md` — 同类型设备踢下线
- `设计文档/后端架构设计v1.2/04-认证与会话/4.6-user-login接口.md` — LoginResp 额外字段说明

### 接口协议
- `proto/src/main/proto/nebula/user/user.proto` — user 域请求/响应消息定义

### 已有实现（Phase 4）
- `service/src/main/kotlin/com/nebula/service/session/Session.kt` — Session 数据模型
- `service/src/main/kotlin/com/nebula/service/session/SessionRegistry.kt` — L1/L2 session 缓存
- `service/src/main/kotlin/com/nebula/service/interceptor/AuthInterceptor.kt` — AuthInterceptor（skipMethods 列表）
- `service/src/main/kotlin/com/nebula/service/dispatcher/Dispatcher.kt` — 请求路由与分发
- `service/src/main/kotlin/com/nebula/service/codec/ProtoCodec.kt` — 序列化/反序列化

### 数据库层（Phase 3）
- `repository/src/main/kotlin/com/nebula/repository/entity/UserEntity.kt` — 用户实体（若已定义）
- `repository/src/main/kotlin/com/nebula/repository/repository/UserRepository.kt` — 用户仓库（若已定义）

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **Session / SessionRegistry** — Phase 4 已实现，直接用于登录后注册 Session 和管理在线映射
- **AuthInterceptor** — skipMethods 机制可扩展，`user/register` 不应走 auth 校验
- **RateLimitInterceptor** — 可扩展注册频率限制规则
- **CoroutineContext Session 注入** — Phase 4 D-03 机制，Handler 通过 context[Session] 获取当前会话
- **SnowflakeIdGenerator** — 用于新注册用户生成 uid（外部唯一标识）

### Established Patterns
- **Handler 接口模式** — `Handler<ReqT, RespT>` + `method()` 绑定，所有用户 API 按此模式实现
- **响应拦截模式** — ChatService onNext → Dispatcher → InterceptorChain → Handler → 反向路径，登录绑定的拦截点选在 ChatService 层
- **L1/L2 缓存模式** — Caffeine (L1) + Redis (L2)，SessionRegistry 已落地此模式

### Integration Points
- **login → SessionRegistry.register**: ChatService 检测 LoginResp 后调用 register(observer) 完成绑定
- **login → 同类型踢下线**: register 内部 eviction 回调，通知旧连接下线（LOGOUT notification）
- **batchGetOnlineStatus → SessionRegistry**: 读取在线用户列表，结合 privacy Redis 过滤
- **searchUser → UserRepository**: LIKE 查询 + 游标分页

</code_context>

<specifics>
## Specific Ideas

- 登录响应拦截模式参考了 Phase 4 Dispatcher 的"响应路径可扩展"设计，拦截点在 ChatService/Gateway 层而非 Handler 层，确保职责分离
- 注册和隐私字段的 Redis key 命名应与 Phase 3 已定义的 `session:{userId}:{deviceType}` / `token:{token}` 结构保持一致

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 5-User & Authentication*
*Context gathered: 2026-06-12*
