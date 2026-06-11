---
phase: 01-project-scaffolding-proto-definitions
plan: 03
subsystem: protocol
tags: [proto, domain, request-response]
key-files:
  - proto/src/main/proto/nebula/user/user.proto
  - proto/src/main/proto/nebula/auth/auth.proto
  - proto/src/main/proto/nebula/chat/chat.proto
  - proto/src/main/proto/nebula/message/message.proto
  - proto/src/main/proto/nebula/conversation/conversation.proto
  - proto/src/main/proto/nebula/group/group.proto
  - proto/src/main/proto/nebula/friend/friend.proto
metrics:
  task_count: 3
  commit_count: 1
  files_created: 7
  methods_covered: 23
---

## Task 1: user.proto + auth.proto

Created user/user.proto with 7 methods (login, search, getProfile, batchGet, batchGetStatus, setPrivacy, getPrivacy) plus UserBrief and UserOnlineStatus (per D-05 relocation from common.proto). Created auth/auth.proto with SessionInfo skeleton.

## Task 2: chat.proto + message.proto

Created chat/chat.proto with SendMessageReq/Resp. Created message/message.proto with PullMessagesReq/Resp, ReadReportReq, and ChatMessage (10 fields).

## Task 3: conversation.proto + group.proto + friend.proto

Created conversation/conversation.proto with 7 methods (list, create_group, invite_member, leave_group, kick_member, edit_group_info, group_members) and ConversationBrief (per D-05). Created group/group.proto with GroupMember (per D-05). Created friend/friend.proto with 6 methods (add, accept, reject, delete, list, requests) plus FriendBrief and FriendRequestItem.

## Deviations

None.

## Self-Check: PASSED

- 7 domain .proto files in correct subdirectories
- 23 methods × Request/Response covered
- D-05 type relocations correct
- Import paths use nebula/ prefix
- Package declarations match directory structure
