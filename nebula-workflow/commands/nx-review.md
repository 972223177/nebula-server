---
description: 审查已变更的源代码文件，查找 bug、安全问题、代码质量缺陷
argument-hint: [阶段号] [--depth quick|standard|deep] [--files 文件列表]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-review — 代码审查

## 目的

审查已变更的源代码文件，查找 bug、安全漏洞和代码质量缺陷。

在执行完成后、目标验证前运行，作为可选的补充质量网关。支持三种审查深度：
- **quick** — 模式匹配快速扫描（硬编码密钥、危险函数、调试残留）
- **standard**（默认）— 逐文件分析，含语言专项检查
- **deep** — 跨文件调用链追踪和依赖分析

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 建议先执行 `/nx-exec <N>` 完成代码实现
- 可选：SUMMARY.md 存在可自动提取关联文件列表

## 参数

| 参数 | 说明 |
|------|------|
| `<N>` | 阶段号（必填，目标阶段号，如 1/2/3） |
| `--depth <模式>` | 审查深度：`quick`、`standard`（默认）、`deep` |
| `--files <列表>` | 逗号分隔的关联文件路径（覆盖自动提取） |

## 流程

### 第一步：解析审查范围

1. 如果指定了 `--files`，使用指定文件列表
2. 否则读取 `.planning/phases/<N>/PLAN.md`，提取所有任务的关联文件
3. 过滤掉 `.planning/` 目录文件和非源码文件

### 第二步：启动 nx-code-reviewer agent

```markdown
Agent(
  nx-code-reviewer,
  审查阶段 <N> 的代码变更，
  审查深度：<quick|standard|deep>，
  关联文件：<文件路径列表>
)
```

### 第三步：阅读审查报告

Agent 输出后，读取 `.planning/phases/<N>/REVIEW.md`。

### 第四步：向用户展示结果

#### 审查干净

```markdown
## ✅ 代码审查通过 — 阶段 <N> (Gate: Revision)

**深度：** <quick|standard|deep>
**审查文件数：** <数量>

所有文件通过审查，未发现 bug、安全问题或代码质量缺陷。

下一步：执行 `/nx-verify <N>` 进行目标验证
```

#### 存在问题

```markdown
## ⚠️ 代码审查发现问题 — 阶段 <N> (Gate: Revision)

**深度：** <深度>
**审查文件数：** <数量>

| 级别 | 数量 |
|------|------|
| ❌ BLOCKER | <X> |
| ⚠️ WARNING | <Y> |
| ℹ️ INFO | <Z> |

完整报告：.planning/phases/<N>/REVIEW.md
```

### 第五步：处理审查结果

#### 如果有 BLOCKER

```markdown
❌ 发现 <X> 个 BLOCKER 问题，必须修复后才能继续。

是否修复？
1. 修复所有 BLOCKER（推荐）— 对每个 BLOCKER 执行修复
2. 仅记录不修复 — 在技术债务中记录（风险较高）
3. 忽略继续 — 跳过此质量网关
```

用户选"修复"后，逐个执行修复（优先 BLOCKER，其次 WARNING）。

#### 如果只有 WARNING/INFO

```markdown
⚠️ 发现 <Y> 个 WARNING 和 <Z> 个 INFO 建议。

是否修复或记录到技术债务？
1. 修复 WARNING — 执行修复
2. 记录到技术债务 — 追加到 PROJECT.md
3. 跳过 — 接受现状
```
