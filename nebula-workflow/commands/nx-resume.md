---
description: 恢复暂停的工作流，从 PAUSE.md 断点自动继续
argument-hint: [无参数，读取 PAUSE.md 自动恢复]
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent, Task, TeamCreate, SendMessage, AskUserQuestion
---

# /nx-resume — 恢复工作流

## 目的

读取 `.planning/PAUSE.md` 中的暂停状态，从断点自动恢复执行。

## 前置条件

- `.planning/PAUSE.md` 必须存在
- 项目文件状态与暂停时一致（uncommitted 变更是允许的）

## 流程

### 第一步：检查暂停状态

1. 读取 `.planning/PAUSE.md`
2. 验证暂停信息完整性：
   - 阶段号是否有效
   - 任务列表是否与当前 PLAN.md 匹配
3. 如果 PAUSE.md 不存在 → 提示"没有检测到暂停状态"，建议 `/nx-status` 查看

### 第二步：展示恢复信息

```markdown
## ⏯️ 恢复工作流

暂停时间：[时间]
暂停阶段：<N>
暂停备注：[如有]

已完成任务：X/Y
待恢复任务：X.Z, X.W...
```

询问用户确认恢复：
- 确认 → 继续
- 取消 → 退出，PAUSE.md 保留

### 第三步：从断点继续执行

根据 PAUSE.md 中的状态，自动执行恢复流程：

1. **已完成任务** → 跳过
2. **执行中任务** → 重新启动（推荐）或从断点继续
3. **待开始任务** → 按正常流程执行

实际执行：
```bash
/nx-exec <N>
```

对于标记为"执行中"的任务，优先重新执行（避免上下文不一致）。

### 第四步：清理暂停状态

恢复执行后：
1. 保留 PAUSE.md（执行完成后再清理）
2. 如果顺利恢复 → 不自动删除，留给用户决定
3. 如果执行完成 → 在 `/nx-done` 时自动清理 PAUSE.md

### 第五步：输出恢复摘要

根据恢复后的执行结果输出不同引导：

#### 恢复执行成功

```markdown
## ⏯️ 已恢复 — 阶段 <N>

重新执行任务：
- X.Z（重新执行）
- X.W（待开始）

跳过任务：X 个（已完成）

### 下一步

按完整工作流继续：
1. 如果执行刚完成 → `/nx-validate <N>` 代码质量验证
2. 如果执行中有失败 → `/nx-exec <N> --gaps-only`
3. `/nx-status` — 查看当前进度
```

#### 用户取消恢复

```markdown
## ↩️ 取消恢复

PAUSE.md 已保留，当前状态不变。

### 下一步

准备就绪后可再次 `/nx-resume` 恢复。
或执行 `/nx-status` 查看项目全景状态。
```
