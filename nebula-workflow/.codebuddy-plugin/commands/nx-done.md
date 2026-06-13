---
description: 完成里程碑，归档阶段，更新项目文档
argument-hint: [阶段号]
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, AskUserQuestion
---

# /nx-done — 完成里程碑

## 目的

标记一个阶段为完成状态：归档阶段文件、更新 PROJECT.md 的需求状态、记录决策、更新 STATE.md。
是工作流循环中"收尾"的一步。

## 前置条件

- 该阶段必须通过 `/nx-verify` 验证

## 流程

### 第一步：检查前置条件

1. 读取 `.planning/phases/<N>/PLAN.md` 检查是否全部完成
2. 读取 STATE.md 检查该阶段验证状态
3. **如果阶段未通过验证** → 阻止并提示先执行 `/nx-verify <N>`

### 第二步：更新 PROJECT.md

1. 读取 `.planning/PROJECT.md`
2. 将阶段中已验证的需求从"Active"移动到"Validated"（如果有明确映射）
3. 更新 "Key Decisions" 记录本阶段的关键决策
4. 更新 "Last updated" 时间戳

### 第三步：归档阶段文件

在 `.planning/phases/<N>/` 目录中：
1. 在 PLAN.md 中标记阶段状态为"已完成"
2. 添加完成日期和总结

### 第四步：更新 STATE.md

```markdown
# 项目状态

## 当前阶段
阶段：<N+1>：[下一阶段名称]
状态：待开始

## 已完成阶段
- 阶段 <N>：[名称] ✅ [完成日期]
  - 交付：...（简短描述）
  - 决策记录：...

## 项目引用
见：.planning/PROJECT.md（更新于 [日期]）
```

### 第五步：审计检查

在完成前做一个快速审计：
1. 是否有未提交的代码改动？→ 建议先提交
2. 是否需要更新 README 或文档？→ 建议
3. 是否有技术债务需要记录？→ 建议记入 PROJECT.md 的 Context 部分
4. `.planning/PAUSE.md` 是否存在？→ 自动清理（阶段已完成）

### 第六步：输出完成总结

```markdown
## ✅ 阶段 <N> 完成

已完成任务：X
关键决策：X 条已记录
新增已验证需求：X 条

### 下一阶段：阶段 <N+1>：[名称]
待办：
1. `/nx-discuss <N+1>` — 讨论下一阶段（如果需要）
2. `/nx-plan <N+1>` — 规划下一阶段
3. 或 `/nx-status` 查看全局状态
```
