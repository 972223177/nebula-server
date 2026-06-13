---
name: 星云核心
description: This skill should be used when the user is working with the nebula-workflow plugin, discussing "星云工作流", "状态外置", "上下文隔离", "多 agent 协同", "验证闭环", or when the commands /nx-init, /nx-plan, /nx-exec, /nx-verify, /nx-discuss, /nx-status, /nx-done are invoked. Provides domain knowledge about the workflow methodology, document formats, and best practices.
version: 0.1.0
---

# 星云核心 — 工作流方法论

## 核心设计原则

### 状态外置

项目的所有状态持久化在 `.planning/` 文件系统目录中，而非依赖对话历史。

**关键实践：**
- 每次 commmand 执行前，先读取 `.planning/` 中的相关文档获取当前状态
- 每次 command 执行后，更新 `.planning/` 中的文档反映最新状态
- 对话只是"窗口" — 一次对话结束后，下一次对话加载文件状态继续

### 上下文隔离

每个 command 和每个 subagent 在独立的上下文中工作，只加载所需的最小文档集。

**关键实践：**
- nx-executor 只读自己分配的任务片段，不读整个 PLAN.md
- 不跨任务共享上下文
- 不使用对话历史中的上下文，仅使用文件中的最新状态

### 多 agent 协同

复杂的 stage（如规划、执行）使用 TeamCreate 实现多 agent 并行工作。

**关键实践：**
- 每个 agent 有明确的单一日标
- agent 之间通过文件系统通信（写入文档），而非通过消息传递
- 一个 agent 的输出是另一个 agent 的输入（通过文件）

### 验证闭环

每个阶段都遵循「规划 → 执行 → 验证 → 修复」的闭环。

**关键实践：**
- 规划阶段定义的验收标准是验证的唯一依据
- 发现问题不跳过，生成 gap_closure 修复计划
- 全部验证通过后才能完成里程碑

## 工作流状态机

```
未初始化 ──→ 已初始化 ──→ 已规划 ──→ 执行中 ──→ 已验证 ──→ 已完成
  │                                                            │
  └── /nx-init                                                  │
                  └── /nx-plan                                   │
                                 └── /nx-exec                    │
                                                  └── /nx-verify│
                                                                  └── /nx-done
```

每个状态的转换：
- **未初始化 → 已初始化**: `/nx-init`
- **已初始化 → 已规划**: `/nx-plan <N>`（需 ROADMAP.md）
- **已规划 → 执行中**: `/nx-exec <N>`（需 PLAN.md）
- **执行中 → 已验证**: `/nx-verify <N>`（需执行完成）
- **已验证 → 已完成**: `/nx-done <N>`（需验证通过）
- **执行中 → 执行中**: `/nx-exec <N> --gaps-only`（修复任务）
- **已验证 → 执行中**: 如需手动修正

## 最佳实践

### 文档质量要求

- PROJECT.md 保持时效性，每次完成阶段后更新
- PLAN.md 的验收标准必须可验证（文件存在、接口存在、行为可观测）
- STATE.md 必须反映真实状态

### 失败的 task 处理

1. 避免在出错的上下文中反复重试（上下文已污染）
2. 先 `/nx-status` 查看全景
3. 用新鲜上下文执行修复命令
4. 必要时重新规划（`/nx-plan <N>`）

### 架构原则

- 不要在一个 command 里做太多事 — 分散到子 agent 中
- 不要在 subagent 中交互 — subagent 只读写文件和执行代码
- 只在 command 层面与用户交互（AskUserQuestion）

## 资源引用

### Reference Files

- **`references/document-formats.md`** — .planning/ 目录下各文档的格式规范
- **`references/workflow.md`** — 完整工作流参考（含示例）

### Agents

- **`../../../agents/nx-researcher.md`** — 技术研究 agent
- **`../../../agents/nx-planner.md`** — 规划 agent
- **`../../../agents/nx-executor.md`** — 执行 agent
