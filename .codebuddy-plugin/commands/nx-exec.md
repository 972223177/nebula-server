---
description: 执行计划，按 wave 分组并行执行任务
argument-hint: [阶段号] [--wave N] [--gaps-only] [--interactive]
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent, Task, TeamCreate, SendMessage
---

# /nx-exec — 执行计划

## 目的

读取 PLAN.md，按依赖关系分组并行执行任务。
核心设计：每个任务在独立上下文中执行，上下文仅包含该任务所需的 PLAN 片段。

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 任务需包含验收标准

## 流程

### 第一步：加载计划

1. 读取 `.planning/phases/<N>/PLAN.md`
2. 解析任务列表和 wave 分组
3. 如果指定 `--wave N`：仅加载该 wave
4. 如果指定 `--gaps-only`：仅加载 `gap_closure: true` 的任务
5. 如果没有任何任务需要执行 → 报告并退出

### 第二步：按 Wave 逐批执行

#### 初始化执行状态

在 PLAN.md 中每个任务添加执行状态追踪字段（如果尚未存在）：
```yaml
**状态**: 待开始 | 执行中 | 已完成 | 失败
**结果**: [执行完成时记录]
```

#### Wave 内并行（使用 TeamCreate）

对每个 Wave：

1. **创建 Team**: `TeamCreate("nx-exec-wave-<N>", "执行 Wave N 的任务")`
2. **创建任务**: 为 wave 内的每个独立任务创建 Task
3. **启动执行 agent**: 对每个任务，启动 nx-executor agent 并分配任务

```
Agent(
  nx-executor,
  执行任务 X.Y：[任务描述]
  验收标准：[列表]
  关联文件：[路径]
  team_name: "nx-exec-wave-<N>"
)
```

**每个 executor 的上下文限制**：
- 只读该任务对应的 PLAN.md 片段
- 只读任务相关的源文件
- 不读整个的 .planning/ 文档
- 不访问其他任务

4. **等待 wave 完成**：监听从各 executor 返回的结果
5. **更新任务状态**：在 PLAN.md 中标记完成/失败

#### Wave 间串行

Wave N 全部完成后，再启动 Wave N+1。
（因为 Wave N+1 依赖 Wave N 的输出）

#### `--interactive` 模式

不使用 TeamCreate，而是顺序执行每个任务，在每个任务开始前询问用户确认：
```markdown
即将执行：任务 X.Y
确认开始？(y/n)
```
适合小型阶段、调试、低 token 消耗场景。

### 第三步：Wave 完成检查

每完成一个 Wave，检查：
1. 所有任务是否完成
2. 完成的任务是否符合验收标准
3. 是否有失败任务需要处理

如果某个 wave 内有失败任务：
- 询问用户：重试、跳过、还是修改计划
- 重试 → 重新启动该任务的 executor
- 跳过 → 标记为已跳过并在最后报告
- 修改 → 提示用 `/nx-plan <N>` 重新规划

### 第四步：执行摘要

所有 waves 完成后，输出：
```markdown
## 执行完成 — 阶段 <N>

Wave 1: ✅ X/Y 完成
Wave 2: ✅ X/Y 完成
...

总计：✅ X/Y 任务完成

下一步：执行 `/nx-verify <N>` 验证实现
```
