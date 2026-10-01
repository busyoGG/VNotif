"""解析层单测：样本全部来自本机真实抓取（busctl / dbus-monitor）。"""

from __future__ import annotations

import json
import unittest

from vnotif.parse import event_from_busctl, events_from_dbus_monitor, loads_busctl_line

# 真实抓取：notify-send -a probe5 probe5-summary probe5-body
BUSCTL_PROBE = (
    '{"type":"method_call","endian":"l","flags":0,"version":1,"cookie":9,'
    '"timestamp-realtime":1790699532127421,"sender":":1.142422",'
    '"destination":":1.49","path":"/org/freedesktop/Notifications",'
    '"interface":"org.freedesktop.Notifications","member":"Notify",'
    '"payload":{"type":"susssasa{sv}i","data":["probe5",0,"","probe5-summary",'
    '"probe5-body",[],{"urgency":{"type":"y","data":1},'
    '"sender-pid":{"type":"x","data":2525074}},-1]}}'
)

# 真实抓取：Element 的通知（hints 里带 desktop-entry）
BUSCTL_ELEMENT = (
    '{"type":"method_call","cookie":1172,"timestamp-realtime":1790699162898775,'
    '"sender":":1.91","destination":":1.49",'
    '"path":"/org/freedesktop/Notifications",'
    '"interface":"org.freedesktop.Notifications","member":"Notify",'
    '"payload":{"type":"susssasa{sv}i","data":["Element",0,"","白白白 (随便观)",'
    '"哎我靠我 matter 的双击放大去哪了",["default","View"],'
    '{"sender-pid":{"type":"x","data":3805},"desktop-entry":{"type":"s","data":"Element"},'
    '"urgency":{"type":"y","data":1}},-1]}}'
)

RETURN_SAMPLE = (
    '{"type":"method_return","reply_cookie":9,"sender":":1.49",'
    '"destination":":1.142422","payload":{"type":"u","data":[42]}}'
)

# 真实抓取：dbus-monitor 文本（兜底路径）
DBUS_MONITOR_TEXT = """\
method call time=1790699532.127440 sender=:1.142422 -> destination=:1.49 serial=9 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
   string "probe5"
   uint32 0
   string ""
   string "probe5-summary"
   string "probe5-body"
   array [
   ]
   array [
      dict entry(
         string "urgency"
         variant             byte 1
      )
      dict entry(
         string "sender-pid"
         variant             int64 2525074
      )
   ]
   int32 -1
"""


class TestBusctlParsing(unittest.TestCase):
    def test_loads_line(self):
        self.assertIsNotNone(loads_busctl_line(BUSCTL_PROBE))
        self.assertIsNone(loads_busctl_line("not json"))
        self.assertIsNone(loads_busctl_line(""))

    def test_probe_event(self):
        event = event_from_busctl(json.loads(BUSCTL_PROBE))
        assert event is not None
        self.assertEqual(event.key, ":1.142422:9")
        self.assertEqual(event.app_name, "probe5")
        self.assertEqual(event.app_id, "probe5")
        self.assertEqual(event.summary, "probe5-summary")
        self.assertEqual(event.body, "probe5-body")
        self.assertEqual(event.pid, 2525074)
        self.assertEqual(event.urgency, 1)
        self.assertEqual(event.replaces_id, 0)
        self.assertAlmostEqual(event.ts, 1790699532.127, places=2)

    def test_element_prefers_desktop_entry(self):
        event = event_from_busctl(json.loads(BUSCTL_ELEMENT))
        assert event is not None
        self.assertEqual(event.app_id, "Element")
        self.assertEqual(event.app_name, "Element")
        self.assertEqual(event.pid, 3805)
        self.assertEqual(event.actions, ["default", "View"])

    def test_non_notify_ignored(self):
        self.assertIsNone(event_from_busctl({"type": "method_call", "member": "GetServerInformation"}))


class TestDbusMonitorFallback(unittest.TestCase):
    def test_text_block(self):
        events = events_from_dbus_monitor(DBUS_MONITOR_TEXT)
        self.assertEqual(len(events), 1)
        event = events[0]
        self.assertEqual(event.key, ":1.142422:9")
        self.assertEqual(event.summary, "probe5-summary")
        self.assertEqual(event.body, "probe5-body")
        self.assertEqual(event.pid, 2525074)
        self.assertEqual(event.urgency, 1)
        self.assertAlmostEqual(event.ts, 1790699532.127, places=3)

    def test_text_with_container_hints_does_not_crash(self):
        text = DBUS_MONITOR_TEXT.replace(
            '         variant             byte 1\n',
            '         variant             struct {\n'
            '            int32 96\n'
            '            int32 96\n'
            '         }\n',
        )
        events = events_from_dbus_monitor(text)
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].summary, "probe5-summary")

    def test_ignores_other_messages(self):
        text = (
            "method call time=1.0 sender=:1.2 -> destination=:1.3 serial=1 "
            "path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; "
            "member=GetServerInformation\n"
        )
        self.assertEqual(events_from_dbus_monitor(text), [])


if __name__ == "__main__":
    unittest.main()
