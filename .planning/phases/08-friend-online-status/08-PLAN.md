---
phase: 8
plan: 8-1
type: implementation
wave: 1
depends_on: []
files_modified: ["proto/src/main/proto/nebula/friend/friend.proto", "proto/src/main/proto/nebula/message_type.proto"]
autonomous: true
---
# Plan 8-1: Proto 扩展 + Flyway V3 + Entity 更新

## 目标
扩展 friend.proto（推送 Payload）、message_type.proto（STATUS_CHANGED 枚举）、Flyway V3 迁移（friend_requests.message 列）、FriendRequestEntity 新增 message 字段。这是所有后续计划的基石。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | proto/src/main/proto/nebula/friend/friend.proto | 1) 追加 FriendRequestPayload、FriendAcceptedPayload、StatusChangedPayload 三个推送 Payload 消息定义；2) 修改 FriendListReq（当前为空消息 `message FriendListReq {}`），新增 `int64 cursor = 1;` 游标字段和 `int32 limit = 2;` 分页字段（D-46） | `./gradlew :proto:generateProto` 成功生成 Java/Kotlin 类 | 生成的类包含 FriendRequestPayload.getRequestId()、FriendAcceptedPayload.getConversationId()、StatusChangedPayload.getUid()、FriendListReq.getCursor()、FriendListReq.getLimit() |
| 2 | modify | proto/src/main/proto/nebula/message_type.proto | 新增 `STATUS_CHANGED = 14;` 枚举值（注释 `payload = StatusChangedPayload`） | `./gradlew :proto:generateProto` 成功 | PushEventType.STATUS_CHANGED 枚举值可引用，值为 14 |
| 3 | create | repository/src/main/resources/db/migration/V3__add_friend_request_message.sql | 新增 Flyway 迁移 SQL：`ALTER TABLE friend_requests ADD COLUMN message VARCHAR(255) NOT NULL DEFAULT '' COMMENT '好友申请附言，D-42'` | `./gradlew :repository:flywayMigrate` 成功 | friend_requests 表包含 message 列，默认值为空字符串 |
| 4 | modify | repository/src/main/kotlin/com/nebula/repository/entity/FriendRequestEntity.kt | 新增 `var message: String = ""` 字段，添加 `@Column(length = 255)` 注解和 `/** 好友申请附言，D-42 */` KDoc 注释 | `./gradlew :repository:compileKotlin` 通过 | FriendRequestEntity 包含 message 属性，可读写 |

## 依赖
- 无

## 产出物
- Proto: `friend.proto`（新增 3 个 Payload + FriendListReq 扩展）
- Proto: `message_type.proto`（新增 STATUS_CHANGED）
- Flyway: `V3__add_friend_request_message.sql`
- Entity: `FriendRequestEntity.kt`（新增 message 字段）

## 验证
1. Proto 编译：`./gradlew :proto:generateProto`
2. Flyway 迁移：`./gradlew :repository:flywayMigrate`
3. Kotlin 编译：`./gradlew :repository:compileKotlin`

## 风险
- Proto 生成后需确认 FriendRequestPayload 等类在 `com.nebula.chat.friend` 包下，与现有 FriendAddReq 一致
- Flyway V3 迁移在已有数据上执行，`NOT NULL DEFAULT ''` 确保兼容

## PLANNING COMPLETE

---
phase: 8
plan: 8-2
type: implementation
wave: 1
depends_on: []
files_modified: []
autonomous: true
---
# Plan 8-2: Repository 层扩展

## 目标
扩展 FriendshipRepository（游标分页查询 + 按 userId/friendId 精确查找）、FriendRequestRepository（状态查询方法）、OnlineStatusRepository（三值状态 JSON 存储 + refreshTtl + batchGetStatus）。Plan 8-2 与 8-1 可并行执行。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | verify | repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt | 确认 `setOnline(userId, statusData)` 旧签名无其他调用方依赖。Grep 搜索全项目 `\.setOnline\(` 调用点，确认无传入 statusData 参数的调用（当前代码仅有 `isOnline()` 调用方） | `grep -rn '\.setOnline(' --include='*.kt'` 无结果 | 确认旧签名可安全移除，Phase 8 是首个主动调用 `setOnline()` 的阶段 |
| 2 | modify | repository/src/main/kotlin/com/nebula/repository/repository/FriendshipRepository.kt | 新增 `findFriendsByUserId(userId, cursor, pageable)` JPQL 游标分页查询（OR 条件：userId=? OR friendId=? AND deleted=0）；新增 `findByUserIdAndFriendId(userId, friendId)` 方法（如未自动生成） | `./gradlew :repository:compileKotlin` 通过 | 游标分页按 id DESC 排序，每页固定大小 |
| 3 | modify | repository/src/main/kotlin/com/nebula/repository/repository/FriendRequestRepository.kt | 新增 `findByFromUidAndToUidAndStatus(fromUid, toUid, status)` 方法（用于 D-51 重复申请检查、D-52 双向竞赛检测）；新增 `findByToUidAndStatusOrderByCreatedAtDesc(toUid, status)` 方法（用于 D-41 待处理申请列表） | `./gradlew :repository:compileKotlin` 通过 | 按状态过滤 + 按创建时间降序 |
| 4 | modify | repository/src/main/kotlin/com/nebula/repository/redis/OnlineStatusRepository.kt | 1) 新增 `OnlineStatusData` @Serializable data class（status: Int, lastActiveAt: Long）；2) `setOnline(userId)` 改为无参，写入 JSON `{"status":1, "lastActiveAt": now}`；3) 新增 `setHidden(userId)` 写入 `{"status":2, ...}`；4) 新增 `getStatus(userId): OnlineStatusData?` 解析 JSON 返回；5) 新增 `refreshTtl(userId)` 调用 EXPIRE 刷新 60s TTL；6) 新增 `batchGetStatus(userIds): Map<Long, OnlineStatusData?>` 使用 MGET 批量查询；7) 保留 `isOnline(userId)` 兼容（改为检查 getStatus != null） | `./gradlew :repository:compileKotlin` 通过 | `setOnline(userId)` 无参调用成功写入 JSON；`refreshTtl` 刷新 TTL；`batchGetStatus` 一次 MGET 返回所有结果 |
| 5 | create | repository/src/test/kotlin/com/nebula/repository/redis/OnlineStatusRepositoryTest.kt | 编写 OnlineStatusRepository 三值状态单元测试：setOnline/getStatus 往返、setHidden/getStatus 验证 status=2、refreshTtl 验证、batchGetStatus 验证 | `./gradlew :repository:test --tests "OnlineStatusRepositoryTest"` 通过 | 覆盖 4 个场景，全部通过 |

## 依赖
- 无（与 Plan 8-1 可并行，但实际编译依赖 Proto 生成产物；Repository 方法签名不依赖 Payload 类型）

## 产出物
- `FriendshipRepository.kt`（新增 1 个查询方法）
- `FriendRequestRepository.kt`（新增 2 个查询方法）
- `OnlineStatusRepository.kt`（扩展为三值状态 JSON 存储 + refreshTtl + batchGetStatus）
- `OnlineStatusRepositoryTest.kt`（单元测试）

## 验证
1. Kotlin 编译：`./gradlew :repository:compileKotlin`
2. 单元测试：`./gradlew :repository:test --tests "OnlineStatusRepositoryTest"`

## 风险
- `OnlineStatusRepository.setOnline(userId, statusData)` 现有签名有 `statusData: String` 参数，修改为无参需确认所有调用点。当前代码中无直接调用 `setOnline()` 的 Handler（仅 BatchGetStatusHandler 调用 `isOnline()`），Phase 8 是首个主动调用方，可安全修改签名
- `FriendshipRepository.findByUserIdAndFriendId()` 当前已存在，需要确认该方法签名是否支持排序后的 userId/friendId 查找（当前表结构 userId 是 smaller，friendId 是 larger）

## PLANNING COMPLETE

---
phase: 8
plan: 8-3
type: implementation
wave: 2
depends_on: [8-1, 8-2]
files_modified: []
autonomous: false
---
# Plan 8-3: 好友 Handler 实现（6 个 Handler）

## 目标
实现 6 个好友业务 Handler：friend/add（含双向竞赛检测）、friend/accept（含单事务创建私聊会话）、friend/reject、friend/delete、friend/list（游标分页）、friend/requests。按复杂度分 3 组串行实现。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendRejectHandler.kt | 实现 friend/reject Handler：加载 FriendRequestEntity → 校验 status=pending + toUid=session.userId → 更新 status=2(rejected) → 返回 Response(OK)。参考 SetPrivacyHandler 简单更新模式 | `./gradlew :gateway:compileKotlin` 通过 | 拒绝后 friend_requests.status=2 |
| 2 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendRequestsHandler.kt | 实现 friend/requests Handler：查询 `findByToUidAndStatus(session.userId, 0)` → 批量获取申请人信息 → 构造 FriendRequestsResp（含 requestId/fromUid/fromUsername/fromAvatar/message/status/createdAt） | `./gradlew :gateway:compileKotlin` 通过 | 返回所有 status=0 的待处理申请 |
| 3 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendListHandler.kt | 实现 friend/list Handler：游标分页查询好友记录 → 提取 friendUid → 批量查询 User 信息 → 批量查询在线状态（排除隐藏用户）→ 构造 FriendListResp（含 uid/username/displayName/avatarUrl/status/createdAt 全部 6 个字段）。参考 ListConversationsHandler 分页模式 | `./gradlew :gateway:compileKotlin` 通过 | 返回好友列表含完整 6 个字段，在线状态为三值（0/1/2） |
| 4 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendDeleteHandler.kt | 实现 friend/delete Handler：排序 uid 后查找好友记录 → 校验 deleted=0 → 软删除 `friendship.deleted=1` → 返回 Response(OK)。参考 KickMemberHandler 软删除模式 | `./gradlew :gateway:compileKotlin` 通过 | 删除后 friendships.deleted=1，会话保留 |
| 5 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendAddHandler.kt | 实现 friend/add Handler（最复杂）：校验 A≠B（D-54）→ 检查已有好友（D-51）→ 双向竞赛检测（D-52，有竞赛→单事务自动好友+创建私聊会话+推送 FRIEND_ACCEPTED）→ 检查重复申请（D-51）→ 创建 FriendRequestEntity → 推送 FRIEND_REQUEST。参考 CreateGroupHandler 的锁+事务+推送模式 | `./gradlew :gateway:compileKotlin` 通过 | 双向竞赛自动好友；重复申请抛 BizException；正常创建推送 FRIEND_REQUEST |
| 6 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/friend/FriendAcceptHandler.kt | 实现 friend/accept Handler（最核心事务）：加载 FriendRequestEntity → 校验 status=pending + toUid=session.userId → 防御性检查是否已是好友 → TransactionTemplate 单事务（更新 request.status=1 → 创建/恢复 FriendshipEntity D-45 → 创建私聊会话 type=1 id=`private:smaller:larger` D-43 → 创建 2 个 ConversationMemberEntity）→ 事务后推送 FRIEND_ACCEPTED 给双方。参考 CreateGroupHandler 的 lockManager.withLock + transactionTemplate 模式 | `./gradlew :gateway:compileKotlin` 通过 | 事务原子创建好友+私聊会话+2成员；重加恢复已删除记录（D-45） |
| 7 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationConstants.kt | 新建共享常量类：定义 `const val CONV_TYPE_PRIVATE = 1`（D-43 私聊）和 `const val CONV_TYPE_GROUP = 2`（Phase 7 群聊常量迁移至此）。FriendAcceptHandler 和 FriendCheckStep 均通过此常量类引用会话类型 | `./gradlew :gateway:compileKotlin` 通过 | 两个常量可被 FriendAcceptHandler 和 FriendCheckStep 引用 |

## 依赖
- Plan 8-1（Proto 扩展：FriendRequestPayload、FriendAcceptedPayload、FriendListReq cursor/limit）
- Plan 8-2（Repository 扩展：FriendshipRepository 分页查询、FriendRequestRepository 状态查询、OnlineStatusRepository 三值状态）

## 产出物
- 6 个 Handler 源文件（`gateway/.../handler/friend/` 目录）
- `gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ConversationConstants.kt`：新建共享常量类，定义 `CONV_TYPE_PRIVATE = 1`（D-43）和 `CONV_TYPE_GROUP = 2`（Phase 7 常量迁移至此）。Plan 8-5 FriendCheckStep 和 Plan 8-3 FriendAcceptHandler 均通过此常量类引用，避免跨文件 private 常量不可达

## 验证
1. 编译：`./gradlew :gateway:compileKotlin`
2. 单元测试：每个 Handler 编写对应测试（可在 Plan 8-6 中统一完成或在此计划中同步完成）

## 风险
- FriendAcceptHandler 的事务复杂度最高（5 个原子操作），需确保 `ConversationLockManager.withLock(privateConvId)` 保护并发创建
- 双向竞赛自动好友逻辑与 FriendAcceptHandler 的创建会话逻辑有大量重复，可提取私有方法复用或接受适度重复
- 私聊会话 ID 格式 `private:smaller:larger` 确保双方一致

## PLANNING COMPLETE

---
phase: 8
plan: 8-4
type: implementation
wave: 3
depends_on: [8-3]
files_modified: []
autonomous: false
---
# Plan 8-4: 在线状态生命周期集成 + 状态推送

## 目标
将 OnlineStatusRepository 集成到 ChatService 生命周期（登录→setOnline、PING→refreshTtl、断连→伪在线 60s），实现状态变更推送给所有在线好友（D-50, D-57, D-58, D-59）。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt | 1) 构造函数新增 4 个依赖：`onlineStatusRepository`、`friendshipRepository`、`pushService`、`privacyRepository`；2) `handleLoginSuccess()` 末尾调用 `onlineStatusRepository.setOnline(userId)` + `pushStatusChangeToFriends(userId)`；3) `handlePing()` 中调用 `onlineStatusRepository.refreshTtl(userId)`；4) `cleanupConnection()` 中检查 UserStreamRegistry 无剩余设备后启动 60s 延迟离线任务（`delayedOfflineJob = scope.launch { delay(60_000); ... }`），任务执行前检查 `userStreamRegistry.getStreams(uid).isEmpty()`；5) 新增 `pushStatusChangeToFriends(userId)` 私有方法：查询好友列表 → 过滤隐藏用户 → 逐个 pushEventToUser(STATUS_CHANGED) | `./gradlew :gateway:compileKotlin` 通过 | 登录→setOnline+推送；PING→refreshTtl；断连→60s 后离线+推送 |
| 2 | modify | server/src/main/kotlin/com/nebula/server/NebulaServer.kt | ChatService 构造参数新增 `onlineStatusRepo`、`friendshipRepo`、`pushService`、`privacyRepo` 4 个依赖（从 Koin 获取） | `./gradlew :server:compileKotlin` 通过 | ChatService 成功构造，gRPC 服务正常启动 |
| 3 | modify | gateway/src/main/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandler.kt | 切换隐藏状态时同步更新 OnlineStatusRepository：`setHideOnlineStatus(true)` → 调用 `onlineStatusRepository.setHidden(userId)`；`setHideOnlineStatus(false)` → 调用 `onlineStatusRepository.setOnline(userId)`。同时触发 `pushStatusChangeToFriends` 广播（通过新增 PushService 依赖） | `./gradlew :gateway:compileKotlin` 通过 | 隐藏→Redis status=2；取消隐藏→Redis status=1；均触发好友推送 |
| 4 | modify | gateway/src/main/kotlin/com/nebula/gateway/handler/user/BatchGetStatusHandler.kt | 适配 OnlineStatusRepository 三值状态：`getStatus(uid)?.status` 替代 `isOnline(uid)`，返回 0=离线/1=在线/2=隐藏（隐藏用户已在隐私过滤阶段跳过，此处 status=2 为安全兜底） | `./gradlew :gateway:compileKotlin` 通过 | 返回三值状态（0/1/2）而非二值 |

## 依赖
- Plan 8-3（好友 Handler 完成后，FriendshipRepository 查询可用）
- Plan 8-2（OnlineStatusRepository 三值状态 API）

## 产出物
- 修改 `ChatService.kt`（新增在线状态生命周期管理 + 状态推送）
- 修改 `NebulaServer.kt`（ChatService 构造参数变更）
- 修改 `SetPrivacyHandler.kt`（隐藏切换同步 Redis 状态 + 推送）
- 修改 `BatchGetStatusHandler.kt`（适配三值状态）

## 验证
1. 编译：`./gradlew :gateway:compileKotlin :server:compileKotlin`
2. 集成验证：启动服务 → 用户 A 登录 → 好友 B 收到 STATUS_CHANGED（A 在线）→ 用户 A 断连 → 60s 后好友 B 收到 STATUS_CHANGED（A 离线）

> **注（D-60）**：上线时客户端拉取补偿为纯客户端行为，客户端登录后主动调用 `user/batchGetStatus` 拉取所有好友最新状态。服务端无额外实现任务。

## 风险
- **伪在线 60s 延迟任务泄漏**（风险 3）：`ChatStreamObserver` 需维护 `var delayedOfflineJob: Job?`，重连时取消旧任务。需在 `handleLoginSuccess` 中 `delayedOfflineJob?.cancel()`
- **ChatService 依赖膨胀**（风险 1）：构造函数参数从 4 个增至 8 个，保持方案 B（命名参数增强可读性）
- `pushStatusChangeToFriends` 需查询好友列表（JPA blocking 调用），注意使用 `withContext(Dispatchers.IO)` 包裹

## PLANNING COMPLETE

---
phase: 8
plan: 8-5
type: implementation
wave: 3
depends_on: [8-3]
files_modified: []
autonomous: false
---
# Plan 8-5: message/send 路径增强 — 私聊非好友禁止发送

## 目标
在 message/send 的 Step 链中增加私聊好友关系校验（D-56）：私聊会话 + 非好友 → 拒绝发送。从私聊会话 ID 解析对方 UID 避免额外 DB 查询。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | gateway/src/main/kotlin/com/nebula/gateway/handler/chat/send/FriendCheckStep.kt | 新增 FriendCheckStep 实现 SendMessageStep 接口：1) 通过 `conversationRepository.findById(req.conversationId)` 获取会话；2) 若 `conv.type != 1`（非私聊）直接返回 true；3) 从 convId 解析对方 UID（`private:smaller:larger` 格式）；4) 排序 uid 后查询 `friendshipRepository.findByUserIdAndFriendId(smaller, larger)`；5) 若不存在或 deleted=1 → 抛 SendMessageException(NOT_FRIEND)。参考 ValidateStep 模式 | `./gradlew :gateway:compileKotlin` 通过 | 非好友发送私聊消息时返回 NOT_FRIEND 错误 |
| 2 | modify | gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt | 在 SendMessageHandler 的 Step 链列表中，在 ValidateStep 之后、DedupStep 之前插入 `FriendCheckStep(get(), get())`（FriendshipRepository + ConversationRepository） | `./gradlew :gateway:compileKotlin` 通过 | Step 链顺序：ValidateStep → FriendCheckStep → DedupStep → WriteStep |
| 3 | modify | gateway/src/main/kotlin/com/nebula/gateway/handler/chat/send/SendMessageStep.kt | 更新接口 KDoc 注释说明 Step 链顺序变更为 ValidateStep → FriendCheckStep → DedupStep → WriteStep | `./gradlew :gateway:compileKotlin` 通过 | KDoc 注释准确描述 Step 链顺序 |

## 依赖
- Plan 8-3（好友 Handler 完成后，FriendshipRepository 的精确查找方法可用）
- Plan 8-1（CONV_TYPE_PRIVATE = 1 常量已定义）

## 产出物
- `FriendCheckStep.kt`（新增 SendMessageStep 实现）
- 修改 `GatewayModule.kt`（Step 链列表插入 FriendCheckStep）
- 修改 `SendMessageStep.kt`（KDoc 更新）

## 验证
1. 编译：`./gradlew :gateway:compileKotlin`
2. 单元测试：Mock 非好友场景 → 验证抛 SendMessageException(NOT_FRIEND)
3. 集成测试：好友间发送私聊消息成功；删除好友后发送失败

## 风险
- **会话 ID 解析依赖格式约定**（风险 5）：`private:smaller:larger` 格式需与 FriendAcceptHandler 创建的 ID 一致。建议提取 `buildPrivateConvId()` 和 `parsePrivateConvId()` 为共享工具方法
- 好友检查增加一次 JPA 查询，但由于使用唯一索引 `uk_friendship (user_id, friend_id)`，性能影响极小
- 仅私聊（type=1）执行检查，群聊不受影响

## PLANNING COMPLETE

---
phase: 8
plan: 8-6
type: implementation
wave: 4
depends_on: [8-1, 8-2, 8-3, 8-4, 8-5]
files_modified: []
autonomous: false
---
# Plan 8-6: DI 注册 + NebulaServer 集成 + 测试

## 目标
在 GatewayModule 中注册 6 个 Friend Handler 和 FriendCheckStep，在 NebulaServer 中注册 Handler 到 HandlerRegistry，编写关键 Handler 的单元测试。

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | modify | gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt | **Koin 注册**：在 `handlerModule` 中新增 6 个 `single {}` 声明：`FriendRejectHandler(get())`、`FriendRequestsHandler(get(), get())`、`FriendListHandler(get(), get(), get(), get())`、`FriendDeleteHandler(get())`、`FriendAddHandler(get(), get(), get(), get())`、`FriendAcceptHandler(get(), get(), get(), get(), get(), get(), get())`；**函数签名变更**：`registerHandlers()` 在 `kickMemberHandler` 参数之后追加 6 个新参数（friendRejectHandler, friendRequestsHandler, friendListHandler, friendDeleteHandler, friendAddHandler, friendAcceptHandler），参数总数从 21 增至 27；**函数体变更**：在最后一个 `registry.register(kickMemberHandler)` 之后新增 6 行 `registry.register(...)`；**FriendCheckStep 注册**：在 handlerModule 中新增 `single { FriendCheckStep(get(), get()) }` | `./gradlew :gateway:compileKotlin` 通过 | Koin 容器可解析所有 Friend Handler 实例 |
| 2 | modify | server/src/main/kotlin/com/nebula/server/NebulaServer.kt | 1) 新增 `import com.nebula.gateway.handler.friend.*`；2) 在 `kickMemberHandler` 之后新增 6 行 `val xxxHandler = GlobalContext.get().get<XxxHandler>()`；3) `registerHandlers()` 调用末尾追加 6 个 Handler 参数；4) **ChatService 构造参数变更**：`ChatService(dispatcher, sessionRegistry, registry, userStreamRegistry)` 改为 8 个参数，末尾追加 `onlineStatusRepository, friendshipRepository, pushService, privacyRepository`（均通过 `GlobalContext.get().get<>()` 获取）；5) `OnlineStatusRepository` 的 Koin 注册无需额外变更（Plan 8-2 直接修改类文件，Koin `externalModule` 中的 `single { OnlineStatusRepository(connection) }` 自动获取新版本） | `./gradlew :server:compileKotlin` 通过 | 启动后 friend/* 路由可正常分发，ChatService 构造成功 |
| 3 | create | gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendAddHandlerTest.kt | 编写 FriendAddHandler 单元测试：1) 正常申请 → 创建 FriendRequestEntity + 推送 FRIEND_REQUEST；2) 自我申请 → SELF_FRIEND 异常；3) 已是好友 → ALREADY_FRIEND 异常；4) 重复申请 → 异常（已有待处理申请）；5) 双向竞赛 → 自动好友 + 推送 FRIEND_ACCEPTED。Mock 所有 Repository + PushService | `./gradlew :gateway:test --tests "FriendAddHandlerTest"` 通过 | 5 个场景全部通过 |
| 4 | create | gateway/src/test/kotlin/com/nebula/gateway/handler/friend/FriendAcceptHandlerTest.kt | 编写 FriendAcceptHandler 单元测试：1) 正常接受 → 单事务创建好友+私聊会话+2成员+推送；2) 请求不存在 → REQUEST_NOT_FOUND；3) 请求已处理 → REQUEST_HANDLED；4) D-45 重加恢复 → 检测 deleted=1 记录并恢复。Mock TransactionTemplate + ConversationLockManager + Repository | `./gradlew :gateway:test --tests "FriendAcceptHandlerTest"` 通过 | 4 个场景全部通过 |
| 5 | create | gateway/src/test/kotlin/com/nebula/gateway/handler/chat/send/FriendCheckStepTest.kt | 编写 FriendCheckStep 单元测试：1) 群聊会话 → 跳过检查返回 true；2) 私聊+好友 → 通过检查返回 true；3) 私聊+非好友 → 抛 SendMessageException(NOT_FRIEND)；4) 私聊+已删除好友 → 抛 SendMessageException(NOT_FRIEND) | `./gradlew :gateway:test --tests "FriendCheckStepTest"` 通过 | 4 个场景全部通过 |

## 依赖
- Plan 8-1（Proto 扩展）
- Plan 8-2（Repository 扩展）
- Plan 8-3（好友 Handler 实现）
- Plan 8-4（在线状态生命周期集成）
- Plan 8-5（message/send 路径增强）

## 产出物
- 修改 `GatewayModule.kt`（DI 注册 6 个 Handler + FriendCheckStep）
- 修改 `NebulaServer.kt`（Handler 注册 + 构造参数变更）
- `FriendAddHandlerTest.kt`
- `FriendAcceptHandlerTest.kt`
- `FriendCheckStepTest.kt`

## 验证
1. 编译：`./gradlew :gateway:compileKotlin :server:compileKotlin`
2. 单元测试：`./gradlew :gateway:test --tests "FriendAddHandlerTest" --tests "FriendAcceptHandlerTest" --tests "FriendCheckStepTest"`
3. 全量回归：`./gradlew test`（确保已有测试不受影响）
4. 集成验证：启动服务 → friend/add → friend/accept → friend/list → message/send（私聊）→ friend/delete → message/send（私聊应拒绝）

## 风险
- GatewayModule 的 `registerHandlers()` 函数参数已达 27 个（原 21 + 6 新增），需考虑重构为数据类或 Builder 模式。本次接受参数膨胀，后续 Phase 统一重构
- ChatService 构造参数变更（Plan 8-4）需在 NebulaServer.kt 中同步适配，确保 `OnlineStatusRepository` 等通过 Koin 获取而非手动 new

## PLANNING COMPLETE

---

# 阶段 8 — Wave 分组总览

## Wave 1（无依赖，可并行）
- **Plan 8-1**: Proto 扩展 + Flyway V3 + Entity 更新（4 个任务）
- **Plan 8-2**: Repository 层扩展（5 个任务）

## Wave 2（依赖 Wave 1）
- **Plan 8-3**: 好友 Handler 实现 — 6 个 Handler + ConversationConstants（7 个任务）

## Wave 3（依赖 Wave 2，可并行）
- **Plan 8-4**: 在线状态生命周期集成 + 状态推送（4 个任务）
- **Plan 8-5**: message/send 路径增强（3 个任务）

## Wave 4（依赖 Wave 2 + Wave 3）
- **Plan 8-6**: DI 注册 + NebulaServer 集成 + 测试（5 个任务）

## 任务总数
28 个任务，分布在 6 个计划中。

## 整体成功标准
1. 所有 6 个 friend/* Handler 可正常处理请求
2. 好友添加/接受/拒绝/删除/列表/请求列表 6 个功能全部可用
3. 用户登录→在线；PING→刷新 TTL；断连→60s 后离线
4. 状态变更推送给所有在线好友（排除隐藏用户）
5. 私聊非好友发送消息被拒绝（NOT_FRIEND）
6. 所有单元测试通过，已有测试无回归
7. 编译无错误：`./gradlew compileKotlin` 全量通过

