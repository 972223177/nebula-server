---
description: 阶段执行 —— 专家 Agent 调度 + Wave 并行 + Safe Resume Gate + 门禁检查，支持 Wave 过滤和 Gap 闭合模式
argument-hint: "<N> [--wave N] [--gaps-only] [--interactive]"
---

# 阶段执行

## 目标
执行阶段 N 的所有计划，按 Wave 分组并行执行，自动提交代码，生成 SUMMARY.md。

## 参数
- `$ARGUMENTS`：阶段编号 N（必需）
- `--wave N`：仅执行第 N 个 Wave（用于配额管理、分批推出）
- `--gaps-only`：仅执行 gap_closure: true 的计划（用于验证→修复闭环）
- `--interactive`：顺序内联执行，每任务用户确认（不创建 TeamCreate，适用于小阶段）

## 门禁检查

### 1. Safe Resume Gate（安全恢复门禁）

```bash
# 对每个不完整计划检查
for PLAN in (不完整计划); do
  PLAN_ID="${PHASE_NUM}-${PLAN_PADDED}"
  SUMMARY_PATH="${PHASE_DIR}/${PLAN_PADDED}-SUMMARY.md"
  
  # 检查 git log 中是否有对应计划的提交
  PLAN_COMMITS=$(git log --oneline --grep="${PLAN_ID}" -30)
  
  if [ -n "$PLAN_COMMITS" ] && [ ! -f "$SUMMARY_PATH" ]; then
    呈现选项：
    1. 手动关闭 —— 检查提交内容 → 写 SUMMARY.md → 更新 STATE/ROADMAP
    2. 从头重做 —— 回滚相关提交 → 重新派遣执行
    3. 标记并跳过 —— 记录异常，显式确认后继续
  fi
done
```

### 2. Blocking Anti-patterns Check（阻塞反模式检查）

```bash
if [ -f "${PHASE_DIR}/.continue-here.md" ]; then
  解析其中的 "严重反模式" 表格
  提取 severity="blocking" 的行
  如果存在 → 停止执行，展示阻塞项，要求先修复
fi
```

### 3. Pre-flight Gate

```bash
# 检查所有 PLAN.md 通过审核
if [ ! -f "${PHASE_DIR}/*-PLAN-CHECK.md" ]; then
  提示: "PLAN.md 未审核，建议先执行 /nx-check-plan N"
  询问: "是否跳过审核直接执行？[y/N]"
fi
```

## 执行流程

### 步骤 1：计划发现与过滤

```bash
# 收集阶段所有计划
PLANS=$(find ${PHASE_DIR} -name "*-PLAN.md" | sort)

# 解析每个 PLAN 的 frontmatter，提取 plan/type/wave/depends_on
# 过滤已完成的计划（有对应 SUMMARY.md）
INCOMPLETE_PLANS=()

# 应用 flag 过滤
if [ "$WAVE_FILTER" != "" ]; then
  INCOMPLETE_PLANS = 过滤到 Wave == WAVE_FILTER 的计划
fi
if [ "$GAPS_ONLY" = "true" ]; then
  INCOMPLETE_PLANS = 过滤到 type == "gap_closure" 的计划
fi
```

### 步骤 2：Wave 分组

```bash
# 分析依赖关系
WAVES = {
  Wave 1: [没有 depends_on 的计划]
  Wave 2: [依赖 Wave 1 的计划]
  ...
}
```

### 步骤 3：执行模式分发

**专家调度模式（默认，v0.4）**：
- 创建 TeamCreate，团队名 `phase-{N}`
- 派发 nx-executor（调度器）作为团队成员，不直接编写代码
- nx-executor 解析 PLAN.md 任务表，根据 `expert` 字段匹配专家 agent
- nx-executor 使用 Agent 工具将专家 agent 加入**同一个团队**（指定 team_name 为当前团队，而非创建新团队）
- 专家 agent 并行执行各自任务
- nx-executor 通过 SendMessage 收集结果，聚合生成 SUMMARY.md
- Wave 全部完成后进入下一 Wave

**交互模式（--interactive）**：
- 按计划顺序依次执行，不使用 subagent
- 每完成一个任务暂停等待用户确认
- 适用于小阶段（1-2 个计划）

### 步骤 4：Git 提交

每个执行计划完成后：
```bash
git add <modified_files>
git commit -m "feat(phase-${N}): plan ${N}-${M} — <简短描述>"
```

### 步骤 5：偏差处理

偏差由执行任务的专家 agent 在本地处理，nx-executor 负责汇总和升级决策。详细规则见 `agents/nx-executor.md` 的"偏差处理"章节。

**升级策略**（nx-executor 汇总后）：
- 所有偏差已自动修复 → 记录到 SUMMARY.md，正常完成
- 存在阻塞/架构变更偏差 → 暂停执行，展示偏差给用户决策

### 步骤 6：进展监控

在 Wave 执行过程中，nx-executor 必须在每个任务完成后向用户输出进展摘要：

```
📊 阶段 N 执行进展
Wave 1 (并行): [████████░░] 4/5 任务完成
├─ ✅ N-1 Task 1 — backend-architect 完成 (abc1234)
├─ ✅ N-1 Task 2 — java-developer 完成 (def5678)  
├─ ✅ N-2 Task 1 — database-optimizer 完成 (ghi9012)
├─ 🔄 N-2 Task 2 — test-automator 执行中...
└─ ⏳ N-2 Task 3 — deployment-engineer 等待中
```

每完成一个任务或每 5 分钟（取较短者）输出一次进展摘要。这确保用户在长时间并行执行中不会处于信息黑洞。

### 步骤 7：分析卡死保护

- 连续 5 次只读工具调用 → 停止，要求用户确认
- 连续 3 次工具调用返回空/错误 → 停止（无意义搜索）

## Agent Handoff 契约

Executor 必须输出给 Verifier 的字段（写入 SUMMARY.md）：
- Commits 表：每任务的 commit hash 和描述
- Deviations：自动修复的问题或 "None"
- Self-Check：PASSED 或 FAILED（含详情）
- Key Files：修改的关键文件列表

## 成功标准
- 所有计划执行完成（SUMMARY.md 已生成）
- 所有代码已提交
- Self-Check 为 PASSED
- STATE.md 已更新
