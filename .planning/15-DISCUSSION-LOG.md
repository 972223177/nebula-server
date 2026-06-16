---
phase: 15
discussion_status: completed
started: 2026-06-16
completed: 2026-06-16
selected_categories:
  - A. Repository 层测试缺口
  - B. Service 层测试缺口
  - C. Gateway 层测试缺口
  - D. 集成测试基础设施
---

# Phase 15: 测试覆盖缺口闭合 — 讨论日志

## 阶段目标
- 补齐代码审查发现的测试覆盖缺口（P0~P3 级别）
- 统一测试风格和断言规范
- 增强关键路径的集成测试覆盖

## 讨论记录

### A. Repository 层测试缺口

#### A-1: SessionRepository 核心方法无覆盖（P0）
- **决策**: 组合方案 — MockK 单元测试覆盖 7 个方法的异常路径 + RedisTestBase 集成测试覆盖核心正路径（save→findByToken→delete）

#### A-2: 游标分页查询无测试（P1）
- **决策**: 单元测试 + 集成测试组合 — MockK 验证游标值计算逻辑，集成测试验证 SQL 正确性
- **覆盖方法**: UserRepository.findByUsernameContaining、ConversationRepository.findConversationsByUserId、FriendshipRepository.findFriendsByUserId

#### A-3: ConversationMemberRepository 批量方法无测试（P1）
- **决策**: 全补集成测试 — incrementUnreadCount、findByConversationIdsAndUserId、findByConversationIdAndUserIds、softDeleteAllByConversationId

#### A-4: 低优先级缺口（P2~P3）
- **决策**: 全部本阶段处理
  - 修复唯一约束异常类型为具体类型（DataIntegrityViolationException）
  - OnlineStatusRepositoryTest 验证 setOnline/setHidden 的写入操作
  - 补充 FlywayMigrationTest 字段校验（friendships 表 + conversation_members 表完整字段）
  - 补充 FriendRequestRepository 2 个方法测试（findByFromUidAndToUid、findByToUidAndStatusOrderByCreatedAtDesc）

### B. Service 层测试缺口

#### B-1: 遗漏方法
- **决策**: 全部补充
  - SeqService.recoverSequences — RedisTestBase 集成测试
  - ConversationService.dissolveGroup — MockK 单元测试
  - MessageService.countByConversationId — MockK 单元测试
  - DeadLetterService.onMessageFailed — MockK 单元测试

#### B-2: DataIntegrityViolationException 兜底
- **决策**: 本阶段补充，在现有 UserServiceTest 中添加测试

#### B-3: Stream fields 字段名不一致
- **决策**: 统一为 camelCase（修改 DeadLetterService.compensate 中的字段名）
- **受影响**: `msg_id` → `msgId`, `conversation_id` → `conversationId`

### C. Gateway 层测试缺口

#### C-1: LogInterceptorTest
- **决策**: 移除无实质意义的 LogInterceptorTest（仅透传验证）

#### C-2: ProtoCodec + 反射注入
- **决策**: 本阶段处理
  - ProtoCodec roundtrip 添加字段级验证（method、payload 字段一致性）
  - ReadReportHandlerTest 和 RedisDeliveryTrackerTest 的反射注入改为构造函数注入

#### C-3: 无 Session 上下文测试
- **决策**: 保持现状 — Dispatcher 层已在 Handler 之前完成 Session 验证

### D. 集成测试基础设施

#### D-1: 缺少 MySQL 集成测试
- **决策**: 创建 MySQLTestBase 基础设施（参考 RedisTestBase pattern），补关键路径集成测试
- **关键路径**: 好友关系创建、双向竞赛

#### D-2: 断言风格 + 并发测试
- **决策**: 仅在本阶段修改的文件中局部统一断言风格为 `kotlin.test.*`
- T04 memberCount 并发测试：保持现有 MockK 方案
