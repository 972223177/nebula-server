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
  - 同时检测是否为 GSD 项目（存在 `phases/0X-description/` 格式的目录）或 nebula 项目 → 提示相关内容
  - 用户确认 → 备份旧目录（`.planning.bak.<timestamp>`）后继续
  - 用户拒绝 → 不初始化，输出引导：
    ```markdown
    ## ⏭️ 保持现有配置

    检测到已有 `.planning/` 目录，项目已初始化。

    下一步：
    1. `/nx-status` — 查看项目全景状态
    2. `/nx-plan <N>` — 直接规划某个阶段
    3. `/nx-phase <N>` — 一键完成某个阶段
    ```
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
  "created_at": "[ISO 日期]",
  "workflow": {
    "mode": "interactive",
    "parallelism": {
      "max_agents_per_wave": 4
    },
    "quality_gates": {
      "plan_review": {
        "enabled": true,
        "auto_skip_warnings": false
      },
      "code_validation": {
        "enabled": true
      },
      "goal_verification": {
        "enabled": true
      }
    },
    "build": {
      "command": "./gradlew build -x test",
      "auto_build_check": true
    }
  },
  "agents": {
    "planner": { "model": "default" },
    "executor": { "model": "default" },
    "researcher": { "model": "lite" },
    "validator": { "model": "lite" },
    "verifier": { "model": "default" },
    "plan_checker": { "model": "lite" }
  }
}
```

**配置说明：**

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `workflow.mode` | 交互模式："interactive"（每个阶段询问确认）/ "autonomous"（全自动） | "interactive" |
| `workflow.parallelism.max_agents_per_wave` | 每个 Wave 最多并行 agent 数 | 4 |
| `workflow.quality_gates.plan_review.enabled` | 是否启用规划审核 | true |
| `workflow.quality_gates.code_validation.enabled` | 是否启用代码验证 | true |
| `workflow.quality_gates.goal_verification.enabled` | 是否启用目标验证 | true |
| `workflow.build.command` | 构建命令（编译检查用） | 项目类型相关 |
| `workflow.build.auto_build_check` | 是否在每个 Wave 后自动编译检查 | true |
| `agents.*.model` | 各 agent 使用的模型变体（default/lite/reasoning） | default |

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
