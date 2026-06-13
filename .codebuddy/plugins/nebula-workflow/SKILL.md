---
name: nebula-workflow
description: GSD 风格的 AI 驱动开发工作流 —— 通过状态外置、验证闭环、专家 Agent 调度和多 Agent 协同实现结构化的阶段化开发管理
---

# Nebula Workflow（星云工作流）

## 设计公式

```
GSD 流程（状态外置 + 验证闭环 + 文档驱动）
+ CodeBuddy 原生能力（TeamCreate 并行 + 上下文隔离 + 专家 Agent 调度）
= 结构化 AI 驱动开发
```

## 核心原则

### 状态外置
所有项目状态存储在 `.planning/` 目录的文件系统中，不依赖对话上下文。每次命令启动时从文件系统加载最新状态。

### 上下文隔离
每个命令和 Agent 拥有独立的上下文窗口。命令文件保持 2-3KB，Agent 文件保持 3-5KB，避免上下文污染。

### 验证闭环
每个阶段遵循：讨论 → 规划 → 审核 → 执行 → 验证 的完整闭环。规划和执行之间有独立的计划审核门禁。

### 专家 Agent 调度（v0.4 新增）
`nx-executor` 为纯调度器，不亲自写代码。根据 PLAN.md 任务表中的 `expert` 字段，自动派发 CodeBuddy 平台提供的 40+ 领域专家 agent（如 `backend-architect`、`java-developer`、`database-optimizer`、`test-automator` 等）并行执行。未指定 expert 时，根据文件路径自动推断；推断失败则回退到通用模式。

### 中文优先
所有文档注释、命令说明、Agent 提示词使用中文。代码标识符（类名、方法名、关键字）保留英文。符合项目 CODEBUDDY.md 中文注释规范。

## 命令体系

| 命令 | 用途 |
|------|------|
| `/nx-init` | 项目初始化：需求收集 → 研究 → 路线图生成 |
| `/nx-discuss <N>` | 阶段讨论：上下文加载 → 灰区识别 → CONTEXT.md |
| `/nx-plan <N>` | 阶段规划：研究 → 模式映射 → PLAN.md 生成 → 审核 |
| `/nx-check-plan <N>` | 独立审核 PLAN.md（含停滞检测） |
| `/nx-exec <N> [--wave N] [--gaps-only] [--interactive]` | 阶段执行：Wave 并行 + 门禁检查 |
| `/nx-verify <N>` | 目标反向验证：四层验证模型 |
| `/nx-validate <N>` | Nyquist 测试覆盖审计 |
| `/nx-integrate` | 跨阶段集成检查 |
| `/nx-status` | 项目状态查看 |
| `/nx-done <N>` | 阶段归档：更新 STATE.md |

## 兼容性

- 完全兼容 GSD 生成的 `.planning/` 目录结构
- 自动识别 `NN-description/` 格式的阶段目录
- 读取时 fallback 到 GSD 文件命名格式
- 新建文件统一使用 Nebula 原生格式，与 GSD 格式共存

## 上下文预算管理

- 每个命令文件 2-3KB，Agent 文件 3-5KB
- Orchestrator 上下文预算 < 20%，Agent 获得独立上下文
- 根据模型上下文窗口自适应调整 subagent 提示词丰富度
