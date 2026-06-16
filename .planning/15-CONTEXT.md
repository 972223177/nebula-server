---
phase: 15
status: contexted
---

# Phase 15: 测试覆盖缺口闭合 — 上下文

## 阶段目标

基于 2026-06-16 三模块（repository/service/gateway）测试审查发现的覆盖缺口，补齐 P0~P3 级别的测试缺失，统一测试规范，增强关键路径的集成测试覆盖。

## 关联需求

本阶段无新增 PROTO/业务需求，主要承接：

- **代码审查遗留**: `20260616-review-test-service-gateway-repository/` 三份审查报告发现测试缺口
- **STATE.md Phase 15**: 测试覆盖缺口闭合

## 技术决策

| 决策编号 | 类别 | 决策描述 |
|----------|------|----------|
| D-15-01 | A-1 | SessionRepository 7 个核心方法采用 **组合测试**：MockK 覆盖异常路径 + RedisTestBase 覆盖核心正路径 |
| D-15-02 | A-2 | 3 个游标分页方法采用 **单元+集成组合**：MockK 验证游标值逻辑 + MySQL 集成测试验证 SQL 正确性 |
| D-15-03 | A-3 | ConversationMemberRepository 4 个批量方法 **全补集成测试** |
| D-15-04 | A-4 | P2~P3 项目 **全部本阶段处理**：异常类型细化、OnlineStatus 写入验证、Flyway 字段校验补充、FriendRequest 测试补充 |
| D-15-05 | B-1 | Service 层 4 个遗漏方法 **全部补充**：recoverSequences（集成测试）、dissolveGroup/countByConversationId/onMessageFailed（MockK） |
| D-15-06 | B-2 | register() DataIntegrityViolationException 兜底 **本阶段补充测试** |
| D-15-07 | B-3 | Stream fields 字段名 **统一为 camelCase**：DeadLetterService 中的 `msg_id`→`msgId`、`conversation_id`→`conversationId` |
| D-15-08 | C-1 | **移除 LogInterceptorTest**（当前仅验证透传，无实质测试价值） |
| D-15-09 | C-2 | ProtoCodec roundtrip 添加字段级验证；反射注入改为构造函数注入 |
| D-15-10 | C-3 | 无 Session 上下文测试 **保持现状**（Dispatcher 层已保障） |
| D-15-11 | D-1 | **创建 MySQLTestBase 基础设施**，补关键路径（好友关系创建、双向竞赛）的 Service 层集成测试 |
| D-15-12 | D-2 | **局部统一断言风格**：仅本阶段修改的文件统一为 `kotlin.test.*` |

## 实现约束

- **测试框架**: Testcontainers MySQL/Redis + JUnit 5 + kotlinx.coroutines.test + MockK
- **兼容性**: 不修改 Proto 定义、不变更公开 API、不修改业务逻辑
- **MySQLTestBase**: 参照 service 模块已有的 RedisTestBase 模式创建
- **字段名修改**: DeadLetterService.compensate 中的 stream fields 统一为 camelCase，需确认无其他消费方

## 灰区已解决

- A. Repository 层测试缺口 — 7 个决策，从 P0 到 P3 全覆盖
- B. Service 层测试缺口 — 6 个遗漏方法和 1 个兜底处理
- C. Gateway 层测试缺口 — 3 项处理（移除/修复/保持现状）
- D. 集成测试基础设施 — MySQLTestBase 创建 + 断言风格统一

## 灰区遗留

| 编号 | 原因 |
|------|------|
| T04 memberCount 并发测试升级为 MySQL 集成测试 | 未选中，保持现有 MockK 方案 |
| 全量断言风格重构（Gateway 59 个文件） | 仅局部统一，不进行全量重构 |
