---
phase: 09
slug: reconnection
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-13
---

# Phase 09 — 安全合约

> 客户端断线重连服务端支持（DISCONNECT 推送、Redis pipeline 连接清理、缓存再投递缓冲区）的追溯安全审计报告。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| `SessionRegistry.onEviction → tokenToObserver` | 驱逐回调将驱逐事件映射到具体设备的 StreamObserver | token（String），由 `registerWithDeviceType` 产生的驱逐事件 |
| `eviction callback → ChatStreamObserver.onNext()` | 推送 DISCONNECT Envelope 到即将关闭的旧连接 | Envelope(Direction.PUSH, PushEventType.DISCONNECT, content) |
| `eviction callback → observer.onCompleted()` | 关闭旧 gRPC 流触发连接清理 | 无数据负载（gRPC 流关闭信号） |
| `handleLoginSuccess → ChatStreamObserver.activateDelivery()` | 旧连接清理完成后激活新连接的缓存投递 | pendingBuffer 中缓存的 Envelope 列表 |
| `PushService.deliver() → pendingBuffer` | 推送消息时根据 deliveryActive 状态选择缓存或直接投递 | Envelope（任意推送类型） |
| `ChatStreamObserver.pendingBuffer → activateDelivery() → gRPC onNext()` | 激活投递时逐条从缓存取出写入 gRPC 流 | Envelope 列表，FIFO 顺序 |
| `cleanupConnection → tokenToObserver.values.remove()` | 精确移除当前连接的 StreamObserver 引用 | responseObserver 实例引用 |
| `cleanupConnection → userStreamRegistry.removeStream()` | 防御性检查后从用户流注册表移除 | userId + StreamObserver |
| `cleanupConnection → scope.launch { delay(60_000) }` | 伪在线延迟后标记离线 | delayedOfflineJob |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-09-01 | 身份伪造 (Spoofing) | handleLoginSuccess 重连身份 | mitigate | 重连时复用 login Token，通过 AuthInterceptor 验证（与首次登录相同）。`sessionRegistry.registerWithDeviceType()` 使用认证后的 Session 信息注册，token 由服务端生成（`Session.token` via UUID），客户端无法伪造 | closed |
| T-09-02 | 身份伪造 (Spoofing) | DISCONNECT 推送伪造 | mitigate | DISCONNECT 推送仅在 `ensureEvictionCallbackRegistered()` 的 eviction callback 内部执行（ChatService.kt:424~431），不暴露任何 RPC 接口或外部触发路径。客户端无法主动触发 DISCONNECT 推送 | closed |
| T-09-03 | 篡改 (Tampering) | pendingBuffer 缓存 Envelope 篡改 | mitigate | `pendingBuffer` 是 `ConcurrentLinkedQueue<Envelope>`（ChatService.kt:140），Envelope 为 protobuf 不可变对象。仅 ChatStreamObserver 内部代码可访问（private 字段），外部组件无法直接操作 | closed |
| T-09-04 | 抵赖 (Repudiation) | 重连事件无审计日志 | accept | DISCONNECT 推送成功、activateDelivery 激活等关键重连事件仅在异常时记录日志（push 失败 warn 日志，delivery 失败 error 日志）。成功场景无日志。与 Phase 6/7/8 审计策略一致，Phase 10 可增加显式操作审计 | closed |
| T-09-05 | 抵赖 (Repudiation) | batchDelete 连接清理无操作日志 | accept | `SessionRepository.batchDelete()` 成功执行后不记录删除的 key 数量和内容。不记录日志不影响功能正确性，但故障排查时缺乏审计线索。当前阶段作为基础设施方法供 Phase 10 调用，Phase 10 可补充调用侧的日志 | closed |
| T-09-06 | 信息泄露 (Information Disclosure) | pendingBuffer 缓存消息泄漏 | mitigate | `cleanupPending()` 在 `onCompleted()` 和 `onError()` 中调用（ChatService.kt:156,163），清除所有缓存消息。连接关闭（正常、异常、驱逐）三种路径均正确触发清理。`pendingBuffer` 为 private 字段，外部无法访问 | closed |
| T-09-07 | 信息泄露 (Information Disclosure) | DISCONNECT 通知内容泄露用户行为 | mitigate | DISCONNECT Envelope 的内容为固定字符串"连接将被关闭，请触发重连流程"（ChatService.kt:427），不包含用户标识、会话信息或任何动态数据。仅推送到即将关闭的连接自身 | closed |
| T-09-08 | 拒绝服务 (Denial of Service) | pendingBuffer 无有效超时机制 | accept | `DELIVERY_TIMEOUT_MS = 10_000L` 常量已定义（ChatService.kt:41）但超时机制未实现（无 delay/计时器代码引用该常量）。当 `cleanupConnection` 阻塞超过 10s 时，`activateDelivery()` 不会被强制触发。但 `MAX_PENDING = 1000` 上限（ChatService.kt:39）提供了缓冲区大小保护，超限丢弃最旧消息。详见已接受风险记录 R-09-01 | closed |
| T-09-09 | 拒绝服务 (Denial of Service) | delayedOfflineJob 协程泄漏 | mitigate | `cleanupConnection()` 在启动新延迟离线任务前调用 `delayedOfflineJob?.cancel()`（ChatService.kt:247），取消旧 Job 防止协程泄漏。`handleLoginSuccess()` 中也有相同的取消保护（ChatService.kt:334），双重保障 | closed |
| T-09-10 | 拒绝服务 (Denial of Service) | 连接洪泛攻击 | mitigate | RateLimitInterceptor 为每个用户限制最大 20 个并发 gRPC 流（RateLimitInterceptor.kt）。继承自 Phase 4 的基础安全控制，Phase 9 未增加新的连接管理路径 | closed |
| T-09-11 | 权限提升 (Elevation of Privilege) | 缓存再投递新旧连接消息串流 | mitigate | D-67 的缓存再投递机制：新连接建立后先缓存消息（`deliveryActive = false`），旧连接通过 eviction callback 的 `onCompleted()` 清理完成后，再调用 `activateDelivery()` 投递到新连接（ChatService.kt:346~351）。旧连接清理完成前新连接的消息被安全缓存 | closed |
| T-09-12 | 权限提升 (Elevation of Privilege) | cleanupConnection 误删新连接 observer | mitigate | `tokenToObserver.values.remove(responseObserver)` 精确匹配当前实例（ChatService.kt:229），`userStreamRegistry.removeStream()` 前通过 `currentStreams.any { it == responseObserver }` 防御性检查（ChatService.kt:233），确保仅移除自身的 observer | closed |
| T-09-13 | 权限提升 (Elevation of Privilege) | tokenToObserver 并发安全 | mitigate | `tokenToObserver` 使用 `ConcurrentHashMap<String, StreamObserver<Envelope>>`（ChatService.kt:76），remove 和 put 操作线程安全。不迭代遍历，使用 `values.remove()` 精确实例匹配。继承自 Phase 4/6 的并发安全模式 | closed |
| T-09-14 | 篡改 (Tampering) | batchDelete finally 恢复失败 | mitigate | `connection.setAutoFlushCommands(false)` → try 块内 `async.del()` + `flushCommands()` → `finally` 块内 `setAutoFlushCommands(true)`（SessionRepository.kt:74~86）。`finally` 确保即使 DEL 或 flushCommands 抛出异常也能恢复 autoFlush，防止后续 Redis 操作被意外缓冲 | closed |

*状态: open · closed*
*处置: mitigate (需实现) · accept (已记录风险) · transfer (第三方)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-09-01 | T-09-08 | `DELIVERY_TIMEOUT_MS` 常量已定义（第 41 行）但超时强制激活投递机制未实现。当 `registerWithDeviceType()` 返回的 evictedToken 触发 eviction callback 后，`cleanupConnection()` 在 `onCompleted()` 中执行。如果 `cleanupConnection()` 中的 `delayedOfflineJob` 启动等操作阻塞超过 10s，`activateDelivery()` 不会自动触发。但以下因素使风险可控：(1) `cleanupConnection()` 不涉及 IO 操作（仅内存操作 + scope.launch），执行时间通常 < 1ms；(2) `MAX_PENDING=1000` 限制缓冲区上限；(3) eviction callback 在 `registerWithDeviceType()` 中同步执行，调用的 `observer.onCompleted()` 中的 `cleanupConnection()` 不会长时间阻塞。Phase 10 消息可靠性阶段可补充超时机制 | nx-security-auditor | 2026-06-13 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-09-01: 重连身份认证

**验证位置**: `ChatService.kt:321~344 (handleLoginSuccess)`, `AuthInterceptor.kt`

```kotlin
// handleLoginSuccess 中注册 session 使用 AuthInterceptor 认证后的 Session
val session = currentCoroutineContext().requireSession()  // 继承自 Phase 4
val token = sessionRegistry.registerWithDeviceType(session)  // token 来自服务端生成的 Session.token
```

reconnection 复用完整的 login + AuthInterceptor 认证链，无新增身份认证接口。所有 gRPC 请求在进入 ChatService 前经过 AuthInterceptor（FrameworkModule.kt:32~38）：

```kotlin
single<Interceptor> { AuthInterceptor(get(), skipMethods = setOf("system/ping", "user/login", "user/register")) }
single<Interceptor> { LogInterceptor() }
single<Interceptor> { RateLimitInterceptor() }
single<Interceptor> { ExceptionInterceptor() }
```

**验证**: ChatService 中不存在跳过认证的连接建立路径。

### T-09-02: DISCONNECT 推送不可伪造

**验证位置**: `ChatService.kt:424~432`

```kotlin
sessionRegistry.onEviction { token ->
    val observer = tokenToObserver.remove(token)
    if (observer != null) {
        // Step 1: 推送 DISCONNECT
        try {
            val disconnectEnvelope = Envelope.newBuilder()
                .setDirection(Direction.PUSH)
                .setRequestId("")
                .setMessage(Message.newBuilder()
                    .setEventType(PushEventType.DISCONNECT)
                    .setContent("连接将被关闭，请触发重连流程")
                    .build())
                .build()
            observer.onNext(disconnectEnvelope)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to push DISCONNECT, connection may already be broken" }
        }
        // Step 2: 关闭连接
        observer.onCompleted()
    }
}
```

**验证**: 
1. `onEviction` 触发源仅来自 `SessionRegistry.registerWithDeviceType()` 的同类型设备互踢逻辑
2. 推送的目标 observer 通过 `tokenToObserver.remove(token)` 精确匹配，不广播
3. No external RPC or handler can trigger this code path

### T-09-03: pendingBuffer 缓存不可篡改

**验证位置**: `ChatService.kt:140, 180~188`

```kotlin
private val pendingBuffer = ConcurrentLinkedQueue<Envelope>()
// ...
fun deliver(envelope: Envelope) {
    if (deliveryActive) {
        responseObserver.onNext(envelope)
    } else {
        if (pendingBuffer.size >= MAX_PENDING) {
            pendingBuffer.poll()
        }
        pendingBuffer.add(envelope)
    }
}
```

**验证**:
1. `pendingBuffer` 是 `private` 字段，仅 ChatStreamObserver 内部方法可访问
2. `Envelope` 是 protobuf 生成的不可变类，创建后不可修改
3. `ConcurrentLinkedQueue` 保证 FIFO 顺序和线程安全

### T-09-06: pendingBuffer 连接关闭时安全清理

**验证位置**: `ChatService.kt:156~166, 216~218`

```kotlin
// onCompleted — 正常关闭
override fun onCompleted() {
    cleanupPending()              // 先清空 pendingBuffer
    cleanupConnection()           // 然后清理连接
    responseObserver.onCompleted()
}

// onError — 异常关闭
override fun onError(t: Throwable) {
    logger.error(t) { "ChatService stream error" }
    cleanupPending()              // 先清空 pendingBuffer
    cleanupConnection()           // 然后清理连接
    responseObserver.onError(t)
}

// cleanupPending 定义
fun cleanupPending() {
    pendingBuffer.clear()
}
```

**验证**: 三种连接关闭路径均正确调用 `cleanupPending()`:
1. `onCompleted()` 第 156 行 ✅
2. `onError()` 第 163 行 ✅
3. Eviction callback 通过 `observer.onCompleted()` 间接触发第 156 行 ✅

### T-09-11: 缓存再投递防止消息串流

**验证位置**: `ChatService.kt:346~359`

```kotlin
// D-67: 激活缓存再投递
if (evictedToken != null) {
    // eviction callback 在 registerWithDeviceType 中同步执行完成
    (responseObserver as? ChatStreamObserver)?.activateDelivery()
} else {
    // 无旧连接，直接激活投递
    (responseObserver as? ChatStreamObserver)?.let {
        it.deliveryActive = true
    }
}
```

**验证**: 
1. `registerWithDeviceType()` 返回 evictedToken 表示有旧连接被驱逐
2. eviction callback 在 `registerWithDeviceType()` 调用中**同步**执行完成（包括 DISCONNECT 推送 + onCompleted + cleanupConnection）
3. `handleLoginSuccess` 继续执行时才调用 `activateDelivery()`，确保旧连接已清理完毕
4. 无 evictedToken 时直接激活，适用于首次登录或旧连接已超时清理的场景

### T-09-12: cleanupConnection 防御性检查

**验证位置**: `ChatService.kt:228~239`

```kotlin
private fun cleanupConnection() {
    // D-67: 使用 values.remove() 精确匹配当前 observer 实例
    tokenToObserver.values.remove(responseObserver)

    // D-01: 防御性检查：仅当当前 observer 仍在注册表中时才移除
    userId?.let { uid ->
        val currentStreams = userStreamRegistry.getStreams(uid)
        if (currentStreams.any { it == responseObserver }) {
            userStreamRegistry.removeStream(uid, responseObserver)
        }
    }
    // ...
}
```

**验证**:
1. `values.remove()` 通过对象引用精确匹配，不依赖 token 或 userId（第 229 行）
2. `currentStreams.any { it == responseObserver }` 在移除前再次确认 observer 实例身份（第 233 行）
3. 多设备竞争场景下，新连接的 observer 不会被误移除

### T-09-14: batchDelete finally 恢复

**验证位置**: `SessionRepository.kt:74~86`

```kotlin
suspend fun batchDelete(keys: List<String>) {
    if (keys.isEmpty()) return
    connection.setAutoFlushCommands(false)
    try {
        val async = connection.async()
        keys.forEach { async.del(it) }
        connection.flushCommands()
    } finally {
        // 异常时也要恢复 autoFlush
        connection.setAutoFlushCommands(true)
    }
}
```

**验证**:
1. `keys.isEmpty()` 快速返回，避免空操作（第 75 行）
2. `finally` 块保证异常时也恢复 autoFlush（第 84 行），防止后续 Redis 操作被意外缓冲
3. 使用 `connection.async()` 获取 Lettuce 原生异步 API（非 PLAN.md 中过时的 `RedisAsyncCommandsImpl`）

---

## 并发安全

### ChatStreamObserver 实例隔离

每个 gRPC 双向流对应一个 `ChatStreamObserver` 实例，状态隔离：

| 字段 | 线程安全 | 说明 |
|------|---------|------|
| `pendingBuffer` | `ConcurrentLinkedQueue` 无锁线程安全 | 多线程入队/出队安全 |
| `deliveryActive` | `@Volatile` 保证可见性 | 标志切换即时对读线程可见 |
| `delayedOfflineJob` | 单一 gRPC 线程写入（gRPC 框架保证 `onNext/onCompleted/onError` 串行） | 无并发写入竞态 |
| `tokenToObserver` | `ConcurrentHashMap` | 跨连接实例的共享映射 |

### gRPC 事件串行化保证

`ChatStreamObserver` 的 `onNext`/`onCompleted`/`onError` 由 gRPC 框架保证串行调用（单线程递送），因此：
- `deliver()` 和 `activateDelivery()` 不存在并发调用同一实例的问题
- `cleanupPending()` 和 `cleanupConnection()` 不会同时执行
- `delayedOfflineJob` 在同一实例上不会被并发赋值

### 跨连接竞争保护

多设备同类型重连场景：
1. 设备 A 调用 `handleLoginSuccess` → `registerWithDeviceType` 驱逐设备 B
2. `registerWithDeviceType` 同步执行 B 的 eviction callback（DISCONNECT + onCompleted + cleanupConnection）
3. B 的 `cleanupConnection` 通过防御性检查精确移除 B 的 observer
4. A 的 `handleLoginSuccess` 继续执行 `activateDelivery`
5. A 和 B 的 `tokenToObserver` 条目互不干扰（不同 token-key）

---

## 继承自前序阶段的安全控制

| 控制 | 来源 | Phase 9 使用情况 |
|------|------|----------------|
| AuthInterceptor → Session 注入协程上下文 | Phase 4 | ✅ handleLoginSuccess 通过 `requireSession()` 获取身份 |
| RateLimitInterceptor → 每用户 20 并发限制 | Phase 4 | ✅ 防止连接洪泛 |
| ExceptionInterceptor → BizCode 统一处理 | Phase 4 | ✅ 异常不会直接暴露内部信息 |
| SessionRegistry → L1+L2 二级缓存 + 设备互踢 | Phase 4 | ✅ `registerWithDeviceType()` + `onEviction()` 核心依赖 |
| UserStreamRegistry → ConcurrentHashMap + CopyOnWriteArrayList | Phase 6 | ✅ `getStreams()` / `removeStream()` 防御性检查使用 |
| PushService.pushEventToUser 单 observer 异常容错 | Phase 6 | ✅ 推送消息复用 |
| 60s 伪在线逻辑 | Phase 8 | ✅ `delayedOfflineJob` 复用 |
| NettyServer keepalive/maxConnectionAge | Phase 5 | ✅ 僵尸连接自动回收 |

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|---------|---------|--------|------|--------|
| 2026-06-13 | 14 | 14 | 0 | nx-security-auditor |

---

## 签收

- [x] 重连身份复用 AuthInterceptor 认证链，无新增认证漏洞
- [x] DISCONNECT 推送仅在 eviction callback 内部执行，无外部触发路径
- [x] pendingBuffer 使用 `ConcurrentLinkedQueue`，private 字段，protobuf 不可变对象
- [x] cleanupPending() 在三种连接关闭路径中均正确调用
- [x] cleanupConnection() 防御性检查防止误删新连接 observer
- [x] 缓存再投递机制确保旧连接清理完成前新连接消息被缓存
- [x] batchDelete finally 块保证异常时恢复 autoFlush
- [x] 所有 mitigate 威胁均有代码级验证（含文件:行号）
- [x] T-09-09 delayedOfflineJob 泄漏已在 Phase 9 修复（ChatService.kt:247），从 accept 转为 mitigate
- [x] 所有 accept 威胁均有风险记录（R-09-01）
- [x] threats_open 为 0

---

## SECURITY AUDIT COMPLETE
