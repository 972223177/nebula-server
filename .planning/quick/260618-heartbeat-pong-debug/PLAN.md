---
description: "为 handlePing/onNext 添加日志和异常保护，诊断客户端握手超时问题"
created: 2026-06-18
---

# Plan: 业务层心跳 PONG 无响应诊断与修复

## 问题描述

客户端日志：
```
23:21:16.332 [GrpcIsolate] 发送握手 PING
23:21:21.349 [GrpcIsolate] 握手超时（5s 未收到 PONG）
```

服务端日志：只有启动日志，**没有任何 PING/PONG 相关日志**，无法确认：
- PING 是否到达服务端应用层
- PONG 是否被发送
- 是否在发送 PONG 时出现异常

## 根因分析

`ChatService.kt` 中 `handlePing()` 方法（L544-562）缺少：
1. **PING 到达的 trace 日志** — 无法确认 PING 是否进入应用层
2. **PONG 发送的 trace 日志** — 无法确认 PONG 是否发送成功
3. **异常保护** — `responseObserver.onNext(pongEnvelope)` 没有 try-catch，若流损坏则异常可能被 gRPC 运行时静默吞噬

`onNext()` 分发方法（L215-222）也只在 `else` 分支记录警告，PING 方向无任何日志。

## 修复方案

### 1. `handlePing()` 添加日志和异常保护
- PING 到达时记录 debug 日志（含 requestId）
- PONG 发送前记录 debug 日志
- 用 try-catch 包裹 `responseObserver.onNext()`，捕获异常时记录 error 日志并清理连接

### 2. `onNext()` 分发添加入口日志
- 对不同 Direction 添加 trace 级别入口日志
- PING 方向记录 debug 日志以便诊断

## 修改文件
- `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt`
