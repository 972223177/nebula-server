---
phase: 14
discussion_status: completed
started: 2026-06-16
completed: 2026-06-16
selected_categories: [A. 嵌入式 Redis 测试, B. 架构/安全债务, C. 数据完整性, D. Proto/API 一致性]
---

# Phase 14: 遗留问题 — 讨论日志

## 阶段目标
- 解决代码审查中标记为「暂不修复」的遗留问题
- 搭建嵌入式 Redis 测试基础设施，补全延期测试

## 讨论记录

### A. 嵌入式 Redis 测试基础设施
- **决策**: 使用 Testcontainers Redis（D-14-01）
- **原因**: 与现有 MySQL Testcontainers 一致，通用性最好，支持 Stream/原子操作等复杂场景
- **测试**: T04(memberCount并发) / T05(DeadLetter补偿) / T06(SeqService恢复)

### B. 架构/安全债务
- **S2**: ✅ 已解决 — Phase 12 已消除 server→repository 依赖，server 生产依赖仅含 :gateway/:proto/:common
- **S5**: ✅ 已解决 — S4 TTL 刷新覆盖活跃用户
- **GC2**: ✅ 设计合理 — TransactionTemplate + runBlocking 是 Kotlin 桥接 suspend→blocking 的标准做法
- **GC4**: ✅ 保持现状 — admin 白名单机制可接受
- **GC5**: ⚠️ 本阶段加 deviceId 验证（D-14-06）
- **GI2**: ✅ 已解决 — 单节点不依赖 L2

### C. 数据完整性
- **R3/R4**: ✅ 已解决 — Handler 层 TransactionTemplate 保证事务
- **R14**: ✅ 已解决 — 序列号阈值理论不可达
- **R17**: ✅ 已解决 — 跨存储事务需 saga 补偿模式，架构设计限制

### D. Proto/API 一致性
- **P4/P8/P9/P10/P11**: ✅ 已解决 — 非兼容性变更，协议兼容性优先

### E. 构建/测试/部署
- **S6/S16/S23**: ⚠️ 本阶段补充关键测试
- **S8**: ⚠️ 清理 server/build.gradle.kts 冗余依赖
- **S3**: ✅ 暂不修复 — 开发环境正常配置

### F. 低优先级重构
- **全部延期 v1.3** — enum替代/命名统一/魔法数字等 20+ 项
