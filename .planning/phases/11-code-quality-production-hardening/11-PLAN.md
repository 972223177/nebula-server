---
phase: 11
status: planned
date: 2026-06-15
---
# Phase 11: Code Quality & Production Hardening — 执行计划

## 阶段目标

基于全量代码审查（11-REVIEW.md 81 个问题），修复所有安全漏洞、数据一致性问题、错误处理缺陷和代码质量问题，使项目达到 v1 生产就绪状态。

## 计划总览

| Plan | 名称 | Wave | 问题数 | CQ 覆盖 | 状态 |
|------|------|------|--------|---------|------|
| 11-01 | 安全与生产加固 | 1 | 13 | CQ-02, CQ-05, CQ-09, CQ-10, CQ-11 | Pending |
| 11-02 | 数据一致性与竞态修复 | 1 | 11 | CQ-03, CQ-04 | Pending |
| 11-03 | 数据完整性与错误处理 | 2 | 30 | CQ-01, CQ-06, CQ-07, CQ-08, CQ-11, CQ-13 | Pending |
| 11-04 | 代码质量与测试加固 | 3 | 27 | CQ-10, CQ-12, CQ-14, CQ-15 | Pending |
| **合计** | | | **81** | 15 个 CQ 需求 | |

## Wave 分组

### Wave 1（P0 HIGH — 可并行，NebulaServer.kt 除外）
- **Plan 11-01**: 安全与生产加固（13 个 HIGH）
- **Plan 11-02**: 数据一致性与竞态修复（11 个 HIGH）

> **并行约束**：11-01 和 11-02 均修改 `server/.../NebulaServer.kt`（11-01: Shutdown Hook + init 回滚；11-02: SeqService 启动恢复）。两个 plan 大部分任务可并行，但 NebulaServer.kt 的修改需串行：**11-01 先完成，11-02 在此基础上修改**。其余文件无冲突，可自由并行。

### Wave 2（P1 MEDIUM — 依赖 Wave 1）
- **Plan 11-03**: 数据完整性与错误处理（30 个 MEDIUM）

### Wave 3（P2 LOW — 依赖 Wave 2）
- **Plan 11-04**: 代码质量与测试加固（27 个 LOW）

## 关键设计决策

| 决策 | 内容 | 影响范围 |
|------|------|---------|
| D-76 | 逐个修复独立 commit + 波级全量回归测试 | 执行策略 |
| D-77 | Redis 分环境控制：dev 免密码，prod 要求密码 | Plan 11-01 |
| D-78 | 密钥注入使用 HOCON `${?VAR}` 语法 | Plan 11-01 |
| D-79 | 事务保护使用 TransactionTemplate（非 @Transactional） | Plan 11-02 |
| D-80 | 好友双向竞态：DB 唯一约束 + DuplicateKeyException 幂等 catch | Plan 11-02 |
| D-81 | SeqService 启动时从 MySQL MAX(seq)+1 恢复 Redis 序列号 | Plan 11-02 |
| D-82 | memberCount 使用 JPQL @Modifying 原子更新 | Plan 11-02 |
| D-83 | N+1 修复：批量查询 + 正确性+性能双重验证 | Plan 11-03 |
| D-84 | JPA 阻塞使用 withContext(Dispatchers.IO) | Plan 11-03 |
| D-85 | 协程管理使用 ApplicationScope + SupervisorJob() | Plan 11-03 |
| D-86 | 空断言按上下文选择替换策略 | Plan 11-04 |
| D-87 | 项目完成标准：257+ 测试全通过 + 81 问题关闭 | Phase 出口 |

## 实现约束

1. **语言**：Kotlin，不使用 Java
2. **事务**：TransactionTemplate（非 @Transactional，D-79）
3. **协程**：withContext(Dispatchers.IO) 处理 JPA 阻塞（D-84）
4. **配置**：HOCON `${?VAR}` 注入密钥，移除明文默认值（D-77/D-78）
5. **数据库**：DDL 修改需 Flyway migration（V5），不手动改表
6. **测试**：每个修复均有专项验证，波级全量回归（D-76）
7. **注释**：所有 AI 生成代码使用中文 KDoc 注释（CODEBUDDY.md 规范）
8. **任务粒度**：每个任务 ≤ 50 行或等价复杂度

## 执行顺序

```
Wave 1 (并行)
├── Plan 11-01: [H01..H13]
└── Plan 11-02: [H14..H24]
     │
     ▼
Wave 2
└── Plan 11-03: [M01..M30]
     │
     ▼
Wave 3
└── Plan 11-04: [L01..L20] + [T01..T07]
```

## PLANNING COMPLETE
