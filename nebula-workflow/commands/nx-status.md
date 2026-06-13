---
description: 查看项目状态 —— 已完成/进行中/待处理阶段，进度统计
argument-hint: "[选项]"
---

# 项目状态

## 目标
展示项目的当前状态：阶段进度、任务统计、快速操作建议。

## 参数
- `$ARGUMENTS`（可选）：无参数展示全貌

## 流程

### 步骤 1：读取状态

```bash
# 读取 STATE.md（含 GSD frontmatter 兼容）
STATE_FILE=".planning/STATE.md"
# 提取状态数据
```

### 步骤 2：构建阶段映射

```bash
# 扫描 .planning/phases/ 目录
# GSD 格式: NN-description/
# Nebula 格式: 0N-description/
for PHASE_DIR in .planning/phases/*/; do
  # 从 STATE.md 获取状态
  # 从目录中分析文件完整性
done
```

### 步骤 3：计算统计

```bash
# 统计信息
TOTAL_PHASES=11
COMPLETED_PHASES=N
INCOMPLETE_PLANS=M
```

### 步骤 4：展示状态

**待处理阶段的状态由文件系统实际内容决定，不依赖 STATE.md 的静态映射：**

对每个未完成的阶段 N，按以下决策树判定状态和下一步建议：

```
读取 .planning/phases/0N-*/0N-PLAN.md（任意编号）
├─ 存在 → 状态 "已规划"，建议 /nx-execute N
├─ 不存在 ↓
读取 .planning/phases/0N-*/0N-DISCUSSION-LOG.md 的 YAML frontmatter
├─ 不存在 → 状态 "待处理"，建议 /nx-discuss N
├─ 存在，discussion_status == "completed" → 状态 "已讨论"，建议 /nx-plan N
├─ 存在，discussion_status != "completed" → 状态 "讨论中"，建议 /nx-discuss N（继续）
```

```markdown
# 项目状态: Nebula Chat Server

## 总览
- 总阶段: 11
- 已完成: 6 (55%)
- 进行中: 0
- 待处理: 5

## 阶段详情
| 阶段 | 状态 | 已执行计划 | 验证 | 安全审计 |
|------|------|-----------|------|---------|
| 1 — Scaffolding | Complete | 5/5 | ✅ | ✅ |
| 2 — Infrastructure | Complete | 3/3 | ✅ | ✅ |
| 3 — Database | Complete | 4/4 | ✅ | ✅ |
| 4 — Handler | Complete | 4/4 | ✅ | ✅ |
| 5 — Auth | Complete | 5/5 | ✅ | ✅ |
| 6 — Chat | Complete | 5/5 | ⏳ | ⏳ |
| 7 — Conversation | Pending | — | — | — |
| 8 — Friend | Pending | — | — | — |
| 9 — Reconnection | Pending | — | — | — |
| 10 — Reliability | Pending | — | — | — |
| 11 — Performance | Pending | — | — | — |

## 下一步建议
1. /nx-discuss 7 —— 开始阶段 7 讨论
2. /nx-verify 6 —— 完成阶段 6 的验证追补
```

## 成功标准
- 状态展示准确反映了文件系统实际内容
- GSD 格式的阶段目录被正确识别
- 提供明确的下一步操作建议
