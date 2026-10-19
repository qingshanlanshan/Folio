"""Sync between your devices (Mac, Android) through your own Cloudflare R2 bucket, end-to-end encrypted.

Off unless you set it up in Settings. Everything is encrypted on the device with a key that only your
devices have (it travels inside the pairing code), so Cloudflare stores data it can't read.

Bucket layout (everything but folio-sync.json is AES-256-GCM encrypted):
    folio-sync.json            {"format": 1, "check": …}  tells a different sync key apart
    b/<id>                     one piece (≤ 8 MiB) of a file; id = keyed hash of the content, written once
    l/<device>/<seq>           a batch of changes by one device
    d/<device>                 the device's name, platform and when it was last seen
A change is {p: path, c: [piece ids], s: size, t: clock, d: device, x: deleted}. Devices only ever add new
keys, so they never overwrite each other. For each path the change with the highest (clock, device) wins.

What syncs (paths are the same on every device): projects (settings, notes, glossary, state + history,
to-dos) and their finished recordings (meeting.json, transcript, summary, fixes, audio.m4a, voices),
and people with their voice prints. Raw recordings (mic/system tracks, originals) stay on the device.
On the Mac, voice prints are .npy/.npz files; they sync as the JSON the Android app uses.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.parse
from pathlib import Path

import numpy as np

from . import store
from .s3 import S3, S3Error

CONFIG = store.HOME / "sync.json"
STATE = store.HOME / ".sync" / "state.json"
CHUNK = 8 << 20
INTERVAL = 120

SYNCED = re.compile(r"""^(
    projects/[^/]+/(project\.json|project\.md|glossary\.tsv|state\.md|todos\.json|state-history/[^/]+\.(md|json))
  | projects/[^/]+/meetings/[^/]+/(meeting\.json|transcript\.json|summary\.json|corrections\.json|audio\.m4a|voices\.json)
  | people/[^/]+\.json
)$""", re.X)
META = ("meeting.json", "project.json")     # written last, removed first: they make a folder show up

_lock = threading.Lock()
_wake = threading.Event()
_status = {"running": False, "last": None, "error": None, "sent": 0, "received": 0, "rev": int(time.time())}


class SyncError(Exception):
    pass


# --- settings and pairing

def config() -> dict | None:
    return store.read_json(CONFIG) if CONFIG.exists() else None


def _save_config(c: dict) -> None:
    store.write_json(CONFIG, c)
    os.chmod(CONFIG, 0o600)


def _keys(c: dict) -> tuple[bytes, bytes]:
    master = base64.urlsafe_b64decode(c["key"])
    return (hmac.new(master, b"folio-sync enc", hashlib.sha256).digest(),
            hmac.new(master, b"folio-sync mac", hashlib.sha256).digest())


def _check(c: dict) -> str:
    return hmac.new(_keys(c)[1], b"folio-sync", hashlib.sha256).hexdigest()[:16]


def _client(c: dict) -> S3:
    return S3(c["endpoint"], c["bucket"], c["keyId"], c["secret"])


def _endpoint(account: str) -> str:
    """The account ID, or the bucket's S3 API URL as Cloudflare shows it (…r2.cloudflarestorage.com/<bucket>)."""
    a = account.strip()
    if a.startswith(("https://", "http://")):
        u = urllib.parse.urlparse(a)
        return f"{u.scheme}://{u.netloc}"
    if not re.fullmatch(r"[0-9a-f]{32}", a):
        raise SyncError("The account ID is 32 letters and digits (Cloudflare dashboard → R2 → Account details).")
    return f"https://{a}.r2.cloudflarestorage.com"


def _marker(c: dict) -> dict | None:
    try:
        raw = _client(c).get("folio-sync.json")
    except S3Error as e:
        if e.status in (401, 403):
            raise SyncError("Cloudflare refused the key. Check the access key ID and secret, and that the "
                            "token can read and write this bucket.")
        if e.status == 404:
            raise SyncError(f"There's no bucket named “{c['bucket']}” in this account.")
        raise SyncError(f"Cloudflare said: {e}")
    except OSError as e:
        raise SyncError(f"Couldn't reach Cloudflare ({e.__class__.__name__}). Check the account ID and your connection.")
    return json.loads(raw) if raw else None


def _device_name() -> str:
    try:
        name = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=5).stdout.strip()
    except OSError:
        name = ""
    return name or socket.gethostname().removesuffix(".local") or "Mac"


def connect(account: str, bucket: str, key_id: str, secret: str) -> dict:
    """First device: check the bucket and start a new sync key."""
    c = {"endpoint": _endpoint(account), "bucket": bucket.strip(), "keyId": key_id.strip(), "secret": secret.strip(),
         "key": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode(), "device": secrets.token_hex(6),
         "name": _device_name()}
    if not all(c.values()):
        raise SyncError("Fill in all four fields.")
    if _marker(c):
        raise SyncError("This bucket already holds Folio data. On the device that set it up, open Settings → "
                        "Sync → “Code for another device”, and join with that code instead.")
    _client(c).put("folio-sync.json", json.dumps({"format": 1, "check": _check(c)}).encode())
    return _start(c)


def code() -> str:
    c = config()
    if not c:
        raise SyncError("Sync is off.")
    payload = {k: c[k] for k in ("endpoint", "bucket", "keyId", "secret", "key")}
    return "folio1:" + base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")


def join(text: str) -> dict:
    """Another device: use the code from a device that already syncs."""
    t = text.strip()
    if not t.startswith("folio1:"):
        raise SyncError("That isn't a Folio sync code. It starts with “folio1:”.")
    try:
        b = t[7:]
        p = json.loads(base64.urlsafe_b64decode(b + "=" * (-len(b) % 4)))
        c = {k: p[k] for k in ("endpoint", "bucket", "keyId", "secret", "key")}
    except (ValueError, KeyError):
        raise SyncError("The code is incomplete. Copy all of it.")
    c.update(device=secrets.token_hex(6), name=_device_name())
    m = _marker(c)
    if not m:
        raise SyncError("The bucket in this code is empty. Set up sync on the other device first.")
    if m.get("check") != _check(c):
        raise SyncError("This code doesn't match the data in the bucket. Get a fresh code from the other device.")
    return _start(c)


def _start(c: dict) -> dict:
    _save_config(c)
    STATE.unlink(missing_ok=True)
    _status.update(error=None, last=None)
    kick()
    return status()


def disconnect() -> None:
    """Stop syncing on this Mac. Nothing is deleted, here or in the bucket."""
    with _lock:
        CONFIG.unlink(missing_ok=True)
        STATE.unlink(missing_ok=True)
    _status.update(error=None, last=None)


def status() -> dict:
    c = config()
    if not c:
        return {"enabled": False}
    st = store.read_json(STATE, default={})
    return {"enabled": True, "bucket": c["bucket"], "account": c["endpoint"].split("//")[-1].split(".")[0],
            "device": c["name"], "devices": st.get("devices", []), **_status}


# --- encryption

def _seal(c: dict, data: bytes, aad: str) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = secrets.token_bytes(12)
    return nonce + AESGCM(_keys(c)[0]).encrypt(nonce, data, aad.encode())


def _open(c: dict, blob: bytes, aad: str) -> bytes:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    try:
        return AESGCM(_keys(c)[0]).decrypt(blob[:12], blob[12:], aad.encode())
    except InvalidTag:
        raise SyncError("Some synced data couldn't be decrypted (wrong key or damaged).")


def _piece_id(c: dict, data: bytes) -> str:
    return hmac.new(_keys(c)[1], data, hashlib.sha256).hexdigest()


# --- local files ↔ synced paths

def _local(path: str) -> Path:
    """Where a synced path lives on this Mac."""
    if path.startswith("people/") and path.endswith(".voices.json"):
        return store.HOME / (path[:-len(".json")] + ".npy")
    if path.endswith("/voices.json"):
        return store.HOME / (path[:-len(".json")] + ".npz")
    return store.HOME / path


def _encode(path: str, f: Path) -> bytes:
    if f.suffix == ".npy":
        return json.dumps({"prints": np.load(f).astype(np.float32).tolist()}).encode()
    if f.suffix == ".npz":
        with np.load(f) as z:
            return json.dumps({"voices": {k: z[k].astype(np.float32).tolist() for k in z.files}}).encode()
    return f.read_bytes()


def _write(path: str, data: bytes) -> Path:
    f = _local(path)
    f.parent.mkdir(parents=True, exist_ok=True)
    tmp = f.with_name(f.name + ".sync-tmp")
    if f.suffix == ".npy":
        with open(tmp, "wb") as out:
            np.save(out, np.array(json.loads(data)["prints"], dtype=np.float32).reshape(-1, _dim(data)))
    elif f.suffix == ".npz":
        with open(tmp, "wb") as out:
            np.savez(out, **{k: np.array(v, dtype=np.float32) for k, v in json.loads(data)["voices"].items()})
    else:
        tmp.write_bytes(data)
    tmp.replace(f)
    if path.endswith("/summary.json"):           # the Mac also keeps a readable copy
        from .summarize import render
        try:
            title = store.read_json(f.parent / "meeting.json", default={}).get("title", "")
            (f.parent / "summary.md").write_text(render(json.loads(data), title), "utf-8")
        except (ValueError, KeyError, TypeError):
            pass
    return f


def _dim(data: bytes) -> int:
    prints = json.loads(data)["prints"]
    return len(prints[0]) if prints else 0


def _busy() -> set[str]:
    """Meeting folders (projects/<p>/meetings/<m>/) that are being recorded or worked on."""
    from . import jobs, live
    out = {f"projects/{k.split('/', 1)[0]}/meetings/{k.split('/', 1)[1]}/" for k in jobs.status() if not k.endswith("/_state")}
    s = live.active()
    if s:
        out.add(f"projects/{s.pid}/meetings/{s.mid}/")
    return out


def _scan() -> tuple[dict[str, Path], set[str]]:
    """→ ({synced path: local file}, meeting folders to leave alone this time)."""
    home = store.HOME
    files, skip = {}, _busy()
    for d in sorted((home / "projects").glob("*/meetings/*/")):
        rel = d.relative_to(home).as_posix() + "/"
        if store.read_json(d / "meeting.json", default={}).get("status") != "ready":
            skip.add(rel)
    for f in list((home / "projects").glob("*/*")) + list((home / "projects").glob("*/state-history/*")) \
            + list((home / "projects").glob("*/meetings/*/*")) + list((home / "people").glob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(home).as_posix()
        if f.suffix in (".npy", ".npz"):
            rel = rel[:-4] + ".json"
        elif rel.endswith(".voices.json") or rel.endswith("/voices.json"):
            continue                              # the Mac's own copies are .npy / .npz
        if SYNCED.match(rel) and not any(rel.startswith(s) for s in skip):
            files[rel] = f
    return files, skip


# --- one round: send local changes, fetch others', apply the winners

def _beats(e: dict, cur: dict | None) -> bool:
    return cur is None or (e["t"], e["d"]) > (cur["t"], cur["d"])


def run_once() -> dict:
    c = config()
    if not c:
        return {}
    with _lock:
        _status.update(running=True)
        try:
            sent, received = _round(c)
            _status.update(last=time.time(), error=None, sent=sent, received=received)
            if received:
                _status["rev"] += 1            # tells open windows to re-read
        except SyncError as e:
            _status["error"] = str(e)
        except S3Error as e:
            _status["error"] = ("Cloudflare refused the key." if e.status in (401, 403) else f"Cloudflare said: {e}")
        except OSError as e:
            _status["error"] = f"Couldn't reach Cloudflare ({e.__class__.__name__}). Will retry."
        finally:
            _status["running"] = False
    return status()


def _round(c: dict) -> tuple[int, int]:
    s3, me = _client(c), c["device"]
    st = store.read_json(STATE, default={})
    st.setdefault("seq", 0); st.setdefault("clock", 0); st.setdefault("cursors", {})
    st.setdefault("files", {}); st.setdefault("known", {}); st.setdefault("pieces", [])
    files, known, pieces = st["files"], st["known"], set(st["pieces"])

    def save():
        st["pieces"] = sorted(pieces)
        STATE.parent.mkdir(exist_ok=True)
        store.write_json(STATE, st)

    # 1. local changes
    local, skip = _scan()
    changes = []
    for path, f in local.items():
        stt = f.stat()
        cur = files.get(path)
        if cur and not cur.get("x") and cur["s"] == stt.st_size and cur["m"] == stt.st_mtime_ns:
            continue
        data = _encode(path, f)
        parts = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)] or [b""]
        ids = [_piece_id(c, p) for p in parts]
        if cur and not cur.get("x") and cur["c"] == ids:
            cur.update(s=stt.st_size, m=stt.st_mtime_ns)
            continue
        for pid, part in zip(ids, parts):
            if pid not in pieces:
                s3.put(f"b/{pid}", _seal(c, part, pid))
                pieces.add(pid)
        st["clock"] += 1
        e = {"p": path, "c": ids, "s": len(data), "t": st["clock"], "d": me}
        changes.append(e)
        files[path] = {"s": stt.st_size, "m": stt.st_mtime_ns, "c": ids, "t": e["t"], "d": me}
        known[path] = e
    for path, cur in list(files.items()):
        if not cur.get("x") and path not in local and not any(path.startswith(s) for s in skip):
            st["clock"] += 1
            e = {"p": path, "x": True, "t": st["clock"], "d": me}
            changes.append(e)
            files[path] = {"x": True, "t": e["t"], "d": me}
            known[path] = e
    if changes:
        key = f"l/{me}/{st['seq'] + 1:010d}"
        s3.put(key, _seal(c, json.dumps({"v": 1, "changes": changes}).encode(), key))
        st["seq"] += 1
    save()

    # 2. other devices' changes
    _, prefixes = s3.list("l/", delimiter="/")
    for prefix in prefixes:
        dev = prefix[2:-1]
        if dev == me:
            continue
        keys, _ = s3.list(prefix, start_after=st["cursors"].get(dev, ""))
        for key in keys:
            raw = s3.get(key)
            if raw is None:
                continue
            for e in json.loads(_open(c, raw, key))["changes"]:
                if not SYNCED.match(e["p"]) or "/../" in e["p"]:
                    continue
                st["clock"] = max(st["clock"], e["t"])
                if _beats(e, known.get(e["p"])):
                    known[e["p"]] = e
                pieces.update(e.get("c", []))
            st["cursors"][dev] = key
    save()

    # 3. apply what other devices won: deletions first (folders go whole to the Trash), files before the
    #    meeting.json / project.json that makes them show up
    todo = [e for p, e in known.items() if e["d"] != me and (files.get(p, {}).get("t"), files.get(p, {}).get("d")) != (e["t"], e["d"])]
    todo.sort(key=lambda e: (not e.get("x"), (Path(e["p"]).name in META) != bool(e.get("x"))))
    busy = _busy()
    received = 0
    for e in todo:
        path = e["p"]
        if any(path.startswith(b) for b in busy):
            continue
        f = _local(path)
        if e.get("x"):
            if Path(path).name in META and f.parent.exists():
                store.trash(f.parent)
            elif f.exists():
                f.unlink()
            files[path] = {"x": True, "t": e["t"], "d": e["d"]}
        else:
            data = b"".join(_open(c, s3.get(f"b/{pid}") or b"", pid) for pid in e["c"])
            got = [_piece_id(c, data[i:i + CHUNK]) for i in range(0, len(data), CHUNK)] or [_piece_id(c, b"")]
            if got != e["c"]:
                raise SyncError("A synced file didn't match its checksum. Will retry.")
            f = _write(path, data)
            stt = f.stat()
            files[path] = {"s": stt.st_size, "m": stt.st_mtime_ns, "c": e["c"], "t": e["t"], "d": e["d"]}
        received += 1
        if received % 20 == 0:
            save()

    # 4. this device (said once a day), and the list of devices for Settings
    now = time.time()
    if now - st.get("announced", 0) > 86400:
        s3.put(f"d/{me}", _seal(c, json.dumps({"name": c["name"], "platform": "mac", "seen": now}).encode(), f"d/{me}"))
        st["announced"] = now
    if now - st.get("listed", 0) > 3600 or received:
        st["listed"] = now
        devices = []
        for key in s3.list("d/")[0]:
            raw = s3.get(key)
            if raw:
                info = json.loads(_open(c, raw, key))
                devices.append({**info, "me": key == f"d/{me}"})
        st["devices"] = sorted(devices, key=lambda d: (not d["me"], d["name"]))
    save()
    return len(changes), received


# --- background

def kick() -> None:
    _wake.set()


def _loop() -> None:
    time.sleep(5)
    while True:
        if config():
            run_once()
        _wake.wait(INTERVAL)
        _wake.clear()


def start() -> None:
    threading.Thread(target=_loop, daemon=True, name="folio-sync").start()
