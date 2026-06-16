# Service 模块测试审查报告

## 总览
- **测试文件数**: 9
- **覆盖的实现类**: UserService, UserPrivacyService, MessageService, FriendService, SeqService, DeadLetterService, ConversationService, RedisTestBase
- **测试框架**: MockK (strict/relaxed) + kotlinx.coroutines.test + Testcontainers (Redis)
- **测试风格**: 纯 MockK 单元测试为主，2 个集成测试 (RedisTestBase)

---

## 逐文件审查

### 1. UserServiceTest.kt
- **对应实现**: `service/src/main/.../user/UserService.kt`
- **覆盖场景**: 
  - register: 密码过短、用户名为空、用户名已存在、创建成功（BCrypt + Snowflake）、昵称默认值、头像默认值
  - loginByPassword: 用户名为空、密码为空、用户不存在、密码不匹配、登录成功
  - searchUsers: 关键词为空、分页 hasMore、无更多结果、limit 超限降级
  - getProfile: 用户不存在、返回完整字段
  - batchGetUsers: 空列表、部分存在、全部存在
- **遗漏场景**:
  - `register` 中的 `DataIntegrityViolationException` 兜底捕获未测试（实现第 101-105 行）
  - `loginByPassword` 中 `req.password` 为 null 的路径未覆盖（protobuf 通常不产生 null，但实现在第 126 行做了 null 检查）
- **质量问题**:
  - 第 77 行使用了 `runBlocking { userService.register(req) }` 在 `runTest` 内，这是反模式——应直接调用 suspend 函数（不过 `assertThrows` 需要非 suspend 包装，可理解）
  - 使用 `spyk(UserService(...))` 覆盖 `verifyPassword` 方法（第 290 行），方式合理
  - 混合使用 `org.junit.jupiter.api.Assertions.*` 和 `kotlin.test.*` 断言风格
- **评分**: 优

### 2. UserPrivacyServiceTest.kt
- **对应实现**: `service/src/main/.../user/UserPrivacyService.kt`
- **覆盖场景**:
  - setHideOnlineStatus: true 时调用 setHidden、false 时调用 setOnline，互斥验证
  - getHideOnlineStatus: 返回 true/false
- **遗漏场景**:
  - 测试仅覆盖了 `setHideOnlineStatus` 和 `getHideOnlineStatus` 两个方法，`batchGetHideOnlineStatus` 方法未测试
  - 未测试 `friendshipRepository` 的交互（虽然该类中未直接使用，但作为构造参数注入）
- **质量问题**:
  - 只使用 `kotlin.test.*` 断言，风格统一（良好）
  - 使用 `clearMocks` 在 `@BeforeEach` 中重置 mock，避免状态污染
- **评分**: 良

### 3. MessageServiceTest.kt
- **对应实现**: `service/src/main/.../chat/MessageService.kt`
- **覆盖场景**:
  - sendMessage: 内容为空、clientMessageId 为空、成员不存在、成员已删除、会话不存在、私聊非好友检查（群聊和私聊两条正常路径）
  - pullMessages: 成员不存在、成员已删除、正常分页 hasMore=false、游标非零、hasMore=true、空结果
  - readReport: 成员不存在、正常更新已读回执
- **遗漏场景**:
  - `countByConversationId`、`checkAndSetDedup`、`incrementUnreadCount` 三个方法无测试
  - 消息去重逻辑（实现第 106-107 行注释说明）无测试（被标记为 Handler 层职责，但服务层仍有接口暴露）
  - `checkFriendshipForPrivateConv` 中会话 ID 格式异常的 fallback 逻辑未测试（实现第 271-273 行）
- **质量问题**:
  - 使用 strict mockk（第 92 行），确保未配置的 mock 调用不会静默成功
  - `@AfterEach unmockkAll()` 清理干净
  - 测试数据工厂方法简洁，可读性好
- **评分**: 优

### 4. FriendServiceTest.kt
- **对应实现**: `service/src/main/.../friend/FriendService.kt`
- **覆盖场景**:
  - addFriend: 加自己、已是好友、双向竞赛、双向竞赛恢复已删好友、双向竞赛复用会话、重复 pending 申请、正常创建申请
  - addFriend 并发场景: 只创建一对好友关系（T03/D-80）
  - acceptFriendRequest: 申请不存在、状态非 pending、无权操作、正常接受、恢复已删好友、复用会话
  - rejectFriendRequest: 不存在、已处理、无权、正常拒绝
  - deleteFriend: 不存在、已删除、正常删除
  - listFriends: 空列表、正常返回+在线状态、游标分页 hasMore、在线状态隐私过滤
  - getFriendRequests: 空、正常返回、多申请人、发起方不存在
- **遗漏场景**:
  - `findFriendsByUserId` 和 `findFriendshipBetween` 两个内部方法无测试
  - 好友列表返回时游标设置逻辑未独立验证（实现第 363-366 行）
- **质量问题**:
  - 非常详细，7 个 Repository/Redis 依赖全部 mock，场景覆盖完整
  - 双向竞赛并发测试使用 `friendCheckCount` 计数器模拟并发状态变更（第 325-328 行），设计巧妙
  - 混合使用 `verify`（非协程）和 `coVerify`，可能因误用导致测试不可靠——`friendshipRepository.findByUserIdAndFriendId` 等非 suspend 方法用 `every`/`verify` 是正确的，但需确保实现不会在某天改为 suspend
  - 第 162 行 `runBlocking { service.addFriend(...) }` 在 `runTest` 内使用，同 UserServiceTest 的问题
- **评分**: 优

### 5. SeqServiceTest.kt
- **对应实现**: `service/src/main/.../sequence/SeqService.kt`
- **覆盖场景**:
  - nextSeq: 首次返回 1、正常递增、INCR 返回 null 兜底、溢出重置、未达阈值不重置、非数字值不重置
  - currentSeq: 正常查询、Key 不存在返回 0、无效值返回 0
  - tryRestoreSeq: SETNX 成功、SETNX 失败、SETNX 返回 null 兜底
  - Key 格式验证
- **遗漏场景**:
  - `recoverSequences` 方法（实现第 121-143 行）无测试
  - 并发场景的序列号单调递增保证未测试（已标注 TODO T06）
- **质量问题**:
  - 通过反射注入 mock `RedisCoroutinesCommands`（第 54-58 行），绕过构造限制，虽然有效但脆弱——如果字段名重命名则测试编译失败
  - 测试覆盖了所有代码分支，包括边界情况和异常路径
  - 文档中明确标注了已知局限（第 308-318 行），透明度好
- **评分**: 优

### 6. RedisTestBaseTest.kt
- **对应实现**: `service/src/test/.../testutil/RedisTestBase.kt`
- **覆盖场景**: 验证 Redis Testcontainers 容器可正常连接，SET/GET 基本操作
- **遗漏场景**:
  - 仅一个简单的 SET/GET 测试，这是基础设施验证而非业务逻辑测试，合理
  - 未测试容器不可用时的降级行为（跳过逻辑）
- **质量问题**:
  - 代码极简，仅验证基础设施可用性
- **评分**: 良（作为基础设施验证已足够）

### 7. DeadLetterServiceTest.kt
- **对应实现**: `service/src/main/.../admin/DeadLetterService.kt`
- **覆盖场景**:
  - create: 字段正确性、返回 ID
  - compensate: 无待处理返回 0、正常处理、OptimisticLockException 跳过、enqueue 异常跳过、补偿后调用 markPermanentFailed
  - retry: 实体不存在、已重试成功、正常重试、OptimisticLockException
  - query: 全部查询、按状态过滤、status 空白
  - markPermanentFailed: 标记过期项、跳过低于阈值的项
  - T05: payload 恢复（Base64 编码验证，使用 slot 捕获）
- **遗漏场景**:
  - `onMessageFailed` 回调方法（实现第 71-93 行）未测试
  - compensate 中 stream fields 字段名与 sendMessage 不一致的潜在问题未验证（实现用 snake_case "msg_id"，sendMessage 用 camelCase "msgId"）
- **质量问题**:
  - 测试覆盖非常全面，特别是 T05 使用 `slot` 捕获 enqueue 参数的验证方式很好
  - `mockMarkPermanentFailed` 辅助方法抽取合理，减少重复代码
- **评分**: 优

### 8. SeqServiceRedisRecoveryTest.kt
- **对应实现**: `service/src/main/.../sequence/SeqService.kt`
- **覆盖场景**:
  - FLUSHALL 后 tryRestoreSeq 恢复序列号
  - SETNX 幂等性：不覆盖已有 Key
- **遗漏场景**:
  - 多会话多 Key 的批量恢复未测试
  - 并发 FLUSHALL + 写入的竞争条件未测试
  - 已标注为 T06，文档中说明了局限
- **质量问题**:
  - 使用真实 Redis 容器，避免了 MockK 无法验证 Redis INCR/SETNX 行为的局限
  - 测试步骤清晰，验证链完整（写入→FLUSHALL→恢复→验证）
- **评分**: 优

### 9. ConversationServiceTest.kt
- **对应实现**: `service/src/main/.../conversation/ConversationService.kt`
- **覆盖场景**:
  - createGroup: 名称为空、超长、包含群主、成员超限、正常创建
  - listConversations: 空列表、正常返回
  - inviteMember: 会话不存在、非群主操作、跳过活跃成员、恢复已删成员、邀请新成员、更新计数
  - leaveGroup: 会话不存在、非成员、群主不能退群、正常退群
  - kickMember: 会话不存在、非群主、目标非成员、不能踢群主、正常踢人
  - editGroupInfo: 会话不存在、非群主、名称为空、正常编辑
  - getGroupMembers: 非成员、正常返回
  - T04: memberCount 并发更新（协程调度层验证）
- **遗漏场景**:
  - `dissolveGroup`（解散群组，实现第 299-318 行）无测试
  - `getConversation`、`getConversationMembers`、`getMemberRole` 三个内部查询方法无测试
  - `listConversations` 未测试 hasMore 分页场景（仅测试了空列表和正常返回）
  - `createGroup` 未测试 `save` 操作失败时的异常处理
- **质量问题**:
  - T04 并发测试是 MockK 方案，文档明确说明了不验证 MySQL JPA 层的原子性（第 679-683 行），透明度好
  - 使用 `by lazy` 延迟初始化测试实体，避免每次 setup 重新创建
- **评分**: 优

---

## 汇总问题

### 1. 未覆盖的实现方法

以下实现类方法完全没有测试覆盖：

| 实现类 | 方法 | 评分影响 |
|--------|------|---------|
| MessageService | `countByConversationId` | 中 |
| MessageService | `checkAndSetDedup` | 低（标记为 Handler 层职责） |
| MessageService | `incrementUnreadCount` | 低（标记为 Handler 层职责） |
| SeqService | `recoverSequences` | 中（启动恢复逻辑，关键路径） |
| DeadLetterService | `onMessageFailed` | 中（同步回调桥接） |
| ConversationService | `dissolveGroup` | 中 |
| ConversationService | `getConversation` | 低（内部辅助方法） |
| ConversationService | `getConversationMembers` | 低（内部辅助方法） |
| ConversationService | `getMemberRole` | 低（内部辅助方法） |
| FriendService | `findFriendsByUserId` | 低（内部辅助方法） |
| FriendService | `findFriendshipBetween` | 低（内部辅助方法） |
| UserPrivacyService | `batchGetHideOnlineStatus` | 低（一次性委托） |

### 2. 断言风格不统一

- `UserServiceTest.kt`: 使用 `org.junit.jupiter.api.Assertions.*`
- `MessageServiceTest.kt`, `FriendServiceTest.kt`, `DeadLetterServiceTest.kt`: 混合使用 `org.junit.jupiter.api.Assertions.*` 和 `kotlin.test.*`
- `UserPrivacyServiceTest.kt`, `SeqServiceTest.kt`: 统一使用 `kotlin.test.*`
- 建议统一为 `kotlin.test.*`，获得更好的 Kotlin 类型推断支持

### 3. `runBlocking` 在 `runTest` 内部的使用

- `UserServiceTest.kt` 第 77、93、110-111、210、229、246、270 行
- `FriendServiceTest.kt` 第 162、176、279、297、343、363、377、393、399、408、415、419、425、432 行
- 当 `assertThrows` 需要非 suspend lambda 时，这是可接受的模式，但破坏了 `runTest` 的虚拟时间效果

### 4. Stream fields 字段名不一致风险

- `MessageService.sendMessage`（实现第 124-134 行）使用 camelCase: `"msgId"`, `"conversationId"`
- `DeadLetterService.compensate`（实现第 168-178 行）使用 snake_case: `"msg_id"`, `"conversation_id"`
- 测试未验证 stream fields 的 key 名称一致性，如果消费方依赖特定的 field 命名，可能在生产中出现问题

### 5. 缺少 MySQL 集成测试

- 除 RedisTestBase 外，所有测试均使用 MockK 模拟 Repository 层
- `ConversationService`, `FriendService`, `MessageService` 涉及复杂的 JPA 操作（save、findById、findAllById），MockK 无法验证 SQL 正确性
- 建议对关键路径（如好友关系创建、双向竞赛、会话创建）添加 Testcontainers + MySQL 集成测试

### 6. 并发测试的局限

- `ConversationServiceTest` 的 T04 并发测试（第 684-731 行）已标注仅验证协程调度层
- `FriendServiceTest` 的 T03 并发测试（第 313-350 行）使用计数器模拟而非真实并发
- 两组并发测试都有明确的文档说明局限，但并发问题恰恰是此类测试最难覆盖的领域

### 7. 异常场景的遗漏

- `register()` 中的 `DataIntegrityViolationException` 兜底（唯一键冲突的最终防线）未测试
- `sendMessage` 和 `compensate` 中的 stream fields 构造细节未逐字段断言
- 死信 `compensate` 的 `markPermanentFailed` 调用仅在 `@AfterEach` 层面隐含验证（第 265-276 行），未显式断言调用

---

## 总体评分

**综合评分: 优**（9/9 文件评分在良以上，其中 7 优 2 良）

### 亮点
1. 测试覆盖全面，9 个测试文件共约 90+ 个测试用例，关键业务路径和异常路径均有覆盖
2. 测试代码质量高，使用 strict mock 模式（`MessageServiceTest`、`DeadLetterServiceTest`）
3. 集成测试（`RedisTestBase` + `SeqServiceRedisRecoveryTest`）填补了纯 Mock 测试的盲区
4. 测试数据工厂方法（helper functions）复用良好，减少样板代码
5. 文档注释完整，测试意图清晰
6. 已知局限透明标注（T04、T05、T06 等 TODO）

### 改进建议
1. 补充已识别的遗漏场景（特别是 `recoverSequences`、`dissolveGroup`、`DataIntegrityViolationException`）
2. 统一断言风格为 `kotlin.test.*`
3. 修复 stream fields 字段名不一致问题（camelCase vs snake_case）
4. 对关键路径添加 MySQL 集成测试
5. 在持续集成中确保 `SeqServiceRedisRecoveryTest` 和 `RedisTestBaseTest` 的 Docker 环境可用
