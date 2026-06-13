---
description: 目标反向验证 —— 四层验证（存在性→内容实在性→连接性→数据流通）+ 编译测试 + 集成冒烟验证 + 行为抽查 + 人体验证清单。自动探测项目类型，适配后端和前端。L1-L4 通过 nx-verifier 内部并行子 agent 执行。
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

### 步骤 1：收集验证输入

从 ROADMAP.md 和 PLAN.md 提取：
- 阶段目标（必须达成的结果）
- 计划产出物列表（文件、API、数据库变更）
- 成功标准（可衡量的完成条件）

### 步骤 2：派发 nx-verifier（并行 L1-L4 + 编译测试 + 集成冒烟）

使用 `Agent` 工具启动 `nx-verifier` agent。nx-verifier 内部执行流程：

1. **准备输入**：解析 PLAN.md/SUMMARY.md/ROADMAP.md
2. **并行 L1-L4**：同时派发 4 个子 agent（`subagent_type: general-purpose`，加入当前团队）：
   - `verifier-L1`：存在性验证 — 对每个预期文件执行 `test -f`
   - `verifier-L2`：内容实在性验证 — grep 扫描存根/TODO/空函数体
   - `verifier-L3`：连接性验证 — 检查组件连线（后端: Handler→Service→Repository→DI / 前端: Component→Hook→API→Store）
   - `verifier-L4`：数据流通验证 — 追踪完整数据链路（后端: API→DB→Response / 前端: UI→State→API→Render）
3. **编译与单元测试**：自动探测项目类型（gradle/maven/node/go/rust）→ 执行对应编译和测试命令，失败则直接标记 FAILED
4. **集成冒烟验证**（仅当产生可运行服务代码时执行，需用户确认）：
   - 5a: 探测项目类型和运行方式（后端: gradle run/spring-boot / 前端: npm dev/next/vite）
   - 5b: 启动基础设施（如 docker-compose.yml 存在，自动启动依赖服务）
   - 5c: 启动应用服务（后台运行，自动检测端口）
   - 5d: 健康检查（后端: grpcurl/curl / 前端: curl HTTP 状态码）
   - 5e: 发送测试请求（后端: gRPC/REST API / 前端: 关键页面可访问性）
   - 5f: 关闭服务（kill 进程，基础设施不自动关闭）
5. **行为抽查**：对关键 API/构建产出执行运行时验证（bash 命令，< 10 秒/项）。如果已执行集成冒烟验证则跳过
6. **人体验证清单**：汇总无法自动验证的项目，供开发者手动确认
7. **生成 VERIFICATION.md**：聚合 L1-L4 结果 → 编译测试结果 → 集成冒烟结果 → 行为抽查结果 → 人体验证清单 → 最终裁决

```
Agent:
  name: "nx-verifier"
  subagent_type: "nx-verifier"
  team_name: <当前团队名>
  prompt: |
    对阶段 N 进行四层反向验证。
    阶段编号：{N}
    计划文件：{PLAN.md 路径}
    执行摘要：{SUMMARY.md 路径}
    
    L1-L4 通过并行子 agent 执行。
```

### 步骤 3：读取并展示验证报告

nx-verifier 完成后，VERIFICATION.md 写入阶段目录。读取并展示关键发现。

## 输出

nx-verifier 生成的 VERIFICATION.md 格式：

```markdown
---
phase: N
verifier: nx-verifier
status: passed|failed|partial|human_needed
---
# Phase N 验证报告

## L1 存在性
| 文件 | 状态 |
|------|------|
| path | ✅/❌ |

## L2 内容实在性
| 文件 | 行数 | 存根数 | 状态 |
|------|------|--------|------|

## L3 连接性
| 连线 | 状态 | 证据 |
|------|------|------|

## L4 数据流通
| 数据路径 | 状态 |
|-----------|------|

## 编译与单元测试
| 步骤 | 项目类型 | 命令 | 结果 | 状态 |
|------|---------|------|------|------|

## 集成冒烟验证
| 步骤 | 内容 | 状态 |
|------|------|------|
| 5a | 项目类型探测 | ✓ DETECTED / ✗ UNKNOWN |
| 5b | 基础设施启动 | ✓ PASS / ✗ FAIL / ? SKIP |
| 5c | 应用启动 | ✓ PASS / ✗ FAIL |
| 5d | 健康检查 | ✓ PASS / ✗ FAIL |
| 5e | 请求验证 | ✓ PASS / ✗ FAIL / ? SKIP |
| 5f | 服务关闭 | ✓ DONE / ✗ FAIL |

## 行为抽查结果
| 行为 | 命令 | 结果 | 状态 |
|------|------|------|------|

## 人体验证清单
| # | 测试名称 | 操作 | 期望结果 | 为什么需要人工 |
|---|---------|------|---------|---------------|

## 最终裁决
- [ ] PASSED —— 所有四层验证通过，无人体验证项
- [ ] HUMAN_NEEDED —— 自动化通过，但有 X 项需人工确认
- [ ] PARTIAL —— 部分层级有 gap（已记录）
- [ ] FAILED —— 关键层级未通过（需修复）

## VERIFICATION COMPLETE
```

## 成功标准
- 四层验证全部执行（L1-L4 通过并行子 agent 完成）
- 每层有具体检测结果（非主观判断）
- 编译通过且单元测试全部通过
- 集成冒烟验证执行完成（或跳过并注明原因）
- 行为抽查完成（或注明跳过原因）
- 人体验证清单已汇总
- VERIFICATION.md 已写入阶段目录
