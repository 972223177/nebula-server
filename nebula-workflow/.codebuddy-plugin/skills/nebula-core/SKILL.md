---
name: 星云核心
description: This skill should be used when the user is working with the nebula-workflow plugin, discussing "星云工作流", "状态外置", "上下文隔离", "多 agent 协同", "验证闭环", or when the commands /nx-init, /nx-plan, /nx-check-plan, /nx-exec, /nx-validate, /nx-verify, /nx-discuss, /nx-status, /nx-done, /nx-phase, /nx-pause, /nx-resume, /nx-review, /nx-secure are invoked. Provides domain knowledge about the workflow methodology, document formats, and best practices.
version: 0.2.1
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

每个阶段都遵循「规划 → 审核 → 执行 → 验证质量 → 验证目标」的闭环，包含三个质量网关：

**质量网关 1 — 规划审核（`/nx-check-plan`）**
执行前验证 PLAN.md 是否能达成阶段目标。检查需求覆盖、任务完整性、依赖正确性、范围合理性。发现 BLOCKER 阻止执行。

**质量网关 2 — 代码验证（`/nx-validate`）**
执行后验证代码质量。扫描反模式、存根代码、错误处理缺失、测试覆盖不足。发现技术债务记录到 PROJECT.md。

**质量网关 3 — 目标验证（`/nx-verify`）**
完成前验证阶段目标在代码库中真正实现。采用 goal-backward 方法：从"阶段应交付什么"出发，验证代码库中是否真实存在。

**补充质量网关：**

除了以上三个核心网关，还可按需启用：

| 网关 | 命令 | 定位 | 输出文件 | 适用场景 |
|------|------|------|---------|---------|
| 代码审查 | `/nx-review` | 查找 bug、安全漏洞、代码质量缺陷 | REVIEW.md | 安全敏感模块、复杂业务逻辑 |
| 安全审计 | `/nx-secure` | 验证威胁缓解措施已实现 | SECURITY.md | 认证/授权/数据存储/网络通信 |

这两个是可选的补充网关，在 `/nx-validate` 之前或并行执行，用于加强安全敏感性阶段的审查。

**关键实践：**
- 规划阶段定义的验收标准是验证的唯一依据
- 发现问题不跳过，生成 gap_closure 修复计划
- 全部验证通过后才能完成里程碑

### Gate 映射表

每个命令对应一种 Gate 类型，决定验证失败时的行为：

| 命令 | Gate 类型 | 检查对象 | 失败行为 |
|------|----------|---------|---------|
| `/nx-plan` 入口 | Pre-flight | ROADMAP.md | 阻止并提示 |
| `/nx-check-plan` | Revision | PLAN.md | 返回 nx-planner 修改（最多 3 次） |
| `/nx-exec` 入口 | Pre-flight | PLAN.md | 阻止并提示 |
| `/nx-validate` | Revision | 代码质量 | 追加 gap_closure 任务 |
| `/nx-verify` | Revision → Escalation | 阶段目标达成 | 通过则继续，失败则升级为人工决策 |
| `/nx-review` | Revision | 代码缺陷 | 追加 gap_closure 或记录技术债务 |
| `/nx-secure` | Revision | 威胁缓解 | 生成 gap_closure 或接受风险 |
| STATE.md 异常 | Abort | 状态文件 | 立即停止，输出诊断 |

## 工作流状态机

```
未初始化 → 已初始化 → [已讨论] ⇢ 已规划 → 已审核 → 执行中 → 已验证 → 已完成
  │                    │ 虚线 = 可选                                    │
  └── /nx-init         │  可通过 --skip-discuss 跳过                    │
                       └── /nx-discuss                                   │
                                       └── /nx-plan                       │
                                                └── /nx-check-plan         │
                                                              └── /nx-exec │
                                                                       └── /nx-validate
                                                                           → /nx-verify │
                                                                                         └── /nx-done

暂停/恢复路径（可在任意状态进入/退出）：
  [当前状态] ⬌ 已暂停
     │            │
     └── /nx-pause ┘── /nx-resume
```

每个状态的转换：
- **未初始化 → 已初始化**: `/nx-init`
- **已初始化 → [已讨论]**: `/nx-discuss <N>`（可选，生成 CONTEXT.md）
- **已初始化/已讨论 → 已规划**: `/nx-plan <N>`（需 ROADMAP.md）
- **已规划 → 已审核**: `/nx-check-plan <N>`（需 PLAN.md）
- **已审核 → 执行中**: `/nx-exec <N>`（需审核通过或跳过）
- **执行中 → 已验证**: `/nx-validate <N>` + `/nx-verify <N>`（需执行完成）
- **已验证 → 已完成**: `/nx-done <N>`（需验证通过）
- **执行中 → 执行中**: `/nx-exec <N> --gaps-only`（修复任务）
- **已规划 → 已规划**: `/nx-plan <N>`（审核发现问题后重新规划）
- **已验证 → 执行中**: 如需手动修正
- **任意状态 → 已暂停**: `/nx-pause`
- **已暂停 → 原状态恢复**: `/nx-resume`

## 最佳实践

### 文档质量要求

- PROJECT.md 保持时效性，每次完成阶段后更新
- PLAN.md 必须包含 `must_haves`（truths/artifacts/key_links），验收标准必须可验证（文件存在、接口存在、行为可观测）
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

## GSD 兼容性

Nebula-workflow 完全兼容 GSD 生成的 `.planning/` 项目。GSD 使用 `NN-description/` 格式的目录名和 `NN-FILENAME.md` 格式的文件名，与本插件的纯数字目录和短文件名不同。以下规则确保所有命令在两种格式下都能正常工作。

### 阶段目录解析规则

当命令参数为阶段号 `<N>`（如 `/nx-plan 1`）时，按以下优先级查找阶段目录：

```
1. 读取 STATE.md 的"阶段目录映射"表，直接查表获取目录名
2. 如果 STATE.md 没有映射表，扫描 .planning/phases/ 目录：
   a. 精确匹配 <N>/（如 phases/1/）
   b. 前缀匹配 0<N>-（如 phases/01-）
   c. 前缀匹配 <N>-（如 phases/1-）
   d. 前缀匹配 <N>_（如 phases/01_）
3. 找到的目录名作为该阶段的真实路径使用
```

### 文件名解析规则

在阶段目录内查找文件时，按以下优先级：

```
1. 先查 nebula 原生格式：直接查找目标文件名
   例：CONTEXT.md、PLAN.md、RESEARCH.md、VALIDATION.md、VERIFICATION.md
2. 如果不存在，查 GSD 格式：NN-前缀 + 文件名
   例：01-CONTEXT.md、01-RESEARCH.md
```

**PLAN.md 特殊处理：**
- 如果 `PLAN.md` 不存在，查找所有 `NN-*-PLAN.md` 文件（如 `01-01-PLAN.md`、`01-02-PLAN.md`）
- 将多个 GSD PLAN 文件聚合为一个逻辑视图读取（每个 GSD PLAN 对应一个任务）
- 聚合后的任务列表用于 `/nx-exec` 等命令

### 文件操作规则

| 操作 | 规则 |
|------|------|
| **读取** | 按上述 fallback 规则，先查 nebula 格式，再查 GSD 格式 |
| **写入新文件** | 始终使用 nebula 原生格式（`FILENAME.md`），不添加阶段前缀 |
| **写入的 PLAN.md** | 使用单文件 nebula 格式（一个阶段一个 PLAN.md），而非 GSD 的多文件格式 |
| **GSD 已有文件** | 可继续读取，新生成的文件用 nebula 格式并存 |

### GSD 项目中的目录创建

在已有 GSD 目录的项目中创建新阶段目录：
- 目录名沿用 GSD 风格：`0<N>-<英文小写名>/`（如 `07-session-management/`）
- 写入 STATE.md 的"阶段目录映射"表
- 阶段内文件使用 nebula 格式（不带前缀）

### 首次使用的自动适配

当在 GSD 项目中首次使用 nebula-workflow 命令时：
1. `/nx-status` 自动扫描 `.planning/phases/` 目录
2. 识别 GSD 格式的目录名（`NN-description`）
3. 构建"阶段目录映射"表并写入 STATE.md
4. 之后所有命令都通过映射表查找目录，零摩擦

## 资源引用

### Reference Files

- **`references/document-formats.md`** — .planning/ 目录下各文档的格式规范
- **`references/workflow.md`** — 完整工作流参考（含示例）

### Agents

- **`../../../agents/nx-researcher.md`** — 技术研究 agent
- **`../../../agents/nx-planner.md`** — 规划 agent
- **`../../../agents/nx-plan-checker.md`** — 规划审核 agent
- **`../../../agents/nx-executor.md`** — 执行 agent
- **`../../../agents/nx-code-validator.md`** — 代码验证 agent
- **`../../../agents/nx-code-reviewer.md`** — 代码审查 agent（补充网关）
- **`../../../agents/nx-verifier.md`** — 目标验证 agent
- **`../../../agents/nx-security-auditor.md`** — 安全审计 agent（补充网关）

### 全流程命令

- **`../../../commands/nx-phase.md`** — 一键阶段命令，自动串联 discuss→plan→check→exec→validate→verify→done

### 补充网关命令

- **`../../../commands/nx-review.md`** — 代码审查命令，查找 bug、安全漏洞、代码质量缺陷
- **`../../../commands/nx-secure.md`** — 安全审计命令，验证威胁缓解措施已实现

### 暂停/恢复

- **`../../../commands/nx-pause.md`** — 暂停工作流，将当前状态写入 PAUSE.md
- **`../../../commands/nx-resume.md`** — 恢复工作流，从 PAUSE.md 断点继续
