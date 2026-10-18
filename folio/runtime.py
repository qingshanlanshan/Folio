"""App-level state: which models are in memory, stopping tasks, open windows, notifications, settings, quit.

Folio behaves like a Mac app whose windows are browser tabs: closing a tab closes a window, and
recording and processing carry on; Quit ends the app. Tabs report in every few seconds, so Folio knows
when no window is open and can tell you (with a notification) that it is still recording, or that
something finished.
"""
import gc
import os
import signal
import subprocess
import threading
import time

from . import store

SETTINGS = store.HOME / "settings.json"
DEFAULTS = {"autoUnloadMinutes": 10, "notifications": True}


class Cancelled(Exception):
    pass


# --- settings

def settings() -> dict:
    return {**DEFAULTS, **store.read_json(SETTINGS, default={})}


def save_settings(**kw) -> dict:
    s = settings()
    s.update({k: v for k, v in kw.items() if k in DEFAULTS and v is not None})
    store.HOME.mkdir(parents=True, exist_ok=True)
    store.write_json(SETTINGS, s)
    return s


# --- stopping tasks

_cancel: set[str] = set()
_local = threading.local()
quitting = False


def begin_job(key: str) -> None:
    _local.key = key


def end_job() -> None:
    _cancel.discard(getattr(_local, "key", None))
    _local.key = None


def cancel(key: str) -> None:
    _cancel.add(key)


def is_cancelled(key: str) -> bool:
    return key in _cancel


def check() -> None:
    """Called from long loops (transcription chunks, model output tokens): stops the current task."""
    key = getattr(_local, "key", None)
    if key and key in _cancel:
        raise Cancelled()


# --- models in memory

_models: dict[str, dict] = {}     # id -> {name, purpose, bytes, unload}
_last_use = time.time()
_mlock = threading.Lock()


def _mlx_bytes() -> int:
    try:
        import mlx.core as mx
        return int(mx.get_active_memory())
    except Exception:
        return 0


def loading(mid: str, name: str, purpose: str, load, unload):
    """Loads a model and remembers how much memory it took and how to drop it."""
    before = _mlx_bytes()
    obj = load()
    with _mlock:
        _models[mid] = {"name": name, "purpose": purpose, "bytes": max(0, _mlx_bytes() - before),
                        "unload": unload, "since": time.time()}
    return obj


def touch() -> None:
    global _last_use
    _last_use = time.time()


def models() -> list[dict]:
    with _mlock:
        return [{"id": k, "name": v["name"], "purpose": v["purpose"], "gb": round(v["bytes"] / 1e9, 2)}
                for k, v in _models.items()]


def unload(mid: str | None = None, wait: float = 3.0) -> list[str]:
    """Drops one model (or all). Waits briefly for the model to be free; returns what was unloaded."""
    from .asr import GPU
    with _mlock:
        ids = [mid] if mid else list(_models)
    done = []
    for i in ids:
        if i not in _models:
            continue
        if not GPU.acquire(timeout=wait):
            raise RuntimeError("busy")
        try:
            with _mlock:
                m = _models.pop(i, None)
            if m:
                m["unload"]()
                done.append(i)
        finally:
            GPU.release()
    gc.collect()
    try:
        import mlx.core as mx
        mx.clear_cache()
    except Exception:
        pass
    return done


def memory() -> dict:
    rss = 0
    try:
        rss = int(subprocess.run(["ps", "-o", "rss=", "-p", str(os.getpid())], capture_output=True,
                                 text=True, timeout=2).stdout.strip() or 0) * 1024
    except Exception:
        pass
    gpu = _mlx_bytes()
    try:
        import mlx.core as mx
        gpu += int(mx.get_cache_memory())
    except Exception:
        pass
    # Model weights live in GPU (Metal) memory, which the process's RSS doesn't include.
    return {"modelsGB": round(_mlx_bytes() / 1e9, 2), "totalGB": round((rss + gpu) / 1e9, 2)}


def _auto_unloader() -> None:
    from . import jobs, live
    while True:
        time.sleep(30)
        minutes = settings().get("autoUnloadMinutes") or 0
        if not minutes or not _models or jobs.status() or live.active():
            continue
        if time.time() - _last_use > minutes * 60:
            try:
                unload()
            except RuntimeError:
                pass


# --- windows (tabs) and notifications

_windows: dict[str, float] = {}   # window id -> last seen
WINDOW_TIMEOUT = 15
_told_recording = False


def window_seen(wid: str, closing: bool = False) -> None:
    global _told_recording
    if not wid:
        return
    if closing:
        _windows.pop(wid, None)
    else:
        _windows[wid] = time.time()
        _told_recording = False


def windows_open() -> int:
    now = time.time()
    for k in [k for k, t in _windows.items() if now - t > WINDOW_TIMEOUT]:
        _windows.pop(k, None)
    return len(_windows)


def notify(title: str, message: str) -> None:
    if not settings().get("notifications"):
        return
    q = lambda s: s.replace("\\", "\\\\").replace('"', '\\"')
    try:
        subprocess.Popen(["osascript", "-e", f'display notification "{q(message)}" with title "{q(title)}"'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def when_no_window(title: str, message: str) -> None:
    if not windows_open():
        notify(title, message)


def _watch_windows() -> None:
    global _told_recording
    from . import live
    while True:
        time.sleep(3)
        s = live.active()
        if s and not s.stopping and not windows_open() and not _told_recording:
            _told_recording = True
            notify("Folio is still recording", f"“{s.title}” keeps recording with the window closed. "
                                                "Open Folio to stop it.")


# --- quit

def quit(force: bool = False) -> None:
    """Quit: stop and save a recording, stop the current task (unfinished transcriptions resume next
    time), then exit. Force quit: exit now (audio already on disk is recovered next time)."""
    global quitting
    quitting = True
    if force:
        threading.Thread(target=lambda: (time.sleep(0.3), os._exit(0)), daemon=True).start()
        return

    def bye():
        from . import jobs, live
        s = live.active()
        if s:
            s.stop()
        for k in jobs.status():
            cancel(k)
        for _ in range(100):           # up to ~20 s for saving and stopping
            if not live.active() and not jobs.running():
                break
            time.sleep(0.2)
        time.sleep(0.3)
        os.kill(os.getpid(), signal.SIGTERM)

    threading.Thread(target=bye, daemon=True).start()


def start() -> None:
    threading.Thread(target=_auto_unloader, daemon=True, name="folio-unload").start()
    threading.Thread(target=_watch_windows, daemon=True, name="folio-windows").start()
