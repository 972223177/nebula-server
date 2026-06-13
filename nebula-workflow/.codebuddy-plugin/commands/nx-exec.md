---
description: 执行计划，按 wave 分组并行执行任务
argument-hint: [阶段号] [--wave N] [--gaps-only] [--interactive] [--snapshot] [--worktree]
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent, Task, TeamCreate, SendMessage, AskUserQuestion
---

# /nx-exec — 执行计划

## 目的

读取 PLAN.md，按依赖关系分组并行执行任务。
核心设计：每个任务在独立上下文中执行，上下文仅包含该任务所需的 PLAN 片段。

## 前置条件

- `.planning/phases/<N>/PLAN.md` 必须存在
- 任务需包含验收标准

## 流程

### 第一步：加载计划

1. 读取 `.planning/phases/<N>/PLAN.md`
2. 解析任务列表和 wave 分组
3. **检测 TDD 模式**：检查 ROADMAP.md 中本阶段 `类型` 是否为 `tdd`，或任务级是否标注 `**类型**：tdd`
4. 如果指定 `--wave N`：仅加载该 wave
5. 如果指定 `--gaps-only`：仅加载 `gap_closure: true` 的任务
6. 如果没有任何任务需要执行 → 报告并退出

### 第二步：按 Wave 逐批执行

#### 入口检查（Gate: Pre-flight）

在执行第一个 Wave 前验证：
1. `.planning/phases/<N>/PLAN.md` 存在且包含有效任务
2. 项目可编译（如果已实现代码）

不满足则阻止执行，提示修复。

#### 初始化执行状态

在 PLAN.md 中每个任务添加执行状态追踪字段（如果尚未存在）：
```yaml
**状态**: 待开始 | 执行中 | 已完成 | 失败
**结果**: [执行完成时记录]
```

#### Wave 内并行（使用 TeamCreate）

对每个 Wave：

**A. 创建 git 快照**
每个 Wave 执行前，记录当前 git 状态：
```bash
git rev-parse HEAD  # 记录 HEAD commit
```
如果指定 `--snapshot`，创建临时 branch：
```bash
git checkout -b nx-wave-<N>-<wave_num>-snapshot
```

**A1. Worktree 隔离模式（--worktree）**

当指定 `--worktree` 参数时，为当前 Wave 创建独立的 git worktree，隔离文件系统操作：

```bash
# 为当前 Wave 创建 worktree
WORKTREE_DIR=".planning/.worktrees/wave-${WAVE_NUM}"
EXPECTED_BASE=$(git rev-parse HEAD)
git worktree add "$WORKTREE_DIR" HEAD

# 在 worktree 中创建 agent 分支
pushd "$WORKTREE_DIR"
git checkout -b "worktree-agent-wave-${WAVE_NUM}"
popd
```

**Worktree 冲突检测**：创建 worktree 前检查目标目录是否已存在：
```bash
if [ -d "$WORKTREE_DIR" ]; then
  # 残留 worktree，尝试清理
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  git worktree prune
fi
```

**B0. Worktree 模式下的 Agent 启动**：

为每个 executor 传递 worktree 上下文：
```
Agent(
  nx-executor,
  执行任务 X.Y：[任务描述]
  WORKTREE_DIR=.planning/.worktrees/wave-${WAVE_NUM}
  EXPECTED_BASE=${EXPECTED_BASE}
  验收标准：[列表]
  关联文件：[路径]
  team_name: "nx-exec-wave-<N>"
)
```

**B1. Wave 完成后 Worktree 合并与清理**：

```bash
# 回到原工作区
cd "$ORIGINAL_MAIN_DIR"

# 获取 worktree 中的分支
WORKTREE_BRANCH="worktree-agent-wave-${WAVE_NUM}"

# 检查 worktree 分支是否存在且无冲突
if git merge-base --is-ancestor HEAD "${WORKTREE_BRANCH}"; then
  # Fast-forward 合并（无冲突）
  git merge --ff-only "${WORKTREE_BRANCH}"
elif git merge --no-commit --no-ff "${WORKTREE_BRANCH}" 2>/dev/null; then
  # 合并成功，提交
  git commit -m "merge: wave-${WAVE_NUM} worktree changes"
else
  # 合并冲突 → Gate: Escalation
  git merge --abort
  echo "ERROR: Worktree 合并冲突，需要人工处理"
fi

# 清理 worktree
git worktree remove "$WORKTREE_DIR" --force 2>/dev/null
git branch -D "${WORKTREE_BRANCH}" 2>/dev/null
git worktree prune
```

**Worktree 失败处理**：

| 失败类型 | Gate 类型 | 行为 |
|---------|----------|------|
| Worktree 创建失败 | Revision | 回退到非 worktree 模式（自动降级） |
| 合并冲突 | Escalation | 暂停 → 展示冲突文件 → 等待用户处理 |
| Worktree 清理失败（脏状态） | Escalation | 报告脏状态 → 手动 `git worktree remove -f` |
| Executor 在 worktree 中失败 | Revision | 丢弃 worktree，git checkout 原分支 |

**Submodule 保护**（--worktree 模式下）：

如果项目包含 git submodule，worktree 中 submodule 路径可能与主工作区一致，导致冲突：
```bash
# 检测 submodule
SUBMODULE_PATHS=$(git submodule status 2>/dev/null | awk '{print $2}')
if [ -n "$SUBMODULE_PATHS" ]; then
  echo "⚠️ 检测到 submodule，worktree 隔离功能受限"
  echo "建议：不带 --worktree 执行，或手动处理 submodule 依赖"
fi
```

**B. 启动并行执行**

1. **创建 Team**: `TeamCreate("nx-exec-wave-<N>", "执行 Wave N 的任务")`
2. **创建任务**: 为 wave 内的每个独立任务创建 Task
3. **启动执行 agent**: 对每个任务，启动 nx-executor agent 并分配任务

```
Agent(
  nx-executor,
  执行任务 X.Y：[任务描述]
  验收标准：[列表]
  关联文件：[路径]
  前置任务：[如果依赖其他任务]
  team_name: "nx-exec-wave-<N>"
)
```

**每个 executor 的上下文限制**：
- 只读该任务对应的 PLAN.md 片段
- 只读任务相关的源文件
- 不读整个的 .planning/ 文档
- 不访问其他任务

4. **等待 Wave 完成**：监听从各 executor 返回的结果
5. **更新任务状态**：在 PLAN.md 中标记完成/失败

**C. 编译检查**

Wave 全部成功后，运行编译检查（验证代码可编译）：
```bash
# Gradle 项目
./gradlew build -x test --no-daemon
# 或 Maven 项目
mvn compile -q
# 如果 config.json 中定义了 build.command，使用配置的命令
```

如果编译失败 → 报告编译错误，标记该 Wave 为部分失败，不阻止用户继续但强烈建议修复。

#### Wave 间串行

Wave N 全部完成后（含编译检查），再启动 Wave N+1。
（因为 Wave N+1 依赖 Wave N 的输出）

#### `--interactive` 模式

不使用 TeamCreate，而是顺序执行每个任务，在每个任务开始前询问用户确认：
```markdown
即将执行：任务 X.Y
确认开始？(y/n)
```
适合小型阶段、调试、低 token 消耗场景。

### 第三步：Wave 完成检查

每完成一个 Wave，检查：
1. 所有任务是否完成
2. 完成的任务是否符合验收标准
3. 编译是否通过
4. 是否有失败任务需要处理
5. **TDD Gate 检查（当阶段类型为 tdd 时）**：

对每个 TDD 任务验证 git 提交记录：
```bash
# RED Gate
git log --oneline --grep="test(${PHASE}-${TASK})" | head -1
# GREEN Gate
git log --oneline --grep="feat(${PHASE}-${TASK})" | head -1
# REFACTOR Gate（可选）
git log --oneline --grep="refactor(${PHASE}-${TASK})" | head -1
```

TDD Gate 判定：
- 缺少 RED 提交 → Gate: Revision（返回 executor 重做 RED 阶段）
- 缺少 GREEN 提交 → Gate: Revision（返回 executor 重做 GREEN 阶段）
- RED 提交存在但无测试失败证据 → Gate: Escalation（询问用户）
- 全部 TDD Gate 通过 → 正常继续

阶段结束时展示 TDD 审查汇总：

```markdown
### TDD 审查（非阻塞）
| 任务 | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 5.1 | ✅ test(5-1): ... | ✅ feat(5-1): ... | ⏭️ 跳过 |
| 5.2 | ✅ test(5-2): ... | ✅ feat(5-2): ... | ✅ refactor(5-2): ... |
```

如果某个 Wave 内有失败任务：

询问用户选择处理方式：
- **重试** → 重新启动该任务的 executor
- **回滚** → 使用 git 恢复到 Wave 执行前状态：
  ```bash
  git checkout . && git clean -fd  # 丢弃所有改动
  ```
  如果使用了 `--snapshot` 创建的临时 branch，切换回去：
  ```bash
  git checkout -  # 回到之前的分支
  ```
- **跳过** → 标记为已跳过并在最后报告
- **修改** → 提示用 `/nx-plan <N>` 重新规划

### 第四步：执行摘要

所有 Waves 完成后，根据结果输出相应的摘要：

#### 全部成功

```markdown
## ✅ 执行完成 — 阶段 <N> (Gate: Revision)

Wave 1: ✅ X/X 完成 | 编译: ✅
Wave 2: ✅ X/X 完成 | 编译: ✅
Wave 3: ✅ X/X 完成 | 编译: ✅

总计：✅ X/X 任务完成

### 下一步

执行 `/nx-validate <N>` 进行代码质量验证
```

### 第五步：生成执行摘要 (PHASE-SUMMARY.md)

执行完成后，将每个 executor 的报告汇总为 PHASE-SUMMARY.md，
记录执行过程的偏差和已知存根，供 verifier 交叉验证。

写入 `.planning/phases/<N>/PHASE-SUMMARY.md`：

```markdown
---
phase: <N>
执行完成时间: [ISO 日期]
任务完成: X/Y
---

# 阶段 <N> 执行摘要

## 任务执行记录

| 任务 | 状态 | 文件变更 | Agent |
|------|------|---------|-------|
| 1.1 | ✅ 完成 | file1.kt, file2.kt | nx-executor |
| 1.2 | ✅ 完成 | file3.kt | nx-executor |

## 偏差记录

<!-- 从各 executor 报告中汇总的自动修复记录 -->

| 规则 | 任务 | 修复内容 |
|------|------|---------|
| 规则 1 (Bug) | 2.1 | 修正了空指针检查 |
| 规则 2 (关键功能) | 1.2 | 添加了输入校验 |
| — | — | (无偏差时填"无：按计划执行") |

## 已知存根

<!-- executor 报告中标注的未完成/占位项 -->

| 文件 | 位置 | 说明 |
|------|------|------|
| (无) | — | 或列出具体的存根位置 |

## 需要决策的事项

<!-- executor 报告中按规则 4 上报的架构问题 -->

(无) 或具体列出

## 决策记录

| 决策 | 影响任务 | 理由 |
|------|---------|------|
| [决策描述] | 2.1 | [理由] |
```

**如果所有任务按计划执行无偏差**，偏差记录标"无：按计划执行"。
**如果存在需要决策的事项**，在摘要中突出显示，并暂停引导用户决策。
```

#### 部分失败（有跳过或失败的任务）

```markdown
## ✅ 执行完成 — 阶段 <N> (Gate: Revision)

Wave 1: ✅ X/X 完成 | 编译: ✅
Wave 2: ✅ X/X 完成 | 编译: ✅
Wave 3: ✅ X/X 完成 | 编译: ✅

总计：✅ X/X 任务完成

### 下一步

执行 `/nx-validate <N>` 进行代码质量验证
```

#### 部分失败（有跳过或失败的任务）

```markdown
## ⚠️ 执行完成（部分） — 阶段 <N> (Gate: Escalation)

Wave 1: ✅ X/X 完成 | 编译: ✅
Wave 2: ⚠️ X/Y 完成（Y-X 跳过）| 编译: ✅
Wave 3: ❌ 0/Y 完成 | 编译: ❌

总计：⚠️ X/Y 任务完成（Z 个失败/跳过）

### 失败任务

| 任务 | 状态 | 建议 |
|------|------|------|
| X.Z | 失败 | 重试 |
| X.W | 跳过 | 评估是否必需 |

### 下一步

优先修复失败任务：
1. `/nx-exec <N> --gaps-only` — 重新执行失败的任务
2. 或 `/nx-plan <N>` — 修改计划后重新执行

修复后继续：
3. `/nx-validate <N>` — 代码质量验证
```

#### 交互模式完成

```markdown
## ✅ 执行完成 — 阶段 <N>（交互模式）

总计：✅ X/X 任务完成

### 下一步

执行 `/nx-validate <N>` 进行代码质量验证
```
