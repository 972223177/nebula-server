---
description: 目标反向验证 —— 四层验证（存在性→内容实在性→连接性→数据流通）
argument-hint: "<N> [阶段编号]"
---

# 阶段验证

## 目标
对阶段 N 的执行结果进行四层反向验证，确认所有产出物真实可用（非存根）。

## 参数
- `$ARGUMENTS`：阶段编号 N（必需）

## 四层验证模型

参考 GSD 的 verification-patterns.md 四层模型，Nebula 重新表述为：

| 层级 | GSD 命名 | Nebula 命名 | 检测内容 |
|------|---------|-----------|---------|
| L1 | Exists | 存在性 | 文件是否在预期路径存在 |
| L2 | Substantive | 内容实在性 | 文件内容是否为真实实现（非占位符/存根） |
| L3 | Wired | 连接性 | 组件是否正确连接到系统的其他部分 |
| L4 | Functional | 数据流通 | 数据是否从源头正确流到终端（端到端） |

> **Nebula 的创新点**: L4 的"数据流通"比 GSD 的"Functional"更精确——它要求验证数据从 API 入口经 Handler → Service → Repository → 数据库 → 并返回的完整链路，而非模糊地"功能可用"。

## 流程

### 步骤 1：收集真理

从 ROADMAP.md 和 PLAN.md 提取：
- 阶段目标（必须达成的结果）
- 计划产出物列表（文件、API、数据库变更）
- 成功标准（可衡量的完成条件）

### 步骤 2：存在性验证（L1）

```bash
# 对每个预期产出物
for artifact in (PLAN.md 中声明的文件); do
  if [ -f "$artifact" ]; then
    标记: EXISTS ✅
  else
    标记: MISSING ❌
  fi
done
```

### 步骤 3：内容实在性验证（L2）

使用具体检测命令（参考 `references/verification-patterns.md`）：

**通用存根检测**：
```bash
# 检查是否有 TODO/FIXME/PLACEHOLDER 注释
grep -n -E "TODO|FIXME|PLACEHOLDER|待实现|占位" <file>

# 检查是否有空函数体
grep -n -E "return null|return Unit|return \{\}|throw NotImplementedError" <file>

# 检查是否有仅日志输出的函数
grep -n -E "println\(|logger\.(info|debug)" <file>
```

**Kotlin 后端特定检测**：
```bash
# Handler 必须有实际的业务逻辑调用
grep -n -E "service\.|repository\.|manager\." <handler_file>

# Service 必须有数据库交互
grep -n -E "repository\.|dao\.|entityManager\." <service_file>

# Repository 必须有查询定义
grep -n -E "@Query|findBy|findAll|save" <repository_file>
```

### 步骤 4：连接性验证（L3）

验证组件间的真实连接：

**Handler → Service 连线**：
```bash
# Handler 是否注入了 Service
grep -n "private.*Service" <handler_file>
# 服务方法是否被实际调用
grep -n "service\." <handler_file>
```

**Service → Repository 连线**：
```bash
# Service 是否注入了 Repository
grep -n "private.*Repository" <service_file>
# Repository 方法是否被实际调用
grep -n "repository\." <service_file>
```

**DI 连线**：
```bash
# 模块注册是否包含所有组件
grep -n "single\|factory" <module_file>
```

### 步骤 5：数据流通验证（L4）

端到端数据流验证路径：
```
gRPC Request → Handler → Service → Repository → Database → Response
```

检查要点：
- gRPC Service 实现类是否正确注册
- Handler 输入的 Protobuf 消息是否正确转换
- Service 返回的数据是否正确序列化
- 数据库查询结果是否完整返回

### 步骤 6：测试验证

```bash
# 检查单元测试存在
find src/test -name "*Test.kt" -path "*${phase_related}*"

# 运行相关测试
./gradlew :module:test --tests "*${TestClass}*"
```

## 输出

```markdown
---
phase: N
verifier: nx-verifier
status: passed|failed
---
# Phase N 验证报告

## L1 存在性
| 文件 | 状态 |
|------|------|
| path | ✅/❌ |

## L2 内容实在性
| 文件 | 行数 | 存根检测 | 状态 |
|------|------|---------|------|

## L3 连接性
| 连线 | 状态 |
|------|------|
| Handler → Service | ✅/❌ |

## L4 数据流通
| 数据路径 | 状态 |
|-----------|------|

## 测试结果
| 测试类 | 通过/总数 | 状态 |
|--------|----------|------|

## 最终裁决
- [ ] PASSED —— 所有四层验证通过
- [ ] PARTIAL —— 部分层级有 gap（已记录）
- [ ] FAILED —— 关键层级未通过（需修复）

## VERIFICATION COMPLETE
```

## 成功标准
- 四层验证全部执行
- 每层有具体检测结果（非主观判断）
- 发现的问题已归类（PASSED/PARTIAL/FAILED）
- VERIFICATION.md 已写入阶段目录
