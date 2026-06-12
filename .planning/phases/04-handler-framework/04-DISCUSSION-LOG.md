# Phase 4: Handler Framework - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 4-Handler Framework
**Areas discussed:** 心跳探测策略, 心跳 Handler 实现位置, 心跳超时处理行为

---

## 心跳探测策略

| Option | Description | Selected |
|--------|-------------|----------|
| A: 纯 gRPC keepalive（保持 D-22） | 当前方案，不修改。但半开连接问题未解决 | |
| B: 双重心跳（推荐） | 传输层 + 业务层双重检测，最可靠 | |
| C: 纯应用层 PING/PONG | 完全由应用层控制，关闭 HTTP/2 PING | ✓ |

**User's choice:** C — 纯应用层 PING/PONG
**Notes:** 用户认为在移动端真实网络环境下，gRPC keepalive（HTTP/2 PING，传输层）无法可靠检测半开连接状态。NAT 网关和中间代理可能透传 HTTP/2 PING 帧，但不代表数据通道畅通。应用层 PING/PONG 能更准确地反映业务层面的连接活性。gRPC keepalive 参数保持现有配置作为传输层兜底。

---

## 心跳 Handler 实现位置

| Option | Description | Selected |
|--------|-------------|----------|
| A: 普通 Handler (system/ping) | 统一走 Dispatcher 路由，复用拦截器链 | ✓ |
| B: Netty 管道拦截 | 在 gRPC 传输层直接拦截，零开销 | |
| C: 专有 HeartbeatManager | 独立定时器管理所有连接，灵活但有额外复杂度 | |

**User's choice:** A — 普通 Handler `system/ping`
**Notes:** 用户倾向于保持一致性，PING/PONG 走标准 Dispatcher 路由。AuthInterceptor 和 LogInterceptor 需跳过 `system/ping` 方法。心跳 Handler 本身不携带业务 payload。

---

## 心跳超时处理行为

| Option | Description | Selected |
|--------|-------------|----------|
| A: 直接断开 | 超时即关闭 StreamObserver | |
| B: 优雅降级 | 标记可疑 → 停止推送 → 最终断开 | ✓ |
| C: 仅日志告警 | 仅记录不主动断开 | |

**User's choice:** B — 优雅降级
**Notes:** 两阶段超时策略：T1=60s 标记可疑、停止推送；T2=150s 强制断开清理 Session。客户端若在窗口内恢复则恢复正常。该策略适合移动端网络抖动场景。

---

## Claude's Discretion

- `system/ping` 方法名可在实现时调整
- T1/T2 时间窗口可在实现中根据测试结果微调
- 心跳 Handler 的测试策略由实现者自行决定

## Deferred Ideas

None.
