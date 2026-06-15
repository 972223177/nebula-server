---
phase: 11
type: execution-supplement
date: 2026-06-15
depends_on: 11-PLAN.md
---

# Phase 11 多 Subagent 并行执行方案

## 概述

补充 11-PLAN.md 的 Wave 分组，将每个 Wave 内的子计划分配到独立 subagent 并行执行，最大化利用并发能力。

## 团队结构

```
Team: phase11-team
├── lead (nx-executor): 调度器，负责 Wave 编排、依赖检查、聚合提交
│
├── [Wave 1 - 并行] 
│   ├── security-agent: 执行 Plan 11-01（安全与生产加固，13 HIGH）
│   └── data-agent: 执行 Plan 11-02（数据一致性，11 HIGH）
│
├── [Wave 2 - 串行] 
│   └── integrity-agent: 执行 Plan 11-03（数据完整性，30 MEDIUM）
│
└── [Wave 3 - 串行] 
    └── quality-agent: 执行 Plan 11-04（代码质量与测试，27 LOW）
```

## Wave 1 并行策略（11-01 ∥ 11-02）

### 文件冲突处理

| 冲突文件 | 冲突来源 | 串行策略 |
|----------|----------|----------|
| `server/.../NebulaServer.kt` | 11-01: Shutdown Hook + init 回滚 (任务8-9) | **security-agent 先完成** → data-agent 在此基础上修改 SeqService 启动恢复 |
| | 11-02: SeqService 启动恢复 (任务3) | |

### Agent 分工

#### security-agent（Plan 11-01）
- **专家类型**: `nx-executor`（调度内部子任务）
- **文件清单**: 14 个文件（1 新建 + 13 修改）
- **内部并行**:
  - 阶段 A（配置安全，任务 1-3）→ 串行
  - 阶段 B（SSL/TLS，任务 4-7）→ 依赖 A
  - 阶段 C（可靠性，任务 8-11）→ 依赖 B，完成后释放 `NebulaServer.kt` 锁
  - 阶段 D（日志，任务 12-16）→ 与 C 并行
  - 阶段 E（限流器，任务 17）→ 与 C/D 并行
- **关键节点**: 阶段 C 完成后通知 data-agent 可开始

#### data-agent（Plan 11-02）
- **专家类型**: `nx-executor`
- **文件清单**: 15 个文件（1 新建 + 14 修改）
- **内部并行**:
  - 阶段 A（Flyway DDL，任务 1）→ 串行
  - 阶段 B（Repository，任务 2）→ 依赖 A
  - 阶段 C（SeqService 启动恢复，任务 3）→ **等待 security-agent 完成 NebulaServer.kt**
  - 阶段 D（ConversationService 事务，任务 4-8）→ 依赖 B
  - 阶段 F（FriendService 事务，任务 12-15）→ 依赖 A+B
  - 阶段 E（JPQL 原子更新，任务 9-11）→ 依赖 B+D
- **关键节点**: 阶段 A+B 可先执行（不依赖 NebulaServer.kt）；阶段 C 等待 security-agent 完成

### Wave 1 并行执行时序

```
security-agent                    data-agent
    │                                │
    ├─ 阶段A: 配置安全 (任务1-3)     ├─ 阶段A: Flyway DDL (任务1)
    │  └─ 完成                       │  └─ 完成
    ├─ 阶段B: SSL/TLS (任务4-7)     ├─ 阶段B: Repository (任务2)
    │  └─ 完成                       │  └─ 完成
    ├─ 阶段C: Shutdown Hook (任务8-9)│
    │  └─ [释放 NebulaServer.kt] ────→ ├─ 阶段C: SeqService 恢复 (任务3)
    │  ├─ 阶段D: 日志 (任务12-16)   │  ├─ 阶段D: Conv 事务 (任务4-8)
    │  ├─ 阶段E: 限流器 (任务17)    │  ├─ 阶段F: Friend 事务 (任务12-15)
    │  └─ 完成                       │  └─ 阶段E: JPQL 原子 (任务9-11)
    │                                │     └─ 完成
    │                                │
    ├──────── Wave 1 聚合 ───────────┤
    │    nx-integration-checker      │
    │    验证 11-01 / 11-02 集成点   │
    └──────── 完成 ───────────────────┘
```

## Wave 2 执行策略（11-03）

### integrity-agent（Plan 11-03）
- **专家类型**: `nx-executor`
- **文件清单**: 10 修改 + 8 删除
- **内部并行**:
  - 阶段 A（死代码清理，任务 1-10）→ 先行（释放编译依赖）
  - 阶段 B（payload 修复，任务 11-15）→ 与 C/D/E 并行
  - 阶段 C（N+1 消除，任务 16-17）→ 与 B/D/E 并行
  - 阶段 D（Bug 修复，任务 18-24）→ 与 B/C/E 并行
  - 阶段 E（协程修复，任务 25-28）→ 与 B/C/D 并行

### 前置检查
1. `uk_client_msg_id` UNIQUE 约束已部署（11-02 阶段 A）
2. `TransactionTemplate` 已注入 Handler（11-02 阶段 D）
3. `incrementMemberCount()` 已可用（11-02 阶段 B）
4. 配置安全基线就绪（11-01 全部完成）

## Wave 3 执行策略（11-04）

### quality-agent（Plan 11-04）
- **专家类型**: `nx-executor`
- **文件清单**: 5 新建 + 14 修改 + 2 删除 + 7 测试文件
- **内部并行**:
  - 阶段 A（枚举，任务 1-6）→ 先行
  - 阶段 B（!! 替换，任务 7-11）→ 与 A/D/E 并行
  - 阶段 D（异常精简，任务 18-20）→ 与 A/B/E 并行
  - 阶段 E（Clock 接口，任务 21-23）→ 与 A/B/D 并行
  - 阶段 C（DRY 重构，任务 12-17）→ **依赖 A**（枚举/扩展准备好后才能统一替换）
  - 阶段 F（测试补充，任务 24-29）→ **依赖所有前序完成**

## 通信协议

### Agent 间协调
```
phase11-team
├── lead → 所有 agent: 广播 Wave 启动信号 + 任务分配
├── lead → nx-integration-checker: 每 Wave 完成后触发集成检查
├── agent → lead: 完成任务报告 + commit hash
├── lead → nx-verifier: 阶段完成后的目标反向验证
└── lead → 所有: 汇总 SUMMARY.md + 最终 git 提交
```

### 消息格式
```json
{
  "type": "task_complete",
  "agent": "security-agent",
  "plan": "11-01",
  "stage": "C",
  "commit": "abc1234",
  "files_changed": ["NebulaServer.kt"],
  "message": "阶段C完成，NebulaServer.kt 已释放给 data-agent"
}
```

## 回滚策略

### 单 Plan 失败
- 失败的 Plan 由分配 agent 自行重试修复
- 不阻塞同 Wave 其他 agent

### Wave 失败
1. 单 agent 失败 3 次 → lead 介入诊断
2. 编译/测试失败 → Wave 内全部 agent 暂停，lead 协调修复
3. Wave 完成但集成检查失败 → nx-integration-checker 输出修复建议，lead 分配修复任务

## 产出物

| 阶段 | 产出 | 负责 |
|------|------|------|
| Wave 1 完成 | PLAN.md + 修改文件 commit | security-agent + data-agent |
| Wave 1 集成 | INTEGRATION-REPORT.md | nx-integration-checker |
| Wave 2 完成 | PLAN.md 更新 + 修改文件 commit | integrity-agent |
| Wave 3 完成 | PLAN.md + 枚举/测试 + commit | quality-agent |
| 全部完成 | SUMMARY.md + 最终 commit | nx-executor (lead) |
| 最终验证 | VERIFICATION.md | nx-verifier |

## EXECUTION SUPPLEMENT COMPLETE
