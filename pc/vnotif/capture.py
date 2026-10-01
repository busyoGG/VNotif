"""启动并维护总线监听子进程（busctl 主路径 / dbus-monitor 兜底）。

设计要点（都是踩过的坑）：
- asyncio 子进程流的 readline 默认上限 64KiB，而带 image-data（应用图标）的通知消息
  轻松上百 KB。超限会抛 ValueError，必须显式放大缓冲并对单条消息做隔离，
  否则一条带图标的通知就能把整个采集任务干掉。
- 任何异常都不允许静默退出：外层 run() 负责记录堆栈并重建监听。
"""

from __future__ import annotations

import asyncio
import logging
import re
import shutil
from collections.abc import Callable

from .model import Event
from .parse import IFACE, events_from_dbus_monitor, loads_busctl_line

log = logging.getLogger("vnotif.capture")

NOTIFY_MATCH = f"interface='{IFACE}'"
_DAEMON_OWNER_RE = re.compile(r'"([^"]+)"')
_BACKOFF_MAX = 30.0
_STREAM_LIMIT = 32 * 1024 * 1024
_RESTART_DELAY = 3.0


async def _kill(proc: asyncio.subprocess.Process) -> None:
    if proc.returncode is None:
        proc.terminate()
        try:
            await asyncio.wait_for(proc.wait(), timeout=3)
        except (TimeoutError, asyncio.TimeoutError):
            proc.kill()
            await proc.wait()


class NotificationCapture:
    """把总线上的通知事件喂给回调；异常、进程退出都自动恢复。"""

    def __init__(
        self,
        on_message: Callable[[dict], None],
        on_event: Callable[[Event], None],
    ) -> None:
        self.on_message = on_message
        self.on_event = on_event
        self.daemon_name: str | None = None

    # -- 公共入口 ----------------------------------------------------------- #
    async def run(self) -> None:
        while True:
            try:
                await self._run_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("采集任务异常，%.0f 秒后重建监听", _RESTART_DELAY)
            await asyncio.sleep(_RESTART_DELAY)

    async def _run_once(self) -> None:
        if shutil.which("busctl"):
            tasks = [
                asyncio.create_task(self._busctl_loop(NOTIFY_MATCH, "notifications")),
                asyncio.create_task(self._daemon_watchdog()),
            ]
        else:
            log.warning("找不到 busctl，降级为 dbus-monitor 文本解析（字段较少）")
            tasks = [asyncio.create_task(self._dbus_monitor_loop())]
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_EXCEPTION)
            for task in done:
                task.result()  # 有异常就带原始堆栈抛给上层
                log.warning("监听任务 %s 意外结束", task.get_name())
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    # -- 回调隔离 ----------------------------------------------------------- #
    def _emit_message(self, msg: dict) -> None:
        try:
            self.on_message(msg)
        except Exception:
            log.exception("处理总线消息失败（已忽略该条）")

    def _emit_event(self, event: Event) -> None:
        try:
            self.on_event(event)
        except Exception:
            log.exception("处理通知事件失败（已忽略该条）")

    async def resolve_daemon(self) -> str | None:
        """查 org.freedesktop.Notifications 当前归属的唯一名，如 :1.49。"""
        try:
            proc = await asyncio.create_subprocess_exec(
                "busctl", "--user", "call",
                "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "GetNameOwner", "s", IFACE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            out, _ = await proc.communicate()
        except OSError as exc:
            log.warning("查询通知守护进程失败: %s", exc)
            return None
        match = _DAEMON_OWNER_RE.search(out.decode("utf-8", "replace"))
        return match.group(1) if match else None

    # -- busctl 主路径 ------------------------------------------------------ #
    async def _busctl_loop(self, match: str, tag: str) -> None:
        backoff = 1.0
        while True:
            argv = ["busctl", "--user", "monitor", "--json=short", "--match", match]
            log.debug("启动监听(%s): %s", tag, " ".join(argv))
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                limit=_STREAM_LIMIT,
            )
            try:
                assert proc.stdout is not None
                while True:
                    try:
                        raw = await proc.stdout.readline()
                    except ValueError as exc:
                        # readline 内部已丢弃超限缓冲，这里跳过这条继续读即可
                        log.warning("监听(%s)丢弃一条超限消息: %s", tag, exc)
                        continue
                    if not raw:
                        break
                    msg = loads_busctl_line(raw.decode("utf-8", "replace"))
                    if msg is not None:
                        backoff = 1.0
                        self._emit_message(msg)
            except asyncio.CancelledError:
                await _kill(proc)
                raise
            finally:
                if proc.returncode is None:
                    await _kill(proc)

            err = ""
            if proc.stderr is not None:
                err = (await proc.stderr.read()).decode("utf-8", "replace").strip()
            log.warning(
                "监听(%s)退出 rc=%s%s，%.0fs 后重启",
                tag, proc.returncode, f" stderr={err[:200]}" if err else "", backoff,
            )
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, _BACKOFF_MAX)

    async def _daemon_watchdog(self) -> None:
        """守护进程重启后唯一名会变，需要跟着换 sender 过滤规则；子任务死了也要重建。"""
        task: asyncio.Task | None = None
        while True:
            name = await self.resolve_daemon()
            died = task is not None and task.done() and not task.cancelled()
            if task is not None and (died or (name is not None and name != self.daemon_name)):
                if died:
                    log.error("守护进程监听异常退出: %r，重建", task.exception())
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                task = None
            target = name or self.daemon_name
            if target is not None and task is None:
                self.daemon_name = target
                log.info("通知守护进程唯一名 = %s", target)
                task = asyncio.create_task(
                    self._busctl_loop(f"sender='{target}'", f"daemon {target}")
                )
            await asyncio.sleep(30)

    # -- dbus-monitor 兜底 -------------------------------------------------- #
    async def _dbus_monitor_loop(self) -> None:
        backoff = 1.0
        while True:
            proc = await asyncio.create_subprocess_exec(
                "dbus-monitor", "--session", NOTIFY_MATCH,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                limit=_STREAM_LIMIT,
            )
            block: list[str] = []
            try:
                assert proc.stdout is not None
                while True:
                    raw = await proc.stdout.readline()
                    if not raw:
                        break
                    line = raw.decode("utf-8", "replace").rstrip("\n")
                    if line[:11] in ("method call", "method retu") or line.startswith("signal "):
                        self._flush_block(block)
                        block = [line]
                    elif block:
                        block.append(line)
                self._flush_block(block)
            except asyncio.CancelledError:
                await _kill(proc)
                raise
            finally:
                if proc.returncode is None:
                    await _kill(proc)
            log.warning("dbus-monitor 退出，%.0fs 后重启", backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, _BACKOFF_MAX)

    def _flush_block(self, block: list[str]) -> None:
        if not block:
            return
        for event in events_from_dbus_monitor("\n".join(block)):
            self._emit_event(event)
