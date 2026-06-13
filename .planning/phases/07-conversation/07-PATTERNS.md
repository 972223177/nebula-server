---
phase: 7
mapper: nx-pattern-mapper
---
# Phase 7 代码模式映射

## 一、已识别模式

### 1. Handler 模式 — Query + Resp（简单查询型）

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/user/GetProfileHandler.kt`

**关键约定**:
- 实现 `Handler<ReqT, RespT>` 接口
- `method` 覆盖为 `"模块/操作"` 格式（如 `"user/getProfile"`）
- 通过构造注入 Repository
- `handle()` 中通过 `currentCoroutineContext().requireSession()` 获取 Session
- DB 查询包裹在 `withContext(Dispatchers.IO) { }` 中
- 使用 `?.let { } ?: throw XxxException(BizCode.XXX)` 进行存在性检查 + 异常抛出
- 返回 proto Builder 链式构建
- `companion object { private val logger = KotlinLogging.logger {} }`

**适用场景**: 单表查询 → 构造响应

---

### 2. Handler 模式 — Query + Cursor Pagination（游标分页型）

**模板文件 1**: `gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt`
**模板文件 2**: `gateway/src/main/kotlin/com/nebula/gateway/handler/user/SearchUserHandler.kt`

**关键约定**:
- cursor=0 代表首次查询，需映射为有效游标边界值（如 `Long.MAX_VALUE` 或 `null`）
- `limit.coerceIn(1, max)` 限制分页范围
- `hasMore = results.size >= limit`（取 limit 条时可能有更多数据）
- 或采用"多取一条"策略：`limit+1` 查询，`hasMore = results.size > limit`，`dropLast(1)`
- `@Query` 中使用 `:param IS NULL OR field < :param` 处理首次查询

**适用场景**: 会话列表（`conversation/list`）— 按 `last_updated_at` 游标分页

---

### 3. Handler 模式 — Validate + Write + Push + Response（命令+推送型）

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt`

**关键约定**:
- 验证会话存在（`conversationRepository.findById()`）
- 验证成员身份（`conversationMemberRepository.findByConversationIdAndUserId()`）
- 非成员抛出 `ConversationException(BizCode.NOT_MEMBER)`
- DB 写入 `withContext(Dispatchers.IO) { }` 包裹
- 推送通过 `pushService.pushXxx()` 执行（可能需要扩展 PushService）
- 返回 `Response.newBuilder().setCode(BizCode.OK.code).setMethod(method).build()`

**适用场景**: invite_member、leave_group、kick_member

---

### 4. Handler 模式 — Command Simple（简单命令型）

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandler.kt`

**关键约定**:
- 最简 Handler：提取 session → 执行操作 → 返回 Response
- 无异常分支时直接 `return Response.newBuilder()...`
- `.setMsg("ok")` 填充消息文本（D-08）

**适用场景**: edit_group（单表更新，D-19 无需事务）

---

### 5. Handler 模式 — Create + Return ID（创建返回ID型）

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt`（结构参考）
**但实际上 SendMessageHandler 更接近** — 它返回 `SendMessageResp`（含 msgId + serverTs）

**关键约定**:
- 创建后返回新资源的 ID
- UUID 生成（`UUID.randomUUID().toString()`）用于 conversation_id（D-02）
- 响应包含至少 `conversation_id` 字段

**适用场景**: create_group

---

### 6. Repository 模式 — JPA @Query

**模板文件 A**: `repository/src/main/kotlin/com/nebula/repository/repository/MessageRepository.kt` — JPQL `@Query`
**模板文件 B**: `repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt` — `@Modifying` + `@Query`
**模板文件 C**: `repository/src/main/kotlin/com/nebula/repository/repository/UserRepository.kt` — 游标分页 `@Query`

**关键约定**:
- 接口继承 `JpaRepository<EntityType, IdType>`
- 简单查询用 Spring Data 方法命名推导（如 `findByUserId(userId)`）
- 复杂查询用 `@Query("JPQL")` + `@Param`
- 写操作需 `@Modifying` 注解
- 游标分页：`WHERE (:cursor IS NULL OR field < :cursor)` 模式

---

### 7. Entity 模式

**模板文件 A**: `repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt` — UUID 主键
**模板文件 B**: `repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt` — 联合唯一索引
**模板文件 C**: `repository/src/main/kotlin/com/nebula/repository/entity/MessageEntity.kt` — Snowflake ID + 复合索引

**关键约定**:
- `@Entity` + `@Table(name = "...", indexes = [...])`
- 构造函数参数 = 非 ID/非时间戳的业务字段
- `@Id` 字段在类体内声明（`var id: Type? = null`）
- 时间戳字段（`createdAt`, `updatedAt`）使用 `LocalDateTime`
- `@Column` 注解指定 nullable、length、updatable 等约束

---

### 8. Proto 消息定义模式

**模板文件**: `proto/src/main/proto/nebula/conversation/conversation.proto`（已存在）
**追加 Payload 参考**: `proto/src/main/proto/nebula/message_type.proto`（PushEventType 枚举）

**关键约定**:
- `syntax = "proto3";` + `package com.nebula.chat.xxx;`
- `java_multiple_files = true` + `java_package = "com.nebula.chat.xxx"`（与 package 一致）
- Request 消息：`XxxReq { ... }` — 含输入字段
- Response 消息：`XxxResp { ... }` — 含输出字段
- Payload 消息：`XxxPayload { ... }` — 推送事件专用
- 跨模块引用：`import "nebula/group/group.proto";`
- 枚举值用全大写下划线（如 `GROUP_CREATED`）

---

### 9. PushService 推送模式

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt`

**关键约定**:
- 构造 `Envelope(Direction.PUSH, Message(eventType, content, payload))`
- 通过 `userStreamRegistry.getStreams(userId)` 获取在线设备
- 逐个 `observer.onNext(envelope)`，try-catch 保护单个 observer 异常
- 推送失败时 `userStreamRegistry.removeStream()` 清理死连接
- `companion object { private val logger }` 日志

**Phase 7 扩展点**: 新增 `pushConversationEvent()` 方法，支持 GROUP_CREATED / MEMBER_JOINED / MEMBER_LEFT / MEMBER_KICKED / GROUP_UPDATED / GROUP_DISSOLVED 六种事件类型

---

### 10. Koin DI 注册模式

**模板文件**: `gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt`

**关键约定 — handlerModule**:
- 每个 Handler 一行 `single { XxxHandler(get(), get(), ...) }` 声明
- `get()` 数量与构造参数一一对应
- 特殊作用域用 `named("xxx")` 限定（如 sendHandlerScope）

**关键约定 — registerHandlers()**:
- 每个 Handler 一个参数：`xxxHandler: XxxHandler`
- 每个 Handler 一行 `registry.register(xxxHandler)` 调用
- Handler 添加顺序无严格要求，但建议按模块分组

**关键约定 — NebulaServer.kt**:
- `externalModule` 中注册所有 Repository 实例
- `GlobalContext.get().get<XxxHandler>()` 获取 Handler 实例
- `registerHandlers(...)` 传入所有 Handler 实例

---

### 11. 单元测试模式

**模板文件**: `gateway/src/test/kotlin/com/nebula/gateway/handler/message/PullMessagesHandlerTest.kt`
**模板文件**: `gateway/src/test/kotlin/com/nebula/gateway/handler/message/ReadReportHandlerTest.kt`
**模板文件**: `gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt`

**关键约定**:
- 使用 `io.mockk:mockk` 框架 mock 所有依赖
- `@BeforeEach fun setUp()` 初始化 mock 和 Handler
- 使用 `kotlinx.coroutines.test.runTest` 运行 suspend 测试
- Session 通过 `withContext(SessionKey(session)) { handler.handle(req) }` 注入
- 异常测试：`assertFailsWith<ConversationException> { ... }`
- 验证异常 bizCode：`assertEquals(BizCode.XXX, exception.bizCode)`
- 验证 mock 调用：`coVerify { repo.method(...) }`（suspend）/ `verify { ... }`（普通）
- Entity 构造：`ConversationEntity(type = 0).apply { id = "xxx" }` 模式
- GatewayModuleTest 使用 `mockk<>()` + `module { single { mockInstance } }` 构建测试模块

---

## 二、新需求 → 模板映射

| 新需求 | 最接近的现有模式 | 模板文件 | 差异说明 |
|--------|---------------|---------|---------|
| **ListConversationsHandler** | Query + Cursor Pagination | `PullMessagesHandler.kt` + `SearchUserHandler.kt` | 需新增 JOIN 查询；cursor 是时间戳（last_updated_at），非 Snowflake ID；需 Entity → ConversationBrief 映射 |
| **CreateGroupHandler** | Create + Return ID + Push | `SendMessageHandler.kt`（结构）+ `ReadReportHandler.kt`（推送） | **全新模式**：多表事务（D-19 TransactionTemplate）+ UUID 生成 + 批量创建成员 + GROUP_CREATED 推送；返回 CreateGroupResp 而非 Response |
| **InviteMemberHandler** | Validate + Write + Push + Response | `ReadReportHandler.kt` | 批量插入成员 + 成员计数原子更新（D-19 事务）；MEMBER_JOINED 推送；需检查群满（GROUP_FULL）、重复加入（ALREADY_IN_GROUP） |
| **LeaveGroupHandler** | Validate + Write + Push + Response | `ReadReportHandler.kt` | 群主退群 → 解散群（软删除 D-09）；普通成员退群 → 删除成员记录 + 更新计数；MEMBER_LEFT / GROUP_DISSOLVED 推送 |
| **KickMemberHandler** | Validate + Write + Push + Response | `ReadReportHandler.kt` | 双重权限检查（踢群主 → GROUP_PERM_DENIED、踢自己 → INVALID_PARAM）；MEMBER_KICKED + MEMBER_LEFT 双推送 |
| **EditGroupHandler** | Command Simple | `SetPrivacyHandler.kt` | 至少传 name/avatar 之一校验；name ≤128、avatar ≤256；GROUP_UPDATED 推送 |
| **GroupMembersHandler** | Query + Resp | `GetProfileHandler.kt` | 全量返回 ≤200 人（D-06 无分页）；Entity + UserEntity JOIN 填充 GroupMember 字段 |

---

## 三、文件级模板映射

### 3.1 Handler 创建

#### ListConversationsHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/ListConversationsHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt
差异:
  - method = "conversation/list"
  - 依赖: ConversationRepository (新增 JOIN 方法) + UserRepository (填充用户名/头像)
  - 游标是 epoch millis 时间戳，非 Snowflake ID
  - 需新增 Entity → ConversationBrief 扩展映射函数
  - 无成员检查（使用 JOIN 已保证只返回用户的会话）
```

#### CreateGroupHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/CreateGroupHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt（结构参考）
差异:
  - method = "conversation/create_group"
  - 依赖: ConversationRepository + ConversationMemberRepository + PushService + TransactionTemplate
  - 使用 UUID.randomUUID().toString() 生成 conversation_id（D-02）
  - 使用 TransactionTemplate.execute {} 编程式事务（D-19，项目新引入模式）
  - 需 ConcurrentHashMap<String, Mutex> 按 conversationId 串行化（D-19）
  - 返回 CreateGroupResp，非 Response
  - 推 GROUP_CREATED 给初始成员（排除创建者）
```

#### InviteMemberHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/InviteMemberHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt
差异:
  - method = "conversation/invite_member"
  - 依赖: ConversationRepository + ConversationMemberRepository + PushService + TransactionTemplate
  - 批量检查：群状态（已解散？）、群满？、已重复？、inviter 是成员？
  - 批量插入 + memberCount 原子更新（TransactionTemplate）
  - 推 MEMBER_JOINED 给现有成员
  - 返回 Response（D-08）
```

#### LeaveGroupHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/LeaveGroupHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt
差异:
  - method = "conversation/leave_group"
  - 依赖: ConversationRepository + ConversationMemberRepository + PushService + TransactionTemplate
  - 群主退群 → 标记 Conversation.status=DISSOLVED + 清空成员列表（D-09 软删除）
  - 普通成员退群 → 删除成员记录 + memberCount--（TransactionTemplate）
  - 推 MEMBER_LEFT（普通退群）或 GROUP_DISSOLVED（群主退群）
  - 返回 Response（D-08）
```

#### KickMemberHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/KickMemberHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/ReadReportHandler.kt
差异:
  - method = "conversation/kick_member"
  - 依赖: ConversationRepository + ConversationMemberRepository + PushService + TransactionTemplate
  - 双重权限检查：踢群主 → GROUP_PERM_DENIED；踢自己 → INVALID_PARAM（D-14）
  - 还需检查操作者是群主
  - 推 MEMBER_KICKED 给被踢者 + MEMBER_LEFT 给剩余成员
  - 返回 Response（D-08）
```

#### EditGroupHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/EditGroupHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/user/SetPrivacyHandler.kt
差异:
  - method = "conversation/edit_group_info"
  - 依赖: ConversationRepository + ConversationMemberRepository + PushService
  - 参数校验：至少传 name 或 avatar_url 之一（D-15）
  - name 最长 128 字符、avatar_url 最长 256 字符
  - 检查操作者是群主
  - 单表更新，无需事务（D-19）
  - 推 GROUP_UPDATED 给所有成员
  - 返回 Response（D-08）
```

#### GroupMembersHandler
```
新建:  gateway/src/main/kotlin/com/nebula/gateway/handler/conversation/GroupMembersHandler.kt
模板:  gateway/src/main/kotlin/com/nebula/gateway/handler/user/GetProfileHandler.kt
差异:
  - method = "conversation/group_members"
  - 依赖: ConversationMemberRepository + UserRepository
  - 全量返回（D-06，≤200 人无分页）
  - 需 JOIN UserEntity 填充 username、display_name、avatar_url
  - 返回 GroupMembersResp（含 repeated GroupMember）
```

---

### 3.2 Entity 修改

#### ConversationEntity 扩展
```
文件:  repository/src/main/kotlin/com/nebula/repository/entity/ConversationEntity.kt
模板:  自身现有结构 + MessageEntity.kt（新增字段风格）
新增字段:
  - var status: Int = 0          // D-17: 0=正常/1=已解散
  - var lastMessageId: Long = 0  // D-21: 最后一条消息 ID
  - var lastMessagePreview: String = ""  // D-21: 最后消息预览，截断 100 字符
  - var lastMessageTs: Long = 0  // D-21: 最后消息时间戳
注意事项:
  - 新增 @Column 注解，status 设默认值 0
  - lastMessagePreview 需设 @Column(length = 100) 或更大
  - 新增字段放在类体末尾，保持原有构造函数参数不变
```

#### ConversationMemberEntity 扩展
```
文件:  repository/src/main/kotlin/com/nebula/repository/entity/ConversationMemberEntity.kt
模板:  自身现有结构
新增字段:
  - var role: String = "member"  // D-17: owner/member
注意事项:
  - 新增 @Column(length = 16, nullable = false)，默认值 "member"
  - 放置在构造函数参数中，位于 userId 之后
```

---

### 3.3 Repository 扩展

#### ConversationRepository 新增方法
```
文件:  repository/src/main/kotlin/com/nebula/repository/repository/ConversationRepository.kt
模板A: repository/.../MessageRepository.kt（@Query JPQL 写法）
模板B: repository/.../UserRepository.kt（游标分页 @Query 写法）

新增方法:
  @Query("""
    SELECT c FROM ConversationEntity c
    JOIN ConversationMemberEntity cm ON cm.conversationId = c.id
    WHERE cm.userId = :userId AND (:cursor = 0L OR c.updatedAt < :cursorDateTime)
    ORDER BY c.updatedAt DESC
  """)
  fun findConversationBriefsByUserId(
      @Param("userId") userId: Long,
      @Param("cursor") cursor: Long,
      @Param("cursorDateTime") cursorDateTime: LocalDateTime,
      limit: Pageable
  ): List<ConversationEntity>

注意:
  - 需要将 cursor（epoch millis）转为 LocalDateTime 传参（D-01: 单次 JOIN 查询）
  - latestMessage 字段已通过 D-21 存储在 ConversationEntity 上，无需 JOIN messages 表
  - 返回的 ConversationEntity 已含 lastMessagePreview 等字段
```

#### ConversationMemberRepository 新增方法
```
文件:  repository/src/main/kotlin/com/nebula/repository/repository/ConversationMemberRepository.kt
模板:  自身现有方法风格

新增方法:
  // 删除特定会话的特定成员（用于退群/踢人）
  @Modifying
  @Query("DELETE FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId")
  fun deleteByConversationIdAndUserId(
      @Param("convId") conversationId: String,
      @Param("userId") userId: Long
  )

  // 统计会话成员数（用于群满检查）
  fun countByConversationId(conversationId: String): Int

  // 按会话解散软删除 — 清空所有成员（用于群主退群 D-09）
  @Modifying
  @Query("DELETE FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId")
  fun deleteAllByConversationId(@Param("convId") conversationId: String)

注意:
  - 删除使用 `@Modifying` + `@Query`（无返回值）
  - countByConversationId 使用 Spring Data 命名推导即可
```

---

### 3.4 Proto 扩展

#### conversation.proto 追加 Payload 消息
```
文件:  proto/src/main/proto/nebula/conversation/conversation.proto
模板:  proto/.../message_type.proto（PushEventType 枚举已定义）+ conversation.proto 现有结构

新增 Payload 消息（D-18）:
  // 群创建推送
  message GroupCreatedPayload {
    string conversation_id = 1;
    string name = 2;
    int64 creator_uid = 3;
  }

  // 成员加入推送
  message MemberJoinedPayload {
    string conversation_id = 1;
    repeated int64 uids = 2;
    int64 inviter_uid = 3;
  }

  // 成员离开推送
  message MemberLeftPayload {
    string conversation_id = 1;
    int64 uid = 2;
  }

  // 成员被踢推送
  message MemberKickedPayload {
    string conversation_id = 1;
    int64 uid = 2;
  }

  // 群信息更新推送
  message GroupUpdatedPayload {
    string conversation_id = 1;
    optional string name = 2;
    optional string avatar_url = 3;
  }

  // 群解散推送
  message GroupDissolvedPayload {
    string conversation_id = 1;
  }

注意:
  - 无需修改 PushEventType 枚举（已有 GROUP_CREATED=5~MEMBER_KICKED=11）
  - GROUP_INVITED=6 枚举保留但不使用（D-18 预留）
```

---

### 3.5 PushService 扩展

```
文件:  gateway/src/main/kotlin/com/nebula/gateway/push/PushService.kt
模板:  PushService.pushMessage() 现有模式

新增方法:
  /**
   * 向会话所有成员推送会话事件（创建群、邀请、退群等）（D-04）。
   *
   * @param convId 会话 ID
   * @param eventType 推送事件类型（GROUP_CREATED 等）
   * @param content 事件文本（通知栏预览）
   * @param payload 事件结构化数据（GroupCreatedPayload 等）
   * @param excludeUid 排除的用户 ID（通常为操作者），null 表示不排除
   * @param targetUids 指定目标用户列表（用于推给特定成员，如被踢者），null 表示推给所有成员
   */
  suspend fun pushConversationEvent(
      convId: String,
      eventType: PushEventType,
      content: String,
      payload: com.google.protobuf.MessageLite,
      excludeUid: Long? = null,
      targetUids: List<Long>? = null
  )

差异:
  - 支持两种推送目标：全体成员（通过 ConversationMemberRepository）或指定列表（如被踢者）
  - 支持排除特定用户（如创建者不推送自己）
  - Build Envelope 中 Message 构造与 pushMessage 一致
```

---

### 3.6 Koin DI 注册

#### handlerModule 新增（GatewayModule.kt）
```
文件:  gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt
模板:  handlerModule 中现有 single { } 声明模式

新增声明:
  // Phase 7: Conversation
  single { ListConversationsHandler(get(), get()) }              // ConversationRepo + UserRepo
  single { CreateGroupHandler(get(), get(), get(), get()) }      // ConvRepo + ConvMemberRepo + PushSvc + TransactionTemplate
  single { InviteMemberHandler(get(), get(), get(), get()) }    // 同上
  single { LeaveGroupHandler(get(), get(), get(), get()) }      // 同上
  single { KickMemberHandler(get(), get(), get(), get()) }      // 同上
  single { EditGroupHandler(get(), get(), get()) }              // ConvRepo + ConvMemberRepo + PushSvc
  single { GroupMembersHandler(get(), get()) }                  // ConvMemberRepo + UserRepo

注意:
  - TransactionTemplate 需额外在 externalModule 或 frameworkModule 注册
  - Mutex Map（ConcurrentHashMap<String, Mutex>）需单独注册为 Koin single
```

#### registerHandlers() 新增（GatewayModule.kt）
```
文件:  gateway/src/main/kotlin/com/nebula/gateway/di/GatewayModule.kt
模板:  registerHandlers() 现有参数 + 调用模式

新增参数:
  listConversationsHandler: ListConversationsHandler,
  createGroupHandler: CreateGroupHandler,
  inviteMemberHandler: InviteMemberHandler,
  leaveGroupHandler: LeaveGroupHandler,
  kickMemberHandler: KickMemberHandler,
  editGroupHandler: EditGroupHandler,
  groupMembersHandler: GroupMembersHandler

新增注册:
  registry.register(listConversationsHandler)     // conversation/list
  registry.register(createGroupHandler)           // conversation/create_group
  registry.register(inviteMemberHandler)          // conversation/invite_member
  registry.register(leaveGroupHandler)            // conversation/leave_group
  registry.register(kickMemberHandler)            // conversation/kick_member
  registry.register(editGroupHandler)             // conversation/edit_group_info
  registry.register(groupMembersHandler)          // conversation/group_members
```

#### NebulaServer.kt 新增
```
文件:  server/src/main/kotlin/com/nebula/server/NebulaServer.kt
模板:  现有 Handler import + GlobalContext.get() 模式

新增 import:
  import com.nebula.gateway.handler.conversation.*

新增获取:
  val listConversationsHandler = GlobalContext.get().get<ListConversationsHandler>()
  val createGroupHandler = GlobalContext.get().get<CreateGroupHandler>()
  // ... 其余 5 个

新增 registerHandlers() 调用参数:
  registerHandlers(registry, codec, ...,
    listConversationsHandler, createGroupHandler, inviteMemberHandler,
    leaveGroupHandler, kickMemberHandler, editGroupHandler, groupMembersHandler
  )
```

---

### 3.7 PullMessagesHandler 安全修复（D-07）

```
文件:  gateway/src/main/kotlin/com/nebula/gateway/handler/message/PullMessagesHandler.kt
模板:  ReadReportHandler.kt 成员检查模式

修改内容:
  在 handle() 方法中，session 获取之后、cursor 处理之前，添加成员检查:

  // Phase 7 安全修复：验证请求者是会话成员（D-07）
  val isMember = withContext(Dispatchers.IO) {
      conversationMemberRepository.findByConversationIdAndUserId(
          req.conversationId, session.userId
      )
  } != null
  if (!isMember) {
      throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")
  }

需额外注入:
  private val conversationMemberRepository: ConversationMemberRepository

需删除:
  文件头部的 SECURITY(FIXME Phase 7) 注释块

Koin 变更:
  PullMessagesHandler 构造参数由 (MessageRepository, ConversationRepository)
  变为 (MessageRepository, ConversationRepository, ConversationMemberRepository)
```

---

### 3.8 单元测试创建

| 新测试文件 | 模板测试文件 | 特殊点 |
|-----------|------------|--------|
| `ListConversationsHandlerTest.kt` | `PullMessagesHandlerTest.kt` | Mock 新增的 JOIN 查询方法；验证 cursor=0 和 cursor>0 场景；验证 ConversationBrief 字段映射 |
| `CreateGroupHandlerTest.kt` | `ReadReportHandlerTest.kt`（结构）+ `SendMessageHandlerTest.kt`（事务） | Mock TransactionTemplate.execute；验证群满/重复创建者/正常创建；验证 UUID 生成；验证 GROUP_CREATED 推送 |
| `InviteMemberHandlerTest.kt` | `ReadReportHandlerTest.kt` | 验证群满（BizCode.GROUP_FULL）、重复加入（ALREADY_IN_GROUP）、非成员邀请（NOT_MEMBER） |
| `LeaveGroupHandlerTest.kt` | `ReadReportHandlerTest.kt` | 验证群主退群解散（GROUP_DISSOLVED）、普通成员退群（MEMBER_LEFT） |
| `KickMemberHandlerTest.kt` | `ReadReportHandlerTest.kt` | 验证踢群主（GROUP_PERM_DENIED）、踢自己（INVALID_PARAM）、非群主踢人（GROUP_PERM_DENIED） |
| `EditGroupHandlerTest.kt` | `SetPrivacyHandler.kt`（简单命令型） | 验证两参数都不传（INVALID_PARAM）、非群主编辑（GROUP_PERM_DENIED）、名称超长 |
| `GroupMembersHandlerTest.kt` | `GetProfileHandler.kt`（简单查询型） | 验证非成员访问（NOT_MEMBER）、返回 GroupMember 字段正确 |
| `PullMessagesHandlerTest.kt`（更新） | 自身现有测试 | 新增非成员抛异常测试；新增成员正常拉取测试；更新 Handler 构造 |

**测试公共模式**:
```kotlin
class XxxHandlerTest {
    private lateinit var handler: XxxHandler
    
    // mock 依赖
    private val convRepo = mockk<ConversationRepository>()
    private val convMemberRepo = mockk<ConversationMemberRepository>()
    private val pushService = mockk<PushService>(relaxed = true)
    
    private val session = Session(1001L, "token", "MOBILE", "dev", "conn")
    
    @BeforeEach
    fun setUp() {
        handler = XxxHandler(convRepo, convMemberRepo, pushService, ...)
    }
    
    @Test
    fun `测试场景描述`() = runTest {
        // coEvery / every 设置 mock 行为
        // withContext(SessionKey(session)) { handler.handle(req) }
        // assertEquals / assertFailsWith 断言
    }
}
```

---

### 3.9 TransactionTemplate + Mutex 模式（D-19 — 项目新引入）

```
这是 Phase 7 引入的全新模式，无现有模板可参考。

TransactionTemplate:
  - 通过构造注入 TransactionTemplate（Spring 提供）
  - 使用 transactionTemplate.execute { status -> ... } 包裹多表操作
  - Repository 层不自行声明 @Transactional（协程兼容）

Mutex 按 conversationId 串行化:
  - 定义 private val mutexMap = ConcurrentHashMap<String, Mutex>()
  - 在事务操作前: mutexMap.getOrPut(conversationId) { Mutex() }.withLock { ... }
  - 防止 memberCount 并发竞态

使用示例:
  private val mutexMap = ConcurrentHashMap<String, Mutex>()
  
  override suspend fun handle(req: CreateGroupReq): CreateGroupResp {
      val session = currentCoroutineContext().requireSession()
      val convId = UUID.randomUUID().toString()
      
      mutexMap.getOrPut(convId) { Mutex() }.withLock {
          transactionTemplate.execute {
              // 1. 创建 ConversationEntity
              // 2. 批量创建 ConversationMemberEntity（含创建者 + member_uids）
          }
      }
      // ... 推送 + 返回
  }

注意:
  - 编辑群聊（EditGroupHandler）单表更新，无需事务包裹（D-19）
  - Mutex 按 conversationId 粒度，不影响不同会话的并发
```

---

## 四、关键差异和定制点汇总

| 类别 | 定制点 | 设计方案 |
|------|--------|---------|
| **新引入模式** | TransactionTemplate + Mutex（D-19） | 项目首次使用，无现有模板，需在 `07-CONTEXT.md` 指导模式下实现 |
| **会话 ID** | UUID 生成（D-02） | `UUID.randomUUID().toString()`，参考 LoginHandler 中 token 生成方式 |
| **游标类型** | epoch millis 时间戳（D-13） | 与现有 Snowflake ID 游标不同，需 `LocalDateTime` ↔ `epoch millis` 互转 |
| **推送模式** | `pushConversationEvent()` | PushService 新方法，支持多事件类型 + 排除特定用户 |
| **Entity 映射** | LocalDateTime → epoch millis | Handler 层使用 `LocalDateTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()` |
| **批量操作** | 批量创建/删除成员 | 使用 TransactionTemplate 保证原子性，Repository `saveAll()`/`@Modifying @Query DELETE` |
| **软删除** | status=DISSOLVED | 参考 D-09：标记 Conversation.status=1 + 清空 ConversationMember |
| **成员上限** | 200 人（D-05） | 与 `ConversationEntity.maxMembers` 默认值一致，`countByConversationId()` 检查 |
| **权限模型** | 群主 vs 成员 | 通过 `ConversationMemberEntity.role` 字段区分，拒绝非群主操作返回 GROUP_PERM_DENIED |

---

## 五、新增依赖与基础设施

| 组件 | 来源 | 注册位置 |
|------|------|---------|
| `TransactionTemplate` | Spring Framework（spring-tx） | `externalModule` 中注册 `single { jpaConfig.transactionTemplate }` |
| `ConcurrentHashMap<String, Mutex>` | kotlinx.coroutines.sync.Mutex | `handlerModule` 中注册 `single { ConcurrentHashMap<String, Mutex>() }` |

---

## PATTERNS COMPLETE
