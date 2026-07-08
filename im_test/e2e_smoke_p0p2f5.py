#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nebula 端到端冒烟（P0/F1 互踢写穿 + F5 推送隔离）。

复用 im_auto_test.NebulaBot 的连接/登录/心跳/推送辅助方法，
在 _Bot 内覆盖 run() 的流消费循环（加入 create_group 响应捕获），记录关键事件并断言：
  - P0/F1: 同账号同 device_type 二次登录触发 eviction，旧连接必须收到 DISCONNECT PUSH
            （验证 F1 的 serverScope.launch 异步写穿，而非被 connectionScope.cancel 取消）
  - F5:    发送方给同为会话成员的接收方发消息，接收方在线必须收到 CHAT_MESSAGE PUSH
            （验证推送挂在 serverScope，不随发送方请求上下文取消而丢失）

用法：python e2e_smoke_p0p2f5.py
"""
import sys
import os
import time
import threading
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from im_auto_test import NebulaBot, BotConfig  # noqa
from nebula.chat import chat_pb2 as chat_pb
from nebula.conversation import conversation_pb2 as conv_pb


def make_bot(username, password, label, captured):
    """构造带事件捕获的 Bot。captured 为 dict，记录 DISCONNECT / CHAT_MESSAGE / conv_id。"""
    cfg = BotConfig(username=username, password=password, server="localhost:9090", label=label)

    class _Bot(NebulaBot):
        def _handle_push_or_pong(self, env):
            if env.direction == self.pb.envelope.Direction.Value("PUSH"):
                et = env.message.eventType
                if et == self.pb.message_type.PushEventType.Value("DISCONNECT"):
                    captured["DISCONNECT"] = env.message.content
                    print(f"[{self.cfg.label}] 收到 DISCONNECT PUSH: {env.message.content}")
                elif et == self.pb.message_type.PushEventType.Value("CHAT_MESSAGE"):
                    captured["CHAT_MESSAGE"] = env.message.content
                    print(f"[{self.cfg.label}] 收到 CHAT_MESSAGE PUSH: {env.message.content}")
            return

        def run(self):
            """精简消费循环：登录 → 心跳 → 捕获 RESPONSE(PUSH/特定响应)，不重连。"""
            self._load_protos()
            while not self.state.should_stop.is_set():
                try:
                    self._connect()
                    self._send_login()
                    self._heartbeat_thread = threading.Thread(
                        target=self._heartbeat_loop, daemon=True)
                    self._heartbeat_thread.start()
                    for env in self._stream:
                        if self.state.should_stop.is_set():
                            break
                        direction = env.direction
                        if direction == self.pb.envelope.Direction.Value("RESPONSE"):
                            resp = env.response
                            if not self.state.logged_in and resp.method == "user/login":
                                self._on_login_response(resp)
                                continue
                            # 捕获建群响应
                            if resp.method == "conversation/create_group" and resp.code == 200:
                                cr = conv_pb.CreateGroupResp()
                                cr.ParseFromString(resp.result)
                                captured["conv_id"] = cr.conversation_id
                                print(f"[{self.cfg.label}] 建群成功 conv={cr.conversation_id}")
                        else:
                            self._handle_push_or_pong(env)
                except Exception as e:
                    self.log.error("消费循环异常: %s", e)
                self._cleanup_connection()
                self.state.logged_in = False
                if self.state.should_stop.is_set():
                    break
                time.sleep(0.5)
            self._cleanup_connection()
            self.log.info("Bot 已停止")

    return _Bot(cfg)


def start_bot(bot):
    t = threading.Thread(target=bot.run, daemon=True)
    t.start()
    bot._login_event.wait(10)
    if not bot.state.logged_in:
        raise RuntimeError(f"{bot.cfg.label} 登录失败 / 超时")
    return bot.state.token


def main():
    results = {}

    # ===== 场景 1: P0/F1 互踢写穿 =====
    print("=== 场景1: P0/F1 互踢写穿 (同账号同设备二次登录) ===")
    capA = {}
    botA = make_bot("test_user1", "123456", "U1-A", capA)
    start_bot(botA)
    print(f"[A] test_user1 登录成功 token={botA.state.token[:12]}...")

    # 同 device_type 二次登录，触发 eviction 踢掉 A
    capB = {}
    botB = make_bot("test_user1", "123456", "U1-B", capB)
    start_bot(botB)
    print(f"[B] test_user1 二次登录成功 token={botB.state.token[:12]}... (应触发 A 被踢)")

    deadline = time.time() + 8
    while "DISCONNECT" not in capA and time.time() < deadline:
        time.sleep(0.2)
    got = "DISCONNECT" in capA
    results["P0_F1_disconnect_written"] = got
    print(f"[结果] A 收到 DISCONNECT 写穿: {'PASS' if got else 'FAIL'}")

    botA.state.should_stop.set()
    botB.state.should_stop.set()
    try: botA._cleanup_connection()
    except Exception: pass
    try: botB._cleanup_connection()
    except Exception: pass
    time.sleep(1)

    # ===== 场景 2: F5 推送隔离 =====
    print("\n=== 场景2: F5 推送隔离 (user1 -> 群(user1,user2) 发消息, user2 收 PUSH) ===")
    capS = {}
    capR = {}
    sender = make_bot("test_user1", "123456", "SENDER", capS)
    receiver = make_bot("test_user2", "123456", "RECEIVER", capR)
    start_bot(sender)
    start_bot(receiver)
    print(f"[S] test_user1 登录, [R] test_user2 登录")

    # 先建群，把 user1 / user2 都拉为成员，拿到合法 conversation_id
    create_req = conv_pb.CreateGroupReq()
    create_req.name = f"e2e-group-{uuid.uuid4().hex[:6]}"
    create_req.member_uids.extend([346663006337241088])
    create_env = sender._make_request_envelope("conversation/create_group", create_req)
    sender._send_queue.put(create_env)

    deadline = time.time() + 6
    while "conv_id" not in capS and time.time() < deadline:
        time.sleep(0.2)
    if "conv_id" not in capS:
        raise RuntimeError("建群失败，未拿到 conversation_id")
    conv_id = capS["conv_id"]

    # 用合法会话发消息
    send_req = chat_pb.SendMessageReq()
    send_req.conversation_id = conv_id
    send_req.content = f"hello-from-e2e-{uuid.uuid4().hex[:6]}"
    send_req.client_message_id = uuid.uuid4().hex[:12]
    env = sender._make_request_envelope("chat/send", send_req)
    sender._send_queue.put(env)
    print(f"[S] 已发送 chat/send conv={conv_id}")

    deadline = time.time() + 8
    while "CHAT_MESSAGE" not in capR and time.time() < deadline:
        time.sleep(0.2)
    got_push = "CHAT_MESSAGE" in capR
    results["F5_push_received"] = got_push
    print(f"[结果] R 收到 CHAT_MESSAGE 推送: {'PASS' if got_push else 'FAIL'}")

    sender.state.should_stop.set()
    receiver.state.should_stop.set()
    try: sender._cleanup_connection()
    except Exception: pass
    try: receiver._cleanup_connection()
    except Exception: pass

    # ===== 汇总 =====
    print("\n=== 汇总 ===")
    all_pass = all(results.values())
    for k, v in results.items():
        print(f"  {k}: {'PASS' if v else 'FAIL'}")
    print(f"OVERALL: {'ALL PASS' if all_pass else 'HAS FAILURE'}")
    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
