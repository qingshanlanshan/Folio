"""One background worker runs the heavy steps one at a time (they share the GPU and 16 GB of memory).
Models load on first use and stay loaded. While a live recording runs, queued work waits so the live
transcript stays responsive."""
import queue
import re
import threading
import time
import traceback
import uuid

import numpy as np

from . import audio, diarize, runtime, store, transcript
from .asr import Word

ASR_MODEL = "parakeet"
EMBEDDER = "titanet"

_q: "queue.Queue[dict]" = queue.Queue()
_status: dict[str, dict] = {}       # "pid/mid" -> {step, progress}
_models: dict[str, object] = {}
_lock = threading.Lock()
_load_lock = threading.Lock()
HANDLERS = {}                        # kind -> fn(job, key); later phases register more


def asr_model():
    with _load_lock:
        if "asr" not in _models:
            from .asr import BACKENDS
            _models["asr"] = runtime.loading("asr", "Parakeet TDT 0.6B", "Speech to text",
                                             BACKENDS[ASR_MODEL], lambda: _models.pop("asr", None))
        return _models["asr"]


def submit(pid: str, mid: str, kind: str = "process", **opts) -> None:
    key = f"{pid}/{mid}"
    jid = uuid.uuid4().hex[:8]
    with _lock:
        if key in _status:
            return
        _status[key] = {"step": "queued", "progress": 0.0, "kind": kind, "jid": jid, "queued": time.time()}
    if kind == "process":
        store.update_meeting(pid, mid, status="queued", error="")
    _q.put({"pid": pid, "mid": mid, "kind": kind, "jid": jid, **opts})


def cancel(key: str) -> bool:
    """Removes a queued task, or asks the running one to stop at its next checkpoint."""
    with _lock:
        st = _status.get(key)
        if not st:
            return False
        queued = st["step"] == "queued" and not st.get("running")
        if queued:
            _status.pop(key)
        else:
            st["stopping"] = True
    if queued:
        _stopped(key, st["kind"])
    else:
        runtime.cancel(key)
    return True


def running() -> bool:
    with _lock:
        return any(v.get("running") for v in _status.values())


def _stopped(key: str, kind: str) -> None:
    pid, mid = key.split("/", 1)
    if kind != "process":
        return
    try:
        if runtime.quitting:
            store.update_meeting(pid, mid, status="queued")      # resume next time
        else:
            store.update_meeting(pid, mid, status="stopped", error="Stopped before transcription finished.")
    except store.NotFound:
        pass


def status() -> dict[str, dict]:
    with _lock:
        return {k: dict(v) for k, v in _status.items()}


def set_step(key: str, step: str, progress: float = 0.0) -> None:
    with _lock:
        if key in _status:
            _status[key].update(step=step, progress=round(progress, 3))
    runtime.check()


# --- transcription + speakers

def load_tracks(d) -> dict[str, np.ndarray]:
    """Recorded meetings have mic.flac and/or system.flac; imports have original.*"""
    tracks = {n: audio.load(d / f"{n}.flac") for n in ("mic", "system") if (d / f"{n}.flac").exists()}
    if not tracks:
        tracks = {"main": audio.load(store.original_file(d))}
    n = max(len(a) for a in tracks.values())
    return {k: np.pad(a, (0, n - len(a))) for k, a in tracks.items()}


def mix(tracks: dict[str, np.ndarray]) -> np.ndarray:
    if len(tracks) == 1:
        return next(iter(tracks.values()))
    m = sum(tracks.values())
    peak = float(np.abs(m).max()) or 1.0
    return (m / max(peak, 1.0)).astype(np.float32)


def _norm(w: str) -> str:
    return re.sub(r"[^a-z0-9']", "", w.lower())


def drop_echo(mic: list[Word], system: list[Word]) -> list[Word]:
    """Without headphones the mic also hears the other side; drop mic words the system track has too."""
    if not system:
        return mic
    times = np.array([w.start for w in system])
    texts = [_norm(w.text) for w in system]
    keep = []
    for w in mic:
        lo, hi = np.searchsorted(times, [w.start - 1.0, w.start + 1.0])
        if _norm(w.text) and _norm(w.text) in texts[lo:hi]:
            continue
        keep.append(w)
    return keep


def _process(job: dict, key: str) -> None:
    pid, mid = job["pid"], job["mid"]
    d = store.meeting_dir(pid, mid)
    set_step(key, "decoding")
    tracks = load_tracks(d)
    full = mix(tracks)
    if len(full) < audio.SR // 2:
        raise ValueError("No audio found.")
    audio.save_m4a(full, d / "audio.m4a")
    store.update_meeting(pid, mid, status="processing", duration=round(len(full) / audio.SR, 2))

    words_by: dict[str, list[Word]] = {}
    for i, (name, pcm) in enumerate(tracks.items()):
        set_step(key, "transcribing")
        words_by[name] = asr_model().transcribe(
            pcm, progress=lambda f, i=i: set_step(key, "transcribing", (i + f) / len(tracks)))
    names = {}
    voices = {}
    if set(tracks) == {"mic", "system"}:
        # Online meeting: the mic is you; only the other side needs telling apart.
        mic = drop_echo(words_by["mic"], words_by["system"])
        for w in mic:
            w.speaker = 0
        names["0"] = "Me"
        _, cents = diarize.diarize_spectral(tracks["mic"], embedder=EMBEDDER, num_speakers=1)
        if 0 in cents:
            voices[0] = cents[0]
        sys_words = words_by["system"]
        _speakers(tracks["system"], sys_words, key, job.get("speakers", -1), voices, offset=1)
        words = sorted(mic + sys_words, key=lambda w: w.start)
    else:
        name, pcm = next(iter(tracks.items()))
        words = words_by[name]
        _speakers(pcm, words, key, job.get("speakers", -1), voices)
    np.savez(d / "voices.npz", **{str(k): v for k, v in voices.items()})
    t = transcript.build(words, asr=ASR_MODEL)
    for k, v in names.items():
        t["speakers"].setdefault(k, {})["name"] = v
    store.write_json(d / "transcript.json", t)
    after_transcript(pid, mid)


def _speakers(pcm, words, key, num: int, voices: dict, offset: int = 0) -> None:
    set_step(key, "speakers")
    turns, cents = diarize.diarize_spectral(pcm, embedder=EMBEDDER, num_speakers=num,
                                            progress=lambda f: set_step(key, "speakers", f))
    for t in turns:
        t.speaker += offset
    diarize.assign_speakers(words, turns)
    voices.update({k + offset: v for k, v in cents.items()})


def _redo_speakers(job: dict, key: str) -> None:
    """Re-detect speakers only (e.g. with a given count), keeping any text edits."""
    pid, mid = job["pid"], job["mid"]
    d = store.meeting_dir(pid, mid)
    t = store.read_json(d / "transcript.json")
    tracks = load_tracks(d)
    online = set(tracks) == {"mic", "system"}
    pcm = tracks["system"] if online else mix(tracks)
    idx = [i for i, w in enumerate(t["words"]) if not (online and w["speaker"] == 0)]
    words = [Word(t["words"][i]["text"], t["words"][i]["start"], t["words"][i]["end"]) for i in idx]
    voices = {}
    if online and (d / "voices.npz").exists():
        old = np.load(d / "voices.npz")
        if "0" in old:
            voices[0] = old["0"]
    _speakers(pcm, words, key, job.get("speakers", -1), voices, offset=1 if online else 0)
    for i, w in zip(idx, words):
        t["words"][i]["speaker"] = w.speaker
    used = sorted({w["speaker"] for w in t["words"] if w["speaker"] is not None})
    keep = {str(s): t["speakers"].get(str(s), {"name": None}) for s in used if online and s == 0}
    t["speakers"] = {**{str(s): {"name": None} for s in used}, **keep}
    np.savez(d / "voices.npz", **{str(k): v for k, v in voices.items()})
    store.write_json(d / "transcript.json", t)
    after_transcript(pid, mid)


def after_transcript(pid: str, mid: str) -> None:
    """Hook for later steps (matching speakers to known people)."""
    for fn in AFTER:
        try:
            fn(pid, mid)
        except Exception:
            traceback.print_exc()


AFTER: list = []
HANDLERS.update(process=_process, speakers=_redo_speakers)
FOLLOW_UP: dict[str, str] = {}       # kind -> kind to queue after it succeeds (e.g. process -> summarize)


def _worker() -> None:
    from . import live
    while True:
        job = _q.get()
        key = f"{job['pid']}/{job['mid']}"
        while live.active():
            time.sleep(1)
        with _lock:
            st = _status.get(key)
            if not st or st.get("jid") != job.get("jid"):
                continue                     # removed from the queue
            st["running"] = True
        t0 = time.time()
        ok = False
        main = job["kind"] in ("process", "speakers")
        runtime.begin_job(key)
        try:
            if main:
                store.update_meeting(job["pid"], job["mid"], status="processing")
            HANDLERS[job["kind"]](job, key)
            ok = True
            if not main:
                store.update_meeting(job["pid"], job["mid"], jobError="")
            if main:
                store.update_meeting(job["pid"], job["mid"], status="ready", error="",
                                     processedIn=round(time.time() - t0, 1))
        except runtime.Cancelled:
            if job["kind"] == "speakers":
                try:
                    store.update_meeting(job["pid"], job["mid"], status="ready")
                except store.NotFound:
                    pass
            _stopped(key, job["kind"])
        except store.NotFound:
            pass  # deleted while queued
        except Exception as e:
            traceback.print_exc()
            try:
                if main:
                    store.update_meeting(job["pid"], job["mid"], status="error", error=str(e)[:500])
                else:
                    store.update_meeting(job["pid"], job["mid"], jobError=f"{job['kind']}: {str(e)[:300]}")
            except store.NotFound:
                pass
        finally:
            runtime.end_job()
            with _lock:
                _status.pop(key, None)
        if not ok:
            continue
        if job["kind"] in FOLLOW_UP:
            try:
                submit(job["pid"], job["mid"], FOLLOW_UP[job["kind"]])
            except store.NotFound:
                pass
        else:
            _tell_done(job)


DONE = {"process": "is transcribed", "speakers": "has new speaker labels", "summarize": "is transcribed and summarized",
        "correct": "has been checked for fixes"}


def _tell_done(job: dict) -> None:
    try:
        title = store.read_json(store.meeting_dir(job["pid"], job["mid"]) / "meeting.json")["title"]
    except store.NotFound:
        return
    runtime.when_no_window("Folio", f"“{title}” {DONE.get(job['kind'], 'is done')}.")


def start() -> None:
    threading.Thread(target=_worker, daemon=True, name="folio-jobs").start()
    # Resume anything interrupted by a quit.
    for p in store.list_projects():
        for m in store.list_meetings(p["id"]):
            if m.get("status") in ("queued", "processing", "recording"):
                d = store.meeting_dir(p["id"], m["id"])
                if (d / "transcript.json").exists():
                    store.update_meeting(p["id"], m["id"], status="ready")
                elif m.get("status") == "recording":
                    _recover(p["id"], m["id"], d)
                else:
                    submit(p["id"], m["id"], "process")


def _recover(pid: str, mid: str, d) -> None:
    """A recording cut off by a crash: keep whatever audio reached disk."""
    found = False
    for name in ("mic", "system"):
        w = d / f"{name}.wav"
        if w.exists() and w.stat().st_size > 44 + audio.SR:
            raw = np.frombuffer(w.read_bytes()[44:], "<i2").astype(np.float32) / 32767
            audio.save_flac(raw, d / f"{name}.flac")
            w.unlink()
            found = True
    if found:
        submit(pid, mid, "process")
    else:
        store.update_meeting(pid, mid, status="error", error="The recording was interrupted.")
