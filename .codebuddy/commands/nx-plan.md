---
description: 阶段规划 —— 研究 → 模式映射 → 生成 PLAN.md → 审核闭环
argument-hint: "<N> [--force] [--gaps] [--skip-research] [--skip-verify]"
---

# 阶段规划

## 目标
为阶段 N 创建可执行的 PLAN.md，包含技术研究、代码模式映射、任务分解和计划审核。

## 参数
- `$ARGUMENTS`：阶段编号 N（可选，默认取 STATE.md 中第一个 Pending 阶段）
- `--force`：对已完成阶段强制执行 replan（覆盖 Closed-Phase Gate）
- `--gaps`：仅生成 gap_closure 类型计划（用于验证→修复闭环）。自动跳过 Research 和 Pattern Mapping 步骤
- `--skip-research`：跳过研究阶段，直接规划
- `--skip-verify`：跳过计划审核循环

## 门禁检查

### Pre-flight Gate
```bash
# 检查 CONTEXT.md 存在
if [ ! -f ".planning/phases/0${N}-*/0${N}-CONTEXT.md" ]; then
  提示: "CONTEXT.md 不存在，建议先执行 /nx-discuss N。继续将基于 ROADMAP.md 进行盲规划。"
fi
```

### Closed-Phase Gate
```bash
# 检查阶段状态
PHASE_STATUS=$(从 STATE.md 获取阶段 N 的状态)
if [ "$PHASE_STATUS" = "Complete" ] && [ "$FORCE" != "true" ]; then
  报错: "阶段 N 已完成。如需覆盖，使用: /nx-plan N --force"
  exit 1
fi
if [ "$PHASE_STATUS" = "Complete" ] && [ "$FORCE" = "true" ]; then
  警告: "在 --force 下重新规划已关闭阶段 N，提交前请确认。"
fi
```

## 流程

### 步骤 0：Gap 闭合模式检查

```bash
if [ "$GAPS" = "true" ]; then
  读取 VERIFICATION.md 中的 gap 列表
  跳过 Research 和 Pattern Mapping
  直接进入 PLAN 生成（所有计划 type: gap_closure）
fi
```

### 步骤 1：技术研究
派发 `nx-researcher` agent：
- 读取 CONTEXT.md 中的技术决策
- 搜索相关技术文档和最佳实践
- 提供技术方案建议和实现路径
- 输出：`NN-RESEARCH.md`

### 步骤 2：代码模式映射
派发 `nx-pattern-mapper` agent：
- 分析已有代码中的模式（Service/Repository/Handler 等）
- 为每个新需求匹配最接近的已有实现模板
- 输出：`NN-PATTERNS.md`

### 步骤 3：生成 PLAN.md
派发 `nx-planner` agent，基于 RESEARCH.md + PATTERNS.md：
- 将阶段需求分解为 1-5 个执行计划
- 每个计划包含有序任务列表
- 按依赖关系分组为 Wave
- 定义每个计划的验证标准和成功条件

输出格式：
```markdown
---
phase: N
plan: N-M
type: implementation|refactor|gap_closure
wave: M
depends_on: []
---
# Plan N-M: <计划名称>

## 目标
<计划目标>

## 任务
| # | 类型 | 文件 | 操作 | 验证 | 验收标准 |
|---|------|------|------|------|---------|
| 1 | create | path | <操作描述> | <验证方法> | <验收标准> |

## Wave 分组
- Wave 1: 计划 N-1, N-2（无依赖，可并行）
- Wave 2: 计划 N-3（依赖 Wave 1）

## 验证
<整体验证步骤>

## 成功标准
<可衡量的完成标准>
```

### 步骤 5：基础门禁审核

派发 `nx-plan-checker` agent 对 PLAN.md 进行基础门禁审核：
- 完整性：所有需求是否被覆盖
- 可行性：任务粒度是否合理（每个任务 < 50 行或等价复杂度）
- 一致性：与 PATTERNS.md 中的已有模式是否一致

最多 2 次审核迭代，通过后进入下一步。

> **基础审核 vs 深度二审**：本步骤聚焦于 PLAN 的可执行性验证。如需深度二审（跨计划契约检查、长尾风险分析、与历史阶段的偏离审查），请使用独立的 `/nx-check-plan N` 命令。

### 步骤 5：呈现结果
- 展示 PLAN.md 摘要
- 标注关键风险和依赖
- 询问用户是否继续执行

## Agent Handoff 契约
Planner 必须输出给 Executor 的字段：
- `<objective>`：计划目标
- `<tasks>`：有序任务列表（type/files/action/verify/acceptance_criteria）
- `<wave>`：Wave 分组
- `<depends_on>`：依赖的计划 ID
- `<success_criteria>`：可衡量的完成标准
- `<project_type>`：backend/frontend/fullstack

## 成功标准
- RESEARCH.md 包含充分的技术分析
- PATTERNS.md 映射了代码模式
- PLAN.md 通过计划审核（VERIFICATION PASSED）
- 用户确认执行方案
