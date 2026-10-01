"""把 D-Bus 总线上的消息解析成通知事件。

两条采集路径：
- 主路径：`busctl --user monitor --json=short` 的逐行 JSON（字段最全）。
- 兜底：`dbus-monitor --session` 的文本输出（busctl 不存在时使用）。

本模块只做纯解析，不起进程，便于单元测试。
"""

from __future__ import annotations

import json
import re

from .model import Event

NOTIFY_SIGNATURE = "susssasa{sv}i"
IFACE = "org.freedesktop.Notifications"


# --------------------------------------------------------------------------- #
# 主路径：busctl --json=short
# --------------------------------------------------------------------------- #
def loads_busctl_line(line: str) -> dict | None:
    line = line.strip()
    if not line.startswith("{"):
        return None
    try:
        obj = json.loads(line)
    except ValueError:
        return None
    return obj if isinstance(obj, dict) else None


def _hint(hints: dict, key: str):
    """busctl 的 hints 值是 {"type": "y", "data": 1} 这种包装。"""
    raw = hints.get(key)
    if isinstance(raw, dict):
        return raw.get("data")
    return raw


def event_from_busctl(msg: dict) -> Event | None:
    if msg.get("member") != "Notify" or msg.get("type") != "method_call":
        return None
    payload = msg.get("payload") or {}
    data = payload.get("data")
    if not isinstance(data, list) or len(data) < 8:
        return None
    app_name, replaces_id, app_icon, summary, body, actions, hints, _timeout = data[:8]
    hints = hints if isinstance(hints, dict) else {}
    pid = _hint(hints, "sender-pid")
    urgency = _hint(hints, "urgency")
    desktop_entry = _hint(hints, "desktop-entry") or ""
    return Event(
        key=f"{msg.get('sender')}:{msg.get('cookie')}",
        sender=str(msg.get("sender") or ""),
        cookie=int(msg.get("cookie") or 0),
        app_name=str(app_name or desktop_entry or "未知应用"),
        app_id=str(desktop_entry or app_name or ""),
        summary=str(summary or ""),
        body=str(body or ""),
        urgency=int(urgency) if isinstance(urgency, (int, float)) else 1,
        replaces_id=int(replaces_id or 0),
        pid=int(pid) if isinstance(pid, (int, float)) else None,
        actions=[str(a) for a in actions] if isinstance(actions, list) else [],
        ts=float(msg.get("timestamp-realtime") or 0) / 1e6,
    )


def return_value(msg: dict) -> tuple[tuple[str, int] | None, object]:
    """method_return → ((目的地唯一名, reply_cookie), payload 第一个值)。"""
    if msg.get("type") != "method_return":
        return None, None
    cookie = msg.get("reply_cookie")
    dest = msg.get("destination")
    if cookie is None or dest is None:
        return None, None
    data = (msg.get("payload") or {}).get("data") or []
    return (str(dest), int(cookie)), (data[0] if data else None)


# --------------------------------------------------------------------------- #
# 兜底路径：dbus-monitor 文本
# --------------------------------------------------------------------------- #
_HEADER_RE = re.compile(
    r"^(?P<kind>method call|method return|signal) time=(?P<time>[\d.]+) "
    r"sender=(?P<sender>\S+) -> destination=(?P<dest>\S+) serial=(?P<serial>\d+) "
    r"path=(?P<path>\S+); interface=(?P<iface>\S+); member=(?P<member>\S+)"
)
_CLOSERS = {"]", ")", "}"}
_STRING_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}


def _unquote(text: str) -> str:
    text = text.strip()
    if not (text.startswith('"') and text.endswith('"') and len(text) >= 2):
        return text
    body = text[1:-1]
    out: list[str] = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            if nxt in _STRING_ESCAPES:
                out.append(_STRING_ESCAPES[nxt])
                i += 2
                continue
            out.append(nxt)
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _parse_scalar(text: str):
    text = text.strip()
    if text.startswith("string "):
        return _unquote(text[len("string "):])
    parts = text.split(None, 1)
    if len(parts) == 2:
        type_, literal = parts
        if type_ in ("byte", "uint16", "int16", "uint32", "int32", "uint64", "int64"):
            try:
                return int(literal)
            except ValueError:
                return 0
        if type_ == "boolean":
            return literal == "true"
        if type_ == "double":
            try:
                return float(literal)
            except ValueError:
                return 0.0
        if type_ in ("object path", "signature"):
            return literal
    return text


def _parse_value(lines: list[str], i: int, indent: int):
    """解析 lines[i]（缩进 indent）处的值，返回 (value, next_index)。"""
    return _parse_text(lines, i, indent, lines[i].strip())


def _parse_text(lines: list[str], i: int, indent: int, text: str):
    if text.startswith("variant"):
        return _parse_text(lines, i, indent, text[len("variant"):].strip())
    if text.startswith("dict entry"):
        entries, j = _parse_block(lines, i + 1, indent + 3)
        j = _skip_closer(lines, j)
        if len(entries) >= 2:
            return (entries[0], entries[1]), j
        return (entries[0] if entries else None, None), j
    for opener, closer in (("array", "]"), ("struct", "}")):
        if text.startswith(opener):
            values, j = _parse_block(lines, i + 1, indent + 3)
            return values, _skip_closer(lines, j)
    return _parse_scalar(text), i + 1


def _skip_closer(lines: list[str], i: int) -> int:
    if i < len(lines) and lines[i].strip() in _CLOSERS:
        return i + 1
    return i


def _parse_block(lines: list[str], i: int, indent: int) -> tuple[list, int]:
    values: list = []
    while i < len(lines):
        raw = lines[i]
        if not raw.strip():
            i += 1
            continue
        ind = _indent_of(raw)
        if ind < indent or (ind == indent and raw.strip() in _CLOSERS):
            break
        if ind > indent:
            i += 1
            continue
        value, i = _parse_value(lines, i, indent)
        values.append(value)
    return values, i


def events_from_dbus_monitor(text: str) -> list[Event]:
    """解析 dbus-monitor 文本，返回其中的 Notify 调用。"""

    def as_int(value, default=None):
        return int(value) if isinstance(value, (int, float)) else default

    lines = text.splitlines()
    events: list[Event] = []
    i = 0
    while i < len(lines):
        header = _HEADER_RE.match(lines[i].strip())
        if not header:
            i += 1
            continue
        block_start = i + 1
        j = block_start
        while j < len(lines) and (_indent_of(lines[j]) >= 3 or not lines[j].strip()):
            j += 1
        block = lines[block_start:j]
        i = j
        if header.group("kind") != "method call" or header.group("member") != "Notify":
            continue
        args, _ = _parse_block(block, 0, 3)
        if len(args) < 8:
            continue
        app_name, replaces_id, _icon, summary, body, actions, hints, _timeout = args[:8]
        hint_map = dict(hints) if isinstance(hints, list) else {}
        pid = as_int(hint_map.get("sender-pid"))
        desktop_entry = hint_map.get("desktop-entry") or ""
        events.append(
            Event(
                key=f"{header.group('sender')}:{header.group('serial')}",
                sender=header.group("sender"),
                cookie=int(header.group("serial")),
                app_name=str(app_name or desktop_entry or "未知应用"),
                app_id=str(desktop_entry or app_name or ""),
                summary=str(summary or ""),
                body=str(body or ""),
                urgency=as_int(hint_map.get("urgency"), 1),
                replaces_id=int(replaces_id or 0),
                pid=pid,
                actions=[str(a) for a in actions] if isinstance(actions, list) else [],
                ts=float(header.group("time")),
            )
        )
    return events
