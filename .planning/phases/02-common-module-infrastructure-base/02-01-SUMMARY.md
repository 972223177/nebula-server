---
phase: 02-common-module-infrastructure-base
plan: 01
subsystem: infra
tags: [gradle, hocon, logback, ssl, openssl]
requires: []
provides:
  - Gradle Version Catalog with 6 new version declarations and 10 library references
  - HOCON configuration file with 4 config sections (server/snowflake/database/ssl)
  - OpenSSL 3.x self-signed certificate generation script
  - Development and production logback configurations
affects: [02-02, 02-03, 03, 04]
tech-stack:
  added: [typesafe-config, hikaricp, mysql-connector, grpc, netty-tcnative]
  patterns: [HOCON config with env var overrides, environment-specific logback configs]
key-files:
  created:
    - config/application.conf
    - config/dev/ssl/generate-dev-cert.sh
    - common/src/main/resources/logback-dev.xml
    - common/src/main/resources/logback-prod.xml
  modified:
    - gradle/libs.versions.toml
    - common/build.gradle.kts
    - server/build.gradle.kts
key-decisions:
  - "HOCON configuration format with ${?ENV_VAR} overrides for sensitive fields"
  - "grpc-netty-shaded in both common and server modules (common for GrpcSslContexts, server for NettyServerBuilder)"
patterns-established:
  - "Sensitive config fields injected via environment variables, not hardcoded"
requirements-completed: [INFRA-05]
duration: 5min
completed: 2026-06-11
---

# Phase 02-01: Dependencies & Static Config Summary

**Gradle Version Catalog with Typesafe Config, HikariCP, gRPC dependencies; HOCON config with 4 sections; dev/prod logback configurations and SSL cert script**

## Performance

- **Duration:** 5 min (previously partially executed)
- **Completed:** 2026-06-11
- **Tasks:** 3
- **Files modified/created:** 7

## Accomplishments
- 6 version declarations and 10 library references added to Gradle Version Catalog
- `common/build.gradle.kts` updated with 4 Phase 2 dependencies (typesafe-config, hikaricp, mysql-connector, grpc-netty-shaded)
- `server/build.gradle.kts` updated with 8 dependencies including :common module reference
- HOCON `config/application.conf` created with 4 config sections and environment variable overrides
- SSL certificate generation script using OpenSSL 3.x `-addext` syntax
- Dev (DEBUG color console) and prod (INFO JSON) logback configurations

## Task Commits

Each task was committed atomically:

1. **Task 1: 更新 Gradle Version Catalog 和模块构建文件** - `ef984f4`
2. **Task 2: 创建 HOCON 配置文件** - `5fee8f2`
3. **Task 3: 创建 SSL 自签证书脚本和 logback 配置** - `d813c5b`

## Files Created/Modified
- `gradle/libs.versions.toml` - 6 versions & 10 libs (typesafe-config, hikaricp, mysql-connector, grpc, grpc-kotlin, netty-tcnative)
- `common/build.gradle.kts` - 4 dependencies added
- `server/build.gradle.kts` - 8 dependencies added including :common
- `config/application.conf` - HOCON config with server/snowflake/database/ssl sections
- `config/dev/ssl/generate-dev-cert.sh` - OpenSSL 3.x self-signed cert generation
- `common/src/main/resources/logback-dev.xml` - DEBUG level, color console
- `common/src/main/resources/logback-prod.xml` - INFO level, JSON encoder

## Decisions Made
- grpc-netty-shaded added to both common and server modules (common for GrpcSslContexts, server for NettyServerBuilder)
- Database password not hardcoded - injected via `${?DB_PASSWORD}` environment variable
- grpc-netty-shaded chosen over grpc-netty to avoid Netty version conflicts

## Deviations from Plan
None - plan executed exactly as written.

## Issues Encountered
None

## Next Phase Readiness
- All dependencies declared and available for Phase 2 plan 02 (config data classes, exception system, Snowflake ID generator)
- HOCON config structure ready for ApplicationConfig data class parsing
- Plan 02-03 can use SSL config and gRPC dependencies for server assembly

---
*Phase: 02-common-module-infrastructure-base*
*Completed: 2026-06-11*
