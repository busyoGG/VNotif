"""通知事件 → 发给手机的消息：id 追踪、replaces_id 归并、过滤、建议包名。"""

from __future__ import annotations

import asyncio
import logging
from collections import OrderedDict
from dataclasses import dataclass

from .appindex import AppIndex
from .filters import Filters
from .hints import hint_for
from .model import Event
from .parse import event_from_busctl, return_value

log = logging.getLogger("vnotif.router")

# 等守护进程回执（回执里带通知 id）的最长时间。回执通常 <1ms 到达。
REPLY_TIMEOUT = 0.15
ID_INDEX_LIMIT = 512


@dataclass
class _Pending:
    event: Event
    handle: asyncio.TimerHandle | None = None


class Router:
    """消费总线消息，产出待广播的消息。"""

    def __init__(
        self,
        index: AppIndex,
        filters: Filters,
        *,
        broadcast,
        loop: asyncio.AbstractEventLoop | None = None,
        on_seen=None,
    ) -> None:
        self.index = index
        self.filters = filters
        self.broadcast = broadcast
        self.loop = loop
        self.on_seen = on_seen  # 可选观察者：每条解析成功的事件都会调用（discover CLI 用）
        self._pending: dict[tuple[str, int], _Pending] = {}
        self._id_index: OrderedDict[int, str] = OrderedDict()
        self.stats = {"seen": 0, "forwarded": 0, "dropped": 0, "updated": 0}

    # -- 总线消息入口 ------------------------------------------------------- #
    def on_message(self, msg: dict) -> None:
        pending_key, value = return_value(msg)
        if pending_key is not None:
            self._on_return(pending_key, value)
            return
        event = event_from_busctl(msg)
        if event is None:
            return
        self.stats["seen"] += 1
        pending = _Pending(event=event)
        self._pending[(event.sender, event.cookie)] = pending
        pending.handle = self._loop().call_later(REPLY_TIMEOUT, self._finalize, pending, None)

    def on_event(self, event: Event) -> None:
        """兜底路径（dbus-monitor）用：直接喂一条已解析好的事件。"""
        self.stats["seen"] += 1
        self._finalize(_Pending(event=event), None)

    def _loop(self) -> asyncio.AbstractEventLoop:
        return self.loop or asyncio.get_running_loop()

    def _on_return(self, pending_key: tuple[str, int], value) -> None:
        pending = self._pending.pop(pending_key, None)
        if pending is None:
            return
        if pending.handle is not None:
            pending.handle.cancel()
        server_id = int(value) if isinstance(value, (int, float)) else None
        self._finalize(pending, server_id)

    # -- 归并与派发 --------------------------------------------------------- #
    def _finalize(self, pending: _Pending, server_id: int | None) -> None:
        self._pending.pop((pending.event.sender, pending.event.cookie), None)
        event = pending.event
        if pending.handle is not None:
            pending.handle.cancel()
            pending.handle = None

        if event.replaces_id and event.replaces_id in self._id_index:
            event.key = self._id_index[event.replaces_id]
            event.op = "update"
            self.stats["updated"] += 1
        if server_id is not None:
            event.server_id = server_id
            self._remember_id(server_id, event.key)

        self.index.touch(event.app_id, event.app_name)
        if self.on_seen is not None:
            self.on_seen(event)
        reason = self.filters.drop_reason(event)
        if reason:
            self.stats["dropped"] += 1
            log.debug("丢弃通知 app=%s 原因=%s", event.app_id or event.app_name, reason)
            return
        pkg = hint_for(event.app_id, event.app_name)
        self.stats["forwarded"] += 1
        log.info(
            "转发 [%s] %s: %s",
            event.app_id or event.app_name,
            event.op,
            (event.summary or event.body)[:60],
        )
        self.broadcast(event.to_wire(pkg))

    def _remember_id(self, server_id: int, key: str) -> None:
        self._id_index[server_id] = key
        self._id_index.move_to_end(server_id)
        while len(self._id_index) > ID_INDEX_LIMIT:
            self._id_index.popitem(last=False)
