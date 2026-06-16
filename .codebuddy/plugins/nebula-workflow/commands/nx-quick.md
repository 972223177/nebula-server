---
description: 快捷任务 —— 轻量规划 + 专家 Agent 调度 + Git 原子提交，支持 list/status/resume 子命令
argument-hint: "[子命令] [--expert <name>] [--team] [--consult] [--review] [--verify] [--full]"
---

# 快捷任务

## 目标
为小型临时任务提供轻量级执行路径，无需完整阶段流程（讨论→研究→规划→执行→验证）。核心特色：自动推断最合适的 CodeBuddy 专家 Agent 执行任务。

**与 `/nx-exec N` 的区别：**
- 无需预先的讨论和规划阶段，适合"我知道要做什么"的任务
- 自动从任务描述推断领域专家，也可手动指定 `--expert`
- 产物存放到 `.planning/quick/`，不修改阶段进度和 ROADMAP.md
- 支持 `--team` 多专家并行协作

## 参数

### 子命令

| 子命令 | 说明 |
|--------|------|
| `list` | 列出所有 quick task 及其完成状态 |
| `status <slug>` | 查看指定 quick task 的详情（PLAN.md + SUMMARY.md） |
| `resume <slug>` | 恢复被中断的 quick task，继续执行未完成部分 |
| （默认，无子命令） | 启动 quick 任务执行流程 |

### 质量管道标志

| 标志 | 说明 |
|------|------|
| （无） | 默认：任务收集 → 自动规划 → 专家执行→ Git 提交 |
| `--consult` | 执行前启动专家咨询服务，输出 EXPERT-CONSULT.md |
| `--review` | 执行后启动代码审查 agent，输出 REVIEW.md |
| `--verify` | 执行后启动四层验证（nx-verifier），输出 VERIFICATION.md |
| `--full` | 完整质量管道，等同于 `--consult --review --verify` |

### 专家调度标志

| 标志 | 说明 |
|------|------|
| `--expert <name>` | 手动指定执行专家（如 `backend-architect`、`debugger` 等）。跳过自动推断 |
| `--team` | 多专家并行协作模式：任务分解后按类型自动分组，每个任务分配最匹配的专家并行执行 |

## 门禁检查

### Pre-flight Gate

检查 `.planning/` 目录和 `STATE.md` 是否存在：

```bash
test -d ".planning/" || { echo "错误: .planning/ 目录不存在，请先执行 /nx-init"; exit 1; }
test -f ".planning/STATE.md" || { echo "错误: STATE.md 不存在，请先执行 /nx-init"; exit 1; }
```

## 流程

### 步骤 1：子命令分发

从 `$ARGUMENTS` 解析子命令：

```
$ARGUMENTS 以 "list" 开头      → SUBCMD=list，跳转到步骤 2
$ARGUMENTS 以 "status " 开头   → SUBCMD=status，SLUG=剩余部分，跳转到步骤 3
$ARGUMENTS 以 "resume " 开头   → SUBCMD=resume，SLUG=剩余部分，跳转到步骤 4
其他                          → SUBCMD=run，跳转到步骤 5
```

**Slug 安全清洗：** 过滤非 `[a-z0-9-]` 字符，拒绝含 `..`、`/` 或长度 > 60 的 slug。若无效则输出 `无效的 slug: {SLUG}` 并停止。

**标志提取：** 从 `$ARGUMENTS` 中提取标志值：
```bash
CONSULT_MODE=false      # --consult
REVIEW_MODE=false       # --review
VERIFY_MODE=false       # --verify
FULL_MODE=false         # --full
MANUAL_EXPERT=""        # --expert <name>
TEAM_MODE=false         # --team

# --full 覆盖三项单独标志
if [ "$FULL_MODE" = true ]; then
  CONSULT_MODE=true
  REVIEW_MODE=true
  VERIFY_MODE=true
fi
```

---

### 步骤 2：list 子命令

列出所有 quick task：

```bash
ls -d .planning/quick/*/ 2>/dev/null
```

若无结果，输出 `暂无 quick task。` 并停止。

对每个目录：
- 提取 YYYYMMDD（目录名前 8 位）和 slug（第 9 位起，去掉末尾 `/`）
- 检查 `SUMMARY.md` 是否存在
- 若存在，读取 frontmatter 的 `status` 字段判断完成状态
- 若不存在，按目录创建日期判断状态：
  - 创建 < 7 天前 → `in-progress`
  - 创建 ≥ 7 天前 → `abandoned?（超过 7 天无摘要）`

展示格式：
```
快捷任务列表
══════════════════════════════════════════════════════
slug                          日期         状态
fix-auth-token                2026-06-16   complete 完成
add-user-avatar               2026-06-15   in-progress
refactor-logging              2026-06-08   abandoned?（超过 7 天无摘要）
══════════════════════════════════════════════════════
共 3 个任务（1 完成，2 未完成/进行中）
```

**安全要求：** 展示前对目录名做安全清洗，过滤不可打印字符和路径分隔符。

列表展示后停止，不继续后续流程。

---

### 步骤 3：status 子命令

查找匹配 `*-{SLUG}` 的目录：

```bash
dir=$(ls -d .planning/quick/*-{SLUG}/ 2>/dev/null | head -1)
```

若无匹配目录，输出 `未找到 slug 为 "{SLUG}" 的 quick task。` 并停止。

读取目录中的 `PLAN.md` 和 `SUMMARY.md`（若存在）：

展示格式：
```
快捷任务: {slug}
═══════════════════════════════════════════════
目录: .planning/quick/{dir}/
计划: {PLAN.md frontmatter 的 description 字段}
状态: {SUMMARY.md frontmatter 的 status，或 "无摘要"}
任务数: {PLAN.md 任务表行数}
专家: {PLAN.md frontmatter 的 expert 字段，或 "未指定"}
═══════════════════════════════════════════════
恢复命令: /nx-quick resume {slug}
```

状态查询后停止，不继续后续流程。

---

### 步骤 4：resume 子命令

查找匹配 `*-{SLUG}` 的目录（同步骤 3）。

打印恢复摘要后：
1. 加载 `PLAN.md` 和 `SUMMARY.md` 的已有上下文
2. 识别已完成的任务（SUMMARY.md 中 `completed_tasks` 列表）
3. 从未完成任务开始继续执行（跳转到步骤 5.6）
4. 完成后更新 SUMMARY.md 和 STATE.md

---

### 步骤 5：run 主流程

#### 5.1 收集任务描述

与用户交互，收集任务信息：

```
请输入快捷任务描述（例如："修复 auth token 刷新逻辑的空指针异常"）：
```

从描述和 `$ARGUMENTS` 中的标志提取：
- `TASK_DESC`：任务描述文本
- `SLUG`：从描述自动生成（取英文关键词，转小写，连字符连接，截断到 60 字符）

示例：
```
输入: "修复 auth token 刷新逻辑的空指针异常"
Slug: fix-auth-token-npe
```

#### 5.2 专家推断（核心特色）

若 `MANUAL_EXPERT` 非空，跳过推断，直接使用用户指定的专家。

否则，从 `TASK_DESC` 提取关键词，按以下映射表匹配：

| 匹配关键词（中文/英文） | 推断专家 | 适用场景 |
|-------------------------|----------|----------|
| API、端点、路由、Handler、gRPC、协议 | `backend-architect` | 接口设计、路由结构、Proto 定义 |
| Service、业务逻辑、协程、Flow、DI | `java-developer` | 业务实现、依赖注入、数据类 |
| 数据库、SQL、表、索引、查询、Repository | `database-optimizer` | 数据层操作、迁移脚本 |
| 测试、用例、覆盖率、断言 | `test-automator` | 单元测试、集成测试 |
| 安全、认证、授权、JWT、加密、权限 | `security-auditor` | 安全加固、认证流程 |
| 性能、优化、缓存、池化、瓶颈 | `performance-engineer` | 性能优化、慢查询 |
| 部署、CI/CD、Docker、配置、构建 | `deployment-engineer` | 部署配置、容器化 |
| 架构、设计、拆分、重构、模块化 | `architect-review` | 架构评审、模式设计 |
| Bug、修复、异常、崩溃、空指针、NPE | `debugger` | 故障诊断、根因分析 |
| 代码审查、规范、风格、检查 | `code-reviewer` | 代码质量审查 |
| 文档、注释、README、说明 | `docs-architect` | 技术文档编写 |
| 构建、Gradle、依赖冲突、模块 | `java-developer` | 构建配置、依赖管理 |

**匹配规则：**
- 遍历所有关键词，第一个匹配到的即为推断结果
- 未匹配到任何关键词 → 回退到 `java-developer`（项目默认专家）
- 推断后向用户确认：

```
[推断] 检测到任务涉及 "{匹配关键词}"，推荐使用 `{expert_name}` 专家。
按 Enter 确认，或输入其他专家名称替换：
```

#### 5.3 创建任务目录

```bash
DATE_PREFIX=$(date +%Y%m%d)
QUICK_DIR=".planning/quick/${DATE_PREFIX}-${SLUG}"

# 确保不覆盖已有目录
if [ -d "$QUICK_DIR" ]; then
  SLUG="${SLUG}-$(date +%H%M)"
  QUICK_DIR=".planning/quick/${DATE_PREFIX}-${SLUG}"
fi

mkdir -p "$QUICK_DIR"
```

#### 5.4 专家咨询（--consult / --full）

当 `CONSULT_MODE=true` 时：

派发推断的专家 Agent（作为只读咨询角色）进行任务分析，生成 `EXPERT-CONSULT.md`。

```
Agent 类型: {推断的 expert_name}
提示词模板:
"作为 {expert_name} 专家，针对以下快捷任务进行快速咨询（只读分析，不要修改代码）：

任务: {TASK_DESC}
项目: 读取 .planning/PROJECT.md 了解项目背景

请分析：
1. 潜在风险点和陷阱
2. 推荐实现方案（1-2 种，标注优缺点 D-附录）
3. 需要关注的边界条件和依赖

输出为 EXPERT-CONSULT.md（项目根目录），咨询完毕后我会移动到正确位置。"
```

咨询完成后，将生成的 `EXPERT-CONSULT.md` 移动到 `$QUICK_DIR/`。

#### 5.5 生成计划

无论是否有咨询结果，均生成简洁的 `PLAN.md`（1-3 个任务）。

**模板结构：**
```markdown
---
slug: {SLUG}
description: {任务的精简中文描述}
created: {YYYY-MM-DD}
expert: {推断或指定的 expert_name}
mode: quick
tasks: {任务数}
---

# Quick Plan: {slug}

## 任务描述

{完整的任务描述}

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | src/... | {操作描述} | {编译通过/单元测试} |
| 2 | create | src/.../Test.kt | 添加单元测试 | 测试通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
- EXPERT-CONSULT.md（若有）
```

任务分解原则：
- 简单任务 → 1 个条目
- 中等任务 → 2-3 个条目（实现 + 测试）
- 复杂任务 → 提示用户拆分或改用 `/nx-exec N`

写入 `$QUICK_DIR/PLAN.md`，确认计划后进入执行。

#### 5.6 执行任务

##### 5.6.1 默认模式（--team 未启用）

按任务表顺序，逐个派发专家 Agent 执行：

```
对每个任务：
  1. 调用 Agent({推断的 expert_name})
     提示词:
     "执行 quick 任务 {SLUG} 的任务 #{任务编号}
      
      计划文件: {QUICK_DIR}/PLAN.md
      任务操作: {操作描述}
      目标文件: {文件路径}
      验证要求: {验证标准}
      
      注意：
      - 只修改任务指定的文件，不做额外重构
      - 注释遵循 CODEBUDDY.md 规范（中文优先）
      - 完成后用 SendMessage 报告结果
      - 输出包含：修改了哪些文件、是否通过验证"
  
  2. 收集结果 → 记录到 SUMMARY.md 进度
```

##### 5.6.2 --team 多专家模式

当 `TEAM_MODE=true` 时：

1. **创建临时团队**：
```bash
TEAM_NAME="quick-${SLUG}"
# 使用 DeferExecuteTool ToolSearch 加载 TeamCreate schema
# TeamCreate(team_name: TEAM_NAME)
```

2. **任务分组**：扫描 PLAN.md 任务表，按任务类型自动分配专家：
   - 实现类任务 → 推断专家 / 手动指定专家
   - 测试类任务 → `test-automator`
   - 多种类型任务 → 每种类型一个专家

3. **并行派发**：所有专家 Agent 加入同一团队并行执行：
```
Agent(name: "expert-1", subagent_type: "{为 task-1 推断的 expert}", team_name: TEAM_NAME, prompt: "...")
Agent(name: "expert-2", subagent_type: "{为 task-2 推断的 expert}", team_name: TEAM_NAME, prompt: "...")
...
```

4. **结果汇总**：
```
SendMessage(type: broadcast, content: "收集所有专家执行结果...")

汇总内容：
- 每个专家修改了哪些文件
- 验证是否通过
- 是否有阻塞问题
```

5. **清理团队**：
```bash
# TeamDelete(team_name: TEAM_NAME)
```

#### 5.7 代码审查（--review / --full）

当 `REVIEW_MODE=true` 时：

派发 `code-reviewer` Agent 审查本次修改的文件：
```
Agent(subagent_type: "code-reviewer")
提示词:
"审查 quick 任务 {SLUG} 的代码变更
 计划: {QUICK_DIR}/PLAN.md
 变更文件: {从 SUMMARY.md 提取的文件列表}
 
 检查项：
 - 安全性（注入、越权）
 - 代码质量（命名、重复、复杂度）
 - 项目规范符合度（CODEBUDDY.md）
 
 输出 REVIEW.md 到项目根目录。"
```

将 `REVIEW.md` 移动到 `$QUICK_DIR/`。

#### 5.8 验证（--verify / --full）

当 `VERIFY_MODE=true` 时：

派发 `nx-verifier` Agent 进行四层验证：
```
Agent(subagent_type: "nx-verifier")
提示词:
"对 quick 任务 {SLUG} 进行目标反向验证（四层验证模型）
 计划: {QUICK_DIR}/PLAN.md
 
 验证目标：
 L1 - 存在性：计划声明的文件是否已创建/修改
 L2 - 内容实在性：实现是否满足任务描述
 L3 - 连接性：改动是否正确集成到项目中
 L4 - 数据流通：端到端流程是否可达
 
 输出 VERIFICATION.md 到项目根目录。"
```

将 `VERIFICATION.md` 移动到 `$QUICK_DIR/`。

#### 5.9 Git 提交

从 PLAN.md frontmatter 提取 title 作为提交信息前缀：

```bash
COMMIT_MSG="quick({SLUG}): {description}"
git add "$QUICK_DIR/"          # 提交计划产物
git add <本次修改的源代码文件>    # 提交代码变更
git commit -m "$COMMIT_MSG"
```

#### 5.10 更新 STATE.md

在 STATE.md 的 "Quick Tasks Completed" 表格追加一行：

```markdown
| {DATE} | {SLUG} | {description}（{任务数} 个文件，验证: {通过/未通过}）|
```

**表格不存在时的处理：** 若 STATE.md 中无 "Quick Tasks Completed" 表格，在 "Next Actions" 章节前插入新表格。

---

## 快速参考：常用场景

| 场景 | 命令示例 |
|------|----------|
| 修复一个小 Bug | `/nx-quick 修复 ChatHandler 空指针异常` |
| 添加单元测试 | `/nx-quick 为 UserService 添加单元测试 --expert test-automator` |
| 重构 + 审查 | `/nx-quick 重构数据库连接池 --consult --review` |
| 多专家协作 | `/nx-quick 新增消息已读功能 --team` |
| 完整质量管道 | `/nx-quick 优化登录性能 --full` |
| 查看历史任务 | `/nx-quick list` |

## 成功标准

- [ ] `list` 正确展示所有 quick task 及其状态
- [ ] `status <slug>` 正确展示任务详情
- [ ] `resume <slug>` 从断点恢复未完成任务
- [ ] 默认模式：任务描述 → 专家推断 → 确认 → 计划 → 执行 → Git 提交
- [ ] `--expert <name>` 手动指定专家生效，跳过推断
- [ ] `--team` 模式：TeamCreate → 多专家并行 → 结果汇总
- [ ] `--consult` 生成 EXPERT-CONSULT.md
- [ ] `--review` 生成 REVIEW.md
- [ ] `--verify` 生成 VERIFICATION.md
- [ ] `--full` 启用全部三项质量检查
- [ ] STATE.md "Quick Tasks Completed" 表正确记录完成任务
- [ ] 任务目录 `.planning/quick/YYYYMMDD-{slug}/` 结构符合规范
- [ ] Slug 安全清洗机制生效（拒绝恶意路径注入）
