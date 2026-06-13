# 门禁系统

Nebula 工作流在各阶段入口处执行的门禁检查。参考 GSD 的四类门禁系统（Pre-flight/Revision/Escalation/Abort）。

## 四类门禁

### Pre-flight Gate（预检门禁）
**目的**: 验证操作前置条件，防止浪费工作。
**行为**: 条件不满足时阻止进入。不会产生部分工作。
**恢复**: 修复缺失的前置条件后重试。

| 阶段 | 位置 | 检查条件 | 失败行为 |
|------|------|---------|---------|
| /nx-init | Entry | 用户已提供项目描述 | 询问描述后继续 |
| /nx-discuss | Entry | PROJECT.md + ROADMAP.md 存在 | 报错，提示执行 /nx-init |
| /nx-plan | Entry | CONTEXT.md 存在（建议非强制） | 警告，允许盲规划 |
| /nx-exec | Entry | PLAN.md 存在 + 已审核 | 警告，建议 /nx-check-plan |
| /nx-verify | Entry | SUMMARY.md 存在 | 报错，提示执行 /nx-exec |
| /nx-done | Entry | 阶段目录存在 | 报错 |

### Revision Gate（修订门禁）
**目的**: 评估输出质量，不足时返回重做。
**行为**: 带具体反馈的修订循环。有迭代上限。
**恢复**: 生产者处理反馈；检查者重新评估。

| 阶段 | 位置 | 检查条件 | 迭代上限 |
|------|------|---------|---------|
| /nx-plan | Post-plan | nx-plan-checker 审核 PLAN.md | 3 次 |
| /nx-exec | Post-exec | Self-Check 为 PASSED | 偏差自动修复限制 |

**停滞检测**: 如果连续两次迭代的问题数未减少 → 提前升级而非等待 max 迭代。

### Escalation Gate（升级门禁）
**目的**: 呈现自动无法解决的问题给开发者决策。
**行为**: 暂停工作流，展示选项，等待人工输入。
**恢复**: 开发者选择操作后继续。

触发条件：
- 修订循环超出上限（3 次审核未通过）
- 偏差处理中的架构变更
- 不明确的需求需要澄清

### Abort Gate（中止门禁）
**目的**: 终止操作以防止破坏或资源浪费。
**行为**: 立即停止，保留状态，报告原因。
**恢复**: 开发者调查根因、修复、从检查点重启。

触发条件：
- 上下文窗口严重不足
- STATE.md 异常状态
- 验证发现关键交付物缺失

## 门禁矩阵

| 命令 | Entry Gate | Revision Gate | Escalation | Abort |
|------|-----------|---------------|------------|-------|
| /nx-init | Pre-flight | — | — | — |
| /nx-discuss | Pre-flight + Closed-Phase | — | — | — |
| /nx-plan | Pre-flight + Closed-Phase | Revision (checker) | Escalation | Abort |
| /nx-check-plan | Pre-flight | Revision (stall detect) | Escalation | — |
| /nx-exec | Safe Resume + Blocking Check + Pre-flight | Revision (self-check) | Escalation | Abort |
| /nx-verify | Pre-flight | — | Escalation | Abort |
| /nx-validate | Pre-flight | — | — | — |
| /nx-integrate | — | — | Escalation | — |
| /nx-done | Completion + Verification | — | — | — |

## 与 GSD 的对比

| 门禁 | GSD | Nebula |
|------|-----|--------|
| Pre-flight | ✅ 文件存在性检查 | ✅ 等价实现 |
| Revision | ✅ plan-checker + stall detection | ✅ + 偏差自动修复限制 |
| Escalation | ✅ AskUserQuestion | ✅ 等价实现 |
| Abort | ✅ 上下文窗口保护 | ✅ 等价实现 |
| Closed-Phase | ✅ STATE.md status check | ✅ 等价实现 |
| Safe Resume | ✅ git log + SUMMARY.md | ✅ 等价实现 |
| Blocking Check | ✅ .continue-here.md | ✅ 等价实现 |
| MVP+TDD Gate | ✅ MVP+TDD mode | ❌ 未实现（无 TDD 支持） |
