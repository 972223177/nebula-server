---
phase: 4
reviewers: [codebuddy-code]
reviewed_at: 2026-06-12T03:15:00+08:00
plans_reviewed: [04-01-PLAN.md, 04-02-PLAN.md, 04-03-PLAN.md, 04-04-PLAN.md]
---

# Cross-AI Plan Review — Phase 4

## CodeBuddy Code 评审

### 总览

Phase 4（Handler Framework）构建了整个项目的请求处理骨架，涵盖 4 个 Wave 共 4 个计划：核心 Handler 接口与 Dispatcher（04-01）、Session 管理与心跳（04-03）、拦截器 Pipeline（04-02）、Koin DI 集成（04-04）。共 6 个需求（HNDL-01~06）全部有计划覆盖。计划整体质量较高，架构决策清晰（D-01~D-32），TDD 测试策略明确，威胁建模覆盖 STRIDE 项。

### 优势

- **架构分离清晰**：Dispatcher 与 gRPC StreamObserver 解耦（D-14），返回 Response proto 对象，使得单元测试不需要启动 gRPC 服务
- **泛型设计合理**：Handler<ReqT, RespT> 泛型接口 + suspend 协程模式，与 Phase 2 建立的协程基础设施一致
- **测试策略完备**：D-23~D-26 完整定义 JUnit5 + MockK + runTest 的测试模式，每个 Plan 都有测试任务
- **威胁建模认真**：每个 Plan 的 threat_model 节都做了 STRIDE 分析，T-04-01~T-04-15 均有应对措施
- **Wave 依赖关系正确**：04-01 (Wave 1) → 04-03 (Wave 2) → 04-02 (Wave 3) → 04-04 (Wave 4)，依赖链清晰
- **设计决策完整可追溯**：CONTEXT.md 记录 32 个决策项（D-01~D-32），每个 Plan 的 must_haves 指定引用哪些 D-x

### 关注点

- **MEDIUM: Registration 仍然在启动时由 Koin 负责，但 Plans 没有明确说明 Handler 何时注册到 HandlerRegistry**。04-04-PLAN.md 的 GatewayModule 注册了 HandlerRegistry 和 PingHandler，但缺少一个步骤将 PingHandler 实际注册到 HandlerRegistry。HandlerRegistry 的 `register(entry)` 需要被调用，Koin 单例的初始化并不自动触发注册。建议在 GatewayModule 中添加 `frameworkModule` 中的 `onCreate` 或独立 `init` 逻辑在 Koin 启动后执行注册。

- **MEDIUM: 04-02 依赖 04-01 和 04-03，但实际代码依赖顺序有问题**。AuthInterceptor 依赖 SessionRegistry（Plan 04-03 创建），而 SessionRegistry 又依赖 Handler 接口（Plan 04-01）和 SessionRepository（Phase 3）。Wave 2（04-03）在 Wave 3（04-02）之前执行，顺序正确，但文件层面的实际编译依赖链需要确保 `:gateway` 模块的 build.gradle.kts 包含 `:repository` 依赖。

- **LOW: KoroutineContext Session 传递的 Pitfall 1 已记录，但 Plan 中未对具体 Handler 编写做约束**。RSEARCH.md Pitfall 1 提到 Handler 内部用 `launch { }` 会导致 Session 丢失，但 Plan 的任务描述中没有明确提醒开发者。建议补充一条 warning 在每个 Handler 实现任务中。

- **LOW: RateLimitInterceptor 在当前阶段仅为骨架（stub），Phase 11 才实现具体逻辑**。这意味着 Phase 5~10 的所有业务 Handler 都没有限流保护。骨架直接 `chain.proceed()` 而不做任何速率检查，存在生产上线风险。建议至少实现一个最简单的计数器（如基于 ConcurrentHashMap 的每秒请求计数），否则 Phase 5+ 的 Handler 在没有限流的情况下直接暴露。

- **LOW: 异常处理兜底的 CoroutineExceptionHandler 日志级别**。04-01 T-04-03 提到 SupervisorJob + CoroutineExceptionHandler 防止协程异常崩溃，但 Plan 中 ExceptionInterceptor 仅在链尾捕获异常。如果 Dispatcher 的 `CoroutineExceptionHandler` 也记录异常，会造成重复日志。建议在 Dispatcher 的 CoroutineExceptionHandler 中使用 `logger.warn` 而非 `logger.error` 以避免与 ExceptionInterceptor 的日志重复。

- **LOW: SessionRegistry L2 Redis 调用未考虑超时和熔断**。SessionRegistry.validate() 在 L1 miss 时会通过 SessionRepository 调用 Redis。如果 Redis 不可用，validate() 会挂起直到超时。建议在 Plan 中补充 Redis 不可用时的降级策略（如直接透传或返回 TOKEN_INVALID）。

- **LOW: 04-04 Plan 3 的 PipelineIntegrationTest 使用 PingHandler 验证全链路，但不能验证 AuthInterceptor 真正执行**。因为 PingHandler 在 skipMethods 白名单中，AuthInterceptor 跳过了它。集成测试无法覆盖"需要认证的 Handler 被 AuthInterceptor 拒绝"的场景。建议增加一个需要认证的 mock Handler 的集成测试。

### 建议

1. 在 GatewayModule 中添加显式的"注册"步骤：Koin 的 `frameworkModule` 中通过 `onCreate` 回调在 HandlerRegistry 单例创建后遍历所有 Handler 单例并调用 `register()`
2. 为 RateLimitInterceptor 添加一个简单的并发计数器限流（如基于 userId 的 Semaphore），待 Phase 11 替换为令牌桶
3. 在 CONTEXT.md 或每个 Handler 任务的 KDoc 中提醒开发者 Pitfall 1（CoroutineContext 传递失效）
4. 集成测试补充需要认证的 Mock Handler 场景
5. 文档化 SessionRegistry L2 超时配置和 Redis 不可用降级策略

### 风险评估

**整体风险等级：LOW**

理由：
- 4 个 Plan 覆盖了 Phase 4 的全部 6 个需求（HNDL-01~06），无遗漏
- 架构决策 32 项（D-01~D-32）全部锁定，设计一致性高
- 测试策略完备（D-23~D-26）
- Wave 依赖顺序经过精心设计
- 主要关注点（Handler 注册时机、缺少限流实现）可以在实现阶段解决
- 无影响 Phase 5~11 的重大设计风险

### 各计划评分

| Plan | 完整性 | 清晰度 | 风险 | 备注 |
|------|--------|--------|------|------|
| 04-01 Core Handler Framework | 9/10 | 9/10 | LOW | 结构最清晰，TDD 任务分解优秀 |
| 04-03 Session & Heartbeat | 8/10 | 8/10 | LOW | 双重心跳策略设计完备，Jitter 防惊群 |
| 04-02 Interceptor Pipeline | 7/10 | 8/10 | LOW | 缺少限流实现，Plan 中已标注 Phase 11 |
| 04-04 Koin DI & Integration | 6/10 | 7/10 | MEDIUM | 缺少显式 Handler 注册步骤，集成测试覆盖不全 |

---

## 共识总结

### 共同优势
- 架构层次清晰，解耦良好
- TDD 测试覆盖全面
- 威胁建模认真

### 共同关注点
- Handler 注册时机不明确（Koin 初始化 vs 显式 register）
- 缺少限流实现（虽然标注 Phase 11）
- 集成测试的认证场景覆盖不足

### 不同观点
- CodeBuddy Code 认为整体风险 LOW，建议实现阶段修正 Handler 注册步骤
