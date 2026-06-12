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
| B: 双重心跳 | 传输层 + 业务层双重检测，最可靠 | |
| C: 纯应用层 PING/PONG | 完全由应用层控制 | （中途选择）|
| D: 业界标准双重心跳（最终） | gRPC keepalive精细化 + 应用层 PING/PONG 端到端检测 | ✓ |

**Final choice:** D — 参考 Go gRPC 业界实践的精细化双重心跳方案
- gRPC keepalive 快速检测 TCP 断开（keepAliveTime=60s, timeout=20s, ~80s 检测），开启 `permitKeepAliveWithoutCalls(true)`
- 新增 `maxConnectionIdle(300s)`、`maxConnectionAge(1800s)`、`maxConnectionAgeGrace(10s)`
- 应用层 PING 间隔 300s（5 分钟），检测半开连接和 NAT 静默清连接
- PING 与业务消息在同一数据通道，端到端真实状态检测

---

## 心跳 Handler 实现位置

| Option | Description | Selected |
|--------|-------------|----------|
| A: 普通 Handler (system/ping) | 统一走 Dispatcher 路由，复用拦截器链 | ✓ |
| B: Netty 管道拦截 | 在 gRPC 传输层直接拦截，零开销 | |
| C: 专有 HeartbeatManager | 独立定时器管理所有连接，灵活但有额外复杂度 | |

**User's choice:** A — 普通 Handler `system/ping`
**Notes:** 用户倾向于保持一致性，PING/PONG 走标准 Dispatcher 路由。AuthInterceptor 和 LogInterceptor 需跳过 `system/ping` 方法。

---

## 心跳超时处理行为

| Option | Description | Selected |
|--------|-------------|----------|
| A: 直接断开 | 超时即关闭 StreamObserver | |
| B: 优雅降级 | 标记可疑 → 停止推送 → 最终断开 | |
| C: 仅日志告警 | 仅记录不主动断开 | |

**User's choice:** B — 优雅降级（拉长超时避免与 gRPC keepalive 重叠）
- T1=450s（7.5 分钟）无 PING → 标记可疑，停止推送
- T2=900s（15 分钟）仍无 PING → 强制断开，清理 Session，触发重连

---

## Claude's Discretion

- `system/ping` 方法名可在实现时调整
- T1/T2 时间窗口可在实现中根据测试结果微调
- gRPC keepalive 参数可在运行环境中微调
- 心跳 Handler 的测试策略由实现者自行决定

## Deferred Ideas

None.
