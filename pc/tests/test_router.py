"""Router 单测：replaces_id 归并、id 追踪、过滤与广播。"""

from __future__ import annotations

import asyncio
import json
import unittest

from vnotif.appindex import AppIndex
from vnotif.filters import Filters
from vnotif.router import Router

NOTIFY_TMPL = (
    '{{"type":"method_call","cookie":{cookie},"timestamp-realtime":{ts},"sender":"{sender}",'
    '"destination":":1.49","path":"/org/freedesktop/Notifications",'
    '"interface":"org.freedesktop.Notifications","member":"Notify",'
    '"payload":{{"type":"susssasa{{sv}}i","data":["{app}",{replaces},"","{title}","{body}",[],'
    '{{"urgency":{{"type":"y","data":1}},"sender-pid":{{"type":"x","data":100}}}},-1]}}}}'
)
RETURN_TMPL = (
    '{{"type":"method_return","reply_cookie":{cookie},"sender":":1.49",'
    '"destination":"{sender}","payload":{{"type":"u","data":[{nid}]}}}}'
)


def notify(cookie: int, app: str, title: str, *, replaces: int = 0, sender: str = ":1.9", ts: int = 100) -> dict:
    return json.loads(NOTIFY_TMPL.format(cookie=cookie, app=app, title=title, body="b", replaces=replaces, sender=sender, ts=ts))


def ret(cookie: int, nid: int, sender: str = ":1.9") -> dict:
    return json.loads(RETURN_TMPL.format(cookie=cookie, nid=nid, sender=sender))


class TestRouter(unittest.TestCase):
    def setUp(self):
        self.loop = asyncio.new_event_loop()
        self.sent: list[dict] = []
        self.router = Router(
            AppIndex(),
            Filters(),
            broadcast=self.sent.append,
            loop=self.loop,
        )

    def tearDown(self):
        self.loop.close()

    def test_plain_forward(self):
        self.router.on_message(notify(1, "Element", "你好"))
        self.router.on_message(ret(1, 42))
        self.assertEqual(len(self.sent), 1)
        msg = self.sent[0]
        self.assertEqual(msg["op"], "notify")
        self.assertEqual(msg["app"], "Element")
        self.assertEqual(msg["title"], "你好")
        self.assertEqual(msg["pkg_hint"], "im.vector.app")

    def test_replaces_becomes_update_on_same_key(self):
        self.router.on_message(notify(1, "Element", "第一条"))
        self.router.on_message(ret(1, 42))
        # 应用拿着 id=42 更新同一条通知
        self.router.on_message(notify(2, "Element", "第二条", replaces=42))
        self.router.on_message(ret(2, 43))
        self.assertEqual(len(self.sent), 2)
        first, second = self.sent
        self.assertEqual(first["op"], "notify")
        self.assertEqual(second["op"], "update")
        self.assertEqual(second["key"], first["key"])
        self.assertEqual(second["title"], "第二条")

    def test_unknown_replaces_stays_new(self):
        self.router.on_message(notify(1, "Discord", "新消息", replaces=999))
        self.router.on_message(ret(1, 7))
        self.assertEqual(self.sent[0]["op"], "notify")

    def test_reply_without_pending_is_ignored(self):
        self.router.on_message(ret(1234, 5))
        self.assertEqual(self.sent, [])

    def test_reply_must_match_sender(self):
        self.router.on_message(notify(1, "Element", "x"))
        self.router.on_message(ret(1, 42, sender=":1.99"))  # 冒充的回执
        self.assertEqual(self.sent, [])
        # 真正的回执到达后仍然可以派发（等超时也会派发，这里手动触发）
        self.router.on_message(ret(1, 42))
        self.assertEqual(len(self.sent), 1)

    def test_blacklist_drops(self):
        router = Router(
            AppIndex(),
            Filters(["Element"]),
            broadcast=self.sent.append,
            loop=self.loop,
        )
        router.on_message(notify(1, "Element", "不该出现"))
        router.on_message(notify(2, "Discord", "该出现"))
        router.on_message(ret(2, 1))
        titles = [m["title"] for m in self.sent]
        self.assertEqual(titles, ["该出现"])

    def test_body_regex_drops(self):
        router = Router(
            AppIndex(),
            Filters([], [r"^音量"]),
            broadcast=self.sent.append,
            loop=self.loop,
        )
        router.on_message(notify(1, "System", "音量 30%"))
        self.assertEqual(self.sent, [])

    def test_on_seen_observer_and_index(self):
        seen = []
        router = Router(
            AppIndex(),
            Filters(),
            broadcast=self.sent.append,
            loop=self.loop,
            on_seen=seen.append,
        )
        router.on_message(notify(1, "Element", "hi"))
        router.on_message(ret(1, 5))
        self.assertEqual(len(seen), 1)
        self.assertEqual([row["app_id"] for row in router.index.snapshot()], ["Element"])

    def test_event_fallback_path(self):
        from vnotif.model import Event

        self.router.on_event(Event(key="k", sender=":1.1", cookie=1, app_name="A", app_id="A", summary="s", body="b"))
        self.assertEqual(self.sent[0]["op"], "notify")
        self.assertEqual(self.sent[0]["key"], "k")


if __name__ == "__main__":
    unittest.main()
