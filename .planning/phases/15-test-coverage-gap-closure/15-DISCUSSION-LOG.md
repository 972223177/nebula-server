---
phase: 15
status: in_discussion
---

# Phase 15: 讨论日志

## 初始上下文

- 来源: 2026-06-16 三模块测试审查（quick task: review-test-service-gateway-repository）
- 审查报告路径: `.planning/quick/20260616-review-test-service-gateway-repository/`
- 审查发现 P0×6、P1×17、P2×11，共 34 项问题

---

### 待讨论问题

1. **范围界定**: 是否全部 P0/P1/P2 问题纳入？还是按优先级裁剪？
2. **Redis 测试策略**: SessionRepository 等纯 Redis 组件使用 MockK 还是 Testcontainers？
3. **反射注入修复**: ReadReportHandlerTest/RedisDeliveryTrackerTest 采用方案 A/B/C？
4. **P2 纳入范围**: 是否全部纳入还是部分延期？
5. **执行顺序**: 按模块（repository→service→gateway）还是按优先级（P0→P1→P2）？

---

*讨论开始于: 2026-06-16 14:02*
