# 星云工作流 (Nebula Workflow)

一个 CodeBuddy Code 插件，提供基于"状态外置 + 上下文隔离 + 多 agent 协同"理念的结构化开发工作流。

## 设计理念

借鉴 GSD (Get-Shit-Done) 的核心思想，但使用 CodeBuddy 原生能力重新实现：

| 理念 | 说明 |
|---|---|
| **状态外置** | 项目状态持久化在 `.planning/` 文件系统中，对话仅是"窗口" |
| **上下文隔离** | 每个命令/子 agent 使用干净上下文，互不干扰 |
| **多 agent 协同** | 利用 TeamCreate 并行执行，比手动 Agent 调用更可靠 |
| **验证闭环** | 规划 → 执行 → 验证 → 修正，循环直到通过 |
| **渐进式披露** | 每次只加载当前阶段所需的最小文档集 |

## 核心命令

| 命令 | 功能 | 前置 |
|---|---|---|
| `/nx-init` | 初始化项目，生成 .planning/ 文档 | — |
| `/nx-discuss <阶段>` | 讨论阶段边界，做决策记录 | ROADMAP.md |
| `/nx-plan <阶段>` | 启动 nx-planner agent 规划阶段，生成 PLAN.md | ROADMAP.md |
| `/nx-check-plan <阶段>` | 规划审核（质量网关1），验证计划能达成目标 | PLAN.md |
| `/nx-exec <阶段>` | 并行执行计划，含编译检查和 git 回滚 | PLAN.md |
| `/nx-validate <阶段>` | 代码验证（质量网关2），运行测试 + 扫描反模式/stub/技术债务 | 执行完成 |
| `/nx-verify <阶段>` | 目标验证（质量网关3），goal-backward 验证阶段目标达成 | 执行完成 |
| `/nx-done <阶段>` | 完成里程碑 | 需通过验证 |
| `/nx-phase <阶段>` | **一键完成阶段**，自动串联全部步骤 | ROADMAP.md |
| `/nx-pause` | **暂停工作流**，状态写入 PAUSE.md | 任意 |
| `/nx-resume` | **恢复工作流**，从 PAUSE.md 断点继续 | PAUSE.md |
| `/nx-status` | 查看项目全景状态 | STATE.md |

## 工作流

三个质量网关确保交付质量：

1. **规划审核** (`/nx-check-plan`) — 执行前验证计划完整性
2. **代码验证** (`/nx-validate`) — 执行后运行测试 + 验证代码质量
3. **目标验证** (`/nx-verify`) — 完成前验证目标达成

```
/nx-init → /nx-discuss → /nx-plan → /nx-check-plan → /nx-exec → /nx-validate → /nx-verify → /nx-done
                                                                   ↑                        |
                                                                   └── 失败 → 修复后再试 ────┘

一键模式：/nx-phase <N>  （推荐）
暂停/恢复：/nx-pause → /nx-resume
```

## 快速开始

### 安装

```bash
# 在当前项目中测试
cc --plugin-dir /path/to/nebula-workflow

# 或复制到项目
cp -r nebula-workflow /your-project/.codebuddy-plugin/
```

### 使用

```bash
# 1. 初始化新项目
/nx-init 一个博客系统

# 2. 一键完成阶段 1（推荐）
/nx-phase 1

# 或手动逐步执行：
# /nx-plan 1
# /nx-check-plan 1
# /nx-exec 1
# /nx-validate 1
# /nx-verify 1
# /nx-done 1
```

## .planning/ 文档体系

插件直接复用 GSD 的文档格式：

- `.planning/PROJECT.md` — 项目背景、核心价值、约束
- `.planning/REQUIREMENTS.md` — 需求列表（已验证/活跃/范围外）
- `.planning/ROADMAP.md` — 阶段规划
- `.planning/STATE.md` — 项目状态文件
- `.planning/config.json` — 工作流配置
- `.planning/phases/<N>/PLAN.md` — 阶段执行计划
- `.planning/phases/<N>/CONTEXT.md` — 阶段讨论上下文

## 兼容性

完全兼容现有 GSD 生成的 `.planning/` 项目。如果你已有 GSD 项目，直接使用 `/nx-status` 即可查看状态。首次运行时会自动检测 GSD 目录格式并构建阶段映射，之后所有命令无缝工作。

**兼容的关键设计：**
- 自动识别 GSD 的 `NN-description/` 格式目录和 `NN-FILENAME.md` 格式文件名
- 读取时先查 nebula 格式，再 fallback 到 GSD 格式
- 写入时统一使用 nebula 原生格式
- GSD 的多文件 PLAN 自动聚合为单一视图

新建阶段的目录沿用项目已建立的命名风格（GSD 项目保持 `0<N>-<name>/` 格式）。
