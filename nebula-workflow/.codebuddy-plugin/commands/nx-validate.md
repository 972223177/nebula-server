---
description: 验证代码质量，运行测试，扫描反模式、存根、技术债务
argument-hint: [阶段号] [--skip-tests]
allowed-tools: Read, Write, Grep, Glob, Bash, Agent, Task, AskUserQuestion
---

# /nx-validate — 代码验证

## 目的

在执行完成后验证代码质量，包含三个步骤：
1. 运行测试（如果有测试文件）
2. 扫描反模式、存根代码和技术债务
是执行与功能验证之间的质量网关。
发现问题则生成修复计划并记录技术债务。

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 建议先执行 `/nx-exec <N>` 运行过实现
- 任务需标记为已完成

## 流程

### 第一步：加载验证上下文

1. 读取 `.planning/phases/<N>/PLAN.md`
2. 识别所有标记为"已完成"的任务和其关联文件
3. 读取 `.planning/PROJECT.md` 了解代码规范

### 第二步：运行测试

如果项目中存在测试文件且未指定 `--skip-tests`：
```bash
# Gradle 项目
./gradlew test --no-daemon
# 或 Maven 项目
mvn test -q
```

记录测试结果，作为验证报告的一部分。

### 第三步：启动 nx-code-validator agent

使用 `Agent` 工具启动 nx-code-validator agent：

```
Agent(
  nx-code-validator,
  验证阶段 <N> 的代码质量，关联文件列表：[从 PLAN.md 提取的文件路径]
)
```

**注意**：nx-code-validator 负责代码质量检查（反模式、存根、错误处理、文档、测试覆盖），不负责功能是否实现（由 nx-verifier 负责）。

### 第四步：阅读验证报告

Agent 输出后，读取 `.planning/phases/<N>/VALIDATION.md`。

### 第五步：向用户展示结果

```markdown
## 代码验证报告 — 阶段 <N> (Gate: Revision)

### 测试
- ✅/❌ 测试通过 / X 个失败

### 遗留标记
- ✅/⚠️/❌ 发现 X 处

### 代码存根
- ✅/⚠️/❌ 发现 X 处

### 错误处理
- ✅/⚠️/❌ 发现 X 处

### 文档注释
- ✅/⚠️/❌ 发现 X 处

### 测试覆盖
- ✅/⚠️/❌ 发现 X 处
```

### 第六步：处理问题

#### 如果有 BLOCKER

1. 在 PLAN.md 中追加 gap_closure 任务：

```markdown
### 任务 N.G1: [修复代码质量问题]
**状态**: 待开始
**gap_closure**: true
**描述**: [具体问题]
**验收标准**:
- [ ] 修复问题 1
- [ ] 修复问题 2
```

2. 输出引导：
```markdown
## ❌ 代码验证发现问题 — 阶段 <N>

BLOCKER: X 个（必须修复）
WARNING: Y 个（建议修复）

### 下一步（二选一）

1. **修复后重新验证**：
   `/nx-exec <N> --gaps-only` → `/nx-validate <N>`
2. **记录技术债务后继续**：
   将 BLOCKER 降级为技术债务记录到 PROJECT.md，
   然后执行 `/nx-verify <N>` 进入功能验证
```

#### 如果只有 WARNING

1. 输出引导：
```markdown
## ⚠️ 代码验证完成（有建议） — 阶段 <N>

WARNING: Y 个（建议修复）
无 BLOCKER

### 下一步（二选一）

1. **记录技术债务**：在 PROJECT.md 中记录警告项 → `/nx-verify <N>`
2. **直接继续**：忽略警告 → `/nx-verify <N>` 进入功能验证
```

#### 全部通过

```markdown
## ✅ 代码验证通过 — 阶段 <N>

所有代码质量检查通过。

### 下一步

执行 `/nx-verify <N>` 进行目标逆向验证（最终质量网关）
```
