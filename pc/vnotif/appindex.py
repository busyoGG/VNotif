"""记录"见过的桌面应用"，用于给手机端映射界面预填列表。"""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path

from .hints import hint_for

log = logging.getLogger("vnotif.appindex")


def default_path() -> Path:
    """缓存文件位置（XDG）。只用来记住"见过哪些应用"，丢了不影响转发。"""
    base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    return Path(base) / "vnotif" / "apps.json"


class AppIndex:
    def __init__(self, limit: int = 300, path: Path | None = None) -> None:
        self._apps: dict[str, dict] = {}
        self.limit = limit
        self.path = path
        self._dirty = False
        if path is not None:
            self._load()

    @staticmethod
    def key_of(app_id: str, app_name: str) -> str:
        return (app_id or app_name or "unknown").strip()

    def touch(self, app_id: str, app_name: str) -> None:
        key = self.key_of(app_id, app_name)
        rec = self._apps.get(key)
        if rec is None:
            rec = {
                "app": app_name or app_id,
                "app_id": app_id or app_name,
                "pkg_hint": hint_for(app_id, app_name) or "",
                "seen": 0,
                "last": 0.0,
            }
            self._apps[key] = rec
        if app_name and not rec["app"]:
            rec["app"] = app_name
        rec["seen"] += 1
        rec["last"] = time.time()
        if not rec["pkg_hint"]:
            rec["pkg_hint"] = hint_for(app_id, app_name) or ""
        if len(self._apps) > self.limit:
            oldest = min(self._apps, key=lambda k: self._apps[k]["last"])
            self._apps.pop(oldest, None)
        self._dirty = True

    def snapshot(self) -> list[dict]:
        return sorted(self._apps.values(), key=lambda r: r["last"], reverse=True)

    def _load(self) -> None:
        """尽力而为地恢复历史索引；文件坏了就当作空的，绝不因此起不来。"""
        try:
            raw = self.path.read_text(encoding="utf-8")
        except OSError:
            return
        try:
            data = json.loads(raw)
        except ValueError:
            log.warning("应用索引缓存损坏，忽略：%s", self.path)
            return
        if not isinstance(data, dict):
            return
        for key, rec in data.items():
            if not isinstance(rec, dict) or not key:
                continue
            self._apps[str(key)] = {
                "app": str(rec.get("app", key)),
                "app_id": str(rec.get("app_id", key)),
                "pkg_hint": str(rec.get("pkg_hint", "")),
                "seen": int(rec.get("seen", 0) or 0),
                "last": float(rec.get("last", 0.0) or 0.0),
            }
        while len(self._apps) > self.limit:
            oldest = min(self._apps, key=lambda k: self._apps[k]["last"])
            self._apps.pop(oldest, None)

    def flush(self) -> None:
        """脏了才落盘；原子替换，避免写一半被杀留下坏文件。"""
        if self.path is None or not self._dirty:
            return
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.parent / (self.path.name + ".tmp")
            tmp.write_text(json.dumps(self._apps, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, self.path)
            self._dirty = False
        except OSError as exc:
            log.warning("写应用索引缓存失败: %s", exc)
