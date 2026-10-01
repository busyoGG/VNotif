"""AppIndex 持久化单测：落盘 / 读回 / 淘汰 / 坏文件容错。"""

from __future__ import annotations

import json
import time

from vnotif import appindex
from vnotif.appindex import AppIndex, default_path


def _by_id(index: AppIndex) -> dict[str, dict]:
    return {row["app_id"]: row for row in index.snapshot()}


def test_touch_flush_roundtrip(tmp_path):
    path = tmp_path / "apps.json"
    idx = AppIndex(path=path)
    idx.touch("Element", "Element")
    idx.touch("Discord", "Discord")
    idx.flush()

    assert path.exists()
    again = AppIndex(path=path)
    assert _by_id(again) == _by_id(idx)
    assert _by_id(again)["Element"]["seen"] == 1


def test_load_evicts_over_limit(tmp_path):
    path = tmp_path / "apps.json"
    path.write_text(
        json.dumps(
            {
                "old": {"app": "old", "app_id": "old", "seen": 1, "last": 1.0},
                "mid": {"app": "mid", "app_id": "mid", "seen": 1, "last": 2.0},
                "new": {"app": "new", "app_id": "new", "seen": 1, "last": 3.0},
            }
        ),
        encoding="utf-8",
    )

    idx = AppIndex(limit=2, path=path)

    assert set(_by_id(idx)) == {"mid", "new"}


def test_touch_evicts_oldest_over_limit(tmp_path):
    path = tmp_path / "apps.json"
    idx = AppIndex(limit=2, path=path)
    idx.touch("A", "A")
    time.sleep(0.002)
    idx.touch("B", "B")
    time.sleep(0.002)
    idx.touch("C", "C")

    assert set(_by_id(idx)) == {"B", "C"}


def test_corrupt_cache_is_ignored(tmp_path):
    path = tmp_path / "apps.json"
    path.write_text("{ this is not json", encoding="utf-8")

    idx = AppIndex(path=path)  # 不应抛异常

    assert idx.snapshot() == []


def test_non_dict_cache_is_ignored(tmp_path):
    path = tmp_path / "apps.json"
    path.write_text("[1, 2, 3]", encoding="utf-8")

    idx = AppIndex(path=path)

    assert idx.snapshot() == []


def test_path_none_never_writes(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path))
    idx = AppIndex()
    idx.touch("Element", "Element")
    idx.flush()

    assert not (tmp_path / "vnotif" / "apps.json").exists()


def test_default_path_uses_xdg_cache_home(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path))
    assert default_path() == tmp_path / "vnotif" / "apps.json"
    assert appindex.default_path() == tmp_path / "vnotif" / "apps.json"
