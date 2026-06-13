---
description: 审核 PLAN.md 计划，验证计划能达成阶段目标
argument-hint: [阶段号]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-check-plan — 规划审核

## 目的

在执行前审核 PLAN.md，验证计划能达成阶段目标。
发现 BLOCKER 问题时阻止执行，发现 WARNING 时建议修改。
是规划与执行之间的质量网关。

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 建议先执行 `/nx-plan <N>` 生成了计划
- 可选：已执行 `/nx-discuss <N>` 生成 CONTEXT.md

## 流程

### 第一步：加载审核上下文

1. 读取 `.planning/phases/<N>/PLAN.md` — 待审核的计划
2. 读取 `.planning/phases/<N>/CONTEXT.md`（如果存在）
3. 读取 `.planning/ROADMAP.md` — 阶段定义
4. 读取 `.planning/PROJECT.md` — 全局约束

### 第二步：启动 nx-plan-checker agent

使用 `Agent` 工具启动 nx-plan-checker agent：

```
Agent(
  nx-plan-checker,
  审核阶段 <N> 的计划，检查需求覆盖、任务完整性、依赖正确性、范围合理性、验收标准可验证性、上下文合规
)
```

### 第三步：阅读审核报告

Agent 输出后，读取 `.planning/phases/<N>/PLAN-REVIEW.md`。

### 第四步：向用户展示结果

#### 审核通过

```markdown
## ✅ 审核通过 — 阶段 <N> (Gate: Revision)

| 维度 | 结果 |
|------|------|
| 需求覆盖 | ✅ |
| 任务完整性 | ✅ |
| 依赖正确性 | ✅ |
| 范围合理性 | ✅ |
| 验收标准可验证性 | ✅ |
| 上下文合规 | ✅ |

所有维度通过。可以执行。

下一步：执行 `/nx-exec <N>` 开始执行此阶段
```

#### 审核有问题

```markdown
## ⚠️ 审核发现问题 — 阶段 <N>

### ❌ BLOCKER（必须修复）
[X 个]

### ⚠️ WARNING（建议修复）
[Y 个]

---

是否修改计划后重新提交审核？
```

- 用户选"是" → 提示 `/nx-plan <N>` 修改计划
- 用户选"否，直接执行" → 提示 `/nx-exec <N>` 执行（忽略 WARNING）
- 如果有 BLOCKER 且用户选择直接执行 → 再次确认："有 X 个 BLOCKER 问题，确定跳过审核直接执行？"

### 第五步：更新 STATE.md

如果审核通过（或用户选择跳过），在 STATE.md 中更新审核状态。
