# Phase 6: Chat & Message — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 06-chat-message
**Areas discussed:** 在线推送架构, 消息扇出编排, 消息拉取策略, 已读回执推送, client_message_id 容错, 推送 Envelope 构建方, 离线成员处理

---

## 在线推送架构

| Option | Description | Selected |
|--------|-------------|----------|
| 扩展 ChatService | 在 ChatService 新增 userId→List\<StreamObserver\> 映射 | |
| 独立 UserStreamRegistry | 新建独立组件管理 userId→StreamObserver 映射 | ✓ |
| 复用 tokenToObserver + userIdIndex | 通过 SessionRegistry 查询后回查 ChatService | |

**User's choice:** 独立 UserStreamRegistry
**Notes:** 用户强调"尽量让每个类的职责更专一"

---

| Option | Description | Selected |
|--------|-------------|----------|
| 推送所有在线设备 | 向用户所有在线设备推送 | ✓ |
| 仅推送最后登录设备 | 只向最后登录设备推送 | |

**User's choice:** 推送所有在线设备

---

| Option | Description | Selected |
|--------|-------------|----------|
| 完整 ChatMessage | 推送全量消息对象 | ✓ |
| 精简推送 + 客户端拉取 | 仅推送 msg_id + conv_id | |

**User's choice:** 完整 ChatMessage
**Notes:** 澄清 RTT 延迟概念；讨论 ChatMessage 不同 messageType（TEXT vs 富文本）；用户期望 Segment 结构（text+file_oss_url+text），但 Segment 尚未定义 proto，标记为 deferred idea。不同内容类型统一推送完整 ChatMessage，图片为 OSS URL 而非二进制 payload。

---

## 消息扇出编排

| Option | Description | Selected |
|--------|-------------|----------|
| Redis Stream 写入后立即返回 | 快速 ACK，推送异步 | ✓ |
| 全部操作完成后返回 | 端到端确认 | |
| 写入 MySQL 后再返回 | 最高可靠性但延迟大 | |

**User's choice:** Redis Stream 写入后立即返回

---

| Option | Description | Selected |
|--------|-------------|----------|
| 推送失败不影响落盘 | 消息可靠优先 | ✓ |
| 推送失败回滚 | 一致性最高但复杂度大增 | |

**User's choice:** 推送失败不影响落盘

---

| Option | Description | Selected |
|--------|-------------|----------|
| Redis INCR 逐个成员 | 简单直接 | ✓ |
| Lua 脚本批量处理 | 减少网络往返 | |

**User's choice:** Redis INCR 逐个成员

---

| Option | Description | Selected |
|--------|-------------|----------|
| Redis SETNX + TTL | 轻量去重 | ✓ |
| MySQL 唯一索引 | 厚重但免去 Redis 查询 | |

**User's choice:** Redis SETNX + TTL

---

| Option | Description | Selected |
|--------|-------------|----------|
| 仅检查成员身份 | 简单高效 | ✓ |
| 检查成员 + 好友关系 | 功能重复（Phase 8 才实现） | |

**User's choice:** 仅检查成员身份

---

| Option | Description | Selected |
|--------|-------------|----------|
| 不推送发送者自己 | 行业主流做法 | ✓ |
| 推送但不依赖 | 浪费一次推送 | |

**User's choice:** 不推送发送者自己

---

| Option | Description | Selected |
|--------|-------------|----------|
| SendHandler 同步更新 | 数据一致性最好 | ✓ |
| ConversationHandler 计算 | 查询开销大 | |

**User's choice:** SendHandler 同步更新（会话 last_message 信息）

---

| Option | Description | Selected |
|--------|-------------|----------|
| Handler 内直接推送 | 耦合但仍然简单 | |
| ChatService 拦截触发推送 | 类似 login 拦截模式，需再查 Redis | |
| 独立推送队列 | 彻底解耦但引入复杂度 | |
| **Handler 直接调用 PushService** | **零额外 I/O，精简** | ✓ |

**User's choice:** Handler 直接调用 PushService（澄清之前 ChatService 拦截与 Handler 直接调用的冲突后选择此项）

---

| Option | Description | Selected |
|--------|-------------|----------|
| 逐个单推 | 简单直接 | ✓ |
| 批量推送 | 增加缓冲和超时逻辑 | |

**User's choice:** 逐个单推

---

| Option | Description | Selected |
|--------|-------------|----------|
| Handler 编排 | Handler 内编排所有步骤 | ✓ |
| 分离推送逻辑 | Handler 只做写入 | |
| Workflow 模式 | 增加额外类层次 | |

**User's choice:** Handler 编排（但进一步讨论后演进为 Step 链模式）

**Notes:** 用户不希望巨无霸 Handler，要求拆分为多个具名 Step，每个 Step 一个类，类名即职责。最终模式：`SendMessageStep` 接口，实现 ValidateStep → DedupStep → WriteStep → PushStep，通过 SendContext 传递共享状态。

---

| Option | Description | Selected |
|--------|-------------|----------|
| 一致 | 推送和存储使用相同 ChatMessage | ✓ |
| 不一致 | 推送排除 payload 等字段 | |

**User's choice:** 一致

---

| Option | Description | Selected |
|--------|-------------|----------|
| 强制客户端传入 | 干净接口契约 | ✓ |
| 服务端自动生成 | 掩盖客户端缺陷 | |

**User's choice:** 强制客户端传入

---

| Option | Description | Selected |
|--------|-------------|----------|
| PushService 内部构建 | 对外暴露简单方法签名 | ✓ |
| 调用方构建后传入 | 职责更单一但调用方需知 Envelope 细节 | |

**User's choice:** PushService 内部构建

---

| Option | Description | Selected |
|--------|-------------|----------|
| 正确，分工清晰 | Phase 3 PEL 负责离线恢复 | ✓ |
| 需要独立离线队列 | 功能重复 | |

**User's choice:** 正确，分工清晰

---

## 消息拉取策略

| Option | Description | Selected |
|--------|-------------|----------|
| MySQL 游标查询 | 简单可靠 | ✓ |
| Redis Stream + MySQL 两级 | 实现复杂 | |
| 纯 Redis 缓存 | 内存成本不可控 | |

**User's choice:** MySQL 游标查询

---

| Option | Description | Selected |
|--------|-------------|----------|
| tail 优先 + 往前翻 | cursor=0→latest, cursor>0→older | ✓ |
| head 优先 + 往后翻 | 从最旧开始 | |
| 双向可控 | 最灵活但客户端复杂 | |

**User's choice:** tail 优先 + 往前翻

---

| Option | Description | Selected |
|--------|-------------|----------|
| 20/100 | 默认 20，最大 100 | ✓ |
| 50/200 | 减少翻页次数 | |

**User's choice:** 20/100

---

| Option | Description | Selected |
|--------|-------------|----------|
| 写时冗余存储 | messages 表加 sender_username/avatar | |
| 读时 JOIN 填充 | 查询时 JOIN user 表 | |

**User's choice:** 不存不查。ChatMessage 只含 sender_uid + receiver_uid，显示信息由客户端通过 user/batchGet 批量获取。

**Notes:** 用户提出会话列表和会话详情是两个不同场景，完全可以通过 user/batchGet 按需获取。ChatMessage 移除 sender_username/sender_avatar 字段已有 proto 定义，新增 receiver_uid。

---

| Option | Description | Selected |
|--------|-------------|----------|
| 保留不删 | direction 字段为 Phase 10 准备 | ✓ |
| 删除 direction | 简化 proto | |

**User's choice:** 保留不删

---

## 已读回执推送

| Option | Description | Selected |
|--------|-------------|----------|
| 私聊推送，群聊不推 | 实时已读反馈 | ✓ |
| 不推送 | 简单但实时性差 | |
| 都推送 | 群聊太吵 | |

**User's choice:** 私聊推送，群聊不推

---

| Option | Description | Selected |
|--------|-------------|----------|
| 精简：conv_id + reader + msg_id | 轻量够用 | ✓ |
| 带时间戳 | 可展示"xx于yy已读" | |

**User's choice:** 精简

---

| Option | Description | Selected |
|--------|-------------|----------|
| 相同 Step 链模式 | 与 chat/send 统一 | |
| 单个 Handler 完成 | message/read 逻辑简单 | ✓ |

**User's choice:** 单个 Handler 完成

---

| Option | Description | Selected |
|--------|-------------|----------|
| 查成员数判定 | 2 人以下=私聊 | |
| ConversationEntity.type 判定 | 更精确 | ✓ |

**User's choice:** ConversationEntity.type 判定

---

| Option | Description | Selected |
|--------|-------------|----------|
| DEL 删除 key | 简单 | ✓ |
| SET 为 0 | 额外写一次 | |

**User's choice:** DEL（乐观放弃，接受极低概率竞态）

**Notes:** 澄清竞态问题后接受乐观放弃策略

---

## Claude's Discretion

- Step 链接口的具体包路径
- PushService.pushMessage() 签名细节
- client_message_id 去重 TTL 值（默认 7 天可调整）

## Deferred Ideas

- **Segment 富文本结构** — 用户期望 `[text_segment + file_url_segment + text_segment]` 形式的消息体，代替当前的单一 `content + payload` 结构。需另行定义 Segment proto，属后续迭代。
- **群聊已读详情** — 群聊场景展示"已读 X/N"，不在 Phase 6 范围内。
