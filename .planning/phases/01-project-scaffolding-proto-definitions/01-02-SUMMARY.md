---
phase: 01-project-scaffolding-proto-definitions
plan: 02
subsystem: protocol
tags: [proto, envelope, message-type]
key-files:
  - proto/src/main/proto/nebula/envelope.proto
  - proto/src/main/proto/nebula/common/common.proto
  - proto/src/main/proto/nebula/message_type.proto
metrics:
  task_count: 3
  commit_count: 1
  files_created: 3
---

## Task 1: envelope.proto

Created Direction enum (DIRECTION_UNSPECIFIED=0, REQUEST=1, RESPONSE=2, PUSH=3, PING=4, PONG=5) and message definitions for Envelope (direction, request_id, protocol_version, oneof payload), Request (method, params bytes), Response (code, msg, method, result bytes), Message (messageType, content, payload bytes). java_package = "com.nebula.chat".

## Task 2: common.proto

Created DeviceType enum (DEVICE_TYPE_UNSPECIFIED=0, MOBILE=1, DESKTOP=2, WEB=3) in package com.nebula.chat.common. Per D-05, does NOT contain UserBrief/ConversationBrief/GroupMember.

## Task 3: message_type.proto

Created MessageType enum with all 14 push event types (TEXT=0 through DELIVERY_ACK=14) in package com.nebula.chat. Independent file per D-04.

## Deviations

None.

## Self-Check: PASSED

- 3 .proto files exist in proto/src/main/proto/nebula/
- envelope.proto includes Direction, Envelope, Request, Response, Message
- common.proto includes DeviceType enum only
- message_type.proto includes 14 MessageType values
- All files use correct package and java_package
