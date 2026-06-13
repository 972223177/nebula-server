---
description: 一键完成阶段，自动串联讨论→规划→审核→执行→验证→完成
argument-hint: [阶段号] [--skip-discuss] [--skip-check] [--auto] [--snapshot] [--interactive]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, TeamCreate, SendMessage, AskUserQuestion
---

# /nx-phase — 一键完成阶段

## 目的

自动串联完整的阶段生命周期：discuss → plan → check-plan → exec → validate → verify → done。遇到 BLOCKER 时暂停询问用户，用户可选择修复后继续或退出。

## 前置条件

- `.planning/ROADMAP.md` 必须存在
- `.planning/PROJECT.md` 必须存在

## 参数

| 参数 | 说明 |
|------|------|
| `<N>` | 阶段号（必需） |
| `--skip-discuss` | 跳过讨论阶段，直接规划 |
| `--skip-check` | 跳过规划审核，直接执行 |
| `--auto` | 全自动模式，遇到 BLOCKER 自动中止而非询问 |
| `--snapshot` | 执行前创建 git 快照，失败可回滚 |
| `--interactive` | 执行阶段使用交互模式（非并行） |

## 流程

### 第一阶段：准备

1. 读取 `.planning/ROADMAP.md` 确认阶段存在
2. 读取 `.planning/PROJECT.md` 了解全局约束
3. 输出阶段概要，确认开始：
```markdown
## 开始阶段 <N>

将依次执行：
1. 讨论（如未跳过）
2. 规划
3. 规划审核（如未跳过）
4. 并行执行
5. 代码验证
6. 目标验证
7. 完成归档

预估步骤：[X] 步
模式：[自动/交互]
```

### 第二阶段：讨论（可选）

如果未设置 `--skip-discuss`：
1. 检查 `.planning/phases/<N>/CONTEXT.md` 是否存在
   - 存在 → 跳过讨论
   - 不存在 → 执行 `/nx-discuss <N>` 流程
2. 如用户选择跳过 → 继续

### 第三阶段：规划

执行规划流程：
1. 加载上下文（ROADMAP.md + CONTEXT.md）
2. 启动 nx-planner agent 执行任务分解
3. 展示 PLAN.md 摘要

### 第四阶段：规划审核（质量网关 1）

如果未设置 `--skip-check`：
1. 启动 nx-plan-checker agent 审核 PLAN.md
2. 如有 BLOCKER：
   - 展示 BLOCKER 详情
   - `--auto` 模式 → 中止，提示用户手动修复
   - 非 auto 模式 → 询问用户是否修改计划
     - 是 → 回到第三阶段重新规划
     - 否 → 跳过 BLOCKER（不推荐，二次确认）
3. 如无 BLOCKER → 继续

### 第五阶段：执行

1. 调用 `/nx-exec <N>` 流程
2. 按 Wave 分组并行执行
3. 每 Wave 完成后运行编译检查
4. 如 Wave 中有失败任务：
   - `--auto` 模式 → 自动重试一次，仍失败则中止
   - 非 auto 模式 → 询问用户处理方式（重试/回滚/跳过）
5. 全部完成 → 继续

### 第六阶段：代码验证（质量网关 2）

1. 运行测试（如果有）
2. 启动 nx-code-validator agent 扫描代码质量
3. 如有 BLOCKER：
   - `--auto` 模式 → 记录到技术债务，不中止
   - 非 auto 模式 → 询问用户是否生成 gap_closure 修复
     - 是 → 生成修复任务，提示后续执行 `/nx-exec <N> --gaps-only`
     - 否 → 记录到技术债务，继续
4. 如无 BLOCKER → 继续

### 第七阶段：目标验证（质量网关 3）

1. 启动 nx-verifier agent 执行 goal-backward 验证
2. 如有未通过：
   - `--auto` 模式 → 中止，提示用户手动修复
   - 非 auto 模式 → 询问用户是否修复
     - 是 → 生成 gap_closure，提示后续执行 `/nx-exec <N> --gaps-only`
     - 否 → 记录未完成项到 STATE.md
3. 全部通过 → 继续

### 第八阶段：完成

1. 执行 `/nx-done <N>` 流程
2. 更新 PROJECT.md → 移动已验证需求
3. 更新 STATE.md → 记录完成状态

### 输出最终摘要

```markdown
## ✅ 阶段 <N> 完成

### 过程摘要
| 步骤 | 结果 |
|------|------|
| 讨论 | ✅ / ⏭️ 跳过 |
| 规划 | ✅ / ⚠️ 有问题 |
| 审核 | ✅ / ⏭️ 跳过 |
| 执行 | ✅ X/Y 任务 |
| 代码验证 | ✅ / ⚠️ |
| 目标验证 | ✅ / ⚠️ |
| 归档 | ✅ |

### 需要关注
- [如有未完成的 gap_closure]
- [如有记录的技术债务]

下一阶段：阶段 <N+1>
命令：`/nx-phase <N+1>` 或 `/nx-status`
```
