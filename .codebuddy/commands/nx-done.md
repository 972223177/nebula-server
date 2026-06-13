---
description: 阶段归档 —— 更新 STATE.md，标记阶段完成
argument-hint: "<N> [阶段编号]"
---

# 阶段归档

## 目标
将阶段 N 标记为完成，更新 STATE.md 中的状态，确保所有产出物齐全。

## 参数
- `$ARGUMENTS`：阶段编号 N（必需）

## 门禁检查

### Pre-flight Gate
```bash
# 检查阶段存在
if [ ! -d ".planning/phases/0${N}-*" ]; then
  报错: "阶段 N 目录不存在"
fi
```

### Completion Gate（完整性检查）

按阶段类型区分必需文件：

```bash
# 1. 通用必需文件（所有阶段）
BASE_FILES=("NN-*-PLAN.md" "NN-*-SUMMARY.md")

# 2. 根据 ROADMAP.md 中该阶段的 phase_type 确定增量文件
PHASE_TYPE=$(从 ROADMAP.md 读取阶段类型: implementation|infrastructure|config|documentation)

case "$PHASE_TYPE" in
  "implementation")
    REQUIRED_FILES=("${BASE_FILES[@]}" "NN-CONTEXT.md" "NN-RESEARCH.md" "NN-PATTERNS.md" "NN-VERIFICATION.md" "NN-VALIDATION.md" "NN-SECURITY.md")
    ;;
  "infrastructure")
    REQUIRED_FILES=("${BASE_FILES[@]}" "NN-CONTEXT.md" "NN-RESEARCH.md" "NN-VERIFICATION.md")
    # 豁免：SECURITY（无安全问题）、VALIDATION（测试审计不适用）、PATTERNS（基础设施无模式参考）
    ;;
  "config")
    REQUIRED_FILES=("${BASE_FILES[@]}" "NN-VERIFICATION.md")
    # 豁免：RESEARCH、PATTERNS、CONTEXT、SECURITY、VALIDATION（纯配置阶段）
    ;;
  "documentation")
    REQUIRED_FILES=("${BASE_FILES[@]}" "NN-CONTEXT.md" "NN-VERIFICATION.md")
    # 豁免：RESEARCH、PATTERNS、SECURITY、VALIDATION（文档阶段不需要代码验证）
    ;;
  *)
    # 未知类型，使用最严格标准
    REQUIRED_FILES=("${BASE_FILES[@]}" "NN-CONTEXT.md" "NN-RESEARCH.md" "NN-PATTERNS.md" "NN-VERIFICATION.md" "NN-VALIDATION.md" "NN-SECURITY.md")
    ;;
esac

for FILE in "${REQUIRED_FILES[@]}"; do
  if [ ! -f "${PHASE_DIR}/${FILE}" ]; then
    列出缺失文件
    MISSING_FILES+=("$FILE")
  fi
done

if [ ${#MISSING_FILES[@]} -gt 0 ]; then
  报错: "阶段 N 缺少必要文件: ${MISSING_FILES[*]}"
  提示: "使用 --skip-validation 跳过验证，但建议先补全缺失文件"
  exit 1
fi
```

### Verification Gate

```bash
# 检查 VERIFICATION.md 状态
VERIFY_STATUS=$(提取 VERIFICATION.md frontmatter 的 status)
if [ "$VERIFY_STATUS" != "passed" ]; then
  警告: "验证状态为 $VERIFY_STATUS，归档可能不完整"
  询问: "是否强制归档？[y/N]"
fi
```

## 流程

### 步骤 1：更新 STATE.md

```markdown
| N — <阶段名称> | Complete | <开始日期> | <完成日期> |
```

更新 frontmatter：
```yaml
progress:
  completed_phases: N+1
  percent: <新百分比>
```

### 步骤 2：Git 提交

```bash
# 步骤 A：自动提交所有未提交变更（源码 + 规划文件）
UNCOMMITTED=$(git status --porcelain)
if [ -n "$UNCOMMITTED" ]; then
  git add -A
  git commit -m "chore(phase-${N}): 阶段归档 —— 自动提交未归档变更"
  echo "未归档变更已自动提交"
else
  echo "无未归档变更"
fi

# 步骤 B：归档提交（仅规划文件）
git add .planning/STATE.md .planning/phases/0${N}-*/
git commit -m "done: 阶段 ${N} 归档 —— 所有产出物已交付"
```

### 步骤 3：展示完成摘要

```markdown
# 阶段 N 完成 ✅

## 交付摘要
- PLAN 数：M
- 代码提交数：K
- 测试文件：T
- 验证状态：PASSED

## 下一步
- /nx-discuss N+1 —— 开始下一阶段
- /nx-status —— 查看最新状态
```

## 成功标准
- STATE.md 中阶段状态已更新为 Complete
- 所有必需文件已验证存在
- 变更已提交到 git
- 用户知道下一步操作
