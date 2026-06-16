# Nebula Chat — Proto API 文档

> 基于 `proto/src/main/proto/nebula/` 模块，gRPC 双向流 + 统一信封协议

---

## 目录

- [1. 架构概览](#1-架构概览)
- [2. 核心协议层](#2-核心协议层)
  - [2.1 Envelope — 消息信封](#21-envelope--消息信封)
  - [2.2 Direction — 通信方向枚举](#22-direction--通信方向枚举)
  - [2.3 Request / Response / Message](#23-request--response--message)
- [3. 公共类型](#3-公共类型)
  - [3.1 DeviceType — 设备类型](#31-devicetype--设备类型)
  - [3.2 ChatContentType — 消息内容类型](#32-chatcontenttype--消息内容类型)
  - [3.3 PushEventType — 推送事件类型](#33-pusheventtype--推送事件类型)
- [4. 用户模块 (user)](#4-用户模块-user)
- [5. 好友模块 (friend)](#5-好友模块-friend)
- [6. 聊天模块 (chat)](#6-聊天模块-chat)
- [7. 消息模块 (message)](#7-消息模块-message)
- [8. 会话模块 (conversation)](#8-会话模块-conversation)
- [9. 群组模块 (group)](#9-群组模块-group)
- [10. 管理模块 (admin)](#10-管理模块-admin)
- [12. 推送 Payload 索引](#12-推送-payload-索引)

---

## 1. 架构概览

- **通信方式**：gRPC 双向流（Bidi Streaming），单一长连接承载所有请求/响应/推送
- **协议封装**：所有消息通过统一的 `Envelope` 信封封装，区分通信方向和负载类型
- **路由机制**：`Request.method` 字段携带接口路由（格式 `模块/方法`），参数/结果均为 `bytes` 类型，按 method 对应的 proto 消息序列化/反序列化
- **服务端口**：默认 `9090`，可通过 `SERVER_PORT` 环境变量覆盖

### 接口全景图

```
┌─────────────────────────────────────────────────────┐
│                    gRPC Bidi Stream                  │
│                     Envelope 封装                     │
├──────────────┬──────────────┬────────────────────────┤
│   REQUEST    │   RESPONSE   │        PUSH            │
│  (客户端→)   │   (服务端→)   │     (服务端→)          │
├──────────────┼──────────────┼────────────────────────┤
│ user/login   │    200/xxx   │  CHAT_MESSAGE          │
│ user/register│              │  FRIEND_REQUEST        │
│ user/search  │              │  FRIEND_ACCEPTED       │
│ user/getProfile│            │  LOGOUT                │
│ user/batchGet│              │  GROUP_CREATED         │
│ user/getPrivacy│            │  GROUP_INVITED         │
│ user/setPrivacy│            │  GROUP_DISSOLVED       │
│ user/batchGetStatus│        │  GROUP_UPDATED         │
│ friend/add   │              │  MEMBER_JOINED         │
│ friend/accept│              │  MEMBER_LEFT           │
│ friend/reject│              │  MEMBER_KICKED         │
│ friend/delete│              │  DELIVERY_ACK          │
│ friend/list  │              │  READ_RECEIPT          │
│ friend/requests│            │  STATUS_CHANGED        │
│ chat/send    │              │  DISCONNECT            │
│ message/pull │              │                        │
│ message/read │              │                        │
│ conversation/list│          │                        │
│ conversation/create_group│  │                        │
│ conversation/invite_member│ │                        │
│ conversation/leave_group  │ │                        │
│ conversation/kick_member  │ │                        │
│ conversation/edit_group_info│                        │
│ conversation/group_members│ │                        │
│ message/seq  │              │                        │
│ admin/dead-letters│         │                        │
│ admin/retry-dead-letter│    │                        │
└──────────────┴──────────────┴────────────────────────┘
```

### 快速上手：Envelope + Request 使用示例

以下演示一个完整的 `user/login` 请求/响应流程。

**步骤 1 — 客户端构造 LoginReq，序列化为 bytes**

```json
// LoginReq (protobuf JSON 表示)
{
  "username": "alice",
  "password": "123456",
  "device_type": "MOBILE",
  "device_id": "ios-uuid-abc123"
}
```

```kotlin
// Kotlin 伪代码：将 LoginReq 序列化为 bytes
val loginReq = LoginReq.newBuilder()
    .setUsername("alice")
    .setPassword("123456")
    .setDeviceType(DeviceType.MOBILE)
    .setDeviceId("ios-uuid-abc123")
    .build()
val paramsBytes: ByteArray = loginReq.toByteArray()
```

**步骤 2 — 包装 Envelope（Request 方向），通过 gRPC 双向流发送**

```kotlin
val envelope = Envelope.newBuilder()
    .setDirection(Direction.REQUEST)
    .setRequestId(UUID.randomUUID().toString())  // 用于匹配响应
    .setProtocolVersion(1)
    .setRequest(
        Request.newBuilder()
            .setMethod("user/login")
            .setParams(ByteString.copyFrom(paramsBytes))
            // metadata 可选，未登录时不传 authorization
            .build()
    )
    .build()

// 发送到 gRPC 流
streamObserver.onNext(envelope)
```

**Envelope 实际内容（JSON 表示）**

```json
{
  "direction": "REQUEST",
  "request_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "protocol_version": 1,
  "request": {
    "method": "user/login",
    "params": "<LoginReq 的 protobuf 二进制>"
  }
}
```

**步骤 3 — 服务端处理并返回 Response**

服务端根据 `method = "user/login"` 路由到 LoginHandler，处理完成后构造 Envelope 响应：

```json
// 服务端返回的 Envelope (Direction = RESPONSE)
{
  "direction": "RESPONSE",
  "request_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "protocol_version": 1,
  "response": {
    "code": 200,
    "msg": "",
    "method": "user/login",
    "result": "<LoginResp 的 protobuf 二进制>"
  }
}
```

**步骤 4 — 客户端接收响应，匹配 request_id，反序列化 result**

```kotlin
// 伪代码：客户端从流中接收 Envelope
streamObserver.onNext { envelope ->
    if (envelope.direction == Direction.RESPONSE) {
        val resp = envelope.response
        if (resp.code == 200) {
            // 按 method 确定 result 的反序列化类型
            val loginResp = LoginResp.parseFrom(resp.result)
            // 登录成功，保存 token 用于后续请求
            val token = loginResp.token  // "eyJ..."
        }
    }
}
```

**后续鉴权请求** — 在 metadata 中携带 authorization

```kotlin
// 例如发送 chat/send，在 metadata 中附带 token
val envelope = Envelope.newBuilder()
    .setDirection(Direction.REQUEST)
    .setRequestId(UUID.randomUUID().toString())
    .setProtocolVersion(1)
    .setRequest(
        Request.newBuilder()
            .setMethod("chat/send")
            .setParams(ByteString.copyFrom(sendMsgBytes))
            .putMetadata("authorization", token)  // ← 鉴权
            .build()
    )
    .build()
```

---

### Proto 文件清单

| 文件 | Package | 说明 |
|------|---------|------|
| `envelope.proto` | `com.nebula.chat` | 核心协议层：Envelope、Request、Response、Message、Direction |
| `message_type.proto` | `com.nebula.chat` | 枚举：ChatContentType、PushEventType |
| `common/common.proto` | `com.nebula.chat.common` | 公共枚举：DeviceType |
| `auth/auth.proto` | `com.nebula.chat.auth` | 认证会话：SessionInfo |
| `user/user.proto` | `com.nebula.chat.user` | 用户服务：登录、注册、搜索、资料、隐私 |
| `friend/friend.proto` | `com.nebula.chat.friend` | 好友服务：添加、同意、拒绝、删除、列表、申请 |
| `chat/chat.proto` | `com.nebula.chat.chat` | 聊天服务：发送消息 |
| `message/message.proto` | `com.nebula.chat.message` | 消息服务：拉取、已读、交付回执、序列号 |
| `conversation/conversation.proto` | `com.nebula.chat.conversation` | 会话服务：列表、群管理、推送 Payload |
| `group/group.proto` | `com.nebula.chat.group` | 群成员信息 |
| `admin.proto` | `com.nebula.chat.admin` | 管理服务：死信查询、重试 |

---

## 2. 核心协议层

### 2.1 Envelope — 消息信封

**文件**：`nebula/envelope.proto`
**Package**：`com.nebula.chat`

所有 gRPC 双向流消息的最外层包装，用于区分通信方向与负载类型。

```protobuf
message Envelope {
  Direction direction = 1;        // 通信方向
  string request_id = 2;          // 请求唯一ID，用于 REQUEST-RESPONSE 配对
  int32 protocol_version = 3;     // 协议版本号，初始值 = 1
  oneof payload {
    Request request = 10;         // REQUEST 方向负载
    Response response = 11;       // RESPONSE 方向负载
    Message message = 12;         // PUSH 方向负载（服务端推送）
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `direction` | `Direction` | 是 | 通信方向，决定 payload 中 oneof 的取值 |
| `request_id` | `string` | 视场景 | 请求唯一ID，REQUEST/RESPONSE 时必填，PUSH/PING/PONG 时可为空 |
| `protocol_version` | `int32` | 是 | 协议版本号，初始值 1，用于后续协议升级兼容性判断 |
| `payload.request` | `Request` | 按方向 | 客户端发起的请求负载（Direction=REQUEST 时） |
| `payload.response` | `Response` | 按方向 | 服务端返回的响应负载（Direction=RESPONSE 时） |
| `payload.message` | `Message` | 按方向 | 服务端主动推送的消息负载（Direction=PUSH 时） |

### 2.2 Direction — 通信方向枚举

**文件**：`nebula/envelope.proto`

应用层双重心跳策略（D-27）：
- **传输层**：gRPC keepalive（HTTP/2 PING/PONG）快速检测 TCP 断开
- **业务层**：应用层 PING/PONG 检测 NAT/代理导致的半开连接

| 枚举值 | 编号 | 方向 | 说明 |
|--------|------|------|------|
| `DIRECTION_UNSPECIFIED` | 0 | — | 未知类型，不应使用 |
| `REQUEST` | 1 | 客户端 → 服务端 | 发起业务请求 |
| `RESPONSE` | 2 | 服务端 → 客户端 | 对请求的响应 |
| `PUSH` | 3 | 服务端 → 客户端 | 服务器主动推送事件 |
| `PING` | 4 | 客户端 → 服务端 | 应用层心跳探测，客户端 30~60s 发送一次（D-29） |
| `PONG` | 5 | 服务端 → 客户端 | 应用层心跳应答，服务端 90s 无 PING 则断开（D-29） |

### 2.3 Request / Response / Message

#### Request — 请求消息

```protobuf
message Request {
  string method = 1;                  // 接口路由，格式 "模块/方法"，如 "user/login"
  bytes params = 2;                   // 请求参数（protobuf 序列化）
  map<string, string> metadata = 3;   // 元数据：Token、客户端 IP、版本等
}
```

**metadata 约定键**：

| Key | 说明 |
|-----|------|
| `authorization` | 认证 Token |
| `x-client-ip` | 客户端 IP 地址 |
| 其他 | 可扩展（客户端版本等） |

**method 路由表**（所有支持的接口）：

| method | 参数类型 | 结果类型 | 模块 |
|--------|---------|---------|------|
| `user/login` | `LoginReq` | `LoginResp` | user |
| `user/register` | `RegisterReq` | `RegisterResp` | user |
| `user/search` | `SearchUserReq` | `SearchUserResp` | user |
| `user/getProfile` | `GetProfileReq` | `GetProfileResp` | user |
| `user/batchGet` | `BatchIdRequest` | `BatchGetUserResp` | user |
| `user/batchGetStatus` | `BatchIdRequest` | `BatchGetStatusResp` | user |
| `user/getPrivacy` | `GetPrivacyReq` | `GetPrivacyResp` | user |
| `user/setPrivacy` | `SetPrivacyReq` | — | user |
| `friend/add` | `FriendAddReq` | `FriendAddResp` | friend |
| `friend/accept` | `FriendAcceptReq` | — | friend |
| `friend/reject` | `FriendRejectReq` | — | friend |
| `friend/delete` | `FriendDeleteReq` | — | friend |
| `friend/list` | `FriendListReq` | `FriendListResp` | friend |
| `friend/requests` | `FriendRequestsReq` | `FriendRequestsResp` | friend |
| `chat/send` | `SendMessageReq` | `SendMessageResp` | chat |
| `message/pull` | `PullMessagesReq` | `PullMessagesResp` | message |
| `message/read` | `ReadReportReq` | — | message |
| `message/seq` | `MessageSeqReq` | `MessageSeqResp` | message |
| `conversation/list` | `ConvListReq` | `ConvListResp` | conversation |
| `conversation/create_group` | `CreateGroupReq` | `CreateGroupResp` | conversation |
| `conversation/invite_member` | `InviteMemberReq` | — | conversation |
| `conversation/leave_group` | `LeaveGroupReq` | — | conversation |
| `conversation/kick_member` | `KickMemberReq` | — | conversation |
| `conversation/edit_group_info` | `EditGroupReq` | — | conversation |
| `conversation/group_members` | `GroupMembersReq` | `GroupMembersResp` | conversation |
| `admin/dead-letters` | `DeadLetterQueryReq` | `DeadLetterQueryResp` | admin |
| `admin/retry-dead-letter` | `RetryDeadLetterReq` | `RetryDeadLetterResp` | admin |

#### Response — 响应消息

```protobuf
message Response {
  int32 code = 1;       // 状态码，200=成功
  string msg = 2;       // 提示信息
  string method = 3;    // 原样返回 method，方便匹配
  bytes result = 4;     // 响应数据（protobuf 序列化）
}
```

#### Message — 推送消息

```protobuf
message Message {
  PushEventType eventType = 1;   // 推送事件类型，决定 payload 的结构
  string content = 2;            // 文本内容，用于通知栏预览
  bytes payload = 3;             // 结构化数据（根据 eventType 反序列化）
}
```

---

## 3. 公共类型

### 3.1 DeviceType — 设备类型

**文件**：`nebula/common/common.proto`
**Package**：`com.nebula.chat.common`

| 枚举值 | 编号 | 说明 |
|--------|------|------|
| `DEVICE_TYPE_UNSPECIFIED` | 0 | 未知类型 |
| `MOBILE` | 1 | 移动设备（手机/平板） |
| `DESKTOP` | 2 | 桌面设备（PC/Mac） |
| `WEB` | 3 | 浏览器 Web 端 |

**引用方**：
- `auth/auth.proto` — `SessionInfo.device_type`
- `user/user.proto` — `LoginReq.device_type`、`LoginResp.device_type`

### 3.2 ChatContentType — 消息内容类型

**文件**：`nebula/message_type.proto`
**Package**：`com.nebula.chat`

描述聊天消息正文的格式。

| 枚举值 | 编号 | 说明 |
|--------|------|------|
| `TEXT` | 0 | 纯文本消息 |
| `TEXT_AND_IMAGE` | 1 | 图文混合消息（预留） |

### 3.3 PushEventType — 推送事件类型

**文件**：`nebula/message_type.proto`
**Package**：`com.nebula.chat`

指示 `Message.payload` 的反序列化目标类型。

| 枚举值 | 编号 | Payload 类型 | 说明 |
|--------|------|-------------|------|
| `PUSH_EVENT_UNSPECIFIED` | 0 | — | 未指定 |
| `CHAT_MESSAGE` | 1 | `ChatMessage` | 新消息推送 |
| `FRIEND_REQUEST` | 2 | `FriendRequestPayload` | 好友申请推送 |
| `FRIEND_ACCEPTED` | 3 | `FriendAcceptedPayload` | 好友接受推送 |
| `LOGOUT` | 4 | 空（content 携带原因） | 强制登出 |
| `GROUP_CREATED` | 5 | `GroupCreatedPayload` | 群创建通知 |
| `GROUP_INVITED` | 6 | `GroupInvitedPayload` | 群邀请通知 |
| `GROUP_DISSOLVED` | 7 | `GroupDissolvedPayload` | 群解散通知 |
| `GROUP_UPDATED` | 8 | `GroupUpdatedPayload` | 群信息更新 |
| `MEMBER_JOINED` | 9 | `MemberJoinedPayload` | 成员加入 |
| `MEMBER_LEFT` | 10 | `MemberLeftPayload` | 成员退出 |
| `MEMBER_KICKED` | 11 | `MemberKickedPayload` | 成员被踢 |
| `READ_RECEIPT` | 12 | `ReadReceiptPayload` | 已读回执 |
| `DELIVERY_ACK` | 13 | `DeliveryAckPayload` | 交付回执 |
| `STATUS_CHANGED` | 14 | `StatusChangedPayload` | 在线状态变更 |
| `DISCONNECT` | 15 | 空 | 服务端主动断连通知（D-68） |

---

## 4. 用户模块 (user)

**文件**：`nebula/user/user.proto`
**Package**：`com.nebula.chat.user`

### LoginReq — 登录请求

`method = "user/login"`，支持用户名+密码登录或 Token 登录。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | `optional string` | 密码登录时 | 用户名 |
| `password` | `optional string` | 密码登录时 | 密码 |
| `token` | `optional string` | Token 登录时 | 登录凭证 |
| `device_type` | `DeviceType` | 是 | 登录设备类型 |
| `last_received_global_id` | `int64` | 否 | 客户端最后收到的全局消息ID，用于增量同步 |
| `device_id` | `string` | 是 | 设备唯一标识，用于同设备类型互踢 |

### LoginResp — 登录响应

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | `int64` | 用户数据库 ID |
| `uid` | `int64` | 用户唯一标识（业务层使用） |
| `token` | `string` | 登录凭证，后续请求通过 metadata 携带 |
| `server_now` | `int64` | 服务端当前时间戳（毫秒），用于客户端时间校准 |
| `last_read_info` | `map<string, int64>` | 各会话的最新已读消息ID |
| `server_last_msg_id` | `int64` | 服务端最新消息全局ID |
| `device_type` | `DeviceType` | 本次登录设备类型 |
| `device_id` | `string` | 本次登录设备 ID |

### RegisterReq — 注册请求

`method = "user/register"`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | `string` | 是 | 用户名，登录唯一凭证 |
| `password` | `string` | 是 | 密码，服务端 BCrypt 哈希存储 |
| `nickname` | `string` | 是 | 显示昵称 |
| `avatar` | `optional string` | 否 | 头像 URL |

### RegisterResp — 注册响应

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 新用户 UID |
| `token` | `string` | 登录凭证，注册后直接完成登录 |

### SearchUserReq — 搜索用户

`method = "user/search"`，游标分页。

| 字段 | 类型 | 说明 |
|------|------|------|
| `keyword` | `string` | 搜索关键词（用户名/昵称） |
| `cursor` | `int64` | 游标（上一页最后一条的 `created_at` 毫秒时间戳），首次传 0 |
| `limit` | `int32` | 每页数量，默认 20，最大 20 |

### SearchUserResp — 搜索结果

| 字段 | 类型 | 说明 |
|------|------|------|
| `users` | `repeated UserBrief` | 匹配的用户列表 |
| `next_cursor` | `int64` | 下一页游标，无更多时为 0 |
| `has_more` | `bool` | 是否还有更多数据 |

### GetProfileReq / GetProfileResp

`method = "user/getProfile"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 目标用户 UID |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 用户 UID |
| `username` | `string` | 用户名 |
| `display_name` | `string` | 显示昵称 |
| `avatar_url` | `string` | 头像 URL |
| `gender` | `int32` | 性别：0=保密 1=男 2=女 |
| `bio` | `string` | 个人简介 |
| `created_at` | `int64` | 注册时间（毫秒时间戳） |

### BatchIdRequest / BatchGetUserResp — 批量查用户

`method = "user/batchGet"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `uids` | `repeated int64` | 用户 UID 列表 |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `users` | `repeated UserBrief` | 用户简要信息列表 |

### batchGetStatus — 批量查在线状态

`method = "user/batchGetStatus"`，请求复用 `BatchIdRequest`。

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `statuses` | `repeated UserOnlineStatus` | 在线状态列表 |

### SetPrivacyReq — 设置隐私

`method = "user/setPrivacy"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `hide_online_status` | `bool` | 是否隐藏在线状态 |

### GetPrivacyReq / GetPrivacyResp — 获取隐私

`method = "user/getPrivacy"`，请求为空消息（userId 由服务端从 Session 获取）。

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `hide_online_status` | `bool` | 是否隐藏在线状态 |

### UserBrief — 用户简要信息

复用消息，出现于 `SearchUserResp`、`BatchGetUserResp`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 用户 UID |
| `username` | `string` | 用户名 |
| `display_name` | `string` | 显示昵称 |
| `avatar_url` | `string` | 头像 URL |
| `created_at` | `int64` | 注册时间（毫秒时间戳） |

### UserOnlineStatus — 在线状态

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 用户 UID |
| `status` | `int32` | 在线状态：0=离线 1=在线 2=隐藏 |
| `last_active_at` | `int64` | 最后活跃时间（毫秒时间戳） |

---

## 5. 好友模块 (friend)

**文件**：`nebula/friend/friend.proto`
**Package**：`com.nebula.chat.friend`

### FriendAddReq / FriendAddResp — 发送好友申请

`method = "friend/add"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `to_uid` | `int64` | 目标用户 UID |
| `message` | `string` | 好友申请附言 |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `request_id` | `int64` | 好友申请 ID |

### FriendAcceptReq — 同意好友申请

`method = "friend/accept"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `request_id` | `int64` | 好友申请 ID |

### FriendRejectReq — 拒绝好友申请

`method = "friend/reject"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `request_id` | `int64` | 好友申请 ID |

### FriendDeleteReq — 删除好友

`method = "friend/delete"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 好友 UID |

### FriendListReq / FriendListResp — 好友列表

`method = "friend/list"`，游标分页（D-46）。

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `cursor` | `int64` | 游标，首次传 0 |
| `limit` | `int32` | 每页数量 |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `friends` | `repeated FriendBrief` | 好友简要信息列表 |

### FriendBrief — 好友简要信息

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 好友 UID |
| `username` | `string` | 好友用户名 |
| `display_name` | `string` | 好友显示昵称 |
| `avatar_url` | `string` | 头像 URL |
| `status` | `int32` | 在线状态：0=离线 1=在线 2=隐藏 |
| `created_at` | `int64` | 成为好友的时间（毫秒时间戳） |

### FriendRequestsReq / FriendRequestsResp — 好友申请列表

`method = "friend/requests"`，请求为空消息。

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `requests` | `repeated FriendRequestItem` | 好友申请列表 |

### FriendRequestItem — 好友申请条目

| 字段 | 类型 | 说明 |
|------|------|------|
| `request_id` | `int64` | 申请 ID |
| `from_uid` | `int64` | 申请人 UID |
| `from_username` | `string` | 申请人用户名 |
| `from_avatar` | `string` | 申请人头像 URL |
| `message` | `string` | 申请附言 |
| `status` | `string` | 申请状态：`pending` / `accepted` / `rejected` |
| `created_at` | `int64` | 申请时间（毫秒时间戳） |

### 好友推送 Payload

#### FriendRequestPayload — 好友申请推送

`PushEventType = FRIEND_REQUEST`

| 字段 | 类型 | 说明 |
|------|------|------|
| `request_id` | `int64` | 好友申请 ID |
| `from_uid` | `int64` | 申请人 UID |
| `from_username` | `string` | 申请人用户名 |
| `from_avatar` | `string` | 申请人头像 URL |
| `message` | `string` | 申请附言 |

#### FriendAcceptedPayload — 好友接受推送

`PushEventType = FRIEND_ACCEPTED`

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 接受方 UID |
| `conversation_id` | `string` | 新建/恢复的私聊会话 ID（D-43） |

#### StatusChangedPayload — 在线状态变更

`PushEventType = STATUS_CHANGED`

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 状态变更用户 UID |
| `status` | `int32` | 在线状态：0=离线 1=在线 2=隐藏（D-57） |

---

## 6. 聊天模块 (chat)

**文件**：`nebula/chat/chat.proto`
**Package**：`com.nebula.chat.chat`

### SendMessageReq — 发送消息

`method = "chat/send"`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | `string` | 是 | 会话ID |
| `message_type` | `ChatContentType` | 是 | 消息内容类型（TEXT / TEXT_AND_IMAGE） |
| `content` | `string` | 是 | 消息文本内容 |
| `payload` | `bytes` | 否 | 附带结构化数据，按 message_type 解析 |
| `client_ts` | `int64` | 是 | 客户端发送时间戳（毫秒），用于消息排序与去重 |
| `client_message_id` | `string` | 是 | 客户端消息幂等标识（UUID），用于防重复入库 |

### SendMessageResp — 发送消息响应

| 字段 | 类型 | 说明 |
|------|------|------|
| `msg_id` | `int64` | 服务端分配的消息 ID |
| `server_ts` | `int64` | 服务端接收时间戳（毫秒） |
| `seq` | `int64` | 服务端分配的会话序列号（D-74），per-(conv, uid) 自增 |

---

## 7. 消息模块 (message)

**文件**：`nebula/message/message.proto`
**Package**：`com.nebula.chat.message`

### PullMessagesReq / PullMessagesResp — 拉取历史消息

`method = "message/pull"`

**请求**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | `string` | 是 | 会话ID |
| `cursor` | `int64` | 否 | 游标，基于消息 ID 的分页 |
| `limit` | `int32` | 否 | 拉取条数上限 |
| `direction` | `string` | 是 | 拉取方向：`forward`（新消息）/ `backward`（历史消息） |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `messages` | `repeated ChatMessage` | 消息列表 |
| `has_more` | `bool` | 是否还有更多数据 |

### ChatMessage — 聊天消息结构

客户端通过 `sender_uid` / `receiver_id` 配合 `user/batchGet` 展示用户信息（D-Phase6-02）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `msg_id` | `int64` | 消息 ID |
| `conversation_id` | `string` | 所属会话ID |
| `sender_uid` | `int64` | 发送者 UID |
| `receiver_uid` | `int64` | 私聊接收者 UID（群聊为 0） |
| `message_type` | `ChatContentType` | 消息内容类型 |
| `content` | `string` | 消息文本内容 |
| `payload` | `bytes` | 消息附加数据 |
| `client_ts` | `int64` | 客户端发送时间戳（毫秒） |
| `server_ts` | `int64` | 服务端接收时间戳（毫秒） |

### ReadReportReq — 已读报告

`method = "message/read"`，客户端上报已读到哪条消息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |
| `last_read_msg_id` | `int64` | 最后一条已读消息 ID |

### ReadReceiptPayload — 已读回执推送

`PushEventType = READ_RECEIPT`，私聊场景下，接收方读了消息后推送给发送者。

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |
| `reader_uid` | `int64` | 已读用户 UID |
| `msg_id` | `int64` | 最后已读消息 ID |

### DeliveryAckPayload — 交付回执推送

`PushEventType = DELIVERY_ACK`，推送给发送者，告知消息已投递到接收方设备。

| 字段 | 类型 | 说明 |
|------|------|------|
| `msg_id` | `int64` | 消息 ID |
| `conversation_id` | `string` | 会话ID |

### MessageSeqReq / MessageSeqResp — 序列号查询

`method = "message/seq"`，客户端用来检测序列号间隙。

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `seq` | `int64` | 当前会话最新序列号 |

---

## 8. 会话模块 (conversation)

**文件**：`nebula/conversation/conversation.proto`
**Package**：`com.nebula.chat.conversation`

### ConvListReq / ConvListResp — 会话列表

`method = "conversation/list"`，游标分页。

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `limit` | `int32` | 拉取条数上限 |
| `cursor` | `int64` | 游标，基于会话最后更新时间 |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversations` | `repeated ConversationBrief` | 会话简要列表 |
| `has_more` | `bool` | 是否还有更多数据 |

### ConversationBrief — 会话简要信息

未读数由客户端根据 `last_read_msg_id` 自行计算。

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |
| `type` | `string` | 会话类型：`private` / `group` |
| `name` | `string` | 会话名称（私聊为对方昵称，群聊为群名） |
| `avatar_url` | `string` | 会话头像 URL |
| `last_message_id` | `int64` | 最后一条消息 ID |
| `last_message_preview` | `string` | 最后一条消息文本预览 |
| `last_message_ts` | `int64` | 最后一条消息时间戳 |
| `last_updated_at` | `int64` | 会话最后更新时间 |
| `last_read_msg_id` | `int64` | 用户在此会话的最后已读消息 ID |

### CreateGroupReq / CreateGroupResp — 创建群聊

`method = "conversation/create_group"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 群名称 |
| `member_uids` | `repeated int64` | 初始成员 UID 列表（不含创建者本人） |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 新群会话ID |
| `name` | `string` | 群名称 |

### InviteMemberReq — 邀请成员

`method = "conversation/invite_member"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |
| `uids` | `repeated int64` | 被邀请者 UID 列表 |

### LeaveGroupReq — 退出群聊

`method = "conversation/leave_group"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |

### KickMemberReq — 踢出成员

`method = "conversation/kick_member"`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |
| `uid` | `int64` | 被踢成员 UID |

### EditGroupReq — 编辑群资料

`method = "conversation/edit_group_info"`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | `string` | 是 | 会话ID |
| `name` | `optional string` | 否 | 新群名称 |
| `avatar_url` | `optional string` | 否 | 新群头像 URL |

### GroupMembersReq / GroupMembersResp — 群成员列表

`method = "conversation/group_members"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话ID |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `members` | `repeated GroupMember` | 群成员列表 |

### 会话推送 Payload

#### GroupCreatedPayload — 群创建通知

`PushEventType = GROUP_CREATED`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 新群会话 ID |
| `name` | `string` | 群名称 |
| `creator_uid` | `int64` | 创建者 UID |

#### MemberJoinedPayload — 成员加入通知

`PushEventType = MEMBER_JOINED`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话 ID |
| `uids` | `repeated int64` | 新加入成员 UID 列表 |
| `inviter_uid` | `int64` | 邀请人 UID |

#### MemberLeftPayload — 成员退出通知

`PushEventType = MEMBER_LEFT`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话 ID |
| `uid` | `int64` | 退出成员 UID |

#### MemberKickedPayload — 被踢通知

`PushEventType = MEMBER_KICKED`（仅推送给被踢者本人）

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话 ID |
| `uid` | `int64` | 被踢成员 UID |

#### GroupUpdatedPayload — 群信息更新通知

`PushEventType = GROUP_UPDATED`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | `string` | 是 | 会话 ID |
| `name` | `optional string` | 否 | 新群名称 |
| `avatar_url` | `optional string` | 否 | 新群头像 URL |

#### GroupDissolvedPayload — 群解散通知

`PushEventType = GROUP_DISSOLVED`

| 字段 | 类型 | 说明 |
|------|------|------|
| `conversation_id` | `string` | 会话 ID |

---

## 9. 群组模块 (group)

**文件**：`nebula/group/group.proto`
**Package**：`com.nebula.chat.group`

### GroupMember — 群成员信息

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 成员 UID |
| `username` | `string` | 成员用户名 |
| `display_name` | `string` | 成员显示昵称 |
| `avatar_url` | `string` | 成员头像 URL |
| `role` | `string` | 成员角色：`owner` / `admin` / `member` |
| `joined_at` | `int64` | 加入时间（毫秒时间戳） |

---

## 10. 认证模块 (auth)

**文件**：`nebula/auth/auth.proto`
**Package**：`com.nebula.chat.auth`

### SessionInfo — 会话信息

服务端存储的客户端连接会话元数据。

| 字段 | 类型 | 说明 |
|------|------|------|
| `uid` | `int64` | 用户 UID |
| `device_type` | `DeviceType` | 登录设备类型 |
| `login_at` | `int64` | 登录时间（毫秒时间戳） |

---

## 11. 管理模块 (admin)

**文件**：`nebula/admin.proto`
**Package**：`com.nebula.chat.admin`

### DeadLetterQueryReq / DeadLetterQueryResp — 死信查询

`method = "admin/dead-letters"`，分页查询死信记录。

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `page` | `int32` | 页码，从 1 开始 |
| `page_size` | `int32` | 每页条数 |
| `status` | `string` | 过滤状态：`pending` / `retrying` / `permanent_failed` / `retry_success`，空=全部 |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | `repeated DeadLetterItem` | 死信记录列表 |
| `total` | `int32` | 总记录数 |

### DeadLetterItem — 死信记录

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `int64` | 死信 ID |
| `msg_id` | `int64` | 原始消息 ID |
| `conversation_id` | `string` | 会话ID |
| `sender_uid` | `int64` | 发送者 UID |
| `fail_reason` | `string` | 失败原因 |
| `fail_count` | `int32` | 失败次数 |
| `status` | `string` | 状态：`pending` / `retrying` / `permanent_failed` / `retry_success` |
| `created_at` | `int64` | 创建时间戳（毫秒） |

### RetryDeadLetterReq / RetryDeadLetterResp — 手动重试

`method = "admin/retry-dead-letter"`

**请求**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `dead_letter_id` | `int64` | 死信 ID |

**响应**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | `bool` | 是否重试成功 |

---

## 12. 推送 Payload 索引

按 `PushEventType` 编号排序，汇总所有推送 Payload 与枚举值的对应关系。

| PushEventType | 编号 | Payload 消息 | 定义位置 | 推送目标 |
|---------------|------|-------------|----------|---------|
| `PUSH_EVENT_UNSPECIFIED` | 0 | — | — | — |
| `CHAT_MESSAGE` | 1 | `ChatMessage` | `message/message.proto` | 会话内其他成员 |
| `FRIEND_REQUEST` | 2 | `FriendRequestPayload` | `friend/friend.proto` | 被申请人 |
| `FRIEND_ACCEPTED` | 3 | `FriendAcceptedPayload` | `friend/friend.proto` | 申请人 |
| `LOGOUT` | 4 | 空 | — | 当前用户（强制登出） |
| `GROUP_CREATED` | 5 | `GroupCreatedPayload` | `conversation/conversation.proto` | 所有群成员 |
| `GROUP_INVITED` | 6 | `GroupInvitedPayload` | `conversation/conversation.proto` | 被邀请者 |
| `GROUP_DISSOLVED` | 7 | `GroupDissolvedPayload` | `conversation/conversation.proto` | 所有群成员 |
| `GROUP_UPDATED` | 8 | `GroupUpdatedPayload` | `conversation/conversation.proto` | 所有群成员 |
| `MEMBER_JOINED` | 9 | `MemberJoinedPayload` | `conversation/conversation.proto` | 群内所有成员 |
| `MEMBER_LEFT` | 10 | `MemberLeftPayload` | `conversation/conversation.proto` | 群内所有成员 |
| `MEMBER_KICKED` | 11 | `MemberKickedPayload` | `conversation/conversation.proto` | 被踢者本人 |
| `READ_RECEIPT` | 12 | `ReadReceiptPayload` | `message/message.proto` | 私聊消息发送者 |
| `DELIVERY_ACK` | 13 | `DeliveryAckPayload` | `message/message.proto` | 消息发送者 |
| `STATUS_CHANGED` | 14 | `StatusChangedPayload` | `friend/friend.proto` | 在线好友 |
| `DISCONNECT` | 15 | 空 | — | 当前用户（触发重连） |

---

> 文档生成时间：2026-06-16 | Proto 版本：v1 (protocol_version=1) | gRPC 端口：9090

---

## 附录：Proto 源码

### envelope.proto

```protobuf
syntax = "proto3";

package com.nebula.chat;

option java_multiple_files = true;
option java_package = "com.nebula.chat";

import "nebula/message_type.proto";

// 通信方向枚举
// 双重心跳策略（D-27）：
// - 传输层：gRPC keepalive（HTTP/2 PING/PONG）快速检测 TCP 断开 — 解决"连接是否存活"问题
// - 业务层：应用层 PING/PONG（Direction 枚举值）检测 NAT/代理导致的半开连接 — 解决"服务是否健康"问题
//   应用层 PING 与业务消息走在同一数据通道上，端到端真实状态检测
// 两者分工：gRPC keepalive 只说明 HTTP/2 帧层面连接通，不代表服务端应用层能正常处理请求；
// 应用层 PING 说明整个请求链路（Dispatcher + Handler）正常工作。
enum Direction {
  DIRECTION_UNSPECIFIED = 0;  // 未知类型，不应使用
  REQUEST = 1;                // 客户端 → 服务端：发起业务请求
  RESPONSE = 2;               // 服务端 → 客户端：对请求的响应
  PUSH = 3;                   // 服务端 → 客户端：服务器主动推送事件（如新消息、踢出通知）
  PING = 4;                   // 客户端 → 服务端：应用层心跳探测，检测半开连接（D-29: 客户端 30~60s 发送）
  PONG = 5;                   // 服务端 → 客户端：应用层心跳应答（D-29: 服务端 90s 无 PING 断开）
}

// 消息信封：双向流中所有消息的外层包装，用于区分通信方向与负载类型
message Envelope {
  Direction direction = 1;        // 通信方向，标识当前消息是请求、响应、推送还是应用层心跳
  string request_id = 2;          // 请求唯一ID，用于请求与响应的配对关联，推送和心跳时可为空
  int32 protocol_version = 3;     // 协议版本号，初始值 = 1，用于后续协议升级的兼容性判断
  oneof payload {
    Request request = 10;         // 客户端发起的请求负载
    Response response = 11;       // 服务端返回的响应负载
    Message message = 12;         // 服务端主动推送的消息负载（用于 PUSH 方向）
  }
}

// 请求消息：客户端发起的业务调用
// metadata 用于传递认证 Token（key="authorization"）、客户端 IP（key="x-client-ip"）、客户端版本等元数据（D-04）
message Request {
  string method = 1;              // 接口路由标识，格式为 "业务模块/接口名称"，如 "user/login"、"chat/send"
  bytes params = 2;               // 请求参数，通过 method 找到对应的 protobuf 消息类型进行序列化/反序列化
  map<string, string> metadata = 3;  // 请求元数据，用于传递 Token、客户端 IP 等（D-04 推荐方式）
}

// 响应消息：服务端对请求的应答
message Response {
  int32 code = 1;                 // 响应状态码，200 表示成功，其他值为各类业务错误码
  string msg = 2;                 // 响应附带的消息提示，成功时可为空，失败时描述错误原因
  string method = 3;              // 原样返回请求的 method，方便客户端匹配请求与响应
  bytes result = 4;               // 响应附带的数据负载，具体类型由 method 决定，反序列化方式与请求参数一致
}

// 推送消息：服务端主动下发给客户端的事件
message Message {
  PushEventType eventType = 1;          // 推送事件类型，指示 payload 的结构
  string content = 2;                   // 事件文本内容，可用于通知栏预览（免反序列化 payload）
  bytes payload = 3;                    // 事件附带的额外结构化数据，根据 eventType 使用对应的 protobuf 类型解析
}
```

### message_type.proto

```protobuf
syntax = "proto3";

package com.nebula.chat;

option java_multiple_files = true;
option java_package = "com.nebula.chat";

// 聊天消息内容类型：描述 ChatMessage 正文的格式
enum ChatContentType {
  TEXT = 0;                             // 纯文本消息
  TEXT_AND_IMAGE = 1;                   // 图文混合消息（预留）
  // 后续可扩展：FILE = 2, LINK = 3 等
}

// 服务端推送事件类型：描述 envelope.Message 中 payload 的结构
enum PushEventType {
  PUSH_EVENT_UNSPECIFIED = 0;           // 未指定，不应使用
  CHAT_MESSAGE = 1;                     // payload = ChatMessage
  FRIEND_REQUEST = 2;                   // payload = FriendRequestPayload
  FRIEND_ACCEPTED = 3;                  // payload = FriendAcceptedPayload
  LOGOUT = 4;                           // payload 为空，content 携带原因
  GROUP_CREATED = 5;                    // payload = GroupCreatedPayload
  GROUP_INVITED = 6;                    // payload = GroupInvitedPayload
  GROUP_DISSOLVED = 7;                  // payload = GroupDissolvedPayload
  GROUP_UPDATED = 8;                    // payload = GroupUpdatedPayload
  MEMBER_JOINED = 9;                    // payload = MemberJoinedPayload
  MEMBER_LEFT = 10;                     // payload = MemberLeftPayload
  MEMBER_KICKED = 11;                   // payload = MemberKickedPayload
  READ_RECEIPT = 12;                    // payload = ReadReceiptPayload
  DELIVERY_ACK = 13;                    // payload = DeliveryAckPayload
  STATUS_CHANGED = 14;                  // payload = StatusChangedPayload
  DISCONNECT = 15;                      // 服务端主动断连通知：旧连接关闭前推送，触发客户端重连流程（D-68）
}
```

### common/common.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.common;

option java_multiple_files = true;
option java_package = "com.nebula.chat.common";

// 设备类型
enum DeviceType {
  DEVICE_TYPE_UNSPECIFIED = 0;  // 未知类型
  MOBILE = 1;                   // 移动设备（手机/平板）
  DESKTOP = 2;                  // 桌面设备（PC/Mac）
  WEB = 3;                      // 浏览器 Web 端
}
```

### auth/auth.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.auth;

option java_multiple_files = true;
option java_package = "com.nebula.chat.auth";

import "nebula/common/common.proto";

// 会话信息：服务端存储的客户端连接会话元数据
message SessionInfo {
  int64 uid = 1;                      // 用户 UID
  common.DeviceType device_type = 2;  // 登录设备类型
  int64 login_at = 3;                 // 登录时间（毫秒时间戳）
}
```

### user/user.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.user;

option java_multiple_files = true;
option java_package = "com.nebula.chat.user";

import "nebula/common/common.proto";

// ---- user/login ----
// 登录请求：支持用户名+密码登录或 token 登录
message LoginReq {
  optional string username = 1;       // 用户名，密码登录时必填
  optional string password = 2;       // 密码，密码登录时必填
  optional string token = 3;          // Token，token 登录时必填
  common.DeviceType device_type = 4;  // 登录设备类型
  int64 last_received_global_id = 5;  // 客户端最后收到的全局消息ID，用于服务端同步增量数据
  string device_id = 6;               // 客户端设备唯一标识（由客户端生成），用于同设备类型互踢
}

// 登录响应
message LoginResp {
  int64 user_id = 1;                  // 用户数据库 ID
  int64 uid = 2;                      // 用户唯一标识 UID，业务中使用
  string token = 3;                   // 登录凭证 token，后续请求通过 token 鉴权
  int64 server_now = 4;               // 服务端当前时间戳（毫秒），用于客户端时间校准
  map<string, int64> last_read_info = 5;  // 各会话的最新已读消息ID，{conversation_id: last_read_msg_id}
  int64 server_last_msg_id = 6;       // 服务端最新消息全局ID，用于客户端判断是否有增量消息
  common.DeviceType device_type = 7;  // 本次登录的设备类型，由 LoginHandler 从 LoginReq 复制（D-04/D-05）
  string device_id = 8;               // 本次登录的设备 ID，由 LoginHandler 从 LoginReq 复制，避免 ChatService 重复解析
}

// ---- user/register ----
// 注册请求：创建新用户账户（D-01）
// 注意：不包含 device_type 字段 — 注册不创建 Session，设备类型在后续登录时提供
message RegisterReq {
  string username = 1;                // 用户名，登录唯一凭证
  string password = 2;                // 密码，BCrypt 哈希后存储
  string nickname = 3;                // 显示昵称
  optional string avatar = 4;         // 头像 URL，可选
}

// 注册响应
message RegisterResp {
  int64 uid = 1;                      // 新用户的 UID
  string token = 2;                   // 登录凭证 token，注册成功后直接完成登录状态
}

// ---- user/search ----
// 搜索用户请求，根据关键词查找用户（游标分页）
message SearchUserReq {
  string keyword = 1;                 // 搜索关键词（用户名/昵称）
  int64 cursor = 2;                   // 游标：上一页最后一条的 created_at 毫秒时间戳，首次查询传 0（D-08）
  int32 limit = 3;                    // 每页数量，默认 20，最大 20
}

// 搜索用户响应
message SearchUserResp {
  repeated UserBrief users = 1;       // 匹配的用户简要列表
  int64 next_cursor = 2;              // 下一页游标（最后一条的 created_at 毫秒时间戳），无更多数据时为 0
  bool has_more = 3;                  // 是否有更多数据
}

// ---- user/getProfile ----
// 获取用户公开资料
message GetProfileReq {
  int64 uid = 1;                      // 目标用户 UID
}

// 用户公开资料响应
message GetProfileResp {
  int64 uid = 1;                      // 用户 UID
  string username = 2;                // 用户名
  string display_name = 3;            // 显示昵称
  string avatar_url = 4;              // 头像 URL
  int32 gender = 5;                   // 性别：0=保密 1=男 2=女
  string bio = 6;                     // 个人简介
  int64 created_at = 7;               // 注册时间（毫秒时间戳）
}

// ---- user/batchGet ----
// 批量查询用户简要信息
message BatchIdRequest {
  repeated int64 uids = 1;            // 要查询的用户 UID 列表
}

// 批量用户简要信息响应
message BatchGetUserResp {
  repeated UserBrief users = 1;       // 用户简要信息列表
}

// ---- user/batchGetStatus ----
// 批量查询用户在线状态
message BatchGetStatusResp {
  repeated UserOnlineStatus statuses = 1;  // 在线状态列表
}

// ---- user/setPrivacy ----
// 设置隐私选项
message SetPrivacyReq {
  bool hide_online_status = 1;        // 是否隐藏在线状态
}

// ---- user/getPrivacy ----
// 获取隐私选项请求（空消息，userId 由服务端从 Session 中获取）
message GetPrivacyReq {}

// 获取隐私选项响应
message GetPrivacyResp {
  bool hide_online_status = 1;        // 是否隐藏在线状态
}

// 用户简要信息（多接口复用）
message UserBrief {
  int64 uid = 1;                      // 用户 UID
  string username = 2;                // 用户名
  string display_name = 3;            // 显示昵称
  string avatar_url = 4;              // 头像 URL
  int64 created_at = 5;               // 注册时间（毫秒时间戳），用于搜索分页排序参考
}

// 在线状态
message UserOnlineStatus {
  int64 uid = 1;                      // 用户 UID
  int32 status = 2;                   // 在线状态：0=离线 1=在线 2=隐藏（在线但对他人不可见）
  int64 last_active_at = 3;           // 最后活跃时间（毫秒时间戳）
}
```

### friend/friend.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.friend;

option java_multiple_files = true;
option java_package = "com.nebula.chat.friend";

// ---- friend/add ----
// 发送好友申请
message FriendAddReq {
  int64 to_uid = 1;                   // 目标用户 UID
  string message = 2;                 // 好友申请附言
}

// 好友申请响应
message FriendAddResp {
  int64 request_id = 1;               // 好友申请 ID
}

// ---- friend/accept ----
// 同意好友申请
message FriendAcceptReq {
  int64 request_id = 1;               // 好友申请 ID
}

// ---- friend/reject ----
// 拒绝好友申请
message FriendRejectReq {
  int64 request_id = 1;               // 好友申请 ID
}

// ---- friend/delete ----
// 删除好友
message FriendDeleteReq {
  int64 uid = 1;                      // 好友 UID
}

// ---- friend/list ----
// 获取好友列表，支持游标分页（D-46）
message FriendListReq {
  int64 cursor = 1;                    // 游标，首次请求传 0
  int32 limit = 2;                    // 每页数量
}

// 好友列表响应
message FriendListResp {
  repeated FriendBrief friends = 1;   // 好友简要信息列表
}

// 好友简要信息
message FriendBrief {
  int64 uid = 1;                      // 好友 UID
  string username = 2;                // 好友用户名
  string display_name = 3;            // 好友显示昵称
  string avatar_url = 4;              // 好友头像 URL
  int32 status = 5;                   // 在线状态：0=离线 1=在线 2=隐藏
  int64 created_at = 6;               // 成为好友的时间（毫秒时间戳）
}

// ---- friend/requests ----
// 获取好友申请列表
message FriendRequestsReq {}

// 好友申请列表响应
message FriendRequestsResp {
  repeated FriendRequestItem requests = 1; // 好友申请列表
}

// 好友申请条目
message FriendRequestItem {
  int64 request_id = 1;               // 申请 ID
  int64 from_uid = 2;                 // 申请人 UID
  string from_username = 3;           // 申请人用户名
  string from_avatar = 4;             // 申请人头像 URL
  string message = 5;                 // 申请附言
  string status = 6;                  // 申请状态：pending / accepted / rejected
  int64 created_at = 7;               // 申请时间（毫秒时间戳）
}

// ========================================
// 推送 Payload 消息（D-49, D-50）
// ========================================

// 好友申请推送载荷（pushEventType = FRIEND_REQUEST）
message FriendRequestPayload {
  int64 request_id = 1;               // 好友申请 ID
  int64 from_uid = 2;                 // 申请人 UID
  string from_username = 3;           // 申请人用户名
  string from_avatar = 4;             // 申请人头像 URL
  string message = 5;                 // 申请附言
}

// 好友接受推送载荷（pushEventType = FRIEND_ACCEPTED）
message FriendAcceptedPayload {
  int64 uid = 1;                      // 接受方 UID
  string conversation_id = 2;         // 新建/恢复的私聊会话 ID（D-43）
}

// 在线状态变更推送载荷（pushEventType = STATUS_CHANGED）
message StatusChangedPayload {
  int64 uid = 1;                      // 状态变更用户 UID
  int32 status = 2;                   // 在线状态：0=离线 1=在线 2=隐藏（D-57）
}
```

### chat/chat.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.chat;

option java_multiple_files = true;
option java_package = "com.nebula.chat.chat";

import "nebula/message_type.proto";

// ---- chat/send ----
// 发送消息请求
message SendMessageReq {
  string conversation_id = 1;         // 会话ID，标识消息所属会话
  ChatContentType message_type = 2;      // 消息内容类型，如 TEXT、TEXT_AND_IMAGE 等
  string content = 3;                 // 消息文本内容
  bytes payload = 4;                  // 消息附带的结构化数据，根据 message_type 解析
  int64 client_ts = 5;                // 客户端发送时间戳（毫秒），用于消息排序与去重
  string client_message_id = 6;       // 客户端消息幂等标识（UUID），服务端用于防重试重复入库
}

// 发送消息响应
message SendMessageResp {
  int64 msg_id = 1;                   // 服务端分配的消息 ID
  int64 server_ts = 2;                // 服务端接收时间戳（毫秒）
  int64 seq = 3;                      // 服务端分配的会话序列号（D-74），per-(conv,uid) 自增
}
```

### message/message.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.message;

option java_multiple_files = true;
option java_package = "com.nebula.chat.message";

import "nebula/message_type.proto";

// ---- message/pull ----
// 拉取消息请求：客户端分页拉取会话历史消息
message PullMessagesReq {
  string conversation_id = 1;         // 会话ID
  int64 cursor = 2;                   // 游标，基于消息 ID 的分页
  int32 limit = 3;                    // 拉取条数上限
  string direction = 4;               // 拉取方向："forward" 新消息 / "backward" 历史消息
}

// 拉取消息响应
message PullMessagesResp {
  repeated ChatMessage messages = 1;  // 消息列表
  bool has_more = 2;                  // 是否还有更多数据
}

// 聊天消息结构
// 客户端通过 sender_uid / receiver_id 配合 user/batchGet 展示用户信息（D-Phase6-02）。
message ChatMessage {
  int64 msg_id = 1;                   // 消息 ID
  string conversation_id = 2;         // 所属会话ID
  int64 sender_uid = 3;               // 发送者 UID
  int64 receiver_uid = 11;            // 私聊接收者 UID（群聊为 0）
  ChatContentType message_type = 6;       // 消息内容类型（ChatContentType），描述正文格式
  string content = 7;                 // 消息文本内容
  bytes payload = 8;                  // 消息附加数据
  int64 client_ts = 9;                // 客户端发送时间戳（毫秒）
  int64 server_ts = 10;               // 服务端接收时间戳（毫秒）
}

// ---- message/read ----
// 已读报告请求：客户端上报已读到哪条消息
message ReadReportReq {
  string conversation_id = 1;         // 会话ID
  int64 last_read_msg_id = 2;         // 最后一条已读消息 ID
}

// 已读回执推送 payload（PUSH EventType=READ_RECEIPT）
// 私聊场景下，接收方读了消息后推送给发送者。
message ReadReceiptPayload {
  string conversation_id = 1;         // 会话ID
  int64 reader_uid = 2;              // 已读用户 UID
  int64 msg_id = 3;                  // 最后已读消息 ID
}

// ---- message/delivery-ack ----
// 交付回执推送 payload（PUSH EventType=DELIVERY_ACK）
// 推送给发送者，告知消息已投递到接收方设备。
message DeliveryAckPayload {
  int64 msg_id = 1;                   // 消息 ID
  string conversation_id = 2;         // 会话ID
}

// ---- message/seq ----
// 查询会话最新序列号请求：客户端用来检测序列号间隙
message MessageSeqReq {
  string conversation_id = 1;         // 会话ID
}

// 查询会话最新序列号响应
message MessageSeqResp {
  int64 seq = 1;                      // 当前会话最新序列号
}
```

### conversation/conversation.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.conversation;

option java_multiple_files = true;
option java_package = "com.nebula.chat.conversation";

import "nebula/group/group.proto";

// ---- conversation/list ----
// 获取会话列表请求
message ConvListReq {
  int32 limit = 1;                    // 拉取条数上限
  int64 cursor = 2;                   // 游标分页，基于会话最后更新时间
}

// 获取会话列表响应
message ConvListResp {
  repeated ConversationBrief conversations = 1; // 会话简要信息列表
  bool has_more = 2;                            // 是否还有更多数据
}

// ---- conversation/create_group ----
// 创建群聊请求
message CreateGroupReq {
  string name = 1;                    // 群名称
  repeated int64 member_uids = 2;     // 初始成员 UID 列表
}

// 创建群聊响应
message CreateGroupResp {
  string conversation_id = 1;         // 新群会话ID
  string name = 2;                    // 群名称
}

// ---- conversation/invite_member ----
// 邀请成员加入群聊
message InviteMemberReq {
  string conversation_id = 1;         // 会话ID
  repeated int64 uids = 2;            // 被邀请者 UID 列表
}

// ---- conversation/leave_group ----
// 主动退出群聊
message LeaveGroupReq {
  string conversation_id = 1;         // 会话ID
}

// ---- conversation/kick_member ----
// 踢出群成员
message KickMemberReq {
  string conversation_id = 1;         // 会话ID
  int64 uid = 2;                      // 被踢成员 UID
}

// ---- conversation/edit_group_info ----
// 编辑群资料（名称/头像）
message EditGroupReq {
  string conversation_id = 1;         // 会话ID
  optional string name = 2;           // 新群名称，不传则不修改
  optional string avatar_url = 3;     // 新群头像 URL，不传则不修改
}

// ---- conversation/group_members ----
// 获取群成员列表
message GroupMembersReq {
  string conversation_id = 1;         // 会话ID
}

// 获取群成员列表响应
message GroupMembersResp {
  repeated com.nebula.chat.group.GroupMember members = 1; // 群成员列表
}

// 会话简要信息
// 未读数由客户端根据 last_read_msg_id 自行计算
message ConversationBrief {
  string conversation_id = 1;         // 会话ID
  string type = 2;                    // 会话类型：private / group
  string name = 3;                    // 会话名称
  string avatar_url = 4;              // 会话头像 URL
  int64 last_message_id = 5;          // 最后一条消息 ID
  string last_message_preview = 6;    // 最后一条消息的文本预览
  int64 last_message_ts = 7;          // 最后一条消息的时间戳
  int64 last_updated_at = 8;          // 会话最后更新时间
  int64 last_read_msg_id = 9;         // 用户在此会话的最后已读消息 ID
}

// ---- 推送事件 Payload（D-11, D-18）----

// 群创建通知
message GroupCreatedPayload {
  string conversation_id = 1;         // 新群会话 ID
  string name = 2;                    // 群名称
  int64 creator_uid = 3;              // 创建者 UID
}

// 成员加入通知
message MemberJoinedPayload {
  string conversation_id = 1;         // 会话 ID
  repeated int64 uids = 2;            // 新加入的成员 UID 列表
  int64 inviter_uid = 3;              // 邀请人 UID
}

// 成员退出通知
message MemberLeftPayload {
  string conversation_id = 1;         // 会话 ID
  int64 uid = 2;                      // 退出的成员 UID
}

// 成员被踢通知（推送给被踢者本人）
message MemberKickedPayload {
  string conversation_id = 1;         // 会话 ID
  int64 uid = 2;                      // 被踢的成员 UID
}

// 群信息更新通知
message GroupUpdatedPayload {
  string conversation_id = 1;         // 会话 ID
  optional string name = 2;           // 新群名称（不传则不修改）
  optional string avatar_url = 3;     // 新群头像 URL（不传则不修改）
}

// 群邀请通知（推送给被邀请者）
message GroupInvitedPayload {
  string conversation_id = 1;         // 会话 ID
  string name = 2;                    // 群名称
  int64 inviter_uid = 3;              // 邀请人 UID
}

// 群解散通知
message GroupDissolvedPayload {
  string conversation_id = 1;         // 会话 ID
}
```

### group/group.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.group;

option java_multiple_files = true;
option java_package = "com.nebula.chat.group";

// 群成员信息
message GroupMember {
  int64 uid = 1;                      // 成员 UID
  string username = 2;                // 成员用户名
  string display_name = 3;            // 成员显示昵称
  string avatar_url = 4;              // 成员头像 URL
  string role = 5;                    // 成员角色：owner / admin / member
  int64 joined_at = 6;                // 加入时间（毫秒时间戳）
}
```

### admin.proto

```protobuf
syntax = "proto3";

package com.nebula.chat.admin;

option java_multiple_files = true;
option java_package = "com.nebula.chat.admin";

// ---- admin/dead-letters 死信查询 ----
// 死信查询请求：分页查询死信记录，可选按状态过滤
message DeadLetterQueryReq {
  int32 page = 1;                     // 页码，从 1 开始
  int32 page_size = 2;                // 每页条数
  string status = 3;                  // 过滤状态（pending / retrying / permanent_failed / retry_success），空表示查全部
}

// 死信查询响应
message DeadLetterQueryResp {
  repeated DeadLetterItem items = 1;  // 死信记录列表
  int32 total = 2;                    // 总记录数
}

// 死信记录项
message DeadLetterItem {
  int64 id = 1;                       // 死信 ID
  int64 msg_id = 2;                   // 原始消息 ID
  string conversation_id = 3;         // 会话ID
  int64 sender_uid = 4;               // 发送者 UID
  string fail_reason = 5;             // 失败原因
  int32 fail_count = 6;               // 失败次数
  string status = 7;                  // 状态: pending / retrying / permanent_failed / retry_success
  int64 created_at = 8;               // 创建时间戳（毫秒）
}

// ---- admin/retry-dead-letter 手动重试 ----
// 手动重试死信请求
message RetryDeadLetterReq {
  int64 dead_letter_id = 1;           // 死信 ID
}

// 手动重试死信响应
message RetryDeadLetterResp {
  bool success = 1;                   // 是否重试成功
}
```
