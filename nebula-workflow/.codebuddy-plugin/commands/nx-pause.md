---
description: 暂停工作流，将当前状态持久化到 PAUSE.md，下次可通过 /nx-resume 恢复
argument-hint: [可选的暂停备注]
allowed-tools: Read, Write, Grep, Glob, Bash, AskUserQuestion
---

# /nx-pause — 暂停工作流

## 目的

在阶段执行中安全暂停，将当前状态写入 `.planning/PAUSE.md`。下次执行 `/nx-resume` 即可从断点恢复。

适用于：
- 对话即将结束，需要在新对话中继续
- 阶段执行中需切换到其他紧急任务
- token 消耗较大，需要换新上下文

## 流程

### 第一步：检测当前状态

1. 读取 `.planning/STATE.md` — 当前阶段
2. 读取 `.planning/phases/<N>/PLAN.md`（如果存在）— 任务执行状态

### 第二步：收集暂停信息

解析 PLAN.md 中的任务状态，构建暂停快照：

```markdown
## 当前状态
- 阶段：<N>
- 当前 Wave：[1/2/3]
- 暂停时间：[时间戳]

## 任务状态

| 任务 | 状态 | 备注 |
|------|------|------|
| X.1 | 已完成 | |
| X.2 | 执行中 | [当前进度] |
| X.3 | 待开始 | |

## 暂停原因
[用户备注或"用户主动暂停"]

## 恢复指引
执行 `/nx-resume` 将从阶段 <N> 的任务 X.2 继续。
```

### 第三步：写入 PAUSE.md

写入 `.planning/PAUSE.md` 文件。

### 第四步：确认

```markdown
## ⏸️ 工作流已暂停

已保存暂停状态到 `.planning/PAUSE.md`

当前进度：
- 阶段：<N>
- 已完成：X/Y 任务
- 下次恢复：任务 X.Z（如未完成），将自动执行 `/nx-exec <N>`

### 下一步

恢复命令：`/nx-resume`

你现在可以安全关闭对话。下次在新对话中执行 `/nx-resume` 即可
从断点自动继续执行。
```
