"""回归脚本：发一条"超大"通知（hints 里塞几百 KB，模拟带 image-data 应用图标的通知）。

为什么需要它：asyncio 子进程流的 readline 默认上限 64KiB，而一条带图标（image-data）
的桌面通知，JSON 化后轻易超过 100KB。曾经的 bug 是：遇到这种消息时 busctl 监听任务抛
ValueError 静默退出，「服务还在跑、日志也正常，但再也收不到任何通知」。

用法：
    python3 tools/send_big_notification.py [字节数]     # 默认 200000

验证方式：跑完之后 `python3 -m vnotif selftest` 仍应通过（或直接看服务端日志出现
“转发 [vnotif-bigtest]”）。
"""

from __future__ import annotations

import sys

import dbus


def main() -> int:
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 200_000
    bus = dbus.SessionBus()
    obj = bus.get_object("org.freedesktop.Notifications", "/org/freedesktop/Notifications")
    notifier = dbus.Interface(obj, "org.freedesktop.Notifications")
    hints = {
        "urgency": dbus.Byte(1),
        "big-payload": "x" * size,  # 单行 JSON 会因此膨胀到 ~size 字节
    }
    nid = notifier.Notify(
        "vnotif-bigtest",
        0,
        "",
        "超大通知回归",
        f"hints 里带了 {size} 字节负载，用来验证采集端不会被撑死",
        dbus.Array([], signature="s"),
        hints,
        -1,
    )
    print(f"已发送通知 id={int(nid)}，单行 JSON 约 {size} 字节（asyncio 默认上限 65536）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
