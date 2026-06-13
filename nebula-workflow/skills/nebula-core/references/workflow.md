# 完整工作流参考

本文档提供星云工作流的完整流程参考，包含典型使用示例和各命令之间的交互细节。

## 完整工作流

```
/nx-init → /nx-discuss → /nx-plan → /nx-check-plan → /nx-exec → /nx-validate → /nx-verify → /nx-done
                                                                       ↑                        |
                                                                       └── 失败 → 修复后再试 ────┘

一键模式（推荐）：
/nx-init → /nx-phase <N>  （自动串联所有步骤，遇到问题暂停）
```

三个质量网关：

1. **`/nx-check-plan`** — 规划审核（在执行前验证计划质量）
2. **`/nx-validate`** — 代码验证（在执行后验证代码质量，含测试运行和编译检查）
3. **`/nx-verify`** — 目标验证（在完成前验证阶段目标达成）

暂停恢复：
- **`/nx-pause`** — 暂停工作流，状态写入 PAUSE.md
- **`/nx-resume`** — 从 PAUSE.md 恢复执行

## Gate 类型分类

Nebula 采用 GSD 的四种规范化 Gate 类型，决定验证失败时的行为：

| Gate 类型 | 触发时机 | 行为 | Nebula 中的位置 |
|-----------|---------|------|----------------|
| **Pre-flight** | 操作开始前 | 条件不满足则阻止，不留半成品 | `/nx-plan` 检查 ROADMAP.md<br>`/nx-exec` 检查 PLAN.md |
| **Revision** | 审查产出物质量 | 反馈给产出者修改，有迭代上限（3 次） | `/nx-check-plan` → nx-planner<br>`/nx-validate` → 追加 gap_closure |
| **Escalation** | 无法自动解决 | 暂停，展示选项，等待人工决策 | `/nx-exec` 失败 > 3 次<br>`/nx-verify` 发现不可自动修复的 gap |
| **Abort** | 继续执行有危害 | 立即停止，保留现场，报告原因 | STATE.md 损坏<br>关键制品缺失导致无法继续 |

### Gate 选择启发式

1. 从 **Pre-flight** 开始 — 检查前置条件
2. 产出物质量审查 — 使用 **Revision**
3. Revision 循环无法收敛 → 升级为 **Escalation**
4. 继续执行会造成损害 → 使用 **Abort**

## 完整使用示例

### 场景：新项目 — 从零到完成第一个里程碑

```bash
# 1. 初始化项目
/nx-init

# [对话交互] 用户回答项目相关问题后...

# 2. 规划第 1 阶段
/nx-plan 1

# [自动] 生成 PLAN.md，包含 Wave 分组

# 3. 规划审核（质量网关 1）
/nx-check-plan 1

# [自动] 审核 PLAN.md 质量，检查需求覆盖、任务完整性、依赖正确性
# [如有 BLOCKER] 需修改后重新审核

# 4. 执行第 1 阶段
/nx-exec 1

# [自动] TeamCreate 并行执行 Wave 1 → Wave 2 → Wave 3

# 5. 代码验证（质量网关 2）
/nx-validate 1

# [自动] 扫描反模式、存根代码、技术债务
# [如有问题] 修复后重新 validate

# 6. 目标验证（质量网关 3）
/nx-verify 1

# [自动] goal-backward 验证阶段目标是否真正实现
# [如果有问题]
/nx-exec 1 --gaps-only  # 修复
/nx-verify 1             # 再次验证

# 7. 完成
/nx-done 1

# 8. 查看状态
/nx-status
```

### 场景：已有 GSD 文档的项目

```bash
# 直接查看状态（自动识别已有 .planning/，构建阶段目录映射）
/nx-status

# [输出] 检测到 GSD 项目，已自动构建阶段目录映射
# [输出] 阶段 1-6 已完成，当前阶段 7（未开始）

# 继续规划下一阶段
/nx-plan 7  # 自动根据映射表找到 07-conversation/ 目录
```

首次使用 `/nx-status` 时，会自动：
1. 扫描 `.planning/phases/` 中的 GSD 格式目录（如 `01-project/`）
2. 构建"阶段目录映射"表并写入 STATE.md
3. 之后所有命令通过映射表查找目录

**兼容点：**
- GSD 文件（如 `01-CONTEXT.md`）可被自动读取
- 新生成的文件使用 nebula 格式（如 `CONTEXT.md`）
- 两种格式文件可共存于同一目录

### 场景：讨论 + 规划

```bash
# 先讨论再做决策
/nx-discuss 2

# 用户回答完决策问题后，CONTEXT.md 自动生成

# 用讨论结果规划
/nx-plan 2
```

### 场景：使用一键命令

```bash
# 一键完成阶段 1（含讨论、规划、审核、执行、验证、归档）
/nx-phase 1

# 跳过讨论直接开始
/nx-phase 1 --skip-discuss

# 全自动模式（不询问用户）
/nx-phase 1 --skip-discuss --auto

# 带 git 快照的执行，失败可回滚
/nx-phase 1 --snapshot
```

### 场景：暂停与恢复

```bash
# 阶段执行中暂停
/nx-pause 需要先处理紧急 bug

# [PAUSE.md 已写入，对话可安全关闭]

# 新对话中恢复
/nx-resume
# 显示暂停状态，确认后自动从断点继续
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
下一步：/nx-check-plan 2
```

### /nx-check-plan

**规划审核流程示例：**

```
审核阶段 2 的 PLAN.md...

维度 1—需求覆盖: ✅ 所有阶段需求都有对应任务
维度 2—任务完整性: ✅ 所有任务字段完整
维度 3—依赖正确性: ⚠️ Wave 3 任务 2.7 依赖 Wave 2 的 2.4，但 Wave 分组不一致
  建议：将任务 2.7 移入 Wave 3
维度 4—范围合理性: ✅ 8 个任务，3 个 Wave，在合理范围内
维度 5—验收标准可验证性: ✅ 所有标准可验证
维度 6—上下文合规: ✅ 计划尊重 CONTEXT.md 中的所有锁定决策

审核结果：
BLOCKER: 0
WARNING: 1（依赖分组建议）

建议：✅ 通过（建议修复 WARNING）

是否修改后重新提交审核？(y/n)
```

### /nx-exec

**执行流程示例（阶段 1，3 个 Wave）：**

```
读取 PLAN.md
解析 8 个任务，3 个 Wave

Wave 1 (3 个任务，无依赖):
  📸 git 快照: HEAD = a1b2c3
  ✅ 启动 3 个 nx-executor（并行）
  ✅ Task 1.1: 完成
  ✅ Task 1.2: 完成
  ✅ Task 1.3: 完成
  🔨 编译检查: ✅ ./gradlew build -x test 通过

Wave 2 (3 个任务，依赖 Wave 1):
  📸 git 快照: HEAD = d4e5f6
  ✅ 启动 3 个 nx-executor（并行）
  ✅ Task 2.1: 完成
  ✅ Task 2.2: 完成
  ✅ Task 2.3: 完成
  🔨 编译检查: ✅ 通过

Wave 3 (2 个任务，依赖 Wave 2):
  📸 git 快照: HEAD = j7k8l9
  ✅ 启动 2 个 nx-executor（并行）
  ✅ Task 3.1: 完成
  ❌ Task 3.2: 失败 — 依赖库版本问题

询问用户：重试/回滚/跳过？
用户：回滚
↩️ git checkout . && git clean -fd → 回退到 Wave 3 执行前
重试 Task 3.2...
✅ Task 3.2: 重试成功
🔨 编译检查: ✅ 通过

执行完成 — 阶段 1
Wave 1: ✅ 3/3 | Wave 2: ✅ 3/3 | Wave 3: ✅ 2/2
编译: 全部通过
总计：8/8 任务完成
下一步：/nx-validate 1 — 代码质量验证
```

### /nx-validate

**代码验证流程示例：**

```
验证阶段 1 的代码质量，扫描关联文件...

反模式扫描:
  Task 1.1 — src/main/.../MessageController.kt: ✅ 无遗留标记
  Task 1.2 — src/main/.../MessageService.kt: ⚠️ 发现 FIXME（行 45）
    备注：引用了 issue #123，转为 WARNING

代码存根检测: ✅ 未发现存根代码

错误处理检查: ⚠️ Task 2.1 的 sendMessage 缺少空输入处理
  建议：添加空消息校验

文档注释检查: ✅ 新增 public 方法均有 KDoc

测试覆盖检查:
  - MessageController: ✅ 有测试文件
  - MessageService: ❌ 无测试文件（WARNING — 核心逻辑建议添加测试）

验证结果：
BLOCKER: 0
WARNING: 3（FIXME 标记、空输入处理、缺少测试）

建议：⚠️ 建议修复（可跳过到 /nx-verify）

是否记录到技术债务？(y/n)
```

### /nx-verify (目标逆向验证)

**验证流程示例：**

```
启动 goal-backward 验证...
阶段目标：用户能发送和接收聊天消息

推导可观测真相：
1. 用户能发送文本消息
2. 消息能正确持久化到数据库
3. 接收方能实时收到消息

验证真相 1 — 用户能发送文本消息:
  ✅ Controller: POST /api/messages 存在
  ✅ Service: MessageService.sendMessage() 真实实现（非存根）
  ✅ 关键链: Controller → Service → Repository 已连接

验证真相 2 — 消息能正确持久化:
  ✅ Repository: MessageRepository.save() 调用 JPA
  ✅ 数据库实体: MessageEntity 字段完整
  ⚠️ 连接: Service 的 save 调用已实现但未验证事务边界
    建议：确认 @Transactional 注解存在

验证真相 3 — 接收方实时接收:
  ✅ WebSocket: /ws/chat 端点存在
  ❌ 关键链: WebSocket 发送→接收 未找到完整实现
    存根：接收方消息处理器只打印日志，未推送到客户端

验证报告：
可观测真相: 2/3 通过
制品检查: 5/6 通过
关键链: 2/3 已连接

人类验证项:
1. WebSocket 消息推送 — 需要手动启动服务测试

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

### 场景：使用暂停/恢复

1. 阶段执行中，需要切换到其他任务
2. 执行 `/nx-pause 紧急任务插队`
3. `.planning/PAUSE.md` 记录当前状态
4. 对话结束（或切换上下文）
5. 新对话中执行 `/nx-resume`
6. 自动从断点继续执行

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
