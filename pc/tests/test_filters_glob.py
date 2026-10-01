"""Filters 通配符单测：精确项行为不变，glob 用 fnmatchcase 匹配归一化后的名字。"""

from __future__ import annotations

from vnotif.filters import Filters
from vnotif.model import Event


def _ev(app_name: str = "", app_id: str = "", summary: str = "标题", body: str = "正文") -> Event:
    return Event(key="k", sender=":1.1", cookie=1, app_name=app_name, app_id=app_id, summary=summary, body=body)


# --- 1. 精确项行为不变 ------------------------------------------------------- #
def test_exact_match_is_case_and_suffix_insensitive():
    flt = Filters(["kscreen"])
    assert flt.blocks_app("kscreen") == "kscreen"
    assert flt.blocks_app("KScreen") == "kscreen"
    assert flt.blocks_app("kscreen.desktop") == "kscreen"
    assert flt.blocks_app("kscreenx") is None


def test_exact_match_covers_app_name_and_app_id():
    flt = Filters(["Element"])
    assert flt.blocks_app("Element", "") == "element"
    assert flt.blocks_app("", "element.desktop") == "element"


# --- 2. glob ----------------------------------------------------------------- #
def test_glob_matches_kdeconnect_variants():
    flt = Filters(["*kdeconnect*"])
    for name in (
        "org.kde.kdeconnect.app",
        "org.kde.kdeconnect.daemon",
        "org.kde.kdeconnect.sms",
        "kdeconnect",
        "kdeconnect.desktop",
    ):
        assert flt.blocks_app(name) == "*kdeconnect*", name
    assert flt.blocks_app("Telegram") is None


def test_glob_with_space_needs_its_own_rule():
    # 归一化不删内部空格：`KDE Connect` 不会被 `*kdeconnect*` 命中，必须另写一条。
    assert Filters(["*kdeconnect*"]).blocks_app("KDE Connect") is None
    assert Filters(["*kde connect*"]).blocks_app("KDE Connect") == "*kde connect*"


# --- 3. ? 与 [ 也是通配符 ----------------------------------------------------- #
def test_question_mark_is_glob():
    flt = Filters(["qq?"])
    assert flt.blocks_app("qq1") == "qq?"
    assert flt.blocks_app("qq") is None


def test_bracket_is_glob():
    flt = Filters(["q[qx]"])
    assert flt.blocks_app("qq") == "q[qx]"
    assert flt.blocks_app("qa") is None


# --- 4. 返回值就是命中的那条规则 --------------------------------------------- #
def test_returns_hit_rule():
    flt = Filters(["KScreen", "*kdeconnect*"])
    assert flt.blocks_app("kscreen.desktop") == "kscreen"  # 精确项返回归一化后的项
    assert flt.blocks_app("org.kde.kdeconnect.app") == "*kdeconnect*"  # glob 返回模式本身


# --- 5. 大小写 --------------------------------------------------------------- #
def test_glob_is_case_insensitive_both_sides():
    assert Filters(["*QQ*"]).blocks_app("qq") == "*qq*"
    assert Filters(["*qq*"]).blocks_app("QQ") == "*qq*"


# --- drop_reason 集成 -------------------------------------------------------- #
def test_drop_reason_uses_glob_and_reports_hit():
    flt = Filters(["*kdeconnect*"])
    assert flt.drop_reason(_ev(app_name="org.kde.kdeconnect.app")) == "黑名单: *kdeconnect*"
    assert flt.drop_reason(_ev(app_name="Telegram")) is None


def test_drop_reason_empty_and_regex_unchanged():
    assert Filters().drop_reason(_ev(summary="", body="")) == "空通知（无标题无正文）"
    assert Filters([], [r"^音量"]).drop_reason(_ev(summary="音量 30%")) == "正文命中正则: ^音量"
