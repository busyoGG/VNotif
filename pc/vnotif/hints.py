"""桌面应用 → 建议安卓包名。

这里只是"建议值"（pkg_hint），仅在没有手机端手动映射时作为初值。
手机端用户手选的映射永远优先，因此本表可以随手增删。
"""

from __future__ import annotations

# key 一律小写、去掉 .desktop 后缀；匹配时先精确后包含。
HINTS: dict[str, str] = {
    # 聊天 / IM
    "element": "im.vector.app",
    "element-desktop": "im.vector.app",
    "im.vector.app": "im.vector.app",
    "telegram-desktop": "org.telegram.messenger",
    "telegramdesktop": "org.telegram.messenger",
    "org.telegram.desktop": "org.telegram.messenger",
    "discord": "com.discord",
    "vesktop": "com.discord",
    "webcord": "com.discord",
    "qq": "com.tencent.mobileqq",
    "linuxqq": "com.tencent.mobileqq",
    "wechat": "com.tencent.wechat",
    "com.tencent.wechat": "com.tencent.mm",
    "signal-desktop": "org.thoughtcrime.securesms",
    "slack": "com.Slack",
    "com.slack.slack": "com.Slack",
    "kdeconnect": "org.kde.kdeconnect_tp",
    "kde-connect": "org.kde.kdeconnect_tp",
    # 邮件 / 日程
    "thunderbird": "net.thunderbird.android",
    "org.mozilla.thunderbird": "net.thunderbird.android",
    "evolution": "com.microsoft.office.outlook",
    # 媒体
    "spotify": "com.spotify.music",
    "org.kde.spotify": "com.spotify.music",
    "vlc": "org.videolan.vlc",
    "mpv": "is.xyz.mpv",
    # 其他常见
    "chromium": "com.android.chrome",
    "google-chrome": "com.android.chrome",
    "firefox": "org.mozilla.firefox",
    "org.mozilla.firefox": "org.mozilla.firefox",
    "steam": "com.valvesoftware.android.steam.community",
    "code": "com.microsoft.vscode",
    "obsidian": "md.obsidian",
}


def _norm(value: str) -> str:
    return (value or "").strip().casefold().removesuffix(".desktop")


def hint_for(app_id: str, app_name: str) -> str | None:
    """按 desktop-entry → 应用名 的顺序找建议包名。"""
    for raw in (app_id, app_name):
        key = _norm(raw)
        if not key:
            continue
        if key in HINTS:
            return HINTS[key]
        for candidate, pkg in HINTS.items():
            if candidate in key or key in candidate:
                return pkg
    return None
