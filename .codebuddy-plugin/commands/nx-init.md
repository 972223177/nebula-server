---
description: 初始化项目，生成 PROJECT.md/REQUIREMENTS.md/ROADMAP.md/STATE.md
argument-hint: [可选项目描述]
allowed-tools: Read, Write, Grep, Glob, Bash, AskUserQuestion, Task
---

# /nx-init — 初始化项目

## 目的

在 `.planning/` 目录中创建项目骨架文档，为后续工作流奠定基础。
如果 `.planning/` 已存在，询问是否重新初始化。

## 流程

### 第一步：检测状态

检查 `.planning/` 目录是否存在：
- 如果存在：列出已有文件，询问用户"已检测到 .planning/ 目录，是否重新初始化？"
  - 用户确认 → 备份旧目录（`.planning.bak.<timestamp>`）后继续
  - 用户拒绝 → 提示可用 `/nx-status` 查看状态
- 如果不存在：继续初始化

### 第二步：收集项目信息

通过 `AskUserQuestion` 逐步收集：

1. **项目名称** — 默认取当前目录名
2. **项目简介** — 2-3 句话，做什么、给谁用
3. **核心价值** — 最核心的一件事，其他都可以失败但这个不能
4. **技术栈偏好** — 框架、语言、数据库等
5. **约束条件** — 时间线、预算、兼容性等
6. **初始需求列表** — 用户提到的功能点

### 第三步：生成文档

使用 Write 工具创建以下文件：

#### `.planning/PROJECT.md`

遵循 PROJECT.md 格式（见 nebula-core skill references/document-formats.md），包含：
- 项目名称
- What This Is（2-3 句描述）
- Core Value（一句话）
- Requirements（Active 区域填入用户提出的需求）
- Constraints（技术栈、时间线等）
- Key Decisions（初始化时的关键选择）

#### `.planning/REQUIREMENTS.md`

格式：
```markdown
# 需求文档

## 已验证
（待验证）

## 活跃
- [ ] 需求 1 — 描述
- [ ] 需求 2 — 描述

## 范围外
- 排除项 — 原因
```

#### `.planning/ROADMAP.md`

根据项目复杂度生成合理的阶段划分：

```markdown
# 路线图

## 阶段 1：[名称]
范围：...
交付物：...
预估复杂度：S/M/L

## 阶段 2：[名称]
...
```

至少包含 2-3 个阶段。参考模板格式（见 references/workflow.md）。

#### `.planning/STATE.md`

```markdown
# 项目状态

## 当前阶段
阶段：无（刚初始化）
状态：未开始

## 项目引用
见：.planning/PROJECT.md（更新于 [日期]）

**核心价值：** [一句话]
**当前焦点：** 阶段 1 规划

## 完成阶段
（无）
```

#### `.planning/config.json`

```json
{
  "project_name": "...",
  "version": "0.1.0",
  "created_at": "[ISO 日期]"
}
```

### 第四步：总结

输出初始化完成摘要：

```markdown
## 初始化完成

生成的文件：
- PROJECT.md — 项目背景
- REQUIREMENTS.md — 需求列表（X 个活跃）
- ROADMAP.md — Y 个阶段
- STATE.md — 项目状态

下一步：执行 `/nx-plan 1` 开始规划第一阶段
```
