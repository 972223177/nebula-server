---
description: 安全审计 —— 威胁建模回顾 → 检查缓解措施 → 生成 SECURITY.md
argument-hint: "<N> [阶段编号] [追溯]"
---

# 阶段安全审计

## 目标
对阶段 N 进行安全威胁建模和缓解措施审计，生成 SECURITY.md 存放到阶段目录。

## 参数
- `$ARGUMENTS`：阶段编号 N（必需），可选 `追溯` 标记表示对已完成阶段追补审计

## 审计模式

| 模式 | 触发条件 | 特点 |
|------|---------|------|
| **前置审计** | Phase 规划完成后、执行前 | 识别威胁 → 检查 PLAN 中的缓解措施是否充分 |
| **追溯审计** | Phase 已执行完毕但未执行 `/nx-secure` | 识别威胁 → 检查实际代码中的缓解措施 → 对比 PLAN 中的 T-编号 |

## 审计范围

聚焦**本阶段引入的新威胁**：
- **本阶段新增的组件**（Handler、Service、Repository、Proto 定义）
- **本阶段修改的组件**（如 DI 注册、已有 Service 的新方法调用）
- **本阶段的信任边界**（数据跨越边界时暴露的新威胁）
- 对继承自前序阶段的安全控制（AuthInterceptor、TLS 等），只检查本阶段是否正确使用，不重复审计

## 流程

### 步骤 1：收集输入

```bash
# 读取阶段上下文
cat .planning/phases/0N-description/0N-CONTEXT.md
cat .planning/phases/0N-description/0N-DISCUSSION-LOG.md

# 读取所有计划文件（识别设计决策中的安全相关项）
for PLAN in .planning/phases/0N-description/0N-*-PLAN.md; do
  echo "=== $(basename "$PLAN") ==="
  cat "$PLAN"
done

# 读取 VALIDATION.md（如已存在，提取 T-编号清单）
cat .planning/phases/0N-description/0N-VALIDATION.md
```

### 步骤 2：代码审查

```bash
# 识别本阶段新增/修改的源码文件
# 从 PLAN.md 和 SUMMARY.md 中的 files_modified 获取文件清单

# 逐文件检查以下安全关注点：
# - 输入验证（内容非空、长度限制、类型校验）
# - 权限检查（是否为当前用户操作自己的数据）
# - 数据暴露（响应中是否包含不应返回的字段）
# - 并发安全（竞态条件、原子操作）
# - 资源限制（分页大小、连接数、速率）
# - 错误处理（异常是否泄露内部信息）
# - 日志安全（是否记录敏感数据如 token/密码）
```

### 步骤 3：威胁建模

根据 STRIDE 分类识别威胁：

| 类别 | 关注点 | Phase 6 示例 |
|------|--------|-------------|
| 身份伪造 (Spoofing) | 未认证的请求、伪造的 userId | 消息发送者身份伪造 |
| 篡改 (Tampering) | 数据被篡改、未验证的输入 | clientMessageId 为空导致去重失效 |
| 抵赖 (Repudiation) | 操作不可追溯 | 消息发送无日志审计 |
| 信息泄露 (Information Disclosure) | 敏感数据暴露 | 推送消息泄露给非会话成员 |
| 拒绝服务 (Denial of Service) | 资源耗尽 | 无限制分页拉取、stream flood |
| 权限提升 (Elevation of Privilege) | 越权操作 | 冒充他人发送消息、篡改他人已读状态 |

### 步骤 4：检查缓解措施

对每个识别的威胁：
- [ ] 是否有对应的安全控制
- [ ] 安全控制是否正确实现（检查具体代码行）
- [ ] 是否有测试验证安全控制（检查单元测试）
- 处置方案：`mitigate`（已缓解）/ `accept`（已接受风险）/ `transfer`（转交后续阶段）

### 步骤 5：评估风险等级

| 等级 | 标准 | 处置要求 |
|------|------|---------|
| Critical | 可直接导致数据泄露或服务瘫痪 | 必须 mitigate，不可 accept |
| High | 可通过组合攻击利用 | 优先 mitigate，accept 需记录理由 |
| Medium | 需要特定条件才能利用 | 可 accept 需记录理由 |
| Low | 理论上存在但实际利用困难 | 可 accept |

### 步骤 6：生成 SECURITY.md

参考模板 `.codebuddy/plugins/nebula-workflow/templates/SECURITY.md` 和已有审计报告格式。

## 输出

SECURITY.md 写入 `.planning/phases/0N-description/0N-SECURITY.md`，包含：

```markdown
---
phase: 0N
slug: <阶段简称>
status: verified
threats_open: <未关闭威胁数>
asvs_level: 1
created: <YYYY-MM-DD>
---

# Phase 0N — 安全合约

## 信任边界
（本阶段新增/修改的信任边界）

## 威胁注册表
| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |

## 已接受的风险记录
（如有 accept 处置的威胁）

## 缓解措施验证详情
（关键威胁的代码级验证，含文件:行号）

## 安全审计轨迹
| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |

## 签收
- [ ] 所有威胁都有处置方案
- [ ] 已接受的风险记录在风险日志中
- [ ] threats_open 确认
```

## 完成标记
- 无开放威胁：写入 `## SECURITY AUDIT COMPLETE` 到报告末尾
- 有开放威胁：列出 `## OPEN_THREATS` 章节（含影响和建议）
- 更新 STATE.md 中的 Phase N 安全审计列为 ✅

## 成功标准
- SECURITY.md 遵循模板格式
- 至少识别 8 个威胁（覆盖 STRIDE 六类）
- 每个 mitigate 威胁都有代码行号验证
- 所有 accept 威胁都有理由记录
- threats_open 为 0（可 accept 的不算 open）
