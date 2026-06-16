---
slug: fix-code-review-issues
description: 修复 code-review-2026-06-16.md 附录中未处理的代码问题，并新增解决方案和涉及文件列
created: 2026-06-16
expert: code-reviewer
mode: quick
tasks: 6
---

# Quick Plan: fix-code-review-issues

## 任务描述

读取 `code-review-2026-06-16.md` 附录完整问题清单，对未处理/未修复的问题执行代码修复，
同时为每个问题补充"解决方案"和"涉及文件"两列，更新问题状态。

修复原则：不影响现有依赖链路，仅做安全、低风险的局部修改。

## 任务表

| # | 类型 | 批次 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | Proto修复 | P1 补充 GroupInvitedPayload；P2/P3/P7 修正 API.md 错误 | Proto 编译通过 |
| 2 | modify | Common修复 | C1 waitNextMillis 退避；C4 AutoCloseable；C7 运行时校验 | 编译通过 |
| 3 | modify | Service修复 | R11 BCrypt 单例化；R2 Redis Stream 字段名统一 | 编译通过 |
| 4 | modify | Gateway修复 | GI1 Dispatcher 传递 chain request；GI8 死代码；GI9 未用DI | 编译通过 |
| 5 | modify | Server修复 | S7 ChatServer 优雅关闭延长到 30s | 编译通过 |
| 6 | modify | 文档更新 | 为所有问题新增"解决方案"和"涉及文件"列，更新修复状态 | 文档完整性 |

## 上下文引用

- 审查报告: code-review-2026-06-16.md
- 项目: .planning/PROJECT.md
