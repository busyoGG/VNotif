"""通知事件的数据模型与线协议（PC → 手机）序列化。"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(slots=True)
class Event:
    """一条桌面通知。

    key 是跨进程稳定的标识：`<D-Bus sender>:<cookie>`。同一通知被更新时
    （replaces_id 指向已有通知）会复用被更新通知的 key，并把 op 置为 update。
    """

    key: str
    sender: str
    cookie: int
    app_name: str
    app_id: str
    summary: str
    body: str
    urgency: int = 1
    replaces_id: int = 0
    pid: int | None = None
    actions: list[str] = field(default_factory=list)
    ts: float = 0.0
    server_id: int | None = None
    op: str = "notify"

    def to_wire(self, pkg_hint: str | None, *, drop_body: bool = False) -> dict:
        """转成发给手机的 NDJSON 消息。"""
        msg: dict = {
            "v": 1,
            "op": self.op,
            "key": self.key,
            "app": self.app_name,
            "app_id": self.app_id,
            "title": self.summary,
            "body": "" if drop_body else self.body,
            "urgency": self.urgency,
            "ts": round(self.ts, 3),
        }
        if pkg_hint:
            msg["pkg_hint"] = pkg_hint
        return msg
