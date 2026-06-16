---
slug: fix-gateway-server-tests
status: complete
created: 2026-06-16
expert: java-developer
mode: quick
files_modified:
  - server/src/test/kotlin/com/nebula/server/KoinVerificationTest.kt
  - server/build.gradle.kts
  - gateway/build.gradle.kts
  - gateway/src/test/kotlin/com/nebula/gateway/testutil/TestHelper.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/admin/DeadLetterQueryHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/handler/chat/send/SendMessageHandlerTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/push/PushServiceTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/service/ChatServiceReconnectTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/service/ChatServiceReconnectIntegrationTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/session/SessionRegistryTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/di/GatewayModuleTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/di/HandlerRegistryTestBase.kt
  - gateway/src/test/kotlin/com/nebula/gateway/di/MessageReliabilityModuleTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/ConversationSmokeTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/FriendSmokeTest.kt
  - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/PrivacySmokeTest.kt
total_files: 16
verification: PASS
---

# Quick Summary: 修复 server/gateway 测试代码编译

## 执行结果

**状态**: 全部通过 — `./gradlew :server:compileTestKotlin :gateway:compileTestKotlin` BUILD SUCCESSFUL

## 修复内容

### 策略

Phase 12 将模块间依赖从 `api` → `implementation`，导致 gateway 测试代码无法访问 `com.nebula.repository.*` 类型。修复分三类：

1. **类型替换**: 测试中直接使用的 Entity/Repository 类型替换为对应的 Service/DTO 类型
2. **构造函数适配**: Handler/ChatService/PushService 构造函数参数从 Repository 改为 Service 类型
3. **DI 测试依赖补充**: gateway/build.gradle.kts 添加 `testImplementation(project(":repository"))`，允许 Koin DI 集成测试访问 repository 类型（与 server 模块一致，不影响生产依赖图）

### 类型映射

| 旧类型 | 新类型 |
|--------|--------|
| `OnlineStatusRepository` | `OnlineStatusService` |
| `FriendshipRepository` | `FriendService` |
| `PrivacyRepository` | `UserPrivacyService` |
| `ConversationMemberRepository` | `ConversationService` |
| `SessionRepository` | `SessionStore` |
| `DeadLetterEntity` | `DeadLetterDTO` |
| `ConversationEntity` | `ConversationInfo` / mock |
| `ConversationMemberEntity` | `ConversationMemberInfo` |
| `FriendshipEntity` | `FriendshipInfo` |
| `serviceModule` (gateway/di) | `serviceKoinModule` (service/init) |

### 文件清单

| 模块 | 文件 | 变更 |
|------|------|------|
| server | KoinVerificationTest.kt | serviceModule → serviceKoinModule import |
| server | build.gradle.kts | 添加 testImplementation(project(":service")) |
| gateway | build.gradle.kts | 添加 testImplementation(project(":repository")) |
| gateway | TestHelper.kt | 实体工厂 → DTO 工厂，移除 testUser/testFriendRequest |
| gateway | DeadLetterQueryHandlerTest.kt | DeadLetterEntity → DeadLetterDTO |
| gateway | SendMessageHandlerTest.kt | ConversationEntity → mock SendMessageResult |
| gateway | PushServiceTest.kt | ConversationMemberRepository → ConversationService |
| gateway | ChatServiceReconnectTest.kt | repository 参数 → service 参数 |
| gateway | ChatServiceReconnectIntegrationTest.kt | repository 参数 → service 参数 |
| gateway | SessionRegistryTest.kt | SessionRepository → SessionStore |
| gateway | GatewayModuleTest.kt | (test 依赖修复后自动通过) |
| gateway | HandlerRegistryTestBase.kt | (test 依赖修复后自动通过) |
| gateway | MessageReliabilityModuleTest.kt | (test 依赖修复后自动通过) |
| gateway | ConversationSmokeTest.kt | Handler 构造函数参数减少 |
| gateway | FriendSmokeTest.kt | Handler 构造函数参数减少 |
| gateway | PrivacySmokeTest.kt | OnlineStatusRepository → OnlineStatusService |

### 依赖链保持

- 生产依赖链不变（所有模块仍使用 `implementation`，无 `api` 传递）
- `testImplementation` 仅在测试编译期生效，不传递到消费者
- 测试覆盖场景（语义）不变，仅适配接口变更
