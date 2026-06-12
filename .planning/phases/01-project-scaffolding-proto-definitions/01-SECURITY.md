---
phase: 01
slug: project-scaffolding-proto-definitions
status: verified
threats_open: 0
asvs_level: 1
created: 2026-06-12
---

# Phase 01 — 安全合约

> 每阶段安全合约：威胁注册表、已接受的风险和审计轨迹。

---

## 信任边界

| 边界 | 描述 | 跨越的数据 |
|----------|-------------|---------------|
| 本地文件系统 → Git 仓库 | .proto 源文件、Gradle 配置文件通过 Git 管理，提交前可能被本地篡改 | .proto 源文件、Gradle 构建文件 |
| Gradle Plugin Portal / Maven Central | 构建时从远程仓库下载依赖 | Gradle 插件 JAR、依赖库 |
| .proto 源文件 → protoc 编译 | proto 文件编译为 Java/Kotlin 代码桩 | 生成的 Java 桩代码 |
| Maven Central → protoc artifact | protoc 编译器从 Maven 下载 | protoc 二进制文件 |

---

## 威胁注册表

| 威胁 ID | 类别 | 组件 | 处置 | 缓解措施 | 验证状态 |
|-----------|----------|-----------|-------------|------------|--------|
| T-01-01 | 篡改 (Tampering) | .proto 源文件 | mitigate | Git 版本控制追踪 + PR code review 流程，所有 .proto 变更通过 git diff 审查。.proto 文件不在 .gitignore 中，确保全部纳入版本管理 | closed |
| T-01-02 | 身份伪造 (Spoofing) | Git submodule URL | mitigate | 项目未使用 git submodule（设计决策：proto 文件直接纳入项目仓库），.gitmodules 不存在，该威胁不适用 | closed |
| T-01-03 | 篡改 (Tampering) | Maven Central 依赖 | accept | 本阶段使用的 protobuf-java 4.29.3、kotlin-logging 8.0.4 等均为 Maven Central 长期稳定包，版本通过 libs.versions.toml 集中管理，供应链风险极低 | closed |
| T-01-04 | 篡改 (Tampering) | .proto 核心文件 | mitigate | envelope.proto、common.proto、message_type.proto 受 Git 追踪，PR code review 时通过 git diff 审查变更，构建时 protoc 编译验证确保文件完整性 | closed |
| T-01-05 | 篡改 (Tampering) | 领域 .proto 文件 | mitigate | 7 个领域 .proto 文件受 Git 追踪；构建时 `./gradlew :proto:generateProto` 编译验证完整性 | closed |
| T-01-06 | 身份伪造 (Spoofing) | Git submodule URL | mitigate | 未使用 git submodule 模式，.proto 文件为项目本地文件，不存在外部 URL 被篡改的攻击面 | closed |
| T-01-07 | 篡改 (Tampering) | Maven Central artifacts | accept | protoc 4.29.3、protobuf-java 4.29.3 为 Google 官方发布在 Maven Central 的 artifact，供应链风险极低 | closed |

*状态: open · closed*
*处置: mitigate (需实现) · accept (已记录风险) · transfer (第三方)*

---

## 已接受的风险记录

| 风险 ID | 威胁引用 | 理由 | 接受方 | 日期 |
|---------|------------|-----------|-------------|------|
| R-01-01 | T-01-03 | protobuf-java 4.29.3、kotlin-logging 8.0.4 等为 Maven Central 高下载量包，版本通过 libs.versions.toml 集中管理，发现 CVE 时可快速升级 | plan-audit (gsd-secure-phase) | 2026-06-12 |
| R-01-02 | T-01-07 | protoc 4.29.3 和 protobuf-java 4.29.3 为 Google 官方发布的 artifact，由 Maven Central 提供 checksum 校验。本阶段无第三方非官方依赖 | plan-audit (gsd-secure-phase) | 2026-06-12 |

*已接受的风险在后续审计运行中不会再出现。*

---

## 缓解措施验证详情

### T-01-01/T-01-04/T-01-05: .proto 文件版本控制完整性

- `.gitignore` 不包含 proto 相关条目，所有 .proto 文件由 Git 追踪 ✅
- `git log -- proto/src/main/proto/` 可验证所有历史变更 ✅
- `./gradlew :proto:generateProto` 构建通过，确保 protoc 编译验证完整性 ✅

### T-01-02/T-01-06: Git submodule 不存在

- `.gitmodules` 文件不存在（项目未使用 submodule）✅
- .proto 文件直接纳入项目仓库，无外部 URL 依赖 ✅

### T-01-03/T-01-07: Maven Central 依赖管理

- `gradle/libs.versions.toml` 使用版本目录集中管理所有依赖版本号 ✅
- protobuf-java/protobuf-kotlin/protoc 均指向 `com.google.protobuf` 官方 group ✅
- kotlin-logging 指向 `io.github.oshai` 官方 group ✅

---

## 安全审计轨迹

| 审计日期 | 威胁总数 | 已关闭 | 开放 | 执行方 |
|------------|---------------|--------|------|--------|
| 2026-06-12 | 7 | 7 | 0 | gsd-secure-phase (追溯验证) |

---

## 签收

- [x] 所有威胁都有处置方案（mitigate / accept）
- [x] 已接受的风险记录在风险日志中
- [x] `threats_open: 0` 确认
- [x] `status: verified` 已在前置元数据中设置

**审批：** verified 2026-06-12
