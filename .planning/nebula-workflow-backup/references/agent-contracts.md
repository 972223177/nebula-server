# Agent 契约系统

定义 Nebula Agent 间的 handoff 契约。由于 Nebula 使用 SendMessage 进行 agent 通信（而非 GSD 的文件 regex 匹配），契约主要定义输出格式要求。

## Agent 注册表

| Agent | 角色 | 输出文件 | 完成状态 |
|-------|------|---------|---------|
| nx-researcher | 阶段研究 | RESEARCH.md | `## RESEARCH COMPLETE` / `## RESEARCH BLOCKED` |
| nx-pattern-mapper | 模式映射 | PATTERNS.md | `## PATTERNS COMPLETE` |
| nx-planner | 计划生成 | PLAN.md | `## PLANNING COMPLETE` |
| nx-plan-checker | 计划审核 | PLAN-CHECK.md | `## VERIFICATION PASSED` / `## ISSUES FOUND` |
| nx-executor | 专家调度 | SUMMARY.md | `## PLAN COMPLETE` / `## CHECKPOINT REACHED` / `## PLAN BLOCKED` |
| nx-verifier | 四层验证 | VERIFICATION.md | `## Verification Complete` |
| nx-nyquist-auditor | 测试审计 | VALIDATION.md | `## NYQUIST AUDIT COMPLETE` / `## PARTIAL` |
| nx-integration-checker | 集成检查 | INTEGRATION.md | `## Integration Check Complete` |
| nx-code-reviewer | 代码审查 | — | `## CODE REVIEW PASSED` / `## ISSUES FOUND` |
| nx-security-auditor | 安全审计 | SECURITY.md | `## SECURITY AUDIT COMPLETE` / `## OPEN_THREATS` |

## 关键 Handoff 契约

### 1. Planner → Executor（via PLAN.md）

| 字段 | 必须 | 说明 |
|------|------|------|
| `phase` | ✅ | 阶段编号 |
| `plan` | ✅ | 计划编号（格式：N-M） |
| `type` | ✅ | implementation / refactor / gap_closure |
| `wave` | ✅ | Wave 分组编号 |
| `depends_on` | ✅ | 依赖的计划 ID（逗号分隔，无则为空） |
| `<objective>` | ✅ | 计划目标（一句话） |
| `<tasks>` | ✅ | 有序任务列表（type/files/expert/action/verify/acceptance_criteria）。`expert` 为 v0.4 新增可选字段，指定执行专家 agent |
| `<wave_group>` | ✅ | 整体 Wave 分组方案 |
| `<success_criteria>` | ✅ | 可衡量的完成标准 |

### 2. Executor → Expert Agent（v0.4 新增，via SendMessage）

nx-executor 作为调度器，通过 SendMessage 向专家 agent 派发任务：

| 字段 | 必须 | 说明 |
|------|------|------|
| `任务描述` | ✅ | 文件路径、操作内容、验收标准 |
| `项目上下文` | ✅ | project_type、language、framework（来自 PROJECT.md） |
| `代码模式` | ✅ | PATTERNS.md 中的相关模式模板 |
| `注释规范` | ✅ | CODEBUDDY.md 中的中文注释要求 |
| `偏差权限` | ✅ | 该任务允许的自动修复范围和升级条件 |

专家 agent 完成后通过 SendMessage 向 nx-executor 返回：

| 字段 | 必须 | 说明 |
|------|------|------|
| `status` | ✅ | completed / failed / blocked |
| `modified_files` | ✅ | 修改的文件列表 |
| `commit_hash` | ✅ | git commit hash |
| `deviations` | ✅ | 遇到的偏差及处理方式，或 "None" |
| `cross_domain_requests` | ❌ | 需要其他专家 agent 处理的跨领域请求（文件路径 + 原因） |

### 专家 Agent 职责边界

专家 agent 必须遵守以下职责边界，防止越权操作：

**范围约束**：
- 只在自己被分配的文件内操作（修改、创建、删除）
- 可读取任意文件以获取上下文（如 PATTERNS.md、已有代码）

**跨领域规则**：
- 发现需要修改**非分配给自己的文件**时 → **不直接修改**，通过 `cross_domain_requests` 字段上报 nx-executor
- nx-executor 评估后决定：创建新任务 / 合并到现有任务 / 暂缓处理

**代码规范**：
- 所有专家 agent 必须遵循 CODEBUDDY.md 的中文注释规范
- KDoc 注释使用中文，专有名词和代码保留原文

**回退策略**：
- 如果专家 agent 无法完成任务（能力不足/工具受限） → 报告 nx-executor，由调度器降级处理
- 专家 agent 不可用时降级规则见 `agents/nx-executor.md` 的"专家 agent 不可用时的降级策略"

### 3. Executor → Verifier（via SUMMARY.md）

| 字段 | 必须 | 说明 |
|------|------|------|
| `phase` | ✅ | 阶段编号 |
| `plan` | ✅ | 计划编号 |
| `status` | ✅ | completed / checkpoint |
| `key-files` | ✅ | 修改的关键文件列表 |
| Commits 表 | ✅ | 每任务的 commit hash 和描述 |
| Deviations | ✅ | 自动修复的问题或 "None" |
| Self-Check | ✅ | PASSED 或 FAILED（含详情） |

### 4. Verifier → (闭环)

VERIFICATION.md 的输出状态决定流程走向：
- `PASSED` → 可以进行 /nx-done
- `PARTIAL` → 记录 gap，可选择性 /nx-done
- `FAILED` → 需要 gap 闭合：/nx-plan N --gaps → /nx-exec N --gaps-only

## 文件格式约定

### Frontmatter 字段
所有产出文件使用 YAML frontmatter：
```yaml
---
phase: N
<agent_field>: <agent_name>
status: <完成状态>
---
```

### Markdown 格式
- 使用 Markdown 表格（非 XML `<task>` 格式）
- 中文标题和内容
- 代码块标注语言类型

## 与 GSD 的差异

| 维度 | GSD | Nebula |
|------|-----|--------|
| 完成检测 | regex 匹配 H2 heading | SendMessage 消息驱动 |
| 文件轮询 | SUMMARY.md 存在性 spot-check | 消息通知替代 |
| Agent 类型 | 通过 `.claude/agents/` 注册 | 通过 plugin agents/ 目录定义 |
