---
phase: 14
status: contexted
---

# Phase 14: 遗留问题 — 上下文

## 阶段目标

解决代码审查（`code-review-2026-06-16.md`）中标记为「暂不修复」的遗留问题，补充延期测试和关键基础设施。

## 关联需求

本阶段无新增 PROTO/业务需求，主要承接：

- **STATE.md Next Actions**: 嵌入式 Redis 测试基础设施 → 补全 T04/T05/T06 延期测试
- **代码审查遗留**: `code-review-2026-06-16.md` 中 ~80 项标记为「⏸️ 暂不修复」的问题

## 技术决策

| 决策编号 | 类别 | 决策描述 |
|----------|------|----------|
| D-14-01 | Redis 测试 | 使用 **Testcontainers Redis** 作为嵌入式 Redis 方案，与现有 MySQL Testcontainers 保持一致 |
| D-14-02 | S2 | server→repository 分层违规 **已解决**（Phase 12 成果：server 生产依赖仅含 :gateway/:proto/:common，repository/service 仅在 testImplementation） |
| D-14-03 | S5 | Token TTL 空窗期 **已解决**（S4 TTL 刷新机制覆盖活跃用户，非活跃用户过期属正常行为） |
| D-14-04 | GC2 | runBlocking + TransactionTemplate 模式 **设计合理**（TransactionTemplate.execute() 是阻塞 API，runBlocking 是 Kotlin 桥接 suspend→blocking 的标准做法。改造为全 suspend 会引入测试中 Dispatcher.IO 协程不关闭的问题） |
| D-14-05 | GC4 | AuthInterceptor admin/ 白名单 **保持现状**（admin 端点是内部运维接口，当前白名单机制可接受） |
| D-14-06 | GC5 | Token 重连需 **本阶段加入 deviceId 验证**（Session 存储加 deviceId，重连时校验 Token 绑定的设备ID） |
| D-14-07 | GI2 | L1/L2 双写 L2 失败无补偿 **已解决**（单节点部署不依赖 L2，L2 失败不影响功能正确性） |
| D-14-08 | R3/R4 | 事务边界 **已解决**（Handler 层 TransactionTemplate.execute() 已包裹所有跨 Repository 写入，D-79） |
| D-14-09 | R14 | SeqService 序列号重置竞态 **已解决**（MAX_SEQ_THRESHOLD = Long.MAX_VALUE - 10000，单会话消息数永不可达，仅理论风险） |
| D-14-10 | R17 | DeadLetter compensate 跨存储事务 **已解决**（跨 Redis+MySQL 事务需 saga 补偿模式，属架构级设计限制） |
| D-14-11 | P4-P11 | Proto/API 一致性 **已解决**（6 项均为非兼容性变更，协议兼容性优先，标记为技术债务） |
| D-14-12 | S6/S16/S23 | **补充关键测试**：server 模块测试 + gRPC 端到端测试 + 并发/重连/恢复测试 |
| D-14-13 | S8 | **清理 server/build.gradle.kts 冗余依赖声明**，降低维护成本 |
| D-14-14 | S3 | 硬编码密码 **暂不修复**（开发环境正常配置，生产通过环境变量注入） |
| D-14-15 | F类 | 低优先级重构（enum替代/命名统一/魔法数字等 20+ 项）**全部延期 v1.3** |

## 实现约束

- **测试框架**: Testcontainers Redis + JUnit 5 + kotlinx.coroutines.test
- **兼容性**: 不修改 Proto 定义、不变更公开 API、保持现有 Handler 签名不变
- **依赖范围**: 修改仅限测试代码和 `server/build.gradle.kts`
- **GC5 deviceId**: 需在 Session/SessionStore/SessionRegistry/AuthInterceptor 中联动修改

## 灰区已解决

- A. 嵌入式 Redis 方案 → Testcontainers Redis
- B. S2/S5/GC2/GC4/GC5/GI2 → 已解决/设计合理/本阶段修复
- C. R3/R4/R14/R17 → 已解决
- D. P4/P8/P9/P10/P11 → 已解决
- E. S6/S16/S23/S8/S3 → 补充测试/清理依赖/暂不修复
- F. 低优先级重构 → 全部延期 v1.3

## 灰区遗留

| 编号 | 原因 |
|------|------|
| Proto/API 命名规范化 (P4/P8/P9/P10/P11) | 非兼容性变更，v1.3 统一处理 |
| 低优先级重构 (F类 20+ 项) | 不阻塞功能，v1.3 统一处理 |
| 硬编码密码 (S3) | 开发环境正常配置，部署时处理 |
| gRPC 端到端集成测试 (S6) | 本阶段补充部分 server 测试，全量 E2E 测试需后续专项规划 |
