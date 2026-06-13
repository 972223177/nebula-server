---
description: 跨阶段集成检查 —— 验证已实现阶段间的 API/数据流/事件真实联通
argument-hint: "[--from N] [--to M]"
---

# 集成检查

## 目标
验证已实现阶段间的真实集成：API 连通性、数据流完整性、事件传递正确性。对于多阶段项目（10+ 阶段），这是确保各部分协同工作的关键检查。

## 参数
- `$ARGUMENTS`（可选）
  - 无参数：检查所有已完成阶段间的集成
  - `--from N`：从阶段 N 开始检查（默认从阶段 1）
  - `--to M`：检查到阶段 M（默认到最后完成阶段）

## 流程

### 步骤 1：识别集成点

扫描所有已完成阶段的 SUMMARY.md，提取跨阶段引用：

```bash
# 从 STATE.md 获取已完成阶段列表
COMPLETED_PHASES=($(从 STATE.md 提取状态为 Complete 的阶段))

# 从每个阶段的 SUMMARY.md 提取 Key Files
# 分析文件间的 import 和依赖关系
```

集成点类型：
1. **API 集成**: 阶段 A 定义的 API 被阶段 B 调用
2. **数据模型集成**: 阶段 A 的表被阶段 B 的查询引用
3. **接口集成**: 阶段 A 定义的接口被阶段 B 实现
4. **事件集成**: 阶段 A 发出的事件被阶段 B 监听

### 步骤 2：API 连通性检查

```bash
# 检查 API 网关注册
grep -r "registerHandler\|route" src/

# 检查 gRPC Service 实现
grep -r "class.*Impl.*:.*Service" src/

# 检查客户端调用
grep -r "Stub\|Client.*call" src/
```

### 步骤 3：数据流完整性检查

```bash
# 验证数据模型在阶段间的一致性
# Schema → Entity → Repository → Service → Handler 的完整链路

# 检查 Repository 是否被 Service 使用
grep -r "<Repo>Repository" src/main/ --include="*Service*"

# 检查 Entity 是否被 Repository 引用
grep -r "<Entity>" src/main/ --include="*Repository*"
```

### 步骤 4：依赖注入完整性检查

```bash
# 检查 Koin 模块注册
grep -r "single\|factory" src/ --include="*Module*"

# 检查所有 Service/Repository 是否被注册
```

### 步骤 5：编译验证

```bash
# 全量编译验证
./gradlew compileKotlin

# 如果有编译错误 → 分析 → 报告
```

## 输出

写入 `INTEGRATION.md`：
```markdown
---
checker: nx-integration-checker
phases_checked: 1-N
status: passed|issues_found
---
# 跨阶段集成检查报告

## 集成矩阵
| 生产者阶段 | 消费者阶段 | 集成类型 | 状态 |
|-----------|-----------|---------|------|
| 3 (DB) | 4 (Handler) | Repository → Service | ✅ |
| 5 (User) | 6 (Chat) | AuthService → ChatHandler | ✅ |

## 发现问题
| 问题 | 涉及阶段 | 严重度 | 建议修复 |
|------|---------|--------|---------|

## 编译状态
- 全量编译：PASSED / FAILED
- 错误数：N

## INTEGRATION CHECK COMPLETE
```

## 成功标准
- 所有跨阶段集成点已检查
- 编译通过
- 发现的问题已分类（阻塞/警告）
- INTEGRATION.md 已写入 .planning/ 根目录
