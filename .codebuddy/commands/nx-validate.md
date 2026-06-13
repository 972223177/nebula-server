---
description: Nyquist 测试覆盖审计 —— 差距分析 → 测试生成 → VALIDATION.md
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

### 步骤 1：收集源码和测试文件

```bash
# 收集阶段相关的源码文件
SOURCE_FILES=$(从 PLAN.md 的 Key Files 获取)

# 收集已有测试文件
TEST_FILES=$(find src/test -name "*Test.kt" -path "*${phase_related}*")
```

### 步骤 2：测试覆盖分析

对每个源码文件分析：
- **类覆盖**: 每个 public class/interface 是否有对应的测试类
- **方法覆盖**: 每个 public 方法是否有对应的测试方法
- **分支覆盖**: 关键 if/when/try-catch 分支是否有测试
- **边界覆盖**: null 输入、空集合、大数据量等边界是否有测试

### 步骤 3：差距分析

```markdown
## 测试覆盖差距

| 源码文件 | 已有测试 | 未覆盖方法 | 未覆盖分支 | 优先级 |
|---------|---------|-----------|-----------|--------|
| Handler.kt | HandlerTest.kt | handleError() | 超时分支 | P0 |
| Service.kt | — | 全部 | — | P1 |
```

### 步骤 4：生成测试

派发 `nx-nyquist-auditor` agent 生成缺失测试：

**生成策略**：
- P0 缺失（核心业务逻辑无测试）→ 自动生成完整测试
- P1 缺失（辅助逻辑无测试）→ 生成测试骨架，标注待补充的断言
- P2 缺失（工具类无测试）→ 记录差距，建议手动补充

**代码生成原则**：
- 遵循项目现有测试模式（从已有测试文件中提取模板）
- 使用项目技术栈的测试框架（Kotlin → JUnit5 + MockK）
- 每个测试方法包含 Given-When-Then 结构

### 步骤 5：验证生成的测试

```bash
# 运行生成的测试
./gradlew :module:test --tests "*GeneratedTest*"

# 如果测试失败 → 分析原因 → 修复 → 重新验证
```

### 步骤 6：更新覆盖统计

```markdown
## 测试覆盖报告

### 覆盖摘要
- 源码文件：N 个
- 测试文件：M 个（新增 K 个）
- 类覆盖率：X% → Y%
- 方法覆盖率：X% → Y%

### 新增测试
| 测试类 | 测试方法数 | 覆盖目标 |
|--------|----------|---------|
```

## 输出

写入 `NN-VALIDATION.md`：
```markdown
---
phase: N
auditor: nx-nyquist-auditor
status: complete|partial
---
# Phase N 测试覆盖审计

## 审计摘要
- 覆盖率提升：X% → Y%
- 新增测试文件：K 个
- 新增测试方法：M 个

## 差距表格
...

## 生成的测试
...

## NYQUIST AUDIT COMPLETE
```

## 成功标准
- 所有 P0 差距已生成测试并通过
- P1 差距已生成测试骨架
- 覆盖率有可测量的提升
- VALIDATION.md 已写入阶段目录
