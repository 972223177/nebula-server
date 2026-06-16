## 审核结果：Plan 14-02

### 审核摘要
- 审核次数：1/3（max 3）
- 审核状态：PASSED
- 问题数：2（阻塞 0 / 警告 2）

---

### 阻塞问题（必须修复）

无阻塞问题。

---

### 警告问题（建议修复）

| # | 类别 | 描述 | 建议修复 |
|---|------|------|---------|
| **W1** | 风险 | **任务 #3 (S8): kotlinx.coroutines.core 保留声明需确认传递路径** — 计划保留 `kotlinx.coroutines.core` 是标准做法，但当前 `server/build.gradle.kts:43` 的声明虽可删除（RESEARCH.md 第 393 行确认 server 源码中无 direct import），保留它是合理的安全选择。需要确认 `:gateway` 的依赖链确实传递了 `kotlinx.coroutines.core`。 | ① 在任务 #3 的注释中补充说明保留 `kotlinx.coroutines.core` 的理由："虽然 server 源码无 direct import，但 `kotlinx.coroutines` 是 Kotlin 协程运行时的基础依赖，保留显式声明可防止 gateway 依赖结构变化导致编译断裂"；② 在 `clean dependencies` 代码块中补充该注释（行 75 位置）。 |
| **W2** | 可测试性 | **任务 #1 deviceId 验证点遗漏：重连分支中 `sessionRegistry.validate()` 返回 null 时回退到密码登录的场景** — 如果 Token 有效但 deviceId 不匹配，当前方案抛 `TOKEN_INVALID`。但如果 `validate()` 返回 null（Token 过期），则回退到密码登录。测试用例 `tokenReconnectShouldReuseToken` 需确认新增 deviceId 校验后原有 Token 重连的正常流程不受影响。现有测试未覆盖"有 deviceId 且匹配"的正面场景。 | 在任务 #2 的测试列表中增加第三个测试方法：`token 重连时 deviceId 匹配应通过` — 构造 LoginRequest 含有效 Token + 匹配的 deviceId → 验证返回正常 LoginResp。确保 deviceId 校验不会误伤正常重连场景。 |

---

### 维度评分

| 维度 | 评分 | 说明 |
|------|------|------|
| 完整性 | PASS | GC5（deviceId 验证）和 S8（依赖清理）均覆盖了 CONTEXT.md 中的对应决策 |
| 可行性 | PASS | 任务 #1 修改 LoginHandler 的 5 行代码方案正确，与现有代码结构兼容；任务 #3 删除 5 行依赖声明风险低 |
| 一致性 | PASS | 两个 PLAN 修改不同模块（gateway + server），无冲突；与 PATTERNS.md 的 deviceId 验证模式一致 |
| 安全性 | PASS | deviceId 验证增强了 Token 重连安全性；无凭据硬编码；无序列化风险 |
| 可测试性 | PASS | 测试方案合理，MockK 可覆盖 deviceId 各种场景（匹配/不匹配/空） |

---

### 审核意见

**GC5 deviceId 验证（任务 #1-#2）** 方案质量较高：
- 利用 Session 已有的 `deviceId` 字段（`Session.kt:22`），无需新增存储
- 向后兼容：`req.deviceId` 为空时跳过验证
- 错误码复用 `TOKEN_INVALID(1101)`，与 Token 过期/无效的错误码一致

**S8 依赖清理（任务 #3）** 方案正确：
- 5 个依赖在 server 源码中均无 direct import（RESEARCH.md 确认）
- Runtime 仍通过 transitive 路径可用，零运行时风险
- 清理后的 `dependencies` 块（PLAN 行 62-85）结构清晰

---

### 最终裁决

**APPROVED** — 可以执行

建议采纳 **W2** 增加 deviceId 匹配的正面测试用例，但非阻塞要求。
