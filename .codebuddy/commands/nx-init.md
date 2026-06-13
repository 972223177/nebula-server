---
description: 初始化新项目：需求收集 → 技术研究 → 路线图生成
argument-hint: "[项目描述]"
---

# 项目初始化

## 目标
基于用户提供的项目描述，生成完整的 GSD 兼容项目结构：PROJECT.md、REQUIREMENTS.md、ROADMAP.md、STATE.md、config.json。

## 参数
- `$ARGUMENTS`：项目描述（可选，如未提供则询问用户）

## 流程

### 步骤 1：收集项目信息
向用户了解：
- 项目类型（backend/frontend/fullstack）
- 技术栈（语言、框架、数据库）
- 核心功能列表
- 特殊约束（性能要求、合规要求等）

### 步骤 2：创建项目文档

**PROJECT.md**——项目定义：
```yaml
---
project_type: backend|frontend|fullstack
language: <主语言>
framework: <主框架>
---
# <项目名称>
## 概述
## 技术栈
## 核心约束
## 关键设计决策
```

**REQUIREMENTS.md**——需求映射：
- 将功能列表映射为可追踪的需求项
- 每个需求标注优先级（P0/P1/P2）
- 标注需求间的依赖关系

**ROADMAP.md**——阶段划分：
- 将需求按依赖关系和优先级分组为 5-15 个阶段
- 每阶段包含目标、需求列表、预估计划数
- 标注阶段间的依赖和 Wave 分组建议

**STATE.md**——状态跟踪：
```yaml
---
gsd_state_version: 1.0
project_type: <backend|frontend|fullstack>
language: <主语言>
framework: <主框架>
milestone: v1.0
---
# State: <项目名称>
## Phase Status
（自动生成的阶段状态表）
```

**config.json**——工作流配置：
```json
{
  "mode": "interactive",
  "granularity": "fine",
  "parallelization": true,
  "commit_docs": true,
  "model_profile": "balanced",
  "workflow": {
    "research": true,
    "plan_check": true,
    "verifier": true,
    "nyquist_validation": true,
    "auto_advance": false
  }
}
```

### 步骤 3：创建阶段目录
按 ROADMAP.md 的分组创建 `.planning/phases/` 目录结构：
```
.planning/phases/
├── 01-<phase-name>/
├── 02-<phase-name>/
...
```

### 步骤 4：首次提交
```bash
git add .planning/ CODEBUDDY.md
git commit -m "init: 项目初始化 —— GSD 兼容结构 + 阶段规划"
```

## 成功标准
- 所有 5 个核心文件（PROJECT/REQUIREMENTS/ROADMAP/STATE/config）已创建
- 阶段目录结构已建立
- 首次 commit 已完成
