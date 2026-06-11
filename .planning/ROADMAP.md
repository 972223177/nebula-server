# Roadmap: Nebula Chat Server

**Created:** 2026-06-11
**Mode:** Standard (Horizontal Layers)

## Overview

**11 phases** | **70 v1 requirements mapped** | 100% covered

| Phase | Name | Requirements | Success Criteria |
|-------|------|--------------|------------------|
| 1 | 5/5 | Complete   | 2026-06-11 |
| 2 | Common Module & Infrastructure Base | INFRA-02~05 | Snowflake ID, HikariCP, SSL dual-mode, Netty bootstrap |
| 3 | Database Schema & Repository Layer | DB-01~07 | 6 MySQL tables, 3 Redis structures, message persistence |
| 4 | Handler Framework | HNDL-01~06 | Generic Handler, Dispatcher, Koin DI, Pipeline, Exceptions |
| 5 | User & Authentication | AUTH-01~06, BIZ-USER-01~06 | Login, session, multi-device, user CRUD APIs |
| 6 | Chat & Message | BIZ-CHAT-01~02, BIZ-MSG-01~02 | Send message, fan-out, pull, read receipt |
| 7 | Conversation | BIZ-CONV-01~08 | List, create group, invite, leave, kick, edit |
| 8 | Friend & Online Status | BIZ-FRIEND-01~06, BIZ-STATUS-01~03 | Add/accept/reject/delete/list, status visibility |
| 9 | Reconnection | RECON-01~05 | State machine, exponential backoff, connection cleanup |
| 10 | Message Reliability | REL-01~04 | 3-state delivery, idempotent retry, dead letter, gap detect |
| 11 | Performance & Monitoring | PERF-01~05 | Scenarios, metrics, tools, optimization, alerts |

---

## Phase 1: Project Scaffolding & Proto Definitions

**Goal:** Establish build system and define the complete communication protocol contract that all subsequent phases depend on.

**Requirements:** INFRA-01, INFRA-06, PROTO-01, PROTO-02, PROTO-03, PROTO-04, PROTO-05, PROTO-06, PROTO-07

**Success Criteria:**

1. `gradle build` passes for all 6 modules
2. `envelope.proto` defines Direction, Envelope, Request, Response, Message
3. `common.proto` defines MessageType enum with all 23 method entries
4. Method proto files (user.proto, chat.proto, conversation.proto, message.proto, friend.proto) define all Request/Response messages
5. Gradle protobuf plugin generates Kotlin code from .proto files
6. Build order enforces proto <- common <- repository <- service <- gateway <- server

**Plans:** 5/5 plans complete

Plans:
**Wave 1**

- [x] 01-01-PLAN.md — Gradle multi-module scaffolding + project init files
- [x] 01-02-PLAN.md — Proto core: envelope.proto, common.proto, message_type.proto
- [x] 01-03-PLAN.md — Proto domain: 7 domain .proto files covering 23 methods

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-04-PLAN.md — Proto build integration: protobuf plugin + git submodule
- [x] 01-05-PLAN.md — Common module: BizCode error codes + kotlin-logging

---

## Phase 2: Common Module & Infrastructure Base

**Goal:** Build shared foundations — Snowflake ID generator, HikariCP pool, SSL/TLS config, Netty server bootstrap.

**Requirements:** INFRA-02, INFRA-03, INFRA-04, INFRA-05

**Success Criteria:**

1. SnowflakeIdGenerator produces unique IDs, handles clock drift with configurable fallback
2. HikariCP configured with validated production parameters
3. SSL SslContext loads self-signed cert for dev, configurable Let's Encrypt for production
4. NettyServerBuilder boots gRPC server with keepalive, flow control, max message size
5. Configuration loaded from environment or config file (dev/prod profiles)
6. Common error codes and BizException defined

**Plans:** 3 plans in 2 waves

Plans:
**Wave 1** *(parallel — no dependencies)*
- [ ] 02-01-PLAN.md — 构建依赖声明（libs.versions.toml/buid.gradle.kts）+ HOCON 配置 + SSL 证书脚本 + logback
- [ ] 02-02-PLAN.md — Common 模块：配置数据类 + BizException 异常体系 + SnowflakeIdGenerator

**Wave 2** *(blocked on Wave 1)*
- [ ] 02-03-PLAN.md — DataSourceProvider/HikariCP + buildSslContext + ConfigLoader + ChatServer + NebulaServer

---

## Phase 3: Database Schema & Repository Layer

**Goal:** Define and implement all persistence — MySQL tables, Redis structures, and message write path.

**Requirements:** DB-01, DB-02, DB-03, DB-04, DB-05, DB-06, DB-07

**Success Criteria:**

1. 6 MySQL tables created with proper indexes and foreign keys
2. Redis session key structure with TTL
3. Redis message pending queue
4. Redis online status storage
5. Message write path: receive -> Redis -> async batch -> MySQL
6. Message pull with cursor pagination
7. Unread count maintained per conversation
8. Read receipt updates last_read_message_id
9. Offline message store and push on reconnect

---

## Phase 4: Handler Framework

**Goal:** Build the generic request processing framework — Handler interface, Dispatcher, Koin DI, interceptor Pipeline.

**Requirements:** HNDL-01, HNDL-02, HNDL-03, HNDL-04, HNDL-05, HNDL-06

**Success Criteria:**

1. Generic Handler<ReqT, RespT> interface with method() binding
2. Dispatcher deserializes payload, routes to correct Handler, serializes response
3. Koin module registers all Handlers by method string
4. Authentication interceptor validates session before any handler
5. Logging interceptor records request/response with timing
6. ExceptionInterceptor catches BizException and maps to gRPC status
7. Handler directory structure organized by domain

---

## Phase 5: User & Authentication

**Goal:** Implement login, session management, multi-device policies, and all user-related APIs.

**Requirements:** AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06, BIZ-USER-01, BIZ-USER-02, BIZ-USER-03, BIZ-USER-04, BIZ-USER-05, BIZ-USER-06

**Success Criteria:**

1. user/login authenticates with password or token
2. Token generated with expiration, stored in Redis
3. Reconnect validates existing token, skips re-auth
4. Local in-memory session map for fast lookup
5. Same-device-type kick: only latest connection preserved
6. Kick pushes LOGOUT notification before closing
7. User search/search by keyword
8. Get profile / batch get user summary
9. Batch query online status
10. Set/get privacy settings

---

## Phase 6: Chat & Message

**Goal:** Implement message sending, fan-out delivery, message pull, and read receipt.

**Requirements:** BIZ-CHAT-01, BIZ-CHAT-02, BIZ-MSG-01, BIZ-MSG-02

**Success Criteria:**

1. chat/send validates message, generates client_message_id
2. Online members receive push via gRPC stream
3. Offline members get stored for pull-on-reconnect
4. message/pull returns messages with cursor pagination
5. message/read updates last_read_message_id and unread count
6. Fan-out latency within acceptable bounds

---

## Phase 7: Conversation

**Goal:** Implement conversation list, group creation, membership management.

**Requirements:** BIZ-CONV-01, BIZ-CONV-02, BIZ-CONV-03, BIZ-CONV-04, BIZ-CONV-05, BIZ-CONV-06, BIZ-CONV-07, BIZ-CONV-08

**Success Criteria:**

1. conversation/list returns all conversations with last message
2. create_group creates group with creator as sole owner
3. invite_member adds member directly (no approval)
4. leave_group member leaves; owner dissolve group
5. kick_member only owner can kick
6. edit_group_info only owner can modify
7. group_members returns full member list
8. Group member cap enforced

---

## Phase 8: Friend & Online Status

**Goal:** Implement friend management, online status with privacy controls.

**Requirements:** BIZ-FRIEND-01, BIZ-FRIEND-02, BIZ-FRIEND-03, BIZ-FRIEND-04, BIZ-FRIEND-05, BIZ-FRIEND-06, BIZ-STATUS-01, BIZ-STATUS-02, BIZ-STATUS-03

**Success Criteria:**

1. friend/add sends or auto-accepts reciprocal request
2. friend/accept creates friend record and auto-creates conversation
3. friend/reject removes pending request
4. friend/delete removes friend, preserves conversation but blocks sending
5. friend/list returns all friends
6. friend/requests returns pending requests
7. Three-value status: online/offline/hidden
8. Pseudo-online mode after disconnect
9. Status change notification to friends

---

## Phase 9: Reconnection

**Goal:** Implement client reconnection state machine, message weighting, and atomic connection cleanup.

**Requirements:** RECON-01, RECON-02, RECON-03, RECON-04, RECON-05

**Success Criteria:**

1. Reconnect state machine: INITIAL -> BACKOFF -> CONNECTING -> CONNECTED
2. Exponential backoff with configurable max retries
3. Heartbeat recovery after reconnect
4. P0 messages retried on reconnect, P1 deferred
5. Atomic Redis pipeline for old connection cleanup
6. Old channel closed before new channel registered

---

## Phase 10: Message Reliability

**Goal:** Implement end-to-end message delivery guarantees — three-state tracking, idempotent retry, dead letter, gap detection.

**Requirements:** REL-01, REL-02, REL-03, REL-04

**Success Criteria:**

1. Message state transitions: sent -> delivered -> read
2. Server push on state change
3. client_message_id dedup via Redis (TTL-based)
4. Async Redis-to-MySQL batch persistence with retry queue
5. Dead-letter table for persistently failing messages
6. Client ID continuity check detects gaps
7. Gap triggers auto-pull on reconnect

---

## Phase 11: Performance & Monitoring

**Goal:** Define benchmark scenarios, set up monitoring, run performance tests, optimize.

**Requirements:** PERF-01, PERF-02, PERF-03, PERF-04, PERF-05

**Success Criteria:**

1. 5 benchmark scenarios defined and runnable
2. 8 metrics collected per test run
3. Testing tool selected and configured
4. Measured baselines for each metric
5. 4-layer optimization plan documented
6. Production monitoring with 8 alert thresholds configured

---

## Requirements Coverage

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | 1 | Pending |
| INFRA-06 | 1 | Pending |
| PROTO-01 | 1 | Pending |
| PROTO-02 | 1 | Pending |
| PROTO-03 | 1 | Pending |
| PROTO-04 | 1 | Pending |
| PROTO-05 | 1 | Pending |
| PROTO-06 | 1 | Pending |
| PROTO-07 | 1 | Pending |
| INFRA-02 | 2 | Pending |
| INFRA-03 | 2 | Pending |
| INFRA-04 | 2 | Pending |
| INFRA-05 | 2 | Pending |
| DB-01 | 3 | Pending |
| DB-02 | 3 | Pending |
| DB-03 | 3 | Pending |
| DB-04 | 3 | Pending |
| DB-05 | 3 | Pending |
| DB-06 | 3 | Pending |
| DB-07 | 3 | Pending |
| HNDL-01 | 4 | Pending |
| HNDL-02 | 4 | Pending |
| HNDL-03 | 4 | Pending |
| HNDL-04 | 4 | Pending |
| HNDL-05 | 4 | Pending |
| HNDL-06 | 4 | Pending |
| AUTH-01 | 5 | Pending |
| AUTH-02 | 5 | Pending |
| AUTH-03 | 5 | Pending |
| AUTH-04 | 5 | Pending |
| AUTH-05 | 5 | Pending |
| AUTH-06 | 5 | Pending |
| BIZ-USER-01 | 5 | Pending |
| BIZ-USER-02 | 5 | Pending |
| BIZ-USER-03 | 5 | Pending |
| BIZ-USER-04 | 5 | Pending |
| BIZ-USER-05 | 5 | Pending |
| BIZ-USER-06 | 5 | Pending |
| BIZ-CHAT-01 | 6 | Pending |
| BIZ-CHAT-02 | 6 | Pending |
| BIZ-MSG-01 | 6 | Pending |
| BIZ-MSG-02 | 6 | Pending |
| BIZ-CONV-01 | 7 | Pending |
| BIZ-CONV-02 | 7 | Pending |
| BIZ-CONV-03 | 7 | Pending |
| BIZ-CONV-04 | 7 | Pending |
| BIZ-CONV-05 | 7 | Pending |
| BIZ-CONV-06 | 7 | Pending |
| BIZ-CONV-07 | 7 | Pending |
| BIZ-CONV-08 | 7 | Pending |
| BIZ-FRIEND-01 | 8 | Pending |
| BIZ-FRIEND-02 | 8 | Pending |
| BIZ-FRIEND-03 | 8 | Pending |
| BIZ-FRIEND-04 | 8 | Pending |
| BIZ-FRIEND-05 | 8 | Pending |
| BIZ-FRIEND-06 | 8 | Pending |
| BIZ-STATUS-01 | 8 | Pending |
| BIZ-STATUS-02 | 8 | Pending |
| BIZ-STATUS-03 | 8 | Pending |
| RECON-01 | 9 | Pending |
| RECON-02 | 9 | Pending |
| RECON-03 | 9 | Pending |
| RECON-04 | 9 | Pending |
| RECON-05 | 9 | Pending |
| REL-01 | 10 | Pending |
| REL-02 | 10 | Pending |
| REL-03 | 10 | Pending |
| REL-04 | 10 | Pending |
| PERF-01 | 11 | Pending |
| PERF-02 | 11 | Pending |
| PERF-03 | 11 | Pending |
| PERF-04 | 11 | Pending |
| PERF-05 | 11 | Pending |

---

## Dependencies

- Phase 1 blocks all — .proto definitions are the foundation contract
- Phase 2 blocks 3~11 — infrastructure needed by all subsequent phases
- Phase 3 blocks 5~8 — database layer required for business logic
- Phase 4 blocks 5~11 — Handler framework required for API execution
- Phase 5 blocks 6~8 — auth/session required for chat/message/conversation/friend
- Phase 6 blocks 10 — chat reliability depends on message flow
- Phase 8 blocks 9 — online status needed for reconnection logic
- Phase 9 blocks 10 — reconnection is foundation for message reliability
- Phase 11 independent — can start after Phase 3 when basic functionality exists

---

*Roadmap created: 2026-06-11*
