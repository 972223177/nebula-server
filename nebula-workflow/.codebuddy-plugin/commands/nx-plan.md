---
description: 规划阶段，启动 nx-planner agent 进行任务分解，生成 PLAN.md
argument-hint: [阶段号] [--skip-discuss] [--skip-research]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-plan — 规划阶段

## 目的

为指定阶段创建可执行的 PLAN.md。启动 nx-planner agent 执行任务分解、依赖分析和 Wave 分组。

## 前置条件

- `.planning/ROADMAP.md` 必须存在
- 建议已有 `.planning/phases/<N>/CONTEXT.md`（可通过 `--skip-discuss` 跳过）

## 流程

### 第一步：加载输入

1. 读取 `.planning/ROADMAP.md` — 阶段描述和范围
2. 读取 `.planning/phases/<N>/CONTEXT.md`（如果存在）— 用户决策
3. 读取 `.planning/PROJECT.md` — 全局约束

### 第二步：讨论检查与研究（可选）

如果 `--skip-discuss` 未设置且没有 CONTEXT.md，先提示用户执行讨论阶段：
- 提示用户先用 `/nx-discuss <N>` 讨论
- 如果用户选择直接规划：设置 `--skip-discuss` 继续

如果 `--skip-research` 未设置且阶段涉及新技术选型，使用 `Agent` 工具启动 nx-researcher agent：
```
Agent(
  nx-researcher,
  研究方向：[根据阶段描述提取的技术调研方向]
  阶段范围：阶段 <N>
  项目上下文：[PROJECT.md 的关键约束]
)
```
nx-researcher 输出作为 RESEARCH.md 参考。

**如果研究失败或 nx-researcher 不可用**：记录到输出摘要中，继续规划（不阻塞），在规划摘要中标注"研究未完成，选型可能需要后续确认"。

### 第三步：启动 nx-planner agent 进行任务分解

使用 `Agent` 工具启动 nx-planner agent，委托任务分解和 Wave 分组：

```
Agent(
  nx-planner,
  阶段号：<N>
  阶段描述：[来自 ROADMAP.md 的阶段范围和交付物]
  上下文：[来自 CONTEXT.md 的关键决策（如果有）]
  研究成果：[来自 RESEARCH.md 的关键发现（如果有）]
)
```

**注意**：任务分解、依赖分析和 PLAN.md 生成均由 nx-planner agent 完成。本命令只负责上下文加载和结果展示。

### 第四步：展示规划摘要

Agent 完成后，读取 `.planning/phases/<N>/PLAN.md`，显示摘要：

```markdown
## 规划完成 — 阶段 <N>

任务数：X（Wave 1: Y, Wave 2: Z, Wave 3: W）
预估工作量：[人天]

### 下一步

执行 `/nx-check-plan <N>` 审核计划质量（推荐）
或 `/nx-exec <N>` 跳过审核直接执行
```

**如果跳过了讨论（--skip-discuss 或无 CONTEXT.md）：**

在摘要中追加：
```markdown
> ⚠️ 本阶段未经过讨论，建议先 `/nx-discuss <N>` 澄清关键决策后再执行。
> 或者直接 `/nx-check-plan <N>` 开始审核。
```

**如果研究失败或跳过：**

追加：
```markdown
> ⚠️ 研究未完成，部分技术选型可能需要后续确认。建议执行前人工审查。
```
