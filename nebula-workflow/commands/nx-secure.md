---
description: 安全审计 — 验证阶段威胁模型的缓解措施已在代码中实现
argument-hint: [阶段号] [--quick]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-secure — 安全审计

## 目的

验证阶段威胁模型的缓解措施是否已在代码中实现。基于 PLAN.md 中定义的威胁登记册，逐威胁检查代码中对应的安全防护。

适用于：
- 安全敏感的模块（认证、数据存储、网络通信）
- 对外暴露的 API 接口
- 涉及用户隐私数据的处理逻辑

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在且包含威胁登记册
- 建议先执行 `/nx-exec <N>` 完成代码实现
- 可选：已有 `.planning/phases/<N>/SECURITY.md` 可增量审计

## 参数

| 参数 | 说明 |
|------|------|
| `<N>` | 阶段号（必填） |
| `--quick` | 快速模式 — 仅 grep 扫描，不启动 agent |

## 流程

### 第一步：加载威胁模型

1. 读取 `.planning/phases/<N>/PLAN.md` — 提取威胁登记册
2. 读取 `.planning/phases/<N>/SECURITY.md`（如果存在）— 增量审计基准
3. 如果 PLAN.md 没有威胁登记册：

```markdown
⚠️ 未找到威胁登记册

阶段 <N> 的 PLAN.md 中没有威胁模型定义。建议：
1. 先分析本阶段的安全风险
2. 通过 `/nx-plan <N>` 补充威胁登记册
3. 再次执行 `/nx-secure <N>`

是否跳过安全审计？（不推荐跳过安全敏感阶段的审计）
```

询问用户：更新 PLAN.md 添加威胁模型 / 跳过审计 / 取消

### 第二步：提取关联文件

从 PLAN.md 中提取本阶段所有任务的关联文件，作为审计范围。

### 第三步：启动 nx-security-auditor agent

```markdown
Agent(
  nx-security-auditor,
  审计阶段 <N> 的安全实现，
  威胁登记册：[从 PLAN.md 解析]，
  关联文件：[文件路径列表]
)
```

**`--quick` 模式：** 跳过 agent，直接运行 grep 扫描常见安全反模式：
- 硬编码密钥：`(password|secret|api_key|token)\s*[=:]\s*['"]\w+['"]`
- SQL 拼接：`"SELECT.*\+|\"SELECT.*\\+`
- 不安全随机数：`Math\.random\(\)|java\.util\.Random`
- 裸的输入使用：`!!|unsafe|@Suppress\("UNCHECKED_CAST"\)`

### 第四步：阅读审计报告

Agent 输出后，读取 `.planning/phases/<N>/SECURITY.md`（如果 agent 输出了）。

### 第五步：向用户展示结果

#### 安全审计通过

```markdown
## ✅ 安全审计通过 — 阶段 <N> (Gate: Revision)

**状态：** SECURED

所有威胁已妥善处理：
- ✅ 已缓解：<X> 个
- ✅ 已接受：<Y> 个
- ✅ 已转移：<Z> 个

无未缓解的威胁。代码实现了安全设计要求。

下一步：执行 `/nx-verify <N>` 进行目标验证
```

#### 存在未缓解威胁

```markdown
## ⚠️ 安全审计发现问题 — 阶段 <N> (Gate: Revision → Escalation)

**状态：** OPEN_THREATS

| 类别 | 数量 |
|------|------|
| ✅ 已缓解 | <X> |
| ⚠️ 未缓解 | <Y> |
| ✅ 已接受 | <Z> |
| ✅ 已转移 | <W> |

⚠️ <Y> 个威胁未缓解。

完整报告：.planning/phases/<N>/SECURITY.md
```

### 第六步：处理审计结果

#### 如果状态为 OPEN_THREATS

```markdown
⚠️ 发现 <Y> 个未缓解的威胁，存在安全风险。

是否处理？
1. 修复未缓解威胁（推荐）— 为每个 OPEN 威胁实施缓解措施
2. 接受剩余风险 — 标记为 accept 并记录理由
3. 忽略继续 — 跳过此质量网关（高风险！不推荐）
```

用户选"修复"后：
1. 对每个 OPEN_THREATS，生成 gap_closure 任务追加到 PLAN.md
2. 建议执行 `/nx-exec <N> --gaps-only` 修复
3. 修复后重新执行 `/nx-secure <N>` 验证

用户选"接受"后：
1. 要求用户提供接受理由
2. 更新 SECURITY.md 标记为 accept
3. 追加到 PROJECT.md 已接受风险记录

#### 如果状态为 SECURED

自动继续，无需额外处理。
