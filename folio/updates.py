"""In-app updates for Folio.app: check folio.qshlab.com, download the new app, swap it in, restart.

Only when running from the app (the launcher sets FOLIO_APP); a dev run from the repo never updates.
The new app must be signed with the same certificate as this one, so a tampered download is refused
and macOS keeps the microphone / system-audio permissions across updates.
"""
import hashlib
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

APP = Path(os.environ["FOLIO_APP"]) if os.environ.get("FOLIO_APP") else None
VERSION = os.environ.get("FOLIO_VERSION", "dev")
FEED = os.environ.get("FOLIO_UPDATE_URL", "https://folio.qshlab.com/mac.json")
SUPPORT = Path(os.environ.get("FOLIO_SUPPORT", Path.home() / "Library/Application Support/Folio"))

_state = {"available": None, "checked": None, "error": None, "installing": False, "step": None}
_lock = threading.Lock()


def _ver(v: str) -> tuple:
    return tuple(int(x) for x in v.split(".") if x.isdigit())


def info() -> dict:
    return {"version": VERSION, "updatable": APP is not None, **_state}


def check() -> dict:
    """Ask the update feed for the latest version. Never raises: errors are reported in the state."""
    if APP is None:
        return info()
    try:
        req = urllib.request.Request(FEED, headers={"User-Agent": f"Folio/{VERSION}", "Cache-Control": "no-cache"})
        with urllib.request.urlopen(req, timeout=15) as r:
            feed = json.load(r)
        newer = _ver(feed["version"]) > _ver(VERSION)
        _state.update(available=feed if newer else None, error=None)
    except Exception as e:  # offline, feed missing…
        _state["error"] = f"Couldn't check for updates ({e.__class__.__name__})."
    _state["checked"] = time.time()
    return info()


def _poller() -> None:
    time.sleep(20)
    while True:
        check()
        time.sleep(12 * 3600)


def start() -> None:
    if APP is not None:
        shutil.rmtree(SUPPORT / "update", ignore_errors=True)   # leftovers of the last update
        threading.Thread(target=_poller, daemon=True, name="folio-updates").start()


def _designated_requirement(app: Path) -> str:
    r = subprocess.run(["codesign", "-d", "-r-", str(app)], capture_output=True, text=True)
    out = r.stdout + r.stderr
    for line in out.splitlines():
        if line.startswith("designated => "):
            return line[len("designated => "):]
    raise RuntimeError("This copy of Folio isn't signed, so it can't update itself.")


def install() -> None:
    """Download, verify and stage the update, then hand over to a small script that swaps the app
    once Folio has quit, and opens the new one. Raises if anything is off; nothing is changed then."""
    from . import jobs, live, runtime
    with _lock:
        feed = _state["available"]
        if APP is None or not feed:
            raise RuntimeError("No update to install.")
        if _state["installing"]:
            raise RuntimeError("Already updating.")
        if not os.access(APP.parent, os.W_OK):
            raise RuntimeError(f"Folio can't replace itself in {APP.parent}. Move it to Applications.")
        if live.active() or jobs.running():
            raise RuntimeError("Folio is busy recording or working. Update when it's idle.")
        _state.update(installing=True, step="Downloading", error=None)
    try:
        stage = SUPPORT / "update"
        shutil.rmtree(stage, ignore_errors=True)
        stage.mkdir(parents=True)
        zpath = stage / "Folio.zip"
        h = hashlib.sha256()
        req = urllib.request.Request(feed["url"], headers={"User-Agent": f"Folio/{VERSION}"})
        with urllib.request.urlopen(req, timeout=60) as r, open(zpath, "wb") as f:
            while chunk := r.read(1 << 20):
                h.update(chunk)
                f.write(chunk)
        if h.hexdigest() != feed["sha256"]:
            raise RuntimeError("The download was damaged. Try again later.")
        _state["step"] = "Checking"
        subprocess.run(["ditto", "-x", "-k", str(zpath), str(stage)], check=True)
        new = stage / "Folio.app"
        req_ = _designated_requirement(APP)
        ok = subprocess.run(["codesign", "--verify", "--deep", "--strict", f"-R={req_}", str(new)],
                            capture_output=True, text=True)
        if ok.returncode != 0:
            raise RuntimeError("The update isn't signed by Folio's publisher, so it wasn't installed.")
        _state["step"] = "Restarting"
        script = stage / "swap.sh"
        script.write_text(f"""#!/bin/sh
# Wait for Folio (engine and app) to quit, put the new app in place (keeping the old one if anything
# fails), reopen.
while kill -0 {os.getpid()} 2>/dev/null; do sleep 0.2; done
APP={_q(APP)}; NEW={_q(new)}; OLD={_q(stage / "Folio-old.app")}
i=0; while pgrep -f "$APP/Contents/MacOS/Folio$" >/dev/null && [ $i -lt 150 ]; do sleep 0.2; i=$((i+1)); done
if mv "$APP" "$OLD" && mv "$NEW" "$APP"; then rm -rf "$OLD"; else [ -d "$APP" ] || mv "$OLD" "$APP"; fi
open "$APP"
""")
        subprocess.Popen(["/bin/sh", str(script)], start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        runtime.quit()
    except Exception as e:
        _state.update(installing=False, step=None, error=str(e) if isinstance(e, RuntimeError) else f"Update failed: {e}")
        raise RuntimeError(_state["error"]) from e


def _q(p) -> str:
    return "'" + str(p).replace("'", "'\\''") + "'"
