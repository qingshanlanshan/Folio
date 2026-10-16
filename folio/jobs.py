"""One background worker runs the heavy steps one at a time (they share the GPU and 16 GB of memory).
Models load on first use and stay loaded."""
import queue
import threading
import time
import traceback

import numpy as np

from . import audio, diarize, store, transcript
from .asr import Word

ASR_MODEL = "parakeet"
EMBEDDER = "titanet"

_q: "queue.Queue[dict]" = queue.Queue()
_status: dict[str, dict] = {}       # "pid/mid" -> {step, progress}
_models: dict[str, object] = {}
_lock = threading.Lock()


def _asr():
    if "asr" not in _models:
        from .asr import BACKENDS
        _models["asr"] = BACKENDS[ASR_MODEL]()
    return _models["asr"]


def submit(pid: str, mid: str, kind: str = "process", **opts) -> None:
    key = f"{pid}/{mid}"
    with _lock:
        if key in _status:
            return
        _status[key] = {"step": "queued", "progress": 0.0, "kind": kind}
    store.update_meeting(pid, mid, status="queued", error="")
    _q.put({"pid": pid, "mid": mid, "kind": kind, **opts})


def status() -> dict[str, dict]:
    with _lock:
        return {k: dict(v) for k, v in _status.items()}


def _set(key: str, step: str, progress: float = 0.0) -> None:
    with _lock:
        if key in _status:
            _status[key].update(step=step, progress=round(progress, 3))


def _process(job: dict, key: str) -> None:
    pid, mid = job["pid"], job["mid"]
    d = store.meeting_dir(pid, mid)
    _set(key, "decoding")
    pcm = audio.load(store.original_file(d))
    if len(pcm) < audio.SR // 2:
        raise ValueError("No audio found in this file.")
    audio.save_m4a(pcm, d / "audio.m4a")
    store.update_meeting(pid, mid, status="processing", duration=round(len(pcm) / audio.SR, 2))

    _set(key, "transcribing")
    words = _asr().transcribe(pcm, progress=lambda f: _set(key, "transcribing", f))
    _speakers(pcm, words, d, key, job.get("speakers", -1))
    t = transcript.build(words, asr=ASR_MODEL)
    store.write_json(d / "transcript.json", t)


def _speakers(pcm, words, d, key, num: int) -> None:
    _set(key, "speakers")
    turns, cents = diarize.diarize_spectral(pcm, embedder=EMBEDDER, num_speakers=num,
                                            progress=lambda f: _set(key, "speakers", f))
    diarize.assign_speakers(words, turns)
    # Voice centroids per speaker, for matching to known people later.
    np.savez(d / "voices.npz", **{str(k): v for k, v in cents.items()})


def _redo_speakers(job: dict, key: str) -> None:
    """Re-detect speakers only (e.g. with a given count), keeping any text edits."""
    pid, mid = job["pid"], job["mid"]
    d = store.meeting_dir(pid, mid)
    t = store.read_json(d / "transcript.json")
    pcm = audio.load(d / "audio.m4a")
    words = [Word(w["text"], w["start"], w["end"], w.get("conf", 1.0)) for w in t["words"]]
    _speakers(pcm, words, d, key, job.get("speakers", -1))
    for w, new in zip(t["words"], words):
        w["speaker"] = new.speaker
    t["speakers"] = {str(s): {"name": None} for s in sorted({w.speaker for w in words if w.speaker is not None})}
    store.write_json(d / "transcript.json", t)


def _worker() -> None:
    while True:
        job = _q.get()
        key = f"{job['pid']}/{job['mid']}"
        t0 = time.time()
        try:
            store.update_meeting(job["pid"], job["mid"], status="processing")
            {"process": _process, "speakers": _redo_speakers}[job["kind"]](job, key)
            store.update_meeting(job["pid"], job["mid"], status="ready", error="",
                                 processedIn=round(time.time() - t0, 1))
        except store.NotFound:
            pass  # deleted while queued
        except Exception as e:
            traceback.print_exc()
            try:
                store.update_meeting(job["pid"], job["mid"], status="error", error=str(e)[:500])
            except store.NotFound:
                pass
        finally:
            with _lock:
                _status.pop(key, None)


def start() -> None:
    threading.Thread(target=_worker, daemon=True, name="folio-jobs").start()
    # Resume anything interrupted by a quit.
    for p in store.list_projects():
        for m in store.list_meetings(p["id"]):
            if m.get("status") in ("queued", "processing"):
                d = store.meeting_dir(p["id"], m["id"])
                if (d / "transcript.json").exists():
                    store.update_meeting(p["id"], m["id"], status="ready")
                else:
                    submit(p["id"], m["id"], "process")
