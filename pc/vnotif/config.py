"""配置加载：~/.config/vnotif/config.toml（缺失时自动生成，含随机 token）。"""

from __future__ import annotations

import os
import re
import secrets
import tomllib
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_PORT = 8765

_TEMPLATE = """\
# VNotif 配置。过滤规则（[filter] 段）2 秒内自动重载；[server] 段改动仍需重启 vnotif。

[server]
# 监听地址。局域网直连需要 0.0.0.0；只想本机自测可改 127.0.0.1。
host = "0.0.0.0"
port = {port}
# 手机端要填同一个 token。换掉它会立刻踢掉已连接的客户端。
token = "{token}"
# 可选：PC 侧自建 TLS（走 SakuraFrp 的 HTTPS 隧道时不需要，留空即可）。
tls_cert = ""
tls_key = ""

[filter]
# 黑名单：应用名或 desktop-entry，大小写不敏感，.desktop 后缀可省略。
apps = [
    "kscreen",
    "fcitx5",
    "power-profiles-daemon",
]
# 标题或正文命中任一正则即丢弃（Python 正则语法）。
body_regex = []
"""


@dataclass
class Config:
    host: str = "0.0.0.0"
    port: int = DEFAULT_PORT
    token: str = ""
    tls_cert: str = ""
    tls_key: str = ""
    blacklist_apps: list[str] = field(default_factory=list)
    body_regex: list[str] = field(default_factory=list)
    path: Path = field(default_factory=lambda: default_path())

    @property
    def local_url(self) -> str:
        scheme = "https" if self.tls_cert else "http"
        return f"{scheme}://127.0.0.1:{self.port}"


def default_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "vnotif" / "config.toml"


def lan_ip() -> str:
    """取本机局域网 IPv4（不发起真实流量）。"""
    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.168.1.1", 1))
        return str(sock.getsockname()[0])
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def load_config(path: Path | None = None, *, create: bool = True) -> Config:
    path = path or default_path()
    if not path.exists():
        if not create:
            return Config(path=path)
        path.parent.mkdir(parents=True, exist_ok=True)
        token = secrets.token_urlsafe(18)
        path.write_text(_TEMPLATE.format(port=DEFAULT_PORT, token=token))
        path.chmod(0o600)
    with path.open("rb") as fh:
        data = tomllib.load(fh)
    server = data.get("server", {})
    flt = data.get("filter", {})
    return Config(
        host=str(server.get("host", "0.0.0.0")),
        port=int(server.get("port", DEFAULT_PORT)),
        token=str(server.get("token", "")),
        tls_cert=str(server.get("tls_cert", "")),
        tls_key=str(server.get("tls_key", "")),
        blacklist_apps=list(flt.get("apps", [])),
        body_regex=list(flt.get("body_regex", [])),
        path=path,
    )


_APPS_RE = re.compile(r"^\s*apps\s*=\s*\[")


def save_blacklist(apps: Iterable[str], path: Path | None = None) -> Path:
    """把黑名单写回配置文件的 [filter].apps，其余内容与注释原样保留。

    逐行改写而不是重新序列化整份 TOML：这个文件是给人手改的，用 toml 序列化会把注释和
    排版全部抹掉，用户下次打开会不认识自己的配置。
    """
    path = path or default_path()
    entries: list[str] = []
    seen: set[str] = set()
    for raw in apps:
        name = (raw or "").strip()
        key = name.casefold().removesuffix(".desktop")
        if not name or key in seen:
            continue
        seen.add(key)
        entries.append(name)

    block = ["apps = ["]
    block += [f'    "{name}",' for name in entries]
    block.append("]")

    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    filter_at = next((i for i, line in enumerate(lines) if line.strip() == "[filter]"), None)
    if filter_at is None:
        if lines and lines[-1].strip():
            lines.append("")
        lines += [
            "[filter]",
            "# 黑名单：应用名或 desktop-entry，大小写不敏感，.desktop 后缀可省略。",
            *block,
        ]
    else:
        start = None
        for i in range(filter_at + 1, len(lines)):
            stripped = lines[i].strip()
            if stripped.startswith("[") and stripped.endswith("]"):
                break  # 进了下一节，说明 [filter] 里没有 apps
            if _APPS_RE.match(lines[i]):
                start = i
                break
        if start is None:
            lines[filter_at + 1 : filter_at + 1] = block
        else:
            end = start
            while "]" not in lines[end] and end + 1 < len(lines):
                end += 1
            lines[start : end + 1] = block

    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.parent / (path.name + ".tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if path.exists():
        tmp.chmod(path.stat().st_mode & 0o777)
    else:
        tmp.chmod(0o600)
    os.replace(tmp, path)
    return path
