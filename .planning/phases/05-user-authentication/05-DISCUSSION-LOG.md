# Phase 5: User & Authentication - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 5-User & Authentication
**Areas discussed:** 用户来源与注册, 登录后连接绑定, 用户搜索行为, 隐私控制范围

---

## 用户来源与注册

| Option | Description | Selected |
|--------|-------------|----------|
| 预置账号导入 | 通过 SQL 初始化脚本或管理员工具批量创建用户，无开放注册 API | |
| 开放注册 API | 提供 user/register 接口，用户可自行注册。需要防滥用策略 | |
| 两者都支持 | 预置基础账号同时提供注册 API | ✓ |

**User's choice:** 两者都支持
**Notes:** 注册防护：IP 频率限制（每小时 5 次，复用 RateLimitInterceptor）+ 用户名唯一性校验 + 密码最低 6 位 + bcrypt cost 12；不额外添加验证码

---

## 登录后连接绑定

| Option | Description | Selected |
|--------|-------------|----------|
| Token 前置认证 | 客户端携带 token 发送 system/bind 完成绑定 | |
| Handler 回调绑定 | LoginHandler 内部引用 ChatGatewayImpl 绑定 StreamObserver | |
| 登录流携带 StreamObserver | OnNext() 中特殊处理 login，不走 Dispatcher | |

**User's choice:** （用户否定了上述三个选项，提出自己分析）
**Notes:** 最终方案——Gateway 层（ChatService）在发送 LoginResp 前拦截响应，自动完成 Session 注册和 StreamObserver 绑定。Handler 层不感知 StreamObserver，保持职责链完整。用户明确要求 "不需要额外客户端请求"。

---

## 用户搜索行为

| Option | Description | Selected |
|--------|-------------|----------|
| 仅 username 模糊匹配 | LIKE %keyword%，注册时间倒序 | ✓ |
| username + display_name | 同时搜索两个字段 | |

| Option | Description | Selected |
|--------|-------------|----------|
| 有限列表 | 最多 20 条，无分页参数 | |
| 游标分页 | cursor + limit，支持翻页 | ✓ |

**User's choice:** 仅 username LIKE 匹配 + 游标分页（最多 20 条）

---

## 隐私控制范围

| Option | Description | Selected |
|--------|-------------|----------|
| 仅存储，不生效 | 存入 MySQL users 表，Phase 8 才读取 | |
| 先存 Redis，后续迁移 | 写入 Redis，后续异步刷 MySQL | ✓ |

| Option | Description | Selected |
|--------|-------------|----------|
| batchGetOnlineStatus 中过滤 | 跳过 hide_online_status=true 的用户 | ✓ |

**User's choice:** 先存 Redis + 异步刷 MySQL；Phase 5 立即在 batchGetOnlineStatus 中生效
**Notes:** 隐私设置生效在 Phase 5 范围内（batchGetOnlineStatus 需要过滤隐藏用户）

---

## Deferred Ideas

None — discussion stayed within phase scope
