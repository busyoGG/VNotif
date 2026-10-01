"""通知过滤（黑名单）。"""

from __future__ import annotations

import re
from collections.abc import Iterable
from fnmatch import fnmatchcase

from .model import Event


def _norm(value: str) -> str:
    return (value or "").strip().casefold().removesuffix(".desktop")


def _is_glob(entry: str) -> bool:
    return any(ch in entry for ch in "*?[")


class Filters:
    """按应用名 / desktop-entry 匹配（精确或 glob），或按标题正文正则过滤。"""

    def __init__(self, apps: Iterable[str] = (), body_regex: Iterable[str] = ()):
        self.apps: set[str] = set()
        self.globs: list[str] = []
        for raw in apps:
            if not raw or not raw.strip():
                continue
            if _is_glob(raw):
                self.globs.append(_norm(raw))
            else:
                self.apps.add(_norm(raw))
        self.patterns: list[re.Pattern[str]] = []
        for expr in body_regex:
            if not expr or not expr.strip():
                continue
            self.patterns.append(re.compile(expr))

    def blocks_app(self, *names: str) -> str | None:
        """返回命中的那条黑名单条目（精确项或 glob），没命中返回 None。

        QQ / KDE Connect 这类应用在不同路径下上报的标识不同，一条 `*kdeconnect*`
        比写五个别名可靠，所以匹配要支持通配符。
        """
        for raw in names:
            norm = _norm(raw)
            if not norm:
                continue
            if norm in self.apps:
                return norm
            for pattern in self.globs:
                if fnmatchcase(norm, pattern):
                    return pattern
        return None

    def drop_reason(self, ev: Event) -> str | None:
        """返回丢弃原因；None 表示应当转发。"""
        hit = self.blocks_app(ev.app_name, ev.app_id)
        if hit:
            return f"黑名单: {hit}"
        if not ev.summary.strip() and not ev.body.strip():
            return "空通知（无标题无正文）"
        for pattern in self.patterns:
            if pattern.search(ev.summary) or pattern.search(ev.body):
                return f"正文命中正则: {pattern.pattern}"
        return None
