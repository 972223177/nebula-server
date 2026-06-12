# Requirements: Nebula Chat Server

**Defined:** 2026-06-11
**Core Value:** Users send and receive real-time messages over a single gRPC bidirectional stream with reliable delivery

## v1 Requirements

### Infrastructure

- [ ] **INFRA-01**: Project builds with Gradle Kotlin DSL 6 sub-module structure (proto/server/gateway/service/repository/common)
- [ ] **INFRA-02**: gRPC Netty server starts with configurable port, flow control (autoFlowControl/maxInboundMessageSize), and keepalive params
- [ ] **INFRA-03**: Snowflake ID generator produces unique 64-bit IDs with configurable worker ID and clock drift fallback
- [ ] **INFRA-04**: HikariCP connection pool configured for MySQL with optimal production parameters
- [ ] **INFRA-05**: SSL/TLS supports dual mode (local self-signed + production Let's Encrypt) via environment config
- [ ] **INFRA-06**: Gradle dependency direction enforces: proto <- common <- repository <- service <- gateway <- server

### Protocol

- [ ] **PROTO-01**: Envelope proto defines Direction enum, request_id (UUIDv4), protocol_version, and oneof payload (Request/Response/Message)
- [ ] **PROTO-02**: Request proto carries method string, MessageType enum for routing
- [ ] **PROTO-03**: Response proto carries code, message, and typed payload per MessageType
- [ ] **PROTO-04**: Message proto (server push) carries MessageType and typed payload
- [ ] **PROTO-05**: 23 method Request/Response proto messages defined across user/chat/conversation/message/friend domains
- [ ] **PROTO-06**: Heartbeat strategy defined: PING/PONG interval and timeout, REQUEST resets heartbeat timer
- [ ] **PROTO-07**: Routing mechanism maps method string to Handler via registry

### Authentication & Session

- [ ] **AUTH-01**: User authenticates via user/login with password or token, returns LoginResp with session token
- [ ] **AUTH-02**: Reconnect flow verifies existing token against Redis, skips re-auth when valid
- [ ] **AUTH-03**: Token format with expiration, stored in Redis with session mapping
- [ ] **AUTH-04**: Local in-memory session map (ConcurrentHashMap) for fast connection lookup
- [ ] **AUTH-05**: Same-device-type kick: only latest active connection preserved per device type
- [ ] **AUTH-06**: Kick notification pushes LOGOUT to displaced session before closing

### Data Storage

- [ ] **DB-01**: MySQL 6 core tables DDL (users, conversations, conversation_members, messages, friendships, friend_requests)
- [ ] **DB-02**: Redis 3 cache structures (session tokens, message pending queue, online status)
- [ ] **DB-03**: Message write path: receive -> Redis ACK immediately -> async batch flush to MySQL
- [ ] **DB-04**: Message pull API supports cursor/offset pagination, fetches from MySQL
- [ ] **DB-05**: Offline message storage and push on reconnect
- [ ] **DB-06**: Unread message count maintained per conversation per user
- [ ] **DB-07**: Read receipt updates unread count and records last_read_message_id per conversation

### Handler Framework

- [ ] **HNDL-01**: Generic Handler interface defined: Handler<ReqT, RespT> with method mapping
- [ ] **HNDL-02**: Dispatcher deserializes Request payload, routes to Handler, serializes Response
- [ ] **HNDL-03**: Koin module registers all Handlers with explicit method -> Handler bindings
- [ ] **HNDL-04**: Interceptor Pipeline: authentication, logging, exception handling in chain
- [ ] **HNDL-05**: BizException converts to typed gRPC Status, ExceptionInterceptor catches and formats
- [ ] **HNDL-06**: Handler directories organized by domain (gateway/handlers/user/chat/conversation/message/friend/)

### User Business Logic

- [ ] **BIZ-USER-01**: user/search searches users by keyword with pagination
- [ ] **BIZ-USER-02**: user/getProfile returns detailed user profile
- [ ] **BIZ-USER-03**: user/batchGet returns user summary by ID list
- [ ] **BIZ-USER-04**: user/batchGetStatus returns online status for user ID list
- [ ] **BIZ-USER-05**: user/setPrivacy configures online status visibility
- [ ] **BIZ-USER-06**: user/getPrivacy reads current privacy settings

### Chat Business Logic

- [ ] **BIZ-CHAT-01**: chat/send validates message, generates client_message_id dedup, fanouts to conversation members
- [ ] **BIZ-CHAT-02**: Fan-out delivery: online members get push, offline members get stored for pull-on-reconnect

### Conversation Business Logic

- [ ] **BIZ-CONV-01**: conversation/list returns user's conversation list with last message preview
- [ ] **BIZ-CONV-02**: conversation/create_group creates group with creator as sole owner
- [ ] **BIZ-CONV-03**: conversation/invite_member adds member to group (no approval needed)
- [ ] **BIZ-CONV-04**: conversation/leave_group member leaves; owner leaving dissolves the group
- [ ] **BIZ-CONV-05**: conversation/kick_member only owner can kick members
- [ ] **BIZ-CONV-06**: conversation/edit_group_info only owner can edit name/avatar
- [ ] **BIZ-CONV-07**: conversation/group_members returns member list of a group
- [ ] **BIZ-CONV-08**: Group member capacity limit enforced

### Message Business Logic

- [x] **BIZ-MSG-01**: message/pull pulls messages by conversation with cursor-based pagination
- [x] **BIZ-MSG-02**: message/read reports read status, updates unread count and last_read_message_id

### Friend Business Logic

- [ ] **BIZ-FRIEND-01**: friend/add sends or reciprocates friend request (bi-directional auto-accept)
- [ ] **BIZ-FRIEND-02**: friend/accept approves incoming request, auto-creates conversation and first system message
- [ ] **BIZ-FRIEND-03**: friend/reject rejects incoming request
- [ ] **BIZ-FRIEND-04**: friend/delete removes friend, retains conversation but blocks sending
- [ ] **BIZ-FRIEND-05**: friend/list returns friend list with pagination
- [ ] **BIZ-FRIEND-06**: friend/requests returns pending friend requests

### Online Status

- [ ] **BIZ-STATUS-01**: Three-value status (online/offline/hidden) with lazy loading
- [ ] **BIZ-STATUS-02**: Pseudo-online: user stays "online" short period after disconnect
- [ ] **BIZ-STATUS-03**: Status push on friend's state change

### Reconnection

- [ ] **RECON-01**: Reconnect state machine: INITIAL -> BACKOFF -> CONNECTING -> CONNECTED with exponential backoff
- [ ] **RECON-02**: Max retry count and heartbeat recovery after reconnection
- [ ] **RECON-03**: P0 (priority messages) retried on reconnect vs P1 (deferred) events
- [ ] **RECON-04**: Atomic old-connection cleanup via Redis pipeline + old channel close
- [ ] **RECON-05**: Connection migration: standard reconnect flow (no QUIC in v1)

### Message Reliability

- [ ] **REL-01**: Three-state delivery tracking (sent -> delivered -> read) with server push on state change
- [ ] **REL-02**: Client retry idempotency via client_message_id dedup in Redis
- [ ] **REL-03**: Async batch persistence with retry queue and dead-letter table
- [ ] **REL-04**: Client message ID continuity check detects gaps and triggers re-pull

### Performance & Monitoring

- [ ] **PERF-01**: 5 benchmark scenarios defined (connection storm, concurrent messaging, group fan-out, offline message pull, mixed load)
- [ ] **PERF-02**: 8 metrics tracked for single-node (connections, msg/s throughput, fan-out latency, P99 latency, etc.)
- [ ] **PERF-03**: 3 performance testing tools evaluated (ghz, custom client, or Locust)
- [ ] **PERF-04**: 4-layer optimization plan (Netty/gRPC/business/JVM)
- [ ] **PERF-05**: 8 production monitoring metrics with alert thresholds

## v2 Requirements

- **PERF-06**: Multi-node deployment / Kubernetes orchestration
- **PERF-07**: Full performance target validation (10K msg/s through)
- Blacklist feature
- Group admin delegation
- Group notice/bulletin system
- System messages in conversations

## Out of Scope

| Feature | Reason |
|---------|--------|
| Multi-admin group management | v1 uses single-owner model; v2 planned |
| Group notice/bulletin | v2 planned |
| Blacklist | v2 planned |
| QUIC protocol migration | v1 uses standard gRPC reconnect flow |
| WebSocket support | Only gRPC bidirectional streaming |
| Client SDK / frontend | Server-side only |
| Microservice decomposition | Single-node v1 |
| Kubernetes deployment | Single-node v1 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | Phase 1 | Pending |
| INFRA-06 | Phase 1 | Pending |
| PROTO-01 | Phase 1 | Pending |
| PROTO-02 | Phase 1 | Pending |
| PROTO-03 | Phase 1 | Pending |
| PROTO-04 | Phase 1 | Pending |
| PROTO-05 | Phase 1 | Pending |
| PROTO-06 | Phase 1 | Pending |
| PROTO-07 | Phase 1 | Pending |
| INFRA-02 | Phase 2 | Pending |
| INFRA-03 | Phase 2 | Pending |
| INFRA-04 | Phase 2 | Pending |
| INFRA-05 | Phase 2 | Pending |
| DB-01 | Phase 3 | Pending |
| DB-02 | Phase 3 | Pending |
| DB-03 | Phase 3 | Pending |
| DB-04 | Phase 3 | Pending |
| DB-05 | Phase 3 | Pending |
| DB-06 | Phase 3 | Pending |
| DB-07 | Phase 3 | Pending |
| HNDL-01 | Phase 4 | Pending |
| HNDL-02 | Phase 4 | Pending |
| HNDL-03 | Phase 4 | Pending |
| HNDL-04 | Phase 4 | Pending |
| HNDL-05 | Phase 4 | Pending |
| HNDL-06 | Phase 4 | Pending |
| AUTH-01 | Phase 5 | Pending |
| AUTH-02 | Phase 5 | Pending |
| AUTH-03 | Phase 5 | Pending |
| AUTH-04 | Phase 5 | Pending |
| AUTH-05 | Phase 5 | Pending |
| AUTH-06 | Phase 5 | Pending |
| BIZ-USER-01 | Phase 5 | Pending |
| BIZ-USER-02 | Phase 5 | Pending |
| BIZ-USER-03 | Phase 5 | Pending |
| BIZ-USER-04 | Phase 5 | Pending |
| BIZ-USER-05 | Phase 5 | Pending |
| BIZ-USER-06 | Phase 5 | Pending |
| BIZ-CHAT-01 | Phase 6 | Pending |
| BIZ-CHAT-02 | Phase 6 | Pending |
| BIZ-MSG-01 | Phase 6 | Complete |
| BIZ-MSG-02 | Phase 6 | Complete |
| BIZ-CONV-01 | Phase 7 | Pending |
| BIZ-CONV-02 | Phase 7 | Pending |
| BIZ-CONV-03 | Phase 7 | Pending |
| BIZ-CONV-04 | Phase 7 | Pending |
| BIZ-CONV-05 | Phase 7 | Pending |
| BIZ-CONV-06 | Phase 7 | Pending |
| BIZ-CONV-07 | Phase 7 | Pending |
| BIZ-CONV-08 | Phase 7 | Pending |
| BIZ-FRIEND-01 | Phase 8 | Pending |
| BIZ-FRIEND-02 | Phase 8 | Pending |
| BIZ-FRIEND-03 | Phase 8 | Pending |
| BIZ-FRIEND-04 | Phase 8 | Pending |
| BIZ-FRIEND-05 | Phase 8 | Pending |
| BIZ-FRIEND-06 | Phase 8 | Pending |
| BIZ-STATUS-01 | Phase 8 | Pending |
| BIZ-STATUS-02 | Phase 8 | Pending |
| BIZ-STATUS-03 | Phase 8 | Pending |
| RECON-01 | Phase 9 | Pending |
| RECON-02 | Phase 9 | Pending |
| RECON-03 | Phase 9 | Pending |
| RECON-04 | Phase 9 | Pending |
| RECON-05 | Phase 9 | Pending |
| REL-01 | Phase 10 | Pending |
| REL-02 | Phase 10 | Pending |
| REL-03 | Phase 10 | Pending |
| REL-04 | Phase 10 | Pending |
| PERF-01 | Phase 11 | Pending |
| PERF-02 | Phase 11 | Pending |
| PERF-03 | Phase 11 | Pending |
| PERF-04 | Phase 11 | Pending |
| PERF-05 | Phase 11 | Pending |

**Coverage:**
- v1 requirements: 70 total
- Mapped to phases: 70
- Unmapped: 0 ✓

---
*Requirements defined: 2026-06-11*
*Last updated: 2026-06-11 after initial definition*
