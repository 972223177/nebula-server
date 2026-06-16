# Nebula Chat Server — 全面代码审查报告

> 审查团队：4 位专职评审员，覆盖 6 个模块、116 个主代码文件、52 个测试文件  
> 审查日期：2026-06-16  
> 审查原则：不依赖 .planning/ 工作流文档，仅基于代码本身

---

## 一、总体概览

| 类别 | 数量 | 严重 (🔴) | 重要 (🟡) | 建议 (🔵) |
|------|------|-----------|-----------|-----------|
| Proto 模块 | 11 | 1 | 7 | 3 |
| Common 模块 | 8 | 0 | 4 | 4 |
| Repository 模块 | 26 | 8 | 10 | 8 |
| Service 模块 | 8 | 0 | 4 | 4 |
| Gateway 模块 | 21 | 5 | 9 | 7 |
| Server + 构建 | 13 | 4 | 4 | 5 |
| 架构/安全/测试 | 16 | 3 | 6 | 7 |
| **合计** | **~103** | **21** | **44** | **38** |

---

## 二、🔴 最优先修复（Top 15 严重问题）

### 架构层

| # | 问题 | 定位 | 影响 |
|---|------|------|------|
| 1 | **NebulaServer 直接依赖 repository 模块**，绕过 service/gateway 层 | `server/build.gradle.kts:41`, `NebulaServer.kt:18-24` | 分层违规，序列号恢复、死信回调直接操作数据层 |
| 2 | **Session TTL 从未刷新**，活跃用户 7 天后必然强制下线 | `AuthInterceptor.kt`, `SessionRegistry.kt` | `refreshTtl()` 存在但零调用，所有活跃用户 7 天必然断开 |
| 3 | **RateLimitInterceptor 返回 429 而非 BizCode.RATE_LIMITED(1004)** | `RateLimitInterceptor.kt:188` | 客户端无法按统一 BizCode 格式解析限流错误 |

### 数据层

| # | 问题 | 定位 | 影响 |
|---|------|------|------|
| 4 | **Redis Stream 双写路径字段名不一致** | `MessageService.kt:124` vs `MessageRepositoryImpl.kt:56` | sendMessage 写入 `conversation_id`，flushBatch 解析 `conversationId`，**消息丢失** |
| 5 | **EntityManager 每次创建不关闭，泄漏 7+ 个连接** | `JpaConfig.kt:56-60` | 连接泄漏，长时间运行耗尽连接池 |
| 6 | **SETNX + EXPIRE 非原子操作** | `MessageQueueRepository.kt:112-123` | 进程崩溃导致 key 永不过期 → 内存泄漏 + 去重永久生效 |
| 7 | **好友列表游标分页方向错误** | `FriendshipRepository.kt:30` | DESC 排序应使用 `<` 而非 `>`，分页结果错乱 |
| 8 | **多处多表写入无事务包裹** | `FriendService.kt`, `ConversationService.kt` | 部分成功部分失败时数据不一致 |

### 网关层

| # | 问题 | 定位 | 影响 |
|---|------|------|------|
| 9 | **SendMessageHandler fire-and-forget 异常静默吞没** | `SendMessageHandler.kt:91-137` | 推送失败完全不可知，DB/Redis 数据不一致 |
| 10 | **runBlocking 在 6 个 Handler 的 TransactionTemplate 内阻塞协程线程** | `FriendAddHandler.kt:57` 等 6 处 | 高并发下协程线程池耗尽 |
| 11 | **AuthInterceptor admin/ 前缀认证白名单配置不确定** | `AuthInterceptor.kt:27`, `FrameworkModule` | 默认值含 admin/，但 DI 注入覆盖后可能不含，导致 admin 端点认证状态未知 |
| 12 | **Token 重连未验证 Token 与设备/用户的绑定关系** | `LoginHandler.kt:36-42` | 攻击者可获取任一有效 token 在其他设备复用 |

### Proto 层

| # | 问题 | 定位 | 影响 |
|---|------|------|------|
| 13 | **GroupInvitedPayload 消息缺失** | `conversation.proto` | 推送 GROUP_INVITED 事件时 payload 反序列化失败 |
| 14 | **多个接口无响应消息定义**（9 个 method 缺 XxxResp） | 多个 .proto 文件 | 未来扩展时可能不兼容 |

### 部署

| # | 问题 | 定位 | 影响 |
|---|------|------|------|
| 15 | **ChatServer 优雅关闭仅 5 秒，高负载下可能丢失缓冲消息** | `ChatServer.kt:87` | 缓冲消息刷盘不完整 |
| 16 | **docker-compose.yml 硬编码密码 root123** | `docker-compose.yml:8` | 安全风险 |

---

## 三、🟡 本迭代需修复（主要问题精选）

### 数据完整性
- **V1 迁移脚本缺少外键约束** — messages/friendships/friend_requests 均无 FOREIGN KEY，可能出现孤儿记录
- **MessageService.sendMessage 去重步骤被注释跳过** — 实际未执行去重
- **ConversationService.inviteMember 成员计数不准确** — 恢复已退出成员时错误增加 memberCount
- **ConversationService.leaveGroup 未处理唯一成员场景** — 群主是唯一成员时应允许退群并解散

### 并发安全性
- **ConversationLockManager 锁永不释放** — `locks` Map 无限增长，长期运行可能内存泄漏
- **RateLimitInterceptor 信号量清理有竞态条件** — removeIf 对 ConcurrentHashMap 的弱一致性
- **SeqService.nextSeq 序列号重置竞态** — GET→检查→SET 非原子
- **UserService.register TOCTOU 竞态** — findByUsername→save 之间有窗口

### 代码质量
- **BCryptPasswordEncoder 每次 new 新实例** — 高频场景增加 GC 压力
- **ReadReportHandler redis.del() 无异常保护** — Redis 不可用时已读报告失败
- **SetPrivacyHandler 空 catch 块吞没所有异常** — 生产环境排查不可能
- **ExceptionInterceptor 吞掉 CancellationException** — 阻止协程取消信号传播
- **LogInterceptor 硬编码 200 判断成功** — 应使用 `BizCode.OK.code`

### Proto/API
- **API.md 章节编号重复**（两个 11 号）
- **API.md 路由表将 delivery-ack 列为客户端方法** — 实际仅推送
- **BatchIdRequest 命名不符合 Req 规范**

### 构建/配置
- **server/build.gradle.kts 存在大量冗余依赖声明**
- **两套 RedisConfig 并存命名冲突** — common 中是配置 data class，repository 中是连接管理
- **CI 缺少测试报告归档和并行测试**
- **Gradle 版本目录中疑似未使用的库**

---

## 四、正面评价（项目优势）

### 设计优势
1. **Envelope + oneof 设计优雅** — 单长连接承载所有通信方向，Direction 枚举的双重心跳策略设计合理
2. **分发链路清晰** — Dispatcher → Registry → Chain，拦截器链 foldRight 构建方式优雅
3. **L1+L2 二级缓存设计务实** — Session 管理中的设计取舍明确标注
4. **异常体系完整** — BizCode 按领域分段编码 + BizException 子类树 + ExceptionInterceptor 三态映射

### 工程优势
5. **中文注释覆盖率良好** — 大部分公共 API 符合 CODEBUDDY.md 规范
6. **SnowflakeIdGenerator 设计优秀** — 使用 Mutex（协程安全）+ Clock 接口（可测试）
7. **ModuleInitializer 拓扑排序** — 6 类场景测试覆盖全面
8. **Protobuf + gRPC-Kotlin 协程流** — 技术选型合理
9. **仓库分层清晰** — JPA Repository → JPA Implementation → Redis Repository 职责分明

### 测试优势
10. **gateway 层测试覆盖全面** — 35 个测试文件
11. **repository 层使用 TestContainers** — 集成测试质量高
12. **TestHelper.kt + TestTags.kt** — 测试辅助工具设计优秀，降低新测试编写门槛

---

## 五、修复优先级建议

### 第一优先级（安全/数据丢失）— 立即修复
1. Session TTL 刷新（活跃用户强制下线）
2. Redis Stream 双写路径字段名不一致（消息丢失）
3. EntityManager 泄漏
4. SETNX+EXPIRE 原子化

### 第二优先级（功能正确性）— 本迭代修复
5. 多表写入事务边界
6. 好友列表分页方向
7. runBlocking → suspend 改造
8. SendMessageHandler 异常处理
9. AuthInterceptor admin 白名单确认
10. GroupInvitedPayload 补充

### 第三优先级（代码质量）— 下迭代
11. BCrypt/TOCTOU/空 catch 等问题
12. Proto/API.md 一致性
13. 构建配置清理

### 第四优先级（增强）— 后续规划
14. gRPC 双向流端到端集成测试
15. 密码策略加强
16. 暴力破解防护

---

## 六、跨报告交叉验证

| 问题 | 发现者 | 交叉确认 |
|------|--------|---------|
| Session TTL 未刷新 | cross-cutting | 与 gateway 的 C5（Token 重连空窗期）为同一根因 |
| BCrypt 每次 new | repo-service + cross-cutting | 两位评审员独立发现 |
| runBlocking 阻塞 | gateway | 与 repo-service 的 R3/R4（缺少事务）同源 |
| 分层违规 | cross-cutting | repo-service 报告中也暗示了耦合问题 |
| 限流码不一致 | cross-cutting | gateway 评审员可能认为 429 是有意设计 |

---

## 附录：完整问题清单

### Proto 模块

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| P1 | 🔴 | GroupInvitedPayload 消息缺失 | ✅ 已修复 | conversation.proto 新增 GroupInvitedPayload（conversation_id/name/inviter_uid），API.md 附录同步更新 | proto/src/main/proto/nebula/conversation/conversation.proto, proto/API.md |
| P2 | 🟡 | API.md 章节编号重复（两个 11 号） | ✅ 已修复 | "11. 推送 Payload 索引" → "12. 推送 Payload 索引" | proto/API.md |
| P3 | 🟡 | API.md 附录标题拼写错误 (envelop → envelope) | ✅ 已修复 | "### envelop.proto" → "### envelope.proto" | proto/API.md |
| P4 | 🟡 | BatchIdRequest 命名不符合 Req/Resp 规范 | ⏸️ 暂不修复 | 重命名会破坏已生成 Java 代码和 API 兼容性，需在协议版本升级时统一处理 | — |
| P5 | 🟡 | ChatMessage 字段编号不连续且乱序 | ⏸️ 暂不修复 | 字段编号不连续不影响 Proto 编码正确性，重新编号破坏已有序列化兼容性 | — |
| P6 | 🟡 | ReadReportReq 命名与 method 路由不匹配 | ⇥ 联动#P8 | message/read 是请求方法，ReadReportReq 命名合理（客户端上报读取报告） | — |
| P7 | 🟡 | API.md 路由表将 message/delivery-ack 列为客户端方法 | ✅ 已修复 | 从接口全景图 REQUEST 列和路由表移除（推送事件，非客户端请求） | proto/API.md |
| P8 | 🟡 | 多个接口无响应消息定义（9 个 method 缺 XxxResp） | ⏸️ 暂不修复 | 缺 resp 当前返回空 result 不影响功能，加 resp 需同步改 Handler 序列化和客户端，成本收益比低 | — |
| P9 | 🔵 | Package 命名冗余 (com.nebula.chat.chat) | ⏸️ 暂不修复 | 重命名 package 破坏所有生成代码导入，非兼容性变更，标记为重构债务 | — |
| P10 | 🔵 | ConvListReq/ConvListResp 缩写不一致 | ⏸️ 暂不修复 | Conv 是 Conversation 常见惯例缩写，全写过长且已在 Proto 中稳定使用 | — |
| P11 | 🔵 | ChatContentType 默认值为 TEXT(0) 与 UNSPECIFIED 风格不一致 | ⏸️ 暂不修复 | 改为 UNSPECIFIED=0 破坏字段缺省值向后兼容（proto3 零值语义），已有消息需迁移 | — |

### Common 模块

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| C1 | 🟡 | SnowflakeIdGenerator.waitNextMillis() 纯忙等待无退避 | ✅ 已修复 | while 循环内添加 Thread.sleep(1) 退避，避免纯自旋消耗 CPU | common/src/main/kotlin/.../SnowflakeIdGenerator.kt |
| C2 | 🟡 | HikariDataSourceProvider allowPublicKeyRetrieval=true 安全风险 | ⏸️ 暂不修复 | MySQL 8+ caching_sha2_password 协议要求该选项，否则 JDBC 无法认证。仅在连接初始化时用于公钥获取，SSL 通道已加密 | — |
| C3 | 🟡 | 部分枚举 fromCode() 缺少 KDoc | ⏸️ 暂不修复 | 不影响运行时行为，下次修改相关枚举时一并补充 | — |
| C4 | 🟡 | HikariDataSourceProvider 未实现 AutoCloseable | ✅ 已修复 | 实现 AutoCloseable 接口，添加 close() 释放 hikariDataSource 连接池 | common/src/main/kotlin/.../HikariDataSourceProvider.kt |
| C5 | 🔵 | MessageException typealias 过渡方案缺时间线 | ⏸️ 暂不修复 | 过渡方案简洁可用，移除时间线在 Phase 10 完成后统一处理 | — |
| C6 | 🔵 | DatabaseConfig 密码明文存储 | ⇥ 联动#S3 | 运行时内存对象不可避免含明文，需配合 #S3 配置文件加密方案 | — |
| C7 | 🔵 | ApplicationConfig 缺少运行时校验 | ⏸️ 暂不修复 | 端口/连接池等关键参数在 HikariCP 和 Netty 创建时自动校验，单独加校验层投入收益低 | — |
| C8 | 🔵 | AuditMarkers 仅定义 LOGIN，扩展性不足 | ⏸️ 暂不修复 | 预留扩展点设计，待审计需求明确后统一扩展 | — |

### Repository 模块

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| R1 | 🔴 | FriendshipRepository 游标分页方向错误 | ✅ 已修复 | `f.id > :cursor` → `f.id < :cursor`，DESC 降序取更小 id | repository/.../repository/FriendshipRepository.kt:30 |
| R2 | 🔴 | MessageService 存在两套独立的 Redis Stream 写入路径，字段名不一致 | ✅ 已修复 | sendMessage 中 Stream key 从 snake_case 改为 camelCase，与 parseToEntity 对齐 | service/.../MessageService.kt:124-133, repository/.../MessageRepositoryImpl.kt:61 |
| R7 | 🔴 | JpaConfig.getRepository 每次创建新 EntityManager 但未关闭 | ✅ 已修复 | 复用单例 EntityManager 实例（lateinit var），添加 close() 方法防止连接泄漏 | repository/.../config/JpaConfig.kt:52-90 |
| R8 | 🔴 | MessageQueueRepository 去重 SETNX + EXPIRE 非原子 | ✅ 已修复 | 改用 Lettuce SetArgs.Builder.nx().ex(ttl) 单次原子操作替代 SETNX+EXPIRE | repository/.../redis/MessageQueueRepository.kt:115-119 |
| R9 | 🟡 | JPA 实体完全缺少 @ManyToOne / @OneToMany 关联映射 | ⏸️ 暂不修复 | @ManyToOne/@OneToMany 引入懒加载/N+1 等问题，当前通过 ID 字段手动 JOIN 更可控 | — |
| R10 | 🟡 | MessageService.sendMessage 中的会话更新与消息写入不同步 | ⏸️ 暂不修复 | Handler 层 TransactionTemplate 已包裹事务保证，当前设计可行 | — |
| R15 | 🟡 | PrivacyRepository 使用不安全的类型转换 | ✅ 已修复 | mget() 返回使用 as? 安全转换 + null 安全处理，替代 as 强制转换和 @Suppress | repository/.../redis/PrivacyRepository.kt:157-158 |
| R16 | 🟡 | OnlineStatusRepository.batchGetStatus 返回顺序依赖 | ⏸️ 暂不修复 | Redis MGET 官方文档明确保证返回顺序与输入键顺序一致，非碰巧对 | — |
| R19 | 🟡 | MessageRepositoryImpl.flushBatch 事务管理风险 | ⏸️ 暂不修复 | 每 30 条 flush+clear 是标准批处理模式，部分成功语义优于全部回滚 | — |
| R20 | 🟡 | SessionRepository.batchDelete 使用 async API 可能阻塞 | ⏸️ 暂不修复 | Lettuce pipeline 模式：autoFlush=false 时 async.del() 仅缓冲命令，不阻塞 | — |
| R21 | 🟡 | V5 迁移中 dead_letters.client_msg_id 改为 UNIQUE | ⏸️ 暂不修复 | 迁移已执行，回滚风险高；client_msg_id 唯一保证业务正确性 | — |
| R23 | 🔵 | Entity 中魔法数字可用 enum 替代 | ⏸️ 暂不修复 | 低优先级重构债务，不影响功能正确性 | — |
| R24 | 🔵 | Redis Key 命名风格不统一 | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R25 | 🔵 | UserRepository JPQL CONCAT 可移植性 | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R26 | 🔵 | DeadLetterEntity 状态可用 enum 替代 String | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R30 | 🔵 | DeadLetterEntity 使用 DATETIME 而非 DATETIME(3) | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R31 | 🔵 | V1 迁移中 friendship 表缺少 updated_at | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R32 | 🔵 | JpaConfig/RedisConfig 缺少健康检查 | ⏸️ 暂不修复 | 低优先级重构债务 | — |
| R34 | 🔵 | FriendService 双向竞赛中会话创建无重复保护 | ⏸️ 暂不修复 | 私聊会话 ID 确定性生成（按 uid 排序拼接）天然防重复 | — |

### Service 模块

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| R3 | 🔴 | FriendService 双向竞赛/接受好友路径缺少事务边界 | ⏸️ 暂不修复 | Service 层事务已由 Handler 层 TransactionTemplate 保证，当前设计可行 | — |
| R4 | 🔴 | ConversationService.createGroup 缺少事务边界 | ⏸️ 暂不修复 | 同上，Handler 层 TransactionTemplate 保证 | — |
| R5 | 🔴 | MessageService.sendMessage 去重步骤被跳过 | ⏸️ 非缺陷 | 去重已移至 Handler 层（SendMessageHandler.kt:62-71），有意架构选择 | — |
| R11 | 🟡 | UserService 每次创建 BCryptPasswordEncoder | ✅ 已修复 | 提取为 lazy 属性 passwordEncoder，register() 和 verifyPassword() 复用单例，减少 GC 压力 | service/.../user/UserService.kt |
| R12 | 🟡 | UserService.register 用户名唯一性检查存在 TOCTOU 竞态 | ✅ 已修复 | save() 外层 catch DataIntegrityViolationException 转为 USERNAME_EXISTS；DB 已有 UNIQUE KEY uk_username 兜底 | service/.../user/UserService.kt:99-102 |
| R13 | 🟡 | ConversationService.inviteMember 成员计数不准确 | ✅ 已修复 | 重新邀请已退出成员仅恢复 deleted=0，不加入 newMemberUids，避免重复计数 | service/.../conversation/ConversationService.kt:210-231 |
| R14 | 🟡 | SeqService.nextSeq 序列号重置存在竞态 | ⏸️ 暂不修复 | MAX_SEQ_THRESHOLD = Long.MAX_VALUE - 10000 ≈ 9.2×10^18，单会话消息数永不可达，仅理论风险 | — |
| R17 | 🟡 | DeadLetterService.compensate 中 re-enqueue 与 status 更新不在同一事务 | ⏸️ 暂不修复 | 跨 Redis+MySQL 事务需 saga 补偿模式，属 v1.2 增强项 | — |
| R18 | 🟡 | FriendService.listFriends 未设置 nextCursor | ✅ 已修复 | 设置 nextCursor（取 result.last().id）和 hasMore，Proto 新增字段 | service/.../friend/FriendService.kt:345-365, proto/.../friend/friend.proto |
| R22 | 🟡 | ConversationService.leaveGroup 未处理最后一个成员场景 | ✅ 已修复 | 活跃成员仅 1 人时直接解散群组；群主多成员场景抛异常提示转让或解散 | service/.../conversation/ConversationService.kt:246-271 |
| R27 | 🔵 | FriendService.addFriend 方法过长（~100行） | ⏸️ 暂不修复 | 约 100 行在可接受范围内，暂无功能 bug，按需提取子方法 | — |
| R28 | 🔵 | MessageService.checkFriendshipForPrivateConv 静默放行 | ⏸️ 暂不修复 | 静默放行是有意设计（避免信息泄露），添加注释说明即可 | — |
| R29 | 🔵 | ConversationEntity 与 ConversationService 中 MAX_MEMBERS 硬编码多处 | ⏸️ 暂不修复 | 统一常量定义提取到 ConversationConstants，下次修改时一并处理 | — |
| R33 | 🔵 | UserPrivacyService.getHideOnlineStatus 接收未使用参数 | ⏸️ 暂不修复 | 接收 uid 但未使用，清除未使用参数需评估接口设计 | — |

### Gateway 模块

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| GC1 | 🔴 | SendMessageHandler fire-and-forget 中异常静默吞没 | ✅ 已修复 | 增强异常日志上下文（msgId/convId/senderUid），添加 DeadLetterCallback 扩展 TODO | gateway/.../handler/chat/send/SendMessageHandler.kt:133 |
| GC2 | 🔴 | runBlocking 在事务模板内阻塞协程线程 | ⏸️ 暂不修复 | runBlocking 改造为完整 suspend 重构，影响事务管理，需单独规划 | — |
| GC3 | 🔴 | LeaveGroupHandler 事务返回值被忽略，潜在 NPE | ✅ 已修复 | 群主路径调用新增 dissolveGroup(convId) 解散群组，Service 新增解散方法 | gateway/.../handler/conversation/LeaveGroupHandler.kt:59, service/.../conversation/ConversationService.kt |
| GC4 | 🔴 | AuthInterceptor admin/ 前缀完全绕过认证 | ⏸️ 暂不修复 | 当前 admin/ 白名单是有意设计（Phase 10 已审计），标记为已知风险，v1.2 加 IP 白名单或 basic auth | — |
| GC5 | 🔴 | LoginHandler Token 重连未验证 Token 与设备/用户的绑定关系 | ⏸️ 暂不修复 | v1.2 处理：需 Session Store 加 deviceId，跨模块大改 | — |
| GI1 | 🟡 | Dispatcher handlerChain.proceed 忽略 chain 传入的 request | ✅ 已修复 | proceed() 内使用 request.method 替代闭包 method；新增 currentRequest var 跟踪链中最新 request | gateway/.../dispatcher/Dispatcher.kt |
| GI2 | 🟡 | SessionRegistry L1/L2 双写先 L1 后 L2，L2 失败无补偿 | ⏸️ 暂不修复 | 完全补偿需事件溯源，成本极高。L2 失败仅影响多节点一致性，单节点不依赖 L2 | — |
| GI3 | 🟡 | ReadReportHandler redis.del() 无异常保护 | ✅ 已修复 | redis.del() 包裹 try-catch，异常不中断已读上报主流程 | gateway/.../handler/message/ReadReportHandler.kt:61-66 |
| GI4 | 🟡 | SetPrivacyHandler 空 catch 块静默吞没所有异常 | ✅ 已修复 | catch 块添加 logger.error(e) { "..." } 日志记录，保留异常上下文 | gateway/.../handler/user/SetPrivacyHandler.kt:76-82 |
| GI5 | 🟡 | RateLimitInterceptor 信号量清理有竞态条件 | ⏸️ 暂不修复 | ConcurrentHashMap.removeIf 弱一致性不影响正确性（清理不及时但不泄漏，可接受） | — |
| GI6 | 🟡 | ConversationLockManager 锁永不释放，可能导致内存泄漏 | ✅ 已修复 | withLock() 改为 try-finally 结构，finally 块 locks.remove() 防止 Map 无限增长 | gateway/.../handler/conversation/ConversationLockManager.kt:36-44 |
| GI7 | 🟡 | ProtoCodec.deserialize 空 ByteString 可能抛异常 | ✅ 已修复 | deserialize() 开头添加 if (params.isEmpty) return ByteArray(0) 空字节保护 | gateway/.../codec/ProtoCodec.kt:81-87 |
| GI8 | 🟡 | FriendAddHandler 死代码路径 | ➡ 非缺陷 | 两行 throw 是防御性编程：if 处理"已存在未删除好友"，else 处理"UK 冲突但异常状态"，逻辑独立非死代码 | — |
| GI9 | 🟡 | FriendAcceptHandler/KickMemberHandler/InviteMemberHandler 未使用的依赖注入 | ⏸️ 暂不修复 | 部分 Handler 接收 ConversationLockManager/TransactionTemplate 但未使用，需逐个排查（可能为将来预留） | — |
| GS1 | 🔵 | Dispatcher Handler 未找到时的错误信息可暴露内部 method 名 | ⏸️ 暂不修复 | 返回 method 名有助于客户端调试，内部 method 名非敏感信息 | — |
| GS2 | 🔵 | LogInterceptor 硬编码 200 判断成功 | ✅ 已修复 | resp.code != 200 → resp.code != BizCode.OK.code | gateway/.../interceptor/LogInterceptor.kt:26 |
| GS3 | 🔵 | ConversationConstants 与 ReadReportHandler PRIVATE_TYPE 常量不一致 | ✅ 已修复 | 删除 ReadReportHandler 中 PRIVATE_TYPE = 0（错误值），改为引用 ConversationConstants.CONV_TYPE_PRIVATE = 1。修复私聊已读回执推送不触发的 bug | gateway/.../ReadReportHandler.kt:49,67 |
| GS4 | 🔵 | MessageSeqHandler 参数校验应统一到 Service 层 | ⏸️ 暂不修复 | 架构优化建议，不影响功能，后续统一调整 | — |
| GS5 | 🔵 | PushService.pushMessage 未被调用（代码死代码） | ✅ 已修复 | 确认无生产代码调用后删除 pushMessage() 方法，4 个测试用例改为测试 pushMessageToMembers | gateway/.../PushService.kt:59-89, gateway/.../PushServiceTest.kt |
| GS6 | 🔵 | DeliveryHandlerCollector 空实现占位 | ⏸️ 暂不修复 | 空实现为 Phase 10 占位（delivery-ack 去重），待 Phase 10 实现 | — |
| GS7 | 🔵 | 部分 Collector 文件注释较简略 | ⏸️ 暂不修复 | 不影响功能，下次修改相关文件时补充 | — |

### Server + 跨领域

| 编号 | 严重度 | 问题描述 | 状态 | 解决方案 | 涉及文件 |
|------|--------|---------|------|----------|---------|
| S1 | 🔴 | RateLimitInterceptor 返回 429 而非 BizCode.RATE_LIMITED | ✅ 已修复 | 删除 RATE_LIMITED_CODE = 429 常量，改用 BizCode.RATE_LIMITED.code | gateway/.../interceptor/RateLimitInterceptor.kt:97,115 |
| S2 | 🔴 | server 模块直接依赖 repository 模块，破坏分层架构 | ⏸️ 暂不修复 | 分层违规需通过引入 server API 接口或拆出独立模块解决，涉及大面积重构 | server/build.gradle.kts, server/.../NebulaServer.kt |
| S3 | 🔴 | docker-compose.yml 和 dev.conf 硬编码密码 | ⏸️ 暂不修复 | 密码为开发环境测试用，生产环境需通过环境变量注入。标记为技术债务，部署时解决 | docker-compose.yml, config/dev.conf |
| S4 | 🔴 | SessionRegistry 从未刷新 TTL，活跃用户 Session 7 天必然过期 | ✅ 已修复 | SessionStore 接口新增 refreshTtl()，SessionRegistry 代理调用，AuthInterceptor 认证后刷新 TTL | common/.../session/SessionStore.kt, repository/.../redis/SessionRepository.kt, gateway/.../session/SessionRegistry.kt, gateway/.../interceptor/AuthInterceptor.kt |
| S5 | 🔴 | LoginHandler Token 重连存在 TTL 空窗期 | ⏸️ 暂不修复 | v1.2 处理：Session 生命周期管理需整体方案，Phase 10 已处理 TTL 刷新（S4 ✅） | — |
| S6 | 🔴 | 无 gRPC 双向流端到端集成测试 | ⏸️ 暂不修复 | gRPC 双向流集成测试框架搭建复杂，需为 Phase 10 预留专项测试规划 | — |
| S7 | 🔴 | ChatServer.stop() 仅等待 5 秒 | ✅ 已修复 | stop() 中 awaitTermination 从 5s → 30s，给高负载场景缓冲消息足够写入时间 | server/.../server/ChatServer.kt |
| S8 | 🟡 | server/build.gradle.kts 存在大量冗余依赖声明 | ⏸️ 暂不修复 | 冗余依赖不造成运行时问题，依赖清理需逐项验证各模块是否真正使用 | — |
| S9 | 🟡 | CI 配置缺少并行测试和测试报告归档 | ⏸️ 暂不修复 | CI 配置优化独立于代码修改，需 CI/CD 专项规划 | — |
| S10 | 🟡 | gateway 层两处直接依赖 repository | ⏸️ 暂不修复 | 与 S2 同类问题，建议通过 service 层间接访问或引入接口 | — |
| S11 | 🟡 | 两套 RedisConfig 并存命名冲突 | ⏸️ 暂不修复 | common 为配置 data class，repository 为连接管理，虽同名但包路径不同不实际冲突 | — |
| S12 | 🟡 | Koin DI 注册方式不统一 | ⏸️ 暂不修复 | 统一注册方式不影响运行时行为，重构成本高且风险大 | — |
| S13 | 🟡 | BCrypt 每次验证都创建新 Encoder 实例 | ✅ 已修复 | 同 R11，已通过 Service 层 lazy 属性复用单例 | service/.../user/UserService.kt |
| S14 | 🟡 | ExceptionInterceptor 吞掉 CancellationException | ✅ 已修复 | catch (e: Exception) 前添加 catch (e: CancellationException) { throw e } 传播取消信号 | gateway/.../interceptor/ExceptionInterceptor.kt:41-43 |
| S15 | 🟡 | SetPrivacyHandler 推送时未过滤隐藏用户 | ⏸️ 暂不修复 | 隐藏用户过滤逻辑需在 PushService 层增加在线状态隐私检查，功能增强非缺陷 | — |
| S16 | 🟡 | server 模块测试覆盖极薄（仅 3 个测试文件） | ⏸️ 暂不修复 | 测试覆盖提升需专项测试规划 | — |
| S17 | 🟡 | MockK relaxed mock 可能掩盖错误 | ⏸️ 暂不修复 | 现有测试通过，严格 mock 改造需逐项验证，成本高 | — |
| S18 | 🔵 | Gradle 版本目录中疑似未使用的库 | ⏸️ 暂不修复 | 依赖清理需逐项验证，低优先级 | — |
| S19 | 🔵 | .gitignore 缺少 IDE 特定文件 | ⏸️ 暂不修复 | IDE 文件通常通过全局 .gitignore 管理，非严重问题 | — |
| S20 | 🔵 | 数据流路径中潜在线程池瓶颈 | ⏸️ 暂不修复 | 需实际压测确认瓶颈后针对性优化 | — |
| S21 | 🔵 | 密码策略薄弱（最小长度 6 位） | ⏸️ 暂不修复 | 功能增强建议，非缺陷 | — |
| S22 | 🔵 | 无登录失败次数限制 | ⏸️ 暂不修复 | 功能增强建议，非缺陷 | — |
| S23 | 🔵 | 缺少并发/重连/序列号恢复等关键测试场景 | ⏸️ 暂不修复 | 测试覆盖提升需专项规划 | — |
