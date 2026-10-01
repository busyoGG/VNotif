"""aiohttp 服务：把通知以 NDJSON 流推给手机。"""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import time

from aiohttp import web

from .appindex import AppIndex
from .config import Config

log = logging.getLogger("vnotif.server")

HEARTBEAT = 20.0
QUEUE_MAX = 200
# 关停时最多等这么久，超过就放弃（否则挂着长连接的手机能让重启卡满一分钟）
SHUTDOWN_TIMEOUT = 2.0

# 塞进客户端队列的哨兵：让流式 handler 立刻退出循环
CLOSE = object()


class Hub:
    """客户端长连接集合。"""

    def __init__(self, index: AppIndex) -> None:
        self.index = index
        self.clients: set[asyncio.Queue] = set()
        self.started = time.time()
        self.total_connects = 0
        self.dropped = 0
        self.closing = False

    @property
    def client_count(self) -> int:
        return len(self.clients)

    def broadcast(self, obj: dict) -> None:
        if not self.clients:
            return
        for queue in list(self.clients):
            try:
                queue.put_nowait(obj)
            except asyncio.QueueFull:
                # 慢客户端：丢最旧的一条，保住连接。
                try:
                    queue.get_nowait()
                    queue.put_nowait(obj)
                    self.dropped += 1
                except (asyncio.QueueEmpty, asyncio.QueueFull):
                    self.clients.discard(queue)

    def shutdown(self) -> None:
        """通知所有客户端流立刻结束，别再等下一次心跳。"""
        self.closing = True
        for queue in list(self.clients):
            try:
                queue.put_nowait(CLOSE)
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                    queue.put_nowait(CLOSE)
                except (asyncio.QueueEmpty, asyncio.QueueFull):
                    pass

    def apps_message(self) -> dict:
        return {"v": 1, "op": "apps", "list": self.index.snapshot()}


def _authorized(request: web.Request, token: str) -> bool:
    if not token:
        return True
    given = request.query.get("token", "")
    if not given:
        header = request.headers.get("Authorization", "")
        if header.lower().startswith("bearer "):
            given = header[7:].strip()
    return bool(given) and hmac.compare_digest(given, token)


async def _stream(request: web.Request) -> web.StreamResponse:
    cfg: Config = request.app["config"]
    hub: Hub = request.app["hub"]
    if not _authorized(request, cfg.token):
        log.warning("拒绝未授权连接 来自 %s", request.remote)
        return web.json_response({"error": "unauthorized"}, status=401)

    response = web.StreamResponse(
        status=200,
        headers={
            "Content-Type": "application/x-ndjson; charset=utf-8",
            "Cache-Control": "no-store",
            "X-Accel-Buffering": "no",
        },
    )
    await response.prepare(request)
    queue: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_MAX)
    hub.clients.add(queue)
    hub.total_connects += 1
    peer = request.remote
    # The app reports its build tag here; without it there is no way to tell a stale APK from a
    # misbehaving one when the phone's rendering looks wrong.
    ua = request.headers.get("User-Agent", "?")
    log.info("客户端接入 %s [%s]（当前 %d 个）", peer, ua, hub.client_count)
    try:
        await response.write(_line(hub.apps_message()))
        while not hub.closing:
            try:
                obj = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT)
            except (TimeoutError, asyncio.TimeoutError):
                obj = {"v": 1, "op": "ping", "ts": round(time.time(), 3)}
            if obj is CLOSE:
                break
            await response.write(_line(obj))
    except (ConnectionResetError, ConnectionAbortedError, asyncio.CancelledError):
        pass
    except Exception as exc:  # 写失败基本等于客户端断开
        log.debug("客户端 %s 写出异常: %s", peer, exc)
    finally:
        hub.clients.discard(queue)
        log.info("客户端断开 %s（剩余 %d 个）", peer, hub.client_count)
    return response


def _line(obj: dict) -> bytes:
    return (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode()


async def _health(request: web.Request) -> web.Response:
    cfg: Config = request.app["config"]
    hub: Hub = request.app["hub"]
    if not _authorized(request, cfg.token):
        return web.json_response({"error": "unauthorized"}, status=401)
    return web.json_response(
        {
            "ok": True,
            "uptime": round(time.time() - hub.started, 1),
            "clients": hub.client_count,
            "total_connects": hub.total_connects,
            "dropped": hub.dropped,
            "apps": [row["app_id"] for row in hub.index.snapshot()],
        }
    )


async def _report(request: web.Request) -> web.Response:
    """手机把一行自检结果回传过来，直接落进日志。

    这台机器和手机之间没有 adb 通路，手机侧才知道的事情（图标最终取自哪个包、系统对它
    那几个通知渠道的声音/振动是怎么配的）原先只能靠用户看屏幕转述，一个问题来回好几轮。
    这条回传通道让它们直接出现在 journalctl 里。只写日志，不改任何状态。
    """
    cfg: Config = request.app["config"]
    if not _authorized(request, cfg.token):
        log.warning("拒绝未授权上报 来自 %s", request.remote)
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        data = await request.json()
    except Exception:
        data = {}
    if not isinstance(data, dict):
        data = {}
    tag = str(data.get("tag", "?"))[:32]
    text = str(data.get("text", ""))[:2000]
    log.info("手机上报 [%s] %s", tag, text.replace("\n", " | "))
    return web.json_response({"ok": True})


async def _index_page(request: web.Request) -> web.Response:
    return web.Response(
        text="VNotif 运行中。手机端拉流地址：/stream?token=…\n",
        content_type="text/plain",
    )


async def start_server(cfg: Config, hub: Hub) -> web.AppRunner:
    app = web.Application()
    app["config"] = cfg
    app["hub"] = hub
    app.router.add_get("/stream", _stream)
    app.router.add_get("/healthz", _health)
    app.router.add_post("/report", _report)
    app.router.add_get("/", _index_page)

    runner = web.AppRunner(app, access_log=None, shutdown_timeout=SHUTDOWN_TIMEOUT)
    await runner.setup()
    ssl_context = None
    if cfg.tls_cert and cfg.tls_key:
        import ssl

        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(cfg.tls_cert, cfg.tls_key)
        log.info("已启用 TLS")
    site = web.TCPSite(runner, cfg.host, cfg.port, ssl_context=ssl_context)
    await site.start()
    log.info("监听 %s:%d", cfg.host, cfg.port)
    return runner
