---
status: complete
completed-at: 2026-06-18T23:30:00+08:00
follow-up-needed: true
---

# 业务层心跳 PONG 无响应 — 诊断与修复

## 问题

客户端在 `23:21:16.332` 发送握手 PING，5s 后超时未收到 PONG。
服务端日志中没有任何 PING/PONG 相关记录，无法定位原因。

## 第一轮修改（commit bb4a29f）

**文件**: `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt`

1. **`onNext()` 入口日志** — 对 REQUEST/PING 方向添加 `[stream]` 前缀 debug 日志，记录 requestId
2. **`handlePing()` 日志** — PING 到达时 `[heartbeat]` debug 日志 + PONG 发送时 debug 日志
3. **`handlePing()` 异常保护** — `responseObserver.onNext(pongEnvelope)` 加 try-catch，失败时记录 error 日志并清理连接
4. **`cleanupConnection()` 公开化** — `private` → `internal`，以便外部异常路径调用清理

## 第二轮修改（commit bb4a29f → 当前）

**补充连接层和传输层的诊断日志，解决"服务端无任何 Envelope 日志"的根因定位。**

### 修改文件

**1. `server/src/main/kotlin/com/nebula/server/server/ChatServer.kt`**
- 新增 `debugInterceptor`（`ServerInterceptor`）：记录每个 gRPC 连接的建立/关闭/消息接收
  - 连接建立时打印 `[transport] #N 新连接建立 remote=IP:port method=/nebula.chat.ChatService/chat`
  - 收到每条消息时打印 `[transport] #N 收到消息 #M remote=IP:port messageClass=Envelope`
  - 连接关闭时打印 `[transport] #N 连接关闭 status=OK`
  - 打印 `authority`（Authority header）用于排查 host 不匹配问题

**2. `gateway/src/main/kotlin/com/nebula/gateway/service/ChatService.kt`**
- `ChatStreamObserver` 构造时打印 `[stream] #N ChatStreamObserver 创建` 日志（含 responseObserver hash）
- `onNext()` PING 分支增强：打印 Envelope **全部关键字段**（direction、protocolVersion、hasRequest/hasResponse/hasMessage、payloadSize），用于诊断 proto 反序列化是否正确
- `onCompleted()` / `onError()` 添加 connId + userId + token 日志
- `handlePing()` 所有日志带上 connId

### 诊断能力

重新连接后，服务端日志将按层级展示：

```
[transport] #1 新连接建立 remote=127.0.0.1:54321 method=/nebula.chat.ChatService/chat
[transport] #1 收到消息 #1 remote=127.0.0.1:54321 messageClass=Envelope
[stream] #1 ChatStreamObserver 创建 ...
[stream] #1 收到 PING requestId="" protocolVersion=1 direction=PING hasRequest=false ...
[heartbeat] #1 发送 PONG requestId=""
```

通过这三层日志可以精确判断：
- **PING 是否到达服务器**（`[transport]` 层：收到消息 #1）
- **Envelope 反序列化是否正确**（`[stream]` 层：direction 是否为 PING，protocolVersion 是否正确）
- **ChatStreamObserver 是否创建**（如果没有 `[stream] #N` 日志，说明 gRPC BIDI_STREAMING 调用未到达 `ServerCalls.asyncBidiStreamingCall` 工厂）
- **PONG 是否发送成功**（`[heartbeat]` 层：发送成功/失败日志）

## Commit

`<next>` fix(quick-260618-heartbeat-pong-debug): ChatServer 添加 transport 层连接拦截器，ChatStreamObserver 添加 Envelope 字段 dump 和连接编号，诊断 PING 未达应用层
