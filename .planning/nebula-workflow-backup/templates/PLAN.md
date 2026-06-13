---
phase: N
plan: N-M
type: implementation
wave: 1
depends_on: []
files_modified: []
autonomous: true
---
# Plan N-M: <计划名称>

## 目标
<一句话描述本计划要实现什么>

## 任务
| # | 类型 | 文件 | expert | 操作 | 验证 | 验收标准 |
|---|------|------|--------|------|------|---------|
| 1 | create | src/main/.../File.kt | backend-architect | 创建 XXX 类 | 编译通过 | 类包含所有必要字段和方法 |
| 2 | modify | src/main/.../Service.kt | java-developer | 添加 YYY 方法 | 单元测试 | 方法返回预期结果 |

<!-- 
  expert 字段说明（v0.4 新增，可选）：
  - 指定值 → nx-executor 派发对应专家 agent 执行
  - 留空 → nx-executor 根据文件路径自动推断
  - 可用值：backend-architect/java-developer/database-optimizer/sql-expert/
            test-automator/security-auditor/api-security-audit/
            performance-engineer/debugger/deployment-engineer/
            architect-review/code-reviewer 等
--> |

## 依赖
- 无 / Plan N-<M>（需先完成）

## 产出物
- 源码文件: src/main/kotlin/.../*.kt
- 测试文件: src/test/kotlin/.../*Test.kt

## 验证步骤
1. 编译验证: `./gradlew compileKotlin`
2. 单元测试: `./gradlew :module:test`
3. 集成测试: <如适用>

## 风险
| 风险 | 可能性 | 影响 | 缓解方案 |
|------|--------|------|---------|

## 成功标准
- <可衡量的完成条件 1>
- <可衡量的完成条件 2>
