---
status: testing
phase: 01-project-scaffolding-proto-definitions
source: 01-01-SUMMARY.md, 01-02-SUMMARY.md, 01-03-SUMMARY.md, 01-04-SUMMARY.md, 01-05-SUMMARY.md
started: 2026-06-11T17:50:00+08:00
updated: 2026-06-11T17:50:00+08:00
---

## Current Test

number: 4
name: Core Proto Files Exist
expected: |
  3 proto files exist under `proto/src/main/proto/nebula/`: `envelope.proto`, `common/common.proto`, `message_type.proto`. All use correct java_package (com.nebula.chat).
awaiting: user response

## Tests

### 1. Gradle Build System - 6 Modules Recognized
expected: Running `./gradlew projects` or inspecting `settings.gradle.kts` shows 6 modules: proto, common, repository, service, gateway, server. Each module has its own build.gradle.kts.
result: pass

### 2. Version Catalog and Root Build
expected: `gradle/libs.versions.toml` exists with kotlin 2.1.20, protobuf 4.29.3, protobuf-plugin 0.10.0. Root `build.gradle.kts` declares kotlin-jvm and protobuf plugins (apply false), group = "com.nebula", version = "1.0.0-SNAPSHOT".
result: issue
reported: "虽然在libs.versions.toml中声明了，但到各个模块的 build.gradle查看并没有应用 toml 中的声明，特别是 plugin"
severity: major
fix: "已修复 root build.gradle.kts 和 proto/build.gradle.kts 改用 alias(libs.plugins...) 引用 catalog。javax.annotation-api 已纳入 catalog。build 验证通过。"

### 3. Project Scaffolding Files
expected: `.gitignore`, `.editorconfig`, `.gitattributes`, `README.md`, `LICENSE` and `.idea/` config files exist with expected content.
result: pass

### 4. Core Proto Files Exist
expected: 3 proto files exist under `proto/src/main/proto/nebula/`: `envelope.proto`, `common/common.proto`, `message_type.proto`. All use correct java_package (com.nebula.chat).
result: [pending]

### 5. envelope.proto Message Definitions
expected: `envelope.proto` contains Direction enum (UNSPECIFIED, REQUEST, RESPONSE, PUSH, PING, PONG) and messages: Envelope (direction, request_id, protocol_version, oneof payload), Request (method, params), Response (code, msg, method, result), Message (messageType, content, payload).
result: [pending]

### 6. common.proto and message_type.proto
expected: `common.proto` has DeviceType enum (MOBILE, DESKTOP, WEB) only in package com.nebula.chat.common. `message_type.proto` has MessageType enum with 14 values from TEXT=0 to DELIVERY_ACK=14.
result: [pending]

### 7. Domain Proto Files Exist
expected: 7 domain proto files exist under `proto/src/main/proto/nebula/`: `user/user.proto`, `auth/auth.proto`, `chat/chat.proto`, `message/message.proto`, `conversation/conversation.proto`, `group/group.proto`, `friend/friend.proto`. Package declarations match directory structure.
result: [pending]

### 8. Domain Proto Methods Coverage
expected: Domain proto files define 23 API methods across all domains. Each method has both Request and Response messages. Import paths use `nebula/` prefix.
result: [pending]

### 9. Proto Code Generation Succeeds
expected: `./gradlew :proto:generateProto` completes successfully. Generated Java stubs exist in `proto/build/generated/source/proto/main/java/`.
result: [pending]

### 10. Common Module Compilation
expected: `./gradlew :common:compileKotlin` compiles successfully against generated proto stubs. BizCode.kt with 30 error code constants compiles without errors.
result: [pending]

### 11. BizCode.kt Error Codes
expected: `common/src/main/kotlin/com/nebula/common/BizCode.kt` exists with 30 error code constants across 7 categories (General 200/1000-1004, Auth 1100-1103, User 1200-1201, Friend 1300-1305, Conversation 1400-1405, Message 1500-1503, System 9000-9002). Includes `fromCode()` factory method.
result: [pending]

### 12. Module Dependency Direction
expected: Module dependencies follow enforced direction: proto (no deps) <- common <- repository <- service <- gateway <- server. Each module's build.gradle.kts only depends on the layer below or adjacent.
result: [pending]

## Summary

total: 12
passed: 2
issues: 1
pending: 9
skipped: 0

## Gaps

- truth: "Root build.gradle.kts 和子模块使用 alias() 引用 version catalog 声明"
  status: fixed
  reason: "User reported: 虽然在libs.versions.toml中声明了，但到各个模块的 build.gradle查看并没有应用 toml 中的声明，特别是 plugin"
  severity: major
  test: 2
  fix: "root build.gradle.kts 和 proto/build.gradle.kts 均改用 alias(libs.plugins...)，javax-annotation-api 纳入 catalog，编译验证通过"
