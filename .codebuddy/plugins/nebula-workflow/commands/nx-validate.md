---
description: Nyquist 测试覆盖审计 —— 差距分析 → 测试生成 → VALIDATION.md。按源文件并行派发审计子 agent。
argument-hint: "<N> [阶段编号]"
---

# 测试覆盖审计

## 目标
对阶段 N 进行 Nyquist 测试覆盖审计，分析测试覆盖率差距，自动生成缺失的测试用例。补全 GSD 的测试覆盖闭环。

## 参数
- `$ARGUMENTS`：阶段编号 N（必需）

## 门禁检查

### Pre-flight Gate
```bash
# 检查 VERIFICATION.md 存在
if [ ! -f ".planning/phases/0${N}-*/0${N}-VERIFICATION.md" ]; then
  提示: "VERIFICATION.md 不存在，建议先执行 /nx-verify N"
  询问: "是否跳过验证直接审计？[y/N]"
fi
```

## 流程

### 步骤 1：收集输入

从 SUMMARY.md 提取 key-files 列表，作为审计目标。

```bash
# 从 SUMMARY.md frontmatter 获取 key-files
KEY_FILES=$(grep "key-files:" SUMMARY.md)

# 查找已有测试
for SRC in "${KEY_FILES[@]}"; do
  TEST_FILE=$(echo "$SRC" | sed 's|src/main|src/test|; s|\.kt|Test.kt|')
  echo "$SRC → $TEST_FILE ($([ -f "$TEST_FILE" ] && echo '已存在' || echo '缺失'))"
done
```

### 步骤 2：派发 nx-nyquist-auditor（按源文件并行审计）

使用 `Agent` 工具启动 `nx-nyquist-auditor` agent。nx-nyquist-auditor 内部执行流程：

1. **收集源文件列表**：从 SUMMARY.md 提取 key-files
2. **并行审计**：为每个源文件同时派发一个子 agent（`subagent_type: test-automator`，加入当前团队）：
   - 每个 `auditor-{filename}` 负责一个源文件的覆盖分析（类/方法/分支/边界）→ 差距分级（P0/P1/P2）→ 测试生成（P0 完整/P1 骨架/P2 仅记录）
3. **聚合结果**：汇总所有子 agent 的审计结果 → 运行生成的测试 → 生成 VALIDATION.md

```
Agent:
  name: "nx-nyquist-auditor"
  subagent_type: "nx-nyquist-auditor"
  team_name: <当前团队名>
  prompt: |
    对阶段 N 进行 Nyquist 测试覆盖审计。
    阶段编号：{N}
    Key Files: {从 SUMMARY.md 提取}
    
    按源文件并行派发审计子 agent 进行覆盖分析和测试生成。
```

### 步骤 3：读取并展示审计结果

nx-nyquist-auditor 完成后，VALIDAION.md 写入阶段目录。读取并展示覆盖摘要和新增测试。

## 输出

nx-nyquist-auditor 生成的 VALIDATION.md 格式：

```markdown
---
phase: N
auditor: nx-nyquist-auditor
status: complete|partial
---
# Phase N 测试覆盖审计

## 覆盖摘要
- 源码文件：N 个
- 测试文件：M 个（新增 K 个）
- 方法覆盖率：X% → Y%

## 新增测试
| 测试类 | 测试方法 | 覆盖目标 | 优先级 |
|--------|---------|---------|--------|

## 遗留差距
| 源码 | 未覆盖方法 | 原因 |
|------|-----------|------|

## NYQUIST AUDIT COMPLETE
```

## 成功标准
- 所有源文件的覆盖审计完成（通过并行子 agent）
- P0 缺口已生成完整测试
- P1 缺口已生成测试骨架
- 覆盖率有可测量的提升
- VALIDATION.md 已写入阶段目录
