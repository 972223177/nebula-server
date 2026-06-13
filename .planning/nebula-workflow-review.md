# Nebula-Workflow 第五次审查：整体流程可行性、遗漏点与边界模糊点

**审查日期**: 2026-06-13  
**审查范围**: nebula-workflow v0.4（已实装 32 文件）的端到端可执行性与内部一致性  
**前四次审查结果**: 兼容性 ✅ / 设计思想覆盖 ✅ / 实现细节 ✅ / 审查文档同上  

---

## 审查方法论

本次审查不同于前四次"逐文件 vs GSD 对比"，而是从三个维度分析：

1. **流程可行性**：设计文档描述的流程能否在 CodeBuddy 平台实际运行？关键路径上是否有阻塞点？
2. **遗漏点**：有哪些缺失的机制或能力？
3. **边界模糊点**：Agent/命令之间的职责是否存在重叠、冲突或空白？

---

## 一、整体流程可行性

### 1.1 端到端流程

```
/nx-init → /nx-discuss → /nx-plan → /nx-check-plan → /nx-exec → /nx-verify → /nx-validate → /nx-integrate → /nx-done
```

所有命令的文件已实装，核心环闭合。以下逐一分析各步骤的可行性。

### 1.2 /nx-init — ✅ 可行

- 生成 PROJECT.md / REQUIREMENTS.md / ROADMAP.md / STATE.md / config.json
- 纯文件生成操作，不依赖 subagent
- 风险：低

### 1.3 /nx-discuss — ✅ 可行

- 加载已有上下文 → 识别灰区 → 交互式讨论 → 生成 CONTEXT.md
- 当前仅 default 模式，文档已注明"未来可扩展多模式"
- 风险：低。单模式对当前项目够用

### 1.4 /nx-plan — ✅ 可行

- 内部串行派发 4 个 subagent：nx-researcher → nx-pattern-mapper → nx-planner → nx-plan-checker
- 每个 subagent 独立上下文，通过文件通信（RESEARCH.md → PATTERNS.md → PLAN.md → PLAN-CHECK.md）
- 风险：中。串行依赖意味着总耗时 = 4 个 agent 时间之和。但由于每个阶段规模不大（1-5 计划），可接受

### 1.5 /nx-check-plan — ✅ 可行

- 独立审核 PLAN.md，含停滞检测
- 与 nx-plan 内部审核逻辑一致，但可独立调用
- 风险：低

### 1.6 /nx-exec — 🔴 存在关键实现风险

这是整个流程中**最复杂、风险最高**的环节。当前设计存在两层调度：

```
/nx-exec 命令
  └─ 创建 TeamCreate → 派发 nx-executor（调度器）
       └─ nx-executor 再创建 TeamCreate → 派发专家 agent（backend-architect / java-developer 等）
```

**风险 1: 嵌套 TeamCreate**

`nx-executor.md` 要求 executor 再创建一个 TeamCreate 来管理专家 agent。但 nx-executor 自己已经是被 nx-exec 命令通过 TeamCreate 派发的团队成员。嵌套 TeamCreate 的能力**从未被验证**。CodeBuddy 是否支持一个 agent 在团队内再创建子团队？这是一个需要实测才能确认的假设。

**建议**：在实装后第一个阶段执行时作为最优先验证项。

**风险 2: 专家 agent 的真实可用性**

nx-executor 的专家映射表引用了 20+ CodeBuddy 外部 agent：

| 引用位置 | 专家 agent | 所属插件 |
|---------|-----------|---------|
| `agents/nx-executor.md:49` | `backend-architect` | multi-platform-apps |
| `agents/nx-executor.md:50` | `java-developer` | agents-language-specialists |
| `agents/nx-executor.md:51` | `database-optimizer` | agents-infrastructure-operations |
| `agents/nx-executor.md:52` | `test-automator` | agents-quality-security |
| `agents/nx-executor.md:55` | `deployment-engineer` | full-stack-orchestration |
| `agents/nx-executor.md:56` | `sql-expert` | agents-language-specialists |
| ... | ... | ... |

这些 agent 需要在当前 CodeBuddy 环境中可用。但是：

- 它们的工具集（能 Read/Write/Edit/Bash 吗？）没有经过验证
- 某些 agent 的职责描述（如 `backend-architect` 侧重架构设计）可能与写代码的任务不匹配
- 这些 agent 的设计 prompt 可能包含不适合 Nebula 场景的假设

**建议**：在首次使用前验证 3-5 个核心专家 agent 的实际能力。

**风险 3: 专家 agent 之间的冲突**

当 Wave 内多个专家 agent 并行修改代码时：
- `backend-architect` 修改 API Route
- `java-developer` 修改 Service
- `database-optimizer` 修改 Repository

如果 Service 依赖 Repository 的接口定义，而两者并行执行，可能出现接口不匹配。当前 PLAN.md 的任务格式没有显式声明任务间的**实现契约**（如 Repository 方法的签名约定）。

**建议**：PLAN.md 增加 `contract` 字段，定义任务间的接口契约。

### 1.7 /nx-verify — ✅ 可行

- 四层验证（存在性 → 内容实在性 → 连接性 → 数据流通）
- 有具体的 bash/grep 命令，可执行性强
- 依赖 SUMMARY.md 存在（Pre-flight Gate 强制）
- 风险：低

### 1.8 /nx-validate — ⚠️ 部分可行

- 测试覆盖审计 → 差距分析 → 生成测试 → 验证生成的测试
- 步骤 5 "测试验证" 假设生成的测试能直接运行通过，实际可能需要多轮修复
- nx-nyquist-auditor agent 的约束是"不修改已有测试"，但没说如何处理生成测试失败的情况
- 风险：中。测试生成后验证可能进入修复循环

### 1.9 /nx-integrate — ✅ 可行

- 跨阶段集成检查：API 连通性 → 数据流 → DI → 编译
- 纯只读检查，不修改代码
- 风险：低

### 1.10 /nx-done — ⚠️ Completion Gate 过于严格

nx-done 要求以下文件全部存在才能归档：

```
NN-CONTEXT.md, NN-RESEARCH.md, NN-PATTERNS.md,
NN-*-PLAN.md, NN-*-SUMMARY.md, NN-VERIFICATION.md,
NN-VALIDATION.md, NN-SECURITY.md
```

但实际场景中：
- SECURITY.md 可能不适用（基础设施阶段无安全问题）
- VALIDATION.md 可能在低风险阶段被跳过
- CONTEXT.md 可能在简单阶段本质上不需要（如纯工具链配置阶段）

**建议**：将 Completion Gate 的必需文件改为**按阶段类型区分**：
- `implementation` 类型：全部 8 个文件
- `infrastructure` 类型：PLAN + SUMMARY + VERIFICATION（豁免 SECURITY）
- `config` 类型：PLAN + SUMMARY（豁免其余）

---

## 二、遗漏点

### 🔴 G1: Gap 闭合 flag 不一致

**问题**: agent-contracts.md 定义了 gap 闭合流程：

```
/nx-verify → 发现 gap → /nx-plan N --gaps → /nx-exec N --gaps-only
```

但 **nx-plan.md 不接受 `--gaps` 参数**。它只接受 `--force`、`--skip-research`、`--skip-verify`。

**影响**: gap 闭合流程无法按设计执行。从验证发现 gap 到生成 gap closure plan 的路径断裂。

**修复**: 添加 `--gaps` 到 nx-plan 支持的参数列表，或调整 gap 闭合流程为其他机制。

### 🔴 G2: 嵌套 TeamCreate 未验证

**问题**: 见 1.6 节风险 1。nx-executor 作为 team member 再创建子团队的设计未被验证。

**影响**: 如果 CodeBuddy 不支持嵌套 TeamCreate，nx-exec 的整个专家调度机制无法工作。这是**整个设计的阿喀琉斯之踵**。

**修复方案（三选一）**:
- 方案 A: 实测验证嵌套 TeamCreate（优先）
- 方案 B: 改为扁平调度 —— nx-exec 命令直接创建包含所有专家 agent 的大团队，由 nx-executor 仅做任务分发而非子团队管理
- 方案 C: 放弃专家调度，回退到 v0.3 的 nx-executor 亲自写代码模式

### 🟡 G3: 缺少年度级别的回滚/清理机制

**问题**: 如果 nx-exec 执行到一半失败，已产生的文件和 git commit 如何处理？

当前只有 Safe Resume Gate（检测已提交但无 SUMMARY.md），但没有**主动回滚**能力：
- 用户需要手动 `git revert` + 删除文件
- 没有 `/nx-undo N` 命令

**影响**: 中等。当前项目是单人开发，手动回滚可接受。但对未来自动化流程是隐患。

### 🟡 G4: 缺少独立代码审查命令

**问题**: nx-code-reviewer agent 存在但只在 nx-exec 内部调用。没有 `/nx-review` 命令让用户在任何时候触发独立审查。

**影响**: 低。用户可以直接使用 CodeBuddy 的原生 code-reviewer agent。

### 🟡 G5: 缺少中间进展监控

**问题**: nx-exec 在后台执行 Wave 并行任务时，用户无法查看中间进展。所有 agent 通过 SendMessage 通信，但这些消息对用户不可见。

**影响**: 中等。长 Wave 执行期间用户处于信息黑洞，不知道进行到哪一步。

**修复**: nx-exec 应定期（每完成一个任务）向用户发送进展摘要。

### 🟢 G6: 无阶段执行配置文件

**问题**: 不同的阶段可能需要不同的默认行为（如某些阶段需要交互模式、某些需要强制 TDD），但没有阶段级别的配置覆盖机制。

**影响**: 低。当前通过命令参数手动指定可接受。

### 🟢 G7: STATE.md 格式未正式定义

**问题**: 命令中多次引用 STATE.md 的字段（如 `progress.completed_phases`、`phase_status`），但没有正式的 schema 定义文件。

**影响**: 低。当前通过 GSD 兼容读取已可工作。

---

## 三、边界模糊点

### 🔴 B1: nx-plan 内部审核 vs nx-check-plan 独立审核

**问题**: `/nx-plan` 第 4 步和 `/nx-check-plan` 做完全相同的事情（调用 nx-plan-checker 审核 PLAN.md）。

- nx-plan 内部已有审核（最多 3 次迭代 + 停滞检测）
- nx-check-plan 是完全重复的功能

**模糊点**: 
- 用户何时该用 nx-check-plan 而非 nx-plan？
- 如果 nx-plan 内部的审核未通过（已用光 3 次迭代），独立的 nx-check-plan 能否作为"第二次意见"？如果可以，审核循环上限的实际效果是什么？
- 两次审核结果不一致时以哪个为准？

**建议**: 明确区分：
- nx-plan 内部审核 = 基础门禁（检查 PLAN.md 是否完整、可执行）
- nx-check-plan = 深度二审（检查与已有模式的偏离、长尾风险、跨计划契约）
- 标注：nx-plan 生产 PLAN.md 后自动执行基础审核；深度审核由用户按需触发

### 🔴 B2: nx-executor（调度器）vs nx-exec 命令的偏差处理

**问题**: 偏差处理规则同时出现在两个文件中：

| 文件 | 偏差处理内容 |
|------|------------|
| `commands/nx-exec.md` | 步骤 5：6 类偏差表格 |
| `agents/nx-executor.md` | "偏差处理" 章节：6 类偏差表格 + 专家降级策略 |

**模糊点**:
- 如果 nx-executor 是"纯调度器，不亲自写代码"，为什么它还需要偏差处理规则？
- 偏差应该由执行任务的**专家 agent** 处理，还是由调度器 nx-executor 处理？
- 两个文件中的偏差处理表格虽然相似但不完全一致（nx-exec.md 多了"升级给用户"，nx-executor.md 多了"降级策略"）

**建议**: 
- 偏差处理的主逻辑归属专家 agent（实际写代码的人）
- nx-executor 只负责汇总专家 agent 报告的偏差，做升级决策
- 合并为唯一的偏差处理定义

### 🟡 B3: nx-verify vs nx-validate 的检测边界

**问题**:

| 验证 | 检测内容 |
|------|---------|
| nx-verify L3（连接性） | Handler → Service 是否注入和调用 |
| nx-validate 步骤 2（覆盖分析） | 方法是否被测试覆盖 |

**模糊点**:
- L3 连接性检测和集成测试覆盖检测可能有重叠。如果 Handler → Service 连接正确但无测试，这属于 L3 失败还是测试覆盖缺失？
- L4 数据流通（端到端数据流检测）和 E2E 测试的边界在哪里？

**建议**:
- nx-verify = 代码正确性验证（结构层面的"有连接"）
- nx-validate = 测试充分性验证（行为层面的"有验证"）
- L4 是代码路径追踪，E2E 测试是运行时验证，两者互补不重叠

### 🟡 B4: nx-integrate vs 各阶段 nx-verify 的连接性维度

**问题**: nx-verify 的 L3 检查"组件是否正确连接到系统的其他部分"，nx-integrate 检查"跨阶段 API/数据流/事件连接"。

**模糊点**:
- "系统的其他部分"和"其他阶段"是什么关系？同一阶段内的组件连接 vs 跨阶段的组件连接，边界在哪？
- 当前项目是单模块 Kotlin 项目，所有阶段的内容在同一个模块中。在这种架构下，"跨阶段集成"几乎等价于"全局连接性检查"。

**建议**: 
- nx-verify L3 只检查**当前阶段 PLAN 中声明的连接**（如 PLAN 说"Service 应注入 Repository"，则检查是否有 `private.*Repository`）
- nx-integrate 检查**未在 PLAN 中声明但应存在的隐含连接**（如阶段 5 定义的 AuthService 是否被阶段 6 的 ChatHandler 正确使用）

### 🟡 B5: 专家 agent 的"能力边界"不明确

**问题**: nx-executor 的专家映射表将所有任务分为 6 大类（编写实现/质量保障/设计审查/故障排查/平台工程/前端），但没有定义：

- 专家 agent 在哪些情况下**不应该**采取行动
- 如果 `backend-architect` 在实现 Route 时发现需要修改 Service（属于 `java-developer` 的领域），应如何处理？跨领域调用还是上报 nx-executor？

**建议**: 在 Agent Contract 中增加"专家 agent 的职责边界"：
- 专家 agent 只在自己被分配的文件内操作
- 发现需要修改其他领域文件时 → 上报 nx-executor → nx-executor 决定是否创建新任务
- 专家 agent 不修改非分配给自己的文件

### 🟢 B6: /nx-init 的输入方式不明确

**问题**: nx-init 接受"项目描述"参数，但：
- 如果用户的项目已经在开发中（如当前 Nebula Server），/nx-init 如何获取已有信息？
- 与 GSD 的 gsd-new-project（需求分析 + 研究 + 路线图）相比，nx-init 显得过于简单

**影响**: 低。当前项目已有 GSD 生成的完整 `.planning/` 结构，不需要重新 init。

---

## 四、设计与实现的交集评估

| 维度 | 状态 | 说明 |
|------|------|------|
| 命令文件 | ✅ 10/10 | 全部实装 |
| Agent 文件 | ✅ 10/10 | 全部实装 |
| Reference 文件 | ✅ 4/4 | gates / contracts / verification / deviation |
| Template 文件 | ✅ 6/6 | 全部存在但未被命令显式引用 |
| 嵌套 TeamCreate | 🔴 未验证 | 见 G2 |
| Gap 闭合 flag | 🔴 不一致 | 见 G1 |
| 偏差处理重复 | 🔴 边界模糊 | 见 B2 |
| Plan 审核边界 | 🔴 边界模糊 | 见 B1 |
| 专家 agent 可用性 | 🟡 未验证 | 见 1.6 风险 2 |
| Completion Gate 过于严格 | 🟡 需调整 | 见 1.10 |
| 无回滚机制 | 🟡 缺失 | 见 G3 |
| 无进展监控 | 🟡 缺失 | 见 G5 |

---

## 五、修复优先级

| 优先级 | 编号 | 问题 | 修复动作 |
|--------|-----|------|---------|
| 🔴 P0 | G2 | 嵌套 TeamCreate 未验证 | 实测验证。如不支持则选 B/C 方案 |
| 🔴 P0 | B2 | 偏差处理重复定义 | 合并到单一文件，明确归属 |
| 🔴 P1 | G1 | Gap 闭合 flag 不一致 | nx-plan.md 增加 `--gaps` 参数 |
| 🔴 P1 | B1 | Plan 审核边界模糊 | 区分基础审核 vs 深度审核 |
| 🟡 P2 | 1.10 | Completion Gate 过于严格 | 按阶段类型区分必需文件 |
| 🟡 P2 | B4 | 连接性验证边界模糊 | 定义 nx-verify L3 vs nx-integrate 的分工 |
| 🟡 P2 | B5 | 专家 agent 职责边界 | Agent Contract 增加"跨领域规则" |
| 🟡 P2 | G5 | 缺少中间进展监控 | nx-exec 增加定期进展摘要输出 |
| 🟢 P3 | G3 | 无回滚机制 | 文档化手动回滚流程，暂不实装命令 |
| 🟢 P3 | G4 | 缺少独立审查命令 | 文档化使用原生 code-reviewer |
| 🟢 P3 | G6 | 无阶段配置文件 | 未来增强 |
| 🟢 P3 | G7 | STATE.md schema | 未来增强 |

---

## 六、总体可行性判断

**Nebula-workflow 的设计在概念层面是完整的**——10 命令 + 10 Agent + 4 引用 + 6 模板覆盖了从初始化到归档的完整生命周期。

**但存在一个阻塞级别的实现风险**：嵌套 TeamCreate（命令层创建团队 → executor 在团队内再创建子团队）这一机制是否被 CodeBuddy 支持，从未被验证。这是整个专家调度架构的基石。

**建议的执行顺序**：
1. 先验证嵌套 TeamCreate 可行性（P0）
2. 修复偏差处理重复和 Gap flag 不一致（P1）  
3. 在第一个阶段执行时验证专家 agent 可用性
4. 根据实测反馈调整边界定义（P2）

---

## 第五次审查 → 第六次的修复追踪

**所有 P0/P1/P2 问题已修复**：

| 编号 | 问题 | 修复方式 | 证据 |
|------|------|---------|------|
| P0 G2 | 嵌套 TeamCreate 未验证 | 改为 `team_name` 参数加入现有团队，而非创建子团队 | `nx-exec.md:83` — 明确 "不创建新团队" |
| P0 B2 | 偏差处理重复定义 | 明确归属：专家 agent 处理偏差，nx-executor 汇总升级 | `nx-exec.md:117` 引用 executor，`nx-executor.md:189-202` 含完整规则 |
| P1 G1 | Gap 闭合 flag 不一致 | nx-plan.md 参数增加 `--gaps` | `nx-plan.md:3` argument-hint 包含 `--gaps` |
| P1 B1 | Plan 审核边界模糊 | 明确基础审核(完整性/可行性) vs 深度二审(契约/长尾/偏离) | `nx-plan.md:105` "基础门禁审核"，`nx-check-plan.md:9-14` 四维深度审核 |
| P2 1.10 | Completion Gate 过于严格 | 按 phase_type 区分必需文件 | `nx-done.md:28-55` 四种类型差异化检查 |
| P2 B4 | 连接性验证边界模糊 | nx-verify L3 限定"当前阶段 PLAN 声明的连接" | `nx-verifier.md:72` "范围限定" |
| P2 B5 | 专家 agent 职责边界 | Agent Contract 增加跨领域规则和职责边界 | `agent-contracts.md:59-76` |
| P2 G5 | 缺少中间进展监控 | nx-exec.md 增加步骤 6 进展摘要输出 | `nx-exec.md:124-137` |

---
---

# Nebula-Workflow 第六次审查：设计初衷一致性

**审查日期**: 2026-06-13  
**审查范围**: 是否偏离「模仿实现 GSD」的初始设计意图  
**版本**: v0.4

---

## 审查方法论

不同于前五次"逐文件 vs GSD 对比"或"流程可行性检查"，本次从更高视角审视：
- 设计公式是否仍然平衡（GSD 侧 vs CodeBuddy 侧）
- 演进轨迹是渐变增强还是突变偏离
- 与 GSD 的差异点是合理改造还是无意识偏离

---

## 一、设计公式一致性

```
GSD 流程（状态外置 + 验证闭环 + 文档驱动）
+ CodeBuddy 原生能力（TeamCreate 并行 + 上下文隔离 + 专家 Agent 调度）
= 结构化 AI 驱动开发
```

逐一验证公式两侧：

| 公式元素 | 实现证据 | 忠实度 |
|---------|---------|--------|
| 状态外置 | `.planning/` GSD 兼容目录结构 + STATE.md | 100% |
| 验证闭环 | discuss→plan→check→exec→verify→done 六环 | 100% |
| 文档驱动 | 10 级文档链（PROJECT→ROADMAP→CONTEXT→...→SECURITY） | 100% |
| TeamCreate 并行 | 三种执行模式 + Wave 分组并行 | 100% |
| 上下文隔离 | 命令 2-4KB，Agent 3-7KB，独立上下文 | 基本满足* |
| 专家调度 | 20+ 映射表 + 推断规则 + 降级策略 | 100% |

> *nx-executor.md 7KB 略超 5KB 目标，但作为调度中枢可接受（GSD 的 execute-phase 也是最大文件）

---

## 二、演进轨迹：渐变增强，非突变偏离

| 版本 | 方向 | 与 GSD 关系 |
|-----|------|------------|
| v0.1 | 建立核心环（7 命令 + 7 Agent） | 直接模仿 |
| v0.2 | 补全 GSD 缺失（+3 命令 + 3 Agent + 8 机制） | 追赶完整性 |
| v0.3 | 执行灵活性（--wave/--gaps-only/--interactive） | Nebula 原创增强 |
| v0.4 | 专家调度（20+ 映射 + 调度器纯化） | 深度利用平台能力 |

**轨迹分析**: v0.1-v0.2 是"追赶"（沿 GSD 轴），v0.3-v0.4 是"超越"（沿 CodeBuddy 轴）。但超越方向是更深地应用公式右侧的元素，属于纵向延伸而非横向偏离。

---

## 三、GSD 对照清单

### 完全继承 ✓

四类门禁、停滞检测、偏差分级、分析卡死保护、四层验证、Nyquist 审计、跨阶段集成、上下文自适应 — 全部等价实现。

### 平台适配改造 ✓

| 改造点 | 原因 |
|-------|------|
| Worktree → 上下文隔离 | 平台能力替代 |
| 文件轮询+regex → SendMessage | 平台通信替代 |
| 90+文件/500KB → 32文件/75KB | 精简冗余 |
| 68命令 → 10命令 | 聚焦核心流程 |

### 唯一未实现

**TDD 集成** — 设计文档标记为"可选 --tdd 模式"，暂未实装。属于有意识的减法，非遗漏。

---

## 四、需要关注的潜在问题

### 1. 理论验证鸿沟

32 文件 + 20+ 专家映射 + 6 级偏差处理 + 4 类门禁全部停留在纸面设计阶段。v0.4 从未在一个真实阶段上实际运行。当理论设计复杂度远超实践验证量时，存在"为设计而设计"的风险。

### 2. 进展监控的实现裂缝

`nx-exec.md:124-137` 定义了进展条格式和"每任务/每 5 分钟"监控节奏，但 `nx-executor.md` 只写"每完成一个任务向用户输出进展摘要"，缺少定时器的实现指令。命令层有规范定义，Agent 层存在空白。

### 3. nx-executor 身份张力

定义为"纯调度器，不亲自写代码"，但包含完整降级策略（专家不可用时回退通用模式）。弹性设计增加了调度器自身复杂度。

---

## 五、设计思想保持度评分

| 原则 | 得分 | 说明 |
|------|------|------|
| 状态外置化 | 100% | 完全保留 |
| 阶段化流水线 | 100% | 完全保留 |
| 文档驱动决策 | 100% | 10 级文档链完整 |
| 门禁式质量管控 | 105% | 4 类 GSD + 3 类增强 |
| 并行执行 | 120% | Wave + TeamCreate + 专家调度 |
| Agent 专业化 | 120% | 10 流程 Agent + 20+ 执行专家 |
| 偏差自适应处理 | 100% | 6 级偏差 + 降级策略 |
| 验证闭环 | 100% | 四层验证完整 |
| TDD 集成 | 0% | 有意暂缓 |
| **加权综合** | **~95%** | 核心设计思想全部保留 |

---

## 六、总体结论

**v0.4 没有偏离设计初衷**。它沿着公式两个坐标轴纵向延伸，X 轴补齐 GSD 完整性，Y 轴深挖 CodeBuddy 平台能力。公式中的 `+` 是乘法——CodeBuddy 能力放大了 GSD 流程效果。

**核心风险唯一**: 所有设计停留在纸面。32 个文件从未在一个真实阶段上运行。建议选择阶段 7 以默认专家调度模式执行一次完整的 nx-exec，用真实数据验证假设。
