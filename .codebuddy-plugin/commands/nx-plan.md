---
description: 规划阶段，研究技术细节，生成 PLAN.md
argument-hint: [阶段号] [--skip-discuss]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-plan — 规划阶段

## 目的

为指定阶段创建可执行的 PLAN.md，包含任务分解、依赖分析、wave 分组。
可选：启动 nx-researcher agent 做技术研究。

## 前置条件

- `.planning/ROADMAP.md` 必须存在
- 建议已有 `.planning/phases/<N>/CONTEXT.md`（可通过 `--skip-discuss` 跳过）

## 流程

### 第一步：加载输入

1. 读取 `.planning/ROADMAP.md` — 阶段描述和范围
2. 读取 `.planning/phases/<N>/CONTEXT.md`（如果存在）— 用户决策
3. 读取 `.planning/PROJECT.md` — 全局约束

### 第二步：研究（可选）

如果 `--skip-discuss` 未设置且没有 CONTEXT.md，先执行讨论阶段：
- 提示用户先用 `/nx-discuss <N>` 讨论
- 如果用户选择直接规划：设置 `--skip-discuss` 继续

使用 `Agent` 工具启动 nx-researcher agent（仅当需要技术调研时）：
```
Agent(nx-researcher, 阶段 <N> 需要研究的方向: [根据阶段描述提取])
```
nx-researcher 输出作为 RESEARCH.md 参考。

### 第三步：任务分解

将阶段目标分解为具体任务。每个任务包含：

```markdown
### 任务 X.Y: [任务名]
**状态**: 待开始
**前置**: [任务 X.Z 或无]
**描述**: 需要做什么
**验收标准**:
- [ ] 标准 1
- [ ] 标准 2
**关联文件**: [文件路径列表]
**复杂度**: S/M/L
```

### 第四步：依赖分析和 Wave 分组

将任务按依赖关系分组为 waves：

- **Wave 1**：无依赖的任务（基础设施、接口定义、数据模型）
- **Wave 2**：依赖 Wave 1 的任务（业务逻辑实现）
- **Wave 3**：依赖 Wave 2 的任务（集成、UI、测试）

格式：
```yaml
waves:
  1:
    tasks: [X.1, X.2]
  2:
    tasks: [X.3, X.4]
  3:
    tasks: [X.5]
```

### 第五步：输出 PLAN.md

写入 `.planning/phases/<N>/PLAN.md`：

```markdown
# 阶段 <N> 执行计划

## 阶段信息
**路线图引用**: [来自 ROADMAP.md]
**复杂度**: S/M/L
**预估工作量**: [人天]

## 任务分解

### Wave 1: 基础设施
<!-- 无依赖任务，可并行 -->

### Wave 2: 核心逻辑

### Wave 3: 集成与验证

## 依赖图
[用文本描述依赖关系]

## 引用
- [CONTEXT.md](CONTEXT.md) — 讨论上下文
- [ROADMAP.md](../../ROADMAP.md) — 路线图
```

### 第六步：摘要

显示规划摘要：
```markdown
## 规划完成 — 阶段 <N>

任务数：X（Wave 1: Y, Wave 2: Z, Wave 3: W）
预估工作量：[人天]

下一步：执行 `/nx-exec <N>` 开始执行此阶段
执行指定 wave：`/nx-exec <N> --wave 1`
```
