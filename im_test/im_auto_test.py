#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nebula IM 自动化测试机器人
============================

功能：
  1. 账号初始化：支持测试账号模式 / API 自动注册模式
  2. 自动登录：执行后自动完成认证与登录操作
  3. 长连接与心跳：建立并维持 gRPC 双向流，定时发送 PING 保持会话活跃
  4. 好友请求处理：实时监听并自动同意所有好友请求
  5. 消息原样转发：监听好友及群组消息，将收到的消息原路完整转发给发送方

特性：
  - 多账号并行运行，每个账号独立 gRPC 连接与线程
  - 自动重连机制（指数退避），连接异常时自动恢复
  - 详细日志输出（带时间戳、账号标记、日志级别）
  - 支持断线后 Session 恢复（token 登录）

使用方式：
  # 1. 安装依赖
  pip install grpcio grpcio-tools protobuf

  # 2. 编译 proto
  python im_auto_test.py --build-proto

  # 3. 运行（测试模式）
  python im_auto_test.py --accounts-file accounts.json

  # 4. 运行（注册模式）
  python im_auto_test.py --mode register --count 3

  # 5. 运行（命令行传参）
  python im_auto_test.py --user user1:pass1 --user user2:pass2

配置示例 (accounts.json):
  {
    "server": "localhost:9090",
    "accounts": [
      {"username": "user1", "password": "pass1"},
      {"username": "user2", "password": "pass2"}
    ]
  }
"""

import os
import sys
import time
import json
import uuid
import queue
import signal
import logging
import threading
import subprocess
import string
import secrets
from pathlib import Path
from typing import Optional, List, Dict, Any
from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# 路径常量
# ---------------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent  # Nebula Server 项目根目录
PROTO_SRC = PROJECT_ROOT / "proto" / "src" / "main" / "proto"
PROTO_OUT = SCRIPT_DIR / "gen" / "python"  # 编译产物放在 im_test/ 下
SERVICE_PROTO = PROTO_SRC / "nebula" / "chat_service.proto"

PROTO_FILES = sorted(
    str(p.relative_to(PROTO_SRC))
    for p in PROTO_SRC.rglob("*.proto")
)

# ---------------------------------------------------------------------------
# 日志配置
# ---------------------------------------------------------------------------
class BotLogAdapter(logging.LoggerAdapter):
    """为每个 Bot 实例附加 label 标识"""

    def process(self, msg, kwargs):
        kwargs["extra"] = {"bot_label": self.extra.get("bot_label", "main")}
        return msg, kwargs


def setup_root_logger(level: int = logging.INFO) -> logging.Logger:
    logger = logging.getLogger("im_test")
    logger.setLevel(level)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(
            logging.Formatter(
                "%(asctime)s.%(msecs)03d  %(levelname)-5s  [%(bot_label)s]  %(message)s",
                datefmt="%H:%M:%S",
            )
        )
        logger.addHandler(handler)
    return logger


root_log = setup_root_logger()
main_log = BotLogAdapter(root_log, {"bot_label": "main"})

# ---------------------------------------------------------------------------
# Proto 编译
# ---------------------------------------------------------------------------
def compile_protos():
    """编译所有 proto 文件为 Python grpc stub。"""
    PROTO_OUT.mkdir(parents=True, exist_ok=True)
    init_file = PROTO_OUT / "__init__.py"
    if not init_file.exists():
        init_file.touch()

    cmd = [
        sys.executable, "-m", "grpc_tools.protoc",
        f"-I{PROTO_SRC}",
        f"--python_out={PROTO_OUT}",
        f"--grpc_python_out={PROTO_OUT}",
        *PROTO_FILES,
    ]
    main_log.info("开始编译 proto（共 %d 个文件）...", len(PROTO_FILES))
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        main_log.error("proto 编译失败:\n%s", result.stderr)
        sys.exit(1)
    main_log.info("proto 编译完成 → %s", PROTO_OUT)


# ---------------------------------------------------------------------------
# 数据结构
# ---------------------------------------------------------------------------
@dataclass
class BotConfig:
    username: str
    password: str
    label: str = ""
    server: str = "localhost:9090"

    def __post_init__(self):
        if not self.label:
            self.label = self.username


@dataclass
class BotState:
    token: str = ""
    user_id: int = 0
    logged_in: bool = False
    should_stop: threading.Event = field(default_factory=threading.Event)


# ---------------------------------------------------------------------------
# 异常类
# ---------------------------------------------------------------------------
class BotConnectionError(Exception):
    """连接异常，触发重连。"""


class BotAuthError(Exception):
    """认证异常。"""


# ---------------------------------------------------------------------------
# 主 Bot 类
# ---------------------------------------------------------------------------
class NebulaBot:
    """单个 IM 机器人实例，管理一个账号的完整交互流程。"""

    HEARTBEAT_INTERVAL = 30  # 秒
    RECONNECT_BASE_DELAY = 2  # 初始重连延迟（秒）
    RECONNECT_MAX_DELAY = 60  # 最大重连延迟（秒）

    def __init__(self, config: BotConfig):
        self.cfg = config
        self.state = BotState()
        self.log = BotLogAdapter(root_log, {"bot_label": self.cfg.label})

        # 通信通道
        self._send_queue: queue.Queue = queue.Queue()
        self._heartbeat_thread: Optional[threading.Thread] = None
        self._stream = None
        self._channel = None
        self._stub = None

        # 登录同步
        self._login_event = threading.Event()

        # 延迟加载 proto 模块（编译后导入）
        self._proto_loaded = False
        self._pb = None  # proto 模块引用容器

    # ---- Proto 懒加载 ----
    def _load_protos(self):
        if self._proto_loaded:
            return
        gen_dir = str(PROTO_OUT)
        if gen_dir not in sys.path:
            sys.path.insert(0, gen_dir)

        try:
            # pyright: ignore[reportMissingImports]
            from nebula import envelope_pb2 as epb
            from nebula import chat_service_pb2_grpc as cs_grpc
            from nebula import message_type_pb2 as mtp
            from nebula.user import user_pb2 as upb
            from nebula.friend import friend_pb2 as fpb
            from nebula.message import message_pb2 as mpb
            from nebula.common import common_pb2 as cpb

            self._pb = type("ProtoRef", (), {})()
            self._pb.envelope = epb
            self._pb.chat_service_grpc = cs_grpc  # ChatServiceStub 在此模块
            self._pb.message_type = mtp
            self._pb.user = upb
            self._pb.friend = fpb
            self._pb.message = mpb
            self._pb.common = cpb
        except ImportError as e:
            self.log.error(
                "Proto 模块导入失败: %s。请先执行: python %s --build-proto",
                e, Path(__file__).name,
            )
            raise

        self._proto_loaded = True

    @property
    def pb(self):
        self._load_protos()
        return self._pb

    # ---- 序列化辅助 ----
    def _make_envelope(self, direction: int, request_id: str = "",
                       request_method: str = "", params_bytes: bytes = b"",
                       metadata: dict = None, response=None,
                       message=None):
        """构造 Envelope 消息。"""
        pb = self.pb
        env = pb.envelope.Envelope()
        env.direction = direction
        env.request_id = request_id
        env.protocol_version = 1

        if direction == pb.envelope.Direction.Value("REQUEST"):
            req = pb.envelope.Request()
            req.method = request_method
            req.params = params_bytes
            if metadata:
                req.metadata.update(metadata)
            env.request.CopyFrom(req)
        elif response is not None:
            env.response.CopyFrom(response)
        elif message is not None:
            env.message.CopyFrom(message)

        return env

    def _make_request_envelope(self, method: str, params_msg,
                               metadata: dict = None):
        """构造 REQUEST Envelope。"""
        req_id = uuid.uuid4().hex[:12]
        if metadata is None:
            metadata = {}
        if self.state.token:
            metadata["authorization"] = self.state.token
        return self._make_envelope(
            direction=self.pb.envelope.Direction.Value("REQUEST"),
            request_id=req_id,
            request_method=method,
            params_bytes=params_msg.SerializeToString(),
            metadata=metadata,
        )

    # ---- gRPC 连接 ----
    def _connect(self):
        """建立 gRPC 连接和双向流。"""
        import grpc

        self._channel = grpc.insecure_channel(
            self.cfg.server,
            options=[
                ("grpc.keepalive_time_ms", 20000),
                ("grpc.keepalive_timeout_ms", 10000),
                ("grpc.keepalive_permit_without_calls", True),
            ],
        )

        self._stub = self.pb.chat_service_grpc.ChatServiceStub(self._channel)

        # 双向流：request_iterator 作为生成器，从队列拉取
        def request_iterator():
            while not self.state.should_stop.is_set():
                try:
                    msg = self._send_queue.get(timeout=1)
                    if msg is None:  # 停止信号
                        break
                    yield msg
                except queue.Empty:
                    continue

        self._stream = self._stub.chat(request_iterator())
        self.log.info("gRPC 双向流建立成功 → %s", self.cfg.server)

    # ---- 主循环（单一消费者） ----
    def _send_login(self):
        """发送登录请求并等待结果。"""
        pb = self.pb
        req = pb.user.LoginReq()
        req.username = self.cfg.username
        req.password = self.cfg.password
        req.device_type = pb.common.DeviceType.Value("DESKTOP")
        req.device_id = f"pybot-{self.cfg.username}-{uuid.uuid4().hex[:8]}"

        env = self._make_request_envelope("user/login", req)
        self._send_queue.put(env)
        self._login_event.clear()
        self.log.info("已发送登录请求: user=%s", self.cfg.username)

    def _on_login_response(self, resp):
        """处理登录响应（从主循环调用）。"""
        pb = self.pb
        if resp.code == 200:
            login_resp = pb.user.LoginResp()
            login_resp.ParseFromString(resp.result)

            self.state.token = login_resp.token
            self.state.user_id = login_resp.uid
            self.state.logged_in = True
            self._login_event.set()

            self.log.info(
                "登录成功: uid=%d token=%s... device=%s",
                self.state.user_id,
                self.state.token[:12],
                login_resp.device_type,
            )
        else:
            self.log.error("登录失败: code=%d msg=%s", resp.code, resp.msg)
            self._login_event.set()
            raise BotAuthError(f"登录失败 code={resp.code}")

    def run(self):
        """Bot 主运行入口（阻塞），在独立线程中调用。"""
        self._load_protos()

        while not self.state.should_stop.is_set():
            retry_delay = self.RECONNECT_BASE_DELAY
            try:
                self._connect()

                # 发送登录 -> 等待登录响应（从流中接收）
                self._send_login()

                # 启动心跳线程
                self._heartbeat_thread = threading.Thread(
                    target=self._heartbeat_loop, daemon=True
                )
                self._heartbeat_thread.start()

                # ----- 单一消费者：所有入站消息在此处理 -----
                self.log.info("进入主接收循环")
                for env in self._stream:
                    if self.state.should_stop.is_set():
                        break

                    direction = env.direction
                    if direction == self.pb.envelope.Direction.Value("RESPONSE"):
                        resp = env.response

                        # 登录响应特殊处理
                        if not self.state.logged_in and resp.method == "user/login":
                            self._on_login_response(resp)
                            continue
                        # 非登录响应：仅日志
                        if resp.code != 200:
                            self.log.warning(
                                "请求异常: method=%s code=%d msg=%s",
                                resp.method, resp.code, resp.msg,
                            )
                        else:
                            self.log.debug(
                                "请求成功: method=%s requestId=%s",
                                resp.method, env.request_id,
                            )
                    else:
                        self._handle_push_or_pong(env)

            except BotConnectionError as e:
                self.log.warning("连接错误: %s — %ds 后重连", e, retry_delay)
            except BotAuthError as e:
                self.log.error("认证错误: %s — %ds 后重试", e, retry_delay)
            except Exception as e:
                self.log.error("未预期异常: %s — %ds 后重连", e, retry_delay)
                import traceback
                self.log.error(traceback.format_exc())

            # 清理并重连
            self._cleanup_connection()
            self.state.logged_in = False

            if self.state.should_stop.is_set():
                break

            time.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, self.RECONNECT_MAX_DELAY)

        self._cleanup_connection()
        self.log.info("Bot 已停止")

    # ---- 心跳 ----
    def _heartbeat_loop(self):
        """心跳线程：定时发送 PING。"""
        self.log.info("心跳线程启动，间隔 %ds", self.HEARTBEAT_INTERVAL)
        while not self.state.should_stop.is_set():
            self.state.should_stop.wait(self.HEARTBEAT_INTERVAL)
            if self.state.should_stop.is_set():
                break
            try:
                pb = self.pb
                ping_env = self._make_envelope(
                    direction=pb.envelope.Direction.Value("PING"),
                    request_id=uuid.uuid4().hex[:12],
                )
                self._send_queue.put(ping_env)
                self.log.debug("发送 PING")
            except Exception as e:
                self.log.warning("PING 发送失败: %s", e)

    # ---- Push / Pong 处理 ----
    def _handle_push_or_pong(self, env):
        """处理非 RESPONSE 的入站消息（PUSH / PONG）。"""
        pb = self.pb
        if env.direction == pb.envelope.Direction.Value("PONG"):
            self.log.debug("收到 PONG")
            return

        if env.direction != pb.envelope.Direction.Value("PUSH"):
            return

        msg = env.message
        event = msg.eventType

        try:
            if event == pb.message_type.PushEventType.Value("FRIEND_REQUEST"):
                self._handle_friend_request(msg)
            elif event == pb.message_type.PushEventType.Value("CHAT_MESSAGE"):
                self._handle_chat_message(msg)
            elif event == pb.message_type.PushEventType.Value("FRIEND_ACCEPTED"):
                self.log.info("好友接受通知: %s", msg.content)
            elif event == pb.message_type.PushEventType.Value("LOGOUT"):
                self.log.warning("收到 LOGOUT: %s → 触发重连", msg.content)
                raise BotConnectionError("服务端 LOGOUT")
            elif event == pb.message_type.PushEventType.Value("DISCONNECT"):
                self.log.warning("收到 DISCONNECT: %s → 触发重连", msg.content)
                raise BotConnectionError("服务端 DISCONNECT")
            else:
                self.log.debug("收到推送: event=%s content=%s", event, msg.content[:50])
        except BotConnectionError:
            raise  # 重新抛出，让 run() 捕获并重连
        except Exception as e:
            self.log.error("处理推送异常: %s", e)

    def _handle_friend_request(self, msg):
        """处理好友请求：自动同意。"""
        pb = self.pb
        payload = pb.friend.FriendRequestPayload()
        payload.ParseFromString(msg.payload)

        self.log.info(
            "收到好友请求 from=%s(uid=%d) msg=%s → 自动同意",
            payload.from_username, payload.from_uid, payload.message,
        )

        accept_req = pb.friend.FriendAcceptReq()
        accept_req.request_id = payload.request_id
        env = self._make_request_envelope("friend/accept", accept_req)
        self._send_queue.put(env)
        self.log.info("已发送 friend/accept request_id=%d", payload.request_id)

    def _handle_chat_message(self, msg):
        """处理聊天消息：原样转发回发送方。"""
        pb = self.pb
        chat_msg = pb.message.ChatMessage()
        chat_msg.ParseFromString(msg.payload)

        # 避免回声循环：不回复自己发出的消息
        if chat_msg.sender_uid == self.state.user_id:
            self.log.debug("忽略自己发出的消息 msg_id=%d", chat_msg.msg_id)
            return

        self.log.info(
            "收到消息 from=%d conv=%s content=%s → 回声转发",
            chat_msg.sender_uid,
            chat_msg.conversation_id,
            chat_msg.content[:80],
        )

        # 构造回声消息
        echo_msg = pb.message.ChatMessage()
        echo_msg.conversation_id = chat_msg.conversation_id
        echo_msg.receiver_uid = chat_msg.sender_uid
        echo_msg.message_type = chat_msg.message_type
        echo_msg.content = f"[回声] {chat_msg.content}"
        echo_msg.client_ts = int(time.time() * 1000)

        env = self._make_request_envelope("chat/send", echo_msg)
        self._send_queue.put(env)

    # ---- 清理 ----
    def _cleanup_connection(self):
        """清理当前连接资源。"""
        try:
            while not self._send_queue.empty():
                try:
                    self._send_queue.get_nowait()
                except queue.Empty:
                    break

            if self._stream is not None:
                try:
                    self._stream.cancel()
                except Exception:
                    pass
                self._stream = None

            if self._channel is not None:
                try:
                    self._channel.close()
                except Exception:
                    pass
                self._channel = None
        except Exception as e:
            self.log.debug("连接清理异常（可忽略）: %s", e)

    def stop(self):
        """优雅停止。"""
        self.state.should_stop.set()
        self._send_queue.put(None)  # 解除阻塞
        self._cleanup_connection()


# ---------------------------------------------------------------------------
# 账号注册（通过渠道 API）
# ---------------------------------------------------------------------------
def register_accounts(api_url: str, count: int = 1) -> List[Dict[str, str]]:
    """
    通过指定渠道 API 自动注册新账号。

    期望 API 签名：
      POST {api_url}
        Request:  {"count": n}
        Response: {"accounts": [{"username": "...", "password": "..."}]}

    若 API 不可用，自动降级为本地随机生成（仅测试目的）。
    """
    main_log.info("尝试通过 API 注册 %d 个账号: %s", count, api_url or "(降级随机)")
    try:
        if not api_url:
            raise ValueError("未提供 API URL")
        import urllib.request
        req_data = json.dumps({"count": count}).encode("utf-8")
        http_req = urllib.request.Request(
            api_url,
            data=req_data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(http_req, timeout=10) as resp:
            data = json.loads(resp.read())
            accounts = data.get("accounts", [])
            if len(accounts) == count:
                main_log.info("API 注册成功: %d 个账号", count)
                return accounts
    except Exception as e:
        main_log.warning("API 注册失败 (%s)，降级为本地随机生成", e)

    # 降级：本地随机生成测试账号（用于开发/调试）
    main_log.info("使用本地随机生成模式（仅测试用，需服务端开放注册）")
    accounts = []
    for _ in range(count):
        suffix = secrets.token_hex(4)
        accounts.append({
            "username": f"bot_{suffix}",
            "password": secrets.token_hex(6),
        })
    return accounts


# ---------------------------------------------------------------------------
# Bot 管理器
# ---------------------------------------------------------------------------
class BotManager:
    """管理多个 Bot 实例的并行运行。"""

    def __init__(self, server: str, accounts: List[Dict[str, str]]):
        self.server = server
        self.bots: List[NebulaBot] = []
        self.threads: List[threading.Thread] = []

        for i, acc in enumerate(accounts):
            label = f"bot-{i+1}"
            cfg = BotConfig(
                username=acc["username"],
                password=acc["password"],
                label=label,
                server=server,
            )
            self.bots.append(NebulaBot(cfg))

    def start(self):
        """启动所有 Bot。"""
        main_log.info("启动 %d 个 Bot 实例...", len(self.bots))
        for i, bot in enumerate(self.bots):
            t = threading.Thread(
                target=bot.run, daemon=True, name=f"BotThread-{i}"
            )
            t.start()
            self.threads.append(t)
            time.sleep(0.5)  # 错峰登录

    def stop(self):
        """停止所有 Bot。"""
        main_log.info("正在停止所有 Bot...")
        for bot in self.bots:
            bot.stop()
        for t in self.threads:
            t.join(timeout=5)
        main_log.info("所有 Bot 已停止")

    def wait(self):
        """阻塞等待，直到被中断。"""
        try:
            while any(t.is_alive() for t in self.threads):
                time.sleep(1)
        except KeyboardInterrupt:
            main_log.info("收到中断信号")
            self.stop()


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="Nebula IM 自动化测试机器人",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 编译 proto
  python im_auto_test.py --build-proto

  # 测试模式 — 使用 JSON 配置文件
  python im_auto_test.py --accounts-file accounts.json

  # 注册模式
  python im_auto_test.py --mode register --count 2

  # 命令行直接传账号
  python im_auto_test.py --user alice:pass123 --user bob:pass456
        """,
    )
    parser.add_argument("--build-proto", action="store_true",
                        help="编译 proto 文件为 Python stub")
    parser.add_argument("--server", default="localhost:9090",
                        help="gRPC 服务器地址 (默认: localhost:9090)")
    parser.add_argument("--mode", choices=["test", "register"], default="test",
                        help="运行模式: test=已有账号, register=自动注册")
    parser.add_argument("--accounts-file", help="JSON 配置文件路径")
    parser.add_argument("--user", action="append",
                        help="命令行指定账号 (格式: username:password)")
    parser.add_argument("--register-api",
                        help="注册 API URL (POST {url}, body: {count: N})")
    parser.add_argument("--count", type=int, default=2,
                        help="注册模式下的账号数量 (默认: 2)")
    parser.add_argument("--log-level", default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                        help="日志级别 (默认: INFO)")

    args = parser.parse_args()

    # 日志级别
    root_log.setLevel(getattr(logging, args.log_level))

    # Proto 编译模式
    if args.build_proto:
        compile_protos()
        return

    # 收集账号
    accounts: List[Dict[str, str]] = []

    if args.mode == "register":
        accounts = register_accounts(args.register_api or "", args.count)
    elif args.user:
        for entry in args.user:
            parts = entry.split(":", 1)
            if len(parts) == 2:
                accounts.append({"username": parts[0], "password": parts[1]})
            else:
                main_log.warning("忽略无效的 --user: %s", entry)
    elif args.accounts_file:
        with open(args.accounts_file, "r") as f:
            config = json.load(f)
            if "server" in config:
                args.server = config["server"]
            accounts = config.get("accounts", [])
    else:
        # 默认注册模式
        main_log.info("未指定账号，启动默认注册模式")
        accounts = register_accounts("", args.count)

    if not accounts:
        main_log.error("没有可用的账号，退出")
        sys.exit(1)

    main_log.info("===== 账号就绪: %d 个 =====", len(accounts))
    for acc in accounts:
        main_log.info("  - %s / %s", acc["username"], "*" * len(acc["password"]))

    # 启动管理器
    manager = BotManager(server=args.server, accounts=accounts)
    manager.start()
    manager.wait()


if __name__ == "__main__":
    main()
