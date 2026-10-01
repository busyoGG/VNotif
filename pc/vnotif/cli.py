"""VNotif 命令行：serve / tail / discover / test-send / token。"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import signal
import subprocess
import sys
from pathlib import Path

from .appindex import AppIndex, default_path as default_index_path
from .capture import NotificationCapture
from .config import Config, lan_ip, load_config, save_blacklist
from .filters import Filters
from .hints import hint_for
from .router import Router
from .server import Hub, start_server

log = logging.getLogger("vnotif")


def _setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )


def stream_url(cfg: Config) -> str:
    scheme = "https" if cfg.tls_cert else "http"
    return f"{scheme}://{lan_ip()}:{cfg.port}/stream?token={cfg.token}"


def _router(cfg: Config, index: AppIndex, broadcast, *, loop=None, on_seen=None) -> Router:
    return Router(
        index,
        Filters(cfg.blacklist_apps, cfg.body_regex),
        broadcast=broadcast,
        loop=loop,
        on_seen=on_seen,
    )


# --------------------------------------------------------------------------- #
# serve
# --------------------------------------------------------------------------- #
async def _stats_loop(router: Router, hub: Hub) -> None:
    while True:
        await asyncio.sleep(300)
        log.info(
            "统计：客户端 %d，收 %d 转 %d 丢 %d 更新 %d",
            hub.client_count,
            router.stats["seen"],
            router.stats["forwarded"],
            router.stats["dropped"],
            router.stats["updated"],
        )


async def _serve(cfg: Config) -> None:
    loop = asyncio.get_running_loop()
    index = AppIndex(path=default_index_path())
    hub = Hub(index)
    router = _router(cfg, index, hub.broadcast, loop=loop)
    capture = NotificationCapture(router.on_message, router.on_event)
    runner = await start_server(cfg, hub)

    if not cfg.token:
        log.warning("配置里 token 为空：任何能访问该端口的人都能看到你的通知，建议设置 token")
    log.info("配置：%s", cfg.path)
    log.info("手机端拉流地址（局域网）：%s", stream_url(cfg))

    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            pass

    tasks = [
        asyncio.create_task(capture.run()),
        asyncio.create_task(_stats_loop(router, hub)),
        asyncio.create_task(_config_watch(cfg, router)),
        asyncio.create_task(_index_flush_loop(index)),
    ]
    try:
        await stop.wait()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        index.flush()  # 退出前补一次，别丢最后几秒
        hub.shutdown()  # 先让手机的流退出，否则 cleanup 要等到 shutdown_timeout
        await runner.cleanup()
        log.info("已退出")


async def _index_flush_loop(index: AppIndex, *, interval: float = 5.0) -> None:
    """定期把"见过的应用"落盘，进程被杀最多丢几秒。"""
    while True:
        await asyncio.sleep(interval)
        index.flush()


# --------------------------------------------------------------------------- #
# tail
# --------------------------------------------------------------------------- #
async def _tail(cfg: Config, as_json: bool) -> None:
    index = AppIndex()

    def emit(msg: dict) -> None:
        if as_json:
            print(json.dumps(msg, ensure_ascii=False), flush=True)
            return
        app = msg.get("app_id") or msg.get("app") or "?"
        head = msg.get("title") or ""
        body = (msg.get("body") or "").replace("\n", " ")
        print(f"[{app}] {msg.get('op')} | {head} | {body[:80]}", flush=True)

    router = _router(cfg, index, emit)
    capture = NotificationCapture(router.on_message, router.on_event)
    print("正在监听通知（Ctrl-C 退出）…", flush=True)
    await capture.run()


# --------------------------------------------------------------------------- #
# discover
# --------------------------------------------------------------------------- #
def _exe_of(pid: int | None) -> str:
    if not pid:
        return ""
    try:
        return os.readlink(f"/proc/{pid}/exe")
    except OSError:
        return ""


async def _discover(cfg: Config, seconds: float) -> None:
    index = AppIndex()
    seen: list = []

    def collect(event) -> None:
        seen.append(event)

    router = _router(cfg, index, lambda _msg: None, on_seen=collect)
    capture = NotificationCapture(router.on_message, router.on_event)
    task = asyncio.create_task(capture.run())
    log.info("采样 %.0f 秒，期间正常使用桌面即可…", seconds)
    try:
        await asyncio.sleep(seconds)
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    exe_by_app: dict[str, str] = {}
    for event in seen:
        key = AppIndex.key_of(event.app_id, event.app_name)
        exe = _exe_of(event.pid)
        if exe:
            exe_by_app.setdefault(key, exe)

    rows = index.snapshot()
    if not rows:
        print("这段时间没有捕获到任何通知。")
        return
    print(f"\n捕获到 {len(seen)} 条通知，涉及 {len(rows)} 个应用：\n")
    print(f"{'desktop-entry':<28} {'显示名':<20} {'条数':>5}  {'建议安卓包名':<28} 可执行路径")
    print("-" * 130)
    for row in rows:
        key = row["app_id"]
        print(
            f"{key[:27]:<28} {row['app'][:19]:<20} {row['seen']:>5}  "
            f"{(row['pkg_hint'] or '-'):<28} {exe_by_app.get(key, '')}"
        )
    print("\n提示：映射关系在手机端 App 里选择，这里只用于排查应用标识。")

    # 把这次采样结果并进持久缓存：用户跑 discover 就是为了拿到确切的应用名去 block，
    # 结果不能只活在这次进程的内存里，否则回头 `vnotif blocked` 还是空的。
    persisted = AppIndex(path=default_index_path())
    for row in rows:
        persisted.touch(row["app_id"], row["app"])
    persisted.flush()
    print(f"已并入应用缓存 {default_index_path()}（`vnotif blocked` 里可以看到）。")


# --------------------------------------------------------------------------- #
# test-send / token
# --------------------------------------------------------------------------- #
def _test_send(app: str, title: str, body: str) -> int:
    try:
        proc = subprocess.run(
            ["notify-send", "-a", app, "-u", "normal", title, body],
            check=False,
        )
    except FileNotFoundError:
        print("找不到 notify-send（需要 libnotify），可直接用桌面应用触发通知测试。", file=sys.stderr)
        return 1
    return proc.returncode


def _print_token(cfg: Config) -> None:
    print(f"配置文件: {cfg.path}")
    print(f"token   : {cfg.token or '(空)'}")
    print(f"局域网  : {stream_url(cfg)}")
    print(f"本机健康检查: curl -s '{cfg.local_url}/healthz?token={cfg.token}'")


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def _selftest(cfg: Config, timeout: float, base: str | None = None) -> int:
    """端到端自检：真拉一次流，真发一条桌面通知，断言手机上会收到的内容。"""
    import threading
    import time
    import urllib.error
    import urllib.request

    base = (base or f"http://127.0.0.1:{cfg.port}").rstrip("/")
    stream = f"{base}/stream?token={cfg.token}"
    got: list[dict] = []
    errors: list[str] = []

    def reader() -> None:
        try:
            with urllib.request.urlopen(stream, timeout=timeout + 5) as resp:
                for raw in resp:
                    line = raw.decode("utf-8", "replace").strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except ValueError:
                        continue
                    if obj.get("op") == "ping":
                        continue
                    got.append(obj)
        except (urllib.error.URLError, OSError) as exc:
            errors.append(repr(exc))

    print(f"1/3 连接 {base} …")
    threading.Thread(target=reader, daemon=True).start()
    time.sleep(0.8)
    if not any(m.get("op") == "apps" for m in got):
        print("✗ 没收到首条 apps 消息。检查：token 是否正确、服务是否在跑、端口是否可达。")
        if errors:
            print(f"  连接错误：{errors[0]}")
        return 1
    print("    ✓ 收到 apps（应用清单）")

    title = f"VNotif 自检 {int(time.time())}"
    print(f"2/3 发送测试通知「{title}」…")
    if _test_send("vnotif-selftest", title, "链路自检，手机端应看到这条") != 0:
        return 1

    print(f"3/3 等待 {timeout:.0f} 秒内到达 …")
    deadline = time.time() + timeout
    while time.time() < deadline:
        for msg in got:
            if msg.get("title") == title:
                print("✓ 通过。手机端会收到的消息：")
                print("   " + json.dumps(msg, ensure_ascii=False))
                return 0
        time.sleep(0.1)
    print("✗ 超时没收到这条通知。检查：")
    print("   - 是否被 config.toml 的 apps/body_regex 黑名单拦掉（vnotif-selftest）")
    print("   - journalctl --user -u vnotif -f 里有没有“转发”")
    print("   - 桌面通知是否真的发出（有些应用静音时不发 D-Bus 通知）")
    if got:
        print(f"   本次共收到 {len(got)} 条其它消息：{json.dumps(got[-1], ensure_ascii=False)}")
    return 1


async def _config_watch(cfg: Config, router: Router, *, interval: float = 2.0) -> None:
    """配置文件一被改动就重载过滤规则。

    这样改黑名单不需要重启，也就不会把手机已经建立的长连接踢掉。
    """

    def stamp() -> float:
        try:
            return cfg.path.stat().st_mtime
        except OSError:
            return 0.0

    last = stamp()
    while True:
        await asyncio.sleep(interval)
        now = stamp()
        if now == last:
            continue
        last = now
        if not cfg.path.exists():
            log.warning("配置文件不见了，继续用旧的过滤规则：%s", cfg.path)
            continue
        try:
            fresh = load_config(cfg.path, create=False)
        except Exception as exc:  # TOML 写坏了不能让守护进程崩
            log.warning("配置重载失败（继续用旧的）：%s", exc)
            continue
        router.filters = Filters(fresh.blacklist_apps, fresh.body_regex)
        log.info(
            "已重载过滤规则：黑名单 %d 项，正文正则 %d 条",
            len(fresh.blacklist_apps),
            len(fresh.body_regex),
        )


def _norm_app(name: str) -> str:
    return (name or "").strip().casefold().removesuffix(".desktop")


def _running_apps(cfg: Config) -> list[str] | None:
    """问正在跑的守护进程它见过哪些应用；没跑或连不上返回 None。"""
    import urllib.error
    import urllib.request

    try:
        with urllib.request.urlopen(
            f"{cfg.local_url}/healthz?token={cfg.token}", timeout=2.0
        ) as resp:
            data = json.load(resp)
    except (urllib.error.URLError, OSError, ValueError):
        return None
    return sorted(str(a) for a in (data.get("apps") or []))


def _cached_apps() -> list[str] | None:
    """读应用索引缓存文件里的应用名；没有缓存文件返回 None。"""
    path = default_index_path()
    if not path.exists():
        return None
    index = AppIndex(path=path)
    names: list[str] = []
    for row in index.snapshot():
        name = str(row.get("app_id") or row.get("app") or "").strip()
        if name:
            names.append(name)
    return names


def _print_blocked(cfg: Config) -> int:
    print(f"配置文件: {cfg.path}")
    if cfg.blacklist_apps:
        print(f"黑名单（{len(cfg.blacklist_apps)} 项）：")
        for name in cfg.blacklist_apps:
            print(f"  - {name}")
    else:
        print("黑名单：空（所有应用都会转发）")

    cached = _cached_apps()  # None = 没有缓存文件
    live = _running_apps(cfg)  # None = 守护进程没跑或连不上
    names = set(cached or [])
    if live:
        names.update(live)

    if not names:
        if cached is None and live is None:
            print(
                "\n还没有任何历史记录——先让电脑正常用一会儿（收到过通知），"
                "或者跑 `vnotif discover`。"
            )
        else:
            print("\n还没有记录到任何应用。")
        return 0

    flt = Filters(cfg.blacklist_apps, cfg.body_regex)
    print(f'\n已见应用（共 {len(names)} 个，"转发"=正在转发，"屏蔽"=已进黑名单）：')
    for name in sorted(names):
        print(f"  [{'屏蔽' if flt.blocks_app(name) else '转发'}] {name}")
    return 0


def _edit_blacklist(cfg: Config, names: list[str], *, add: bool) -> int:
    current = list(cfg.blacklist_apps)
    keys = {_norm_app(a) for a in current}
    changed: list[str] = []
    for raw in names:
        name = (raw or "").strip()
        if not name:
            continue
        key = _norm_app(name)
        if add:
            if key in keys:
                print(f"已在黑名单：{name}")
                continue
            current.append(name)
            keys.add(key)
            changed.append(name)
        else:
            if key not in keys:
                print(f"不在黑名单：{name}")
                continue
            current = [c for c in current if _norm_app(c) != key]
            keys.discard(key)
            changed.append(name)
    if not changed:
        return 1
    save_blacklist(current, cfg.path)
    print(f"{'已屏蔽' if add else '已取消屏蔽'}：{', '.join(changed)}")
    print("黑名单现有 %d 项：%s" % (len(current), ", ".join(current) if current else "（空）"))
    print("守护进程 2 秒内自动重载，不用重启。")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vnotif", description="把桌面通知按应用转发到安卓手机")
    parser.add_argument("--config", type=Path, default=None, help="配置文件路径")
    parser.add_argument("--log-level", default="INFO", help="日志级别（默认 INFO）")
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("serve", help="运行守护进程（默认）")

    tail = sub.add_parser("tail", help="只监听并在终端打印转发的消息")
    tail.add_argument("--json", action="store_true", help="打印原始 JSON")

    disc = sub.add_parser("discover", help="采样一段时间，列出出现过的应用与建议包名")
    disc.add_argument("-t", "--seconds", type=float, default=60.0, help="采样秒数（默认 60）")

    test = sub.add_parser("test-send", help="发一条测试通知")
    test.add_argument("-a", "--app", default="test-vnotif")
    test.add_argument("-t", "--title", default="VNotif 测试通知")
    test.add_argument("-b", "--body", default="如果你在手机上看到这条，链路是通的。")

    check = sub.add_parser("selftest", help="端到端自检（拉流 + 发通知 + 断言）")
    check.add_argument("-t", "--timeout", type=float, default=8.0, help="等待秒数（默认 8）")
    check.add_argument("--base", default=None, help="要测的地址，默认 http://127.0.0.1:<port>")

    blk = sub.add_parser("block", help="把应用加入黑名单（2 秒内生效，不用重启）")
    blk.add_argument("apps", nargs="+", metavar="应用名")
    unblk = sub.add_parser("unblock", help="把应用移出黑名单")
    unblk.add_argument("apps", nargs="+", metavar="应用名")
    sub.add_parser("blocked", help="查看黑名单与守护进程见过的应用")

    sub.add_parser("token", help="打印 token 与拉流地址")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    _setup_logging(args.log_level)
    cfg = load_config(args.config)

    cmd = args.cmd or "serve"
    try:
        if cmd == "serve":
            asyncio.run(_serve(cfg))
        elif cmd == "tail":
            asyncio.run(_tail(cfg, args.json))
        elif cmd == "discover":
            asyncio.run(_discover(cfg, args.seconds))
        elif cmd == "test-send":
            return _test_send(args.app, args.title, args.body)
        elif cmd == "selftest":
            return _selftest(cfg, args.timeout, args.base)
        elif cmd == "block":
            return _edit_blacklist(cfg, args.apps, add=True)
        elif cmd == "unblock":
            return _edit_blacklist(cfg, args.apps, add=False)
        elif cmd == "blocked":
            return _print_blocked(cfg)
        elif cmd == "token":
            _print_token(cfg)
    except KeyboardInterrupt:
        return 130
    return 0
