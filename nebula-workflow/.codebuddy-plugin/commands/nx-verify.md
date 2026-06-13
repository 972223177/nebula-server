---
description: Goal-backward 验证阶段目标在代码库中真正实现，生成 VERIFICATION.md
argument-hint: [阶段号] [--quick]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-verify — 目标逆向验证 (Verify-Work)

## 目的

从阶段目标出发，验证代码库中是否真正实现了阶段承诺的目标。
采用 goal-backward 验证方法：先确认"阶段应交付什么"，再检查代码是否真实交付了这些价值。
这是验证闭环中最终的质量网关，通过后即可完成里程碑。

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 建议先执行 `/nx-validate <N>` 完成代码质量验证
- 建议先执行 `/nx-exec <N>` 运行过实现

## 流程

### 第一步：加载验证上下文

1. 读取 `.planning/ROADMAP.md` — 提取阶段目标（phase goal）和成功标准
2. 读取 `.planning/phases/<N>/PLAN.md` — 任务验收标准
3. 读取 `.planning/PROJECT.md` — 全局约束
4. 读取 `.planning/phases/<N>/VALIDATION.md`（如果有）— 代码验证结果

### 第二步：启动 nx-verifier agent

使用 `Agent` 工具启动 nx-verifier agent 进行深度验证：

```
Agent(
  nx-verifier,
  验证阶段 <N> 的目标是否在代码库中真正实现。
  阶段目标：[从 ROADMAP.md 提取]
  成功标准：[从 ROADMAP.md 或 PLAN.md 提取]
)
```

### 第三步：同时执行基础任务级验证

在等待 agent 的同时，也可以执行基础的逐任务验收标准检查（从现有版本保留）：

对每个标记为"已完成"的任务，逐条检查验收标准：

**验证方法：**
- 文件存在性检查：`Glob`/`test -f` 验证关联文件是否存在
- 代码检查：`Grep`/`Read` 验证关键实现是否存在
- 接口检查：验证公开 API/接口是否按计划实现

### 第四步：综合验证结果

将 agent 的 goal-backward 验证结果与基础任务级验证结果合并。

读取 `.planning/phases/<N>/VERIFICATION.md`（由 nx-verifier agent 生成）。

### 第五步：展示验证报告

```markdown
## 验证报告 — 阶段 <N> (Gate: Revision)

### 阶段目标
[来自 ROADMAP.md]

### 可观测真相验证

| # | 真相 | 状态 |
|---|------|------|
| 1 | [可观测真相] | ✅/⚠️/❌ |

### 任务验收标准检查

| 任务 | 验收标准 | 状态 |
|------|----------|------|
| X.Y | [标准] | ✅ |
| X.Z | [标准] | ⚠️ 部分通过 |

### 关键链连接

| 从 → 到 | 状态 |
|----------|------|
| Controller → Service | ✅/❌ |

### 人类验证项
- [需要人工确认的行为]

---

**验证状态**: [通过 / 部分通过 / 未通过]
```

### 第六步：处理验证结果

#### 如果发现问题（❌ 或 ⚠️）— Gate: Escalation

1. 在 PLAN.md 中追加 gap_closure 任务：

```markdown
### 任务 N.G1: [修复验证问题]
**状态**: 待开始
**gap_closure**: true
**描述**: [具体问题]
**验收标准**:
- [ ] 修复问题 1
- [ ] 验证原始验收标准通过
```

2. 询问用户是否立即修复：
   - 是 → 提示执行 `/nx-exec <N> --gaps-only`
   - 否 → 记录到 STATE.md 作为未完成项

#### 全部通过

```markdown
## ✅ 验证通过 — 阶段 <N> (Gate: Revision → 闭环)

可观测真相通过率：X/X
任务验收标准通过率：X/X
关键链连接率：X/X

### 下一步
执行 `/nx-done <N>` 完成此阶段
```

1. 在 STATE.md 中更新当前阶段验证状态为"已通过"
2. 更新 PLAN.md 的阶段状态为"已验证"

### `--quick` 模式

跳过 nx-verifier agent，只执行简单的任务级验收标准检查（保留旧行为）。
适用于快速迭代或熟悉项目阶段的快速验证。
