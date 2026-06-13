# 完整工作流参考

本文档提供星云工作流的完整流程参考，包含典型使用示例和各命令之间的交互细节。

## 完整使用示例

### 场景：新项目 — 从零到完成第一个里程碑

```bash
# 1. 初始化项目
/nx-init

# [对话交互] 用户回答项目相关问题后...

# 2. 规划第 1 阶段
/nx-plan 1

# [自动] 生成 PLAN.md，包含 Wave 分组

# 3. 执行第 1 阶段
/nx-exec 1

# [自动] TeamCreate 并行执行 Wave 1 → Wave 2 → Wave 3

# 4. 验证
/nx-verify 1

# [如果有问题]
/nx-exec 1 --gaps-only  # 修复
/nx-verify 1             # 再次验证

# 5. 完成
/nx-done 1

# 6. 查看状态
/nx-status
```

### 场景：已有 GSD 文档的项目

```bash
# 直接查看状态（自动识别已有 .planning/）
/nx-status

# 继续规划
/nx-plan 2  # 如果 GSD 已定义阶段 2
```

### 场景：讨论 + 规划

```bash
# 先讨论再做决策
/nx-discuss 2

# 用户回答完决策问题后，CONTEXT.md 自动生成

# 用讨论结果规划
/nx-plan 2
```

### 场景：只执行特定 Wave

```bash
# 项目较大，想先跑 Wave 1 看看
/nx-exec 1 --wave 1

# Wave 1 完成后，再跑 Wave 2
/nx-exec 1 --wave 2
```

## 命令交互细节

### /nx-init

**典型问答流程：**

```
(用户): /nx-init
(系统): 检测到当前目录没有 .planning/，开始初始化。

Q1: 项目名称？
A: Nebula Server

Q2: 用 2-3 句话描述这个项目做什么？
A: ...

Q3: 核心价值是什么？
A: ...

Q4: 有哪些初始需求？
A: ...

Q5: 技术栈偏好？
A: Kotlin, Spring Boot, PostgreSQL

Q6: 有哪些约束？
A: 无严格时间线
```

**生成文档示例：**

生成后显示摘要。

### /nx-discuss

**典型问答流程：**

```
(用户): /nx-discuss 2

(系统): 加载阶段 2 信息后，识别灰色区域...

Q1: 关于数据模型设计，你倾向哪种方式？
A: 选项 A（推荐）：... 选项 B：...

Q2: API 风格偏好？
A: ...

(系统): 生成 CONTEXT.md

讨论完成 — 阶段 2
锁定决策数：3
排除项：1
下一步：/nx-plan 2
```

### /nx-plan

**典型自动化流程：**

```
1. 读取 ROADMAP.md + CONTEXT.md
2. 启动 nx-researcher 研究技术细节（可选）
3. 分解任务（按复杂度降序）
4. 分析依赖图 → 生成 Wave 分组
5. 写入 PLAN.md
6. 显示摘要

规划完成 — 阶段 2
任务数：8（Wave 1: 3, Wave 2: 3, Wave 3: 2）
预估工作量：3 人天
下一步：/nx-exec 2
```

### /nx-exec

**执行流程示例（阶段 1，3 个 Wave）：**

```
读取 PLAN.md
解析 8 个任务，3 个 Wave

Wave 1 (3 个任务，无依赖):
  ✅ 启动 3 个 nx-executor（并行）
  ✅ Task 1.1: 完成
  ✅ Task 1.2: 完成
  ✅ Task 1.3: 完成

Wave 2 (3 个任务，依赖 Wave 1):
  ✅ 启动 3 个 nx-executor（并行）
  ✅ Task 2.1: 完成
  ✅ Task 2.2: 完成
  ✅ Task 2.3: 完成

Wave 3 (2 个任务，依赖 Wave 2):
  ✅ 启动 2 个 nx-executor（并行）
  ✅ Task 3.1: 完成
  ❌ Task 3.2: 失败 — 依赖库版本问题

询问用户：重试/跳过？
用户：重试
✅ Task 3.2: 重试成功

执行完成 — 阶段 1
Wave 1: ✅ 3/3 | Wave 2: ✅ 3/3 | Wave 3: ✅ 2/2
总计：8/8 任务完成
下一步：/nx-verify 1
```

### /nx-verify

**验证流程示例：**

```
检查 8 个任务的验收标准...

Task 1.1: ✅ 数据库表存在，字段正确
Task 1.2: ✅ API 端点就绪，返回格式正确
Task 1.3: ✅ 错误处理实现
Task 2.1: ✅ 核心逻辑实现，单元测试通过
Task 2.2: ⚠️ 基本功能实现但缺少空状态处理
Task 2.3: ✅ 日志记录就绪
Task 3.1: ✅ 集成测试通过
Task 3.2: ✅ 重试后通过

验证报告：
通过: 7
部分通过: 1 (Task 2.2)
未通过: 0

生成 gap_closure 修复计划...
是否立即修复？(y/n)
```

### /nx-done

**完成流程示例：**

```
检查阶段 1 验证状态... ✅ 已验证通过

更新 PROJECT.md:
  - 移动 3 条需求到 Validated
  - 记录 2 条决策到 Key Decisions
  - 更新 Last updated

更新 STATE.md:
  - 当前阶段: 阶段 2
  - 状态: 待开始
  - 完成阶段: 阶段 1 ✅

快速审计:
  - 未提交的代码？→ 无
  - 需要更新文档？→ 推荐

阶段 1 完成！
已完成任务：8
已记录决策：2 条
已验证需求：3 条

下一阶段：阶段 2: API 集成
1. /nx-discuss 2 — 讨论下一阶段
2. /nx-plan 2 — 规划
3. /nx-status — 查看全景
```

## 状态外置的恢复场景

### 场景：新对话，记住项目

用户在新对话中执行 `/nx-status` 或 `/nx-plan 2`：

1. 命令读取 `.planning/STATE.md`
2. 获取当前阶段、完成进度
3. 读取 PROJECT.md 了解全局约束
4. 读取当前阶段的 PLAN.md 或 ROADMAP.md
5. 开始工作

无需任何对话历史，完全基于文件状态。

### 场景：对话被打断，恢复

1. 之前的对话意外结束
2. 新对话执行 `/nx-status`
3. 看到上次执行到 Wave 2 的 Task 2.2
4. 继续 `/nx-exec 1 --wave 2` 重新执行 Wave 2

文件状态不会丢失。

## 决策原则

### 什么该记录到文档

- **PROJECT.md**: 项目级约束、关键决策、已验证需求
- **CONTEXT.md**: 阶段级决策、用户偏好、排除项
- **PLAN.md**: 任务分解、执行状态、验收结果
- **STATE.md**: 当前状态、进度、阻塞项

### 什么不该记录到文档

- 临时性信息（正在考虑但未决定的选项）
- 对话中的闲聊或非决策性讨论
- 代码实现细节（已经写进代码的）
- private 的 token 或密码
