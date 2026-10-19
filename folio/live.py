"""Live recording: runs bin/folio-capture, saves each track, and transcribes speech as it happens.

The live text is a draft: speech is cut at pauses (VAD) and each piece is transcribed when it ends,
with a rough partial every ~1.5 s while someone is still talking. After Stop, the meeting goes through
the full pipeline (whole-file transcription + speakers), which replaces the draft.
"""
import json
import os
import queue
import signal
import struct
import subprocess
import threading
import time
import wave

import numpy as np

from . import store
from .audio import SR
from .paths import CAPTURE, MODELS

HELPER = CAPTURE
TRACKS = {0: "mic", 1: "system"}
PARTIAL_EVERY = 1.5
MAX_PIECE = 20.0

_session: "Session | None" = None
_lock = threading.Lock()


def active() -> "Session | None":
    return _session if _session and not _session.done else None


class Track:
    def __init__(self, idx: int, d):
        import sherpa_onnx as so
        self.idx, self.name = idx, TRACKS[idx]
        self.wav = wave.open(str(d / f"{self.name}.wav"), "wb")
        self.wav.setnchannels(1)
        self.wav.setsampwidth(2)
        self.wav.setframerate(SR)
        c = so.VadModelConfig()
        c.sample_rate = SR
        c.silero_vad.model = str(MODELS / "silero_vad.onnx")
        c.silero_vad.min_silence_duration = 0.5
        c.silero_vad.max_speech_duration = MAX_PIECE
        self.vad = so.VoiceActivityDetector(c, buffer_size_in_seconds=120)
        self.win = c.silero_vad.window_size
        self.pending = np.zeros(0, np.float32)   # not yet fed to the VAD (needs whole windows)
        self.samples = 0                          # total received
        self.audio = []                           # recent audio for partials: list of arrays
        self.audio_start = 0                      # sample index of self.audio[0]
        self.last_end = 0                         # end (samples) of the last finished piece
        self.last_partial = 0.0
        self.level = 0.0
        self.lock = threading.Lock()   # audio is appended by the reader and read by the ASR thread

    def tail(self, start: int) -> np.ndarray:
        with self.lock:
            a = np.concatenate(self.audio) if self.audio else np.zeros(0, np.float32)
            return a[max(0, start - self.audio_start):]

    def trim(self) -> None:
        # Keep only audio after the last finished piece (partials never need more).
        with self.lock:
            if not self.audio:
                return
            a = np.concatenate(self.audio)
            cut = max(0, self.last_end - self.audio_start - SR)  # one second of slack
            self.audio, self.audio_start = [a[cut:]], self.audio_start + cut


class Session:
    def __init__(self, pid: str, title: str, mic: bool, system: bool, simulate: str | None = None):
        self.pid, self.title = pid, title
        self.target = pid               # where to file it; can change while recording
        self.mic, self.system = mic, system
        self.started = time.time()
        self.mid, self.dir = store.create_meeting(pid, title, self.started, "recording", None)
        store.update_meeting(pid, self.mid, status="recording",
                             tracks=[n for n, on in (("mic", mic), ("system", system)) if on])
        self.events: list[dict] = []          # finished pieces, in order
        self.partials: dict[int, dict] = {}   # track -> latest partial
        self.error = ""
        self.done = False
        self.stopping = False
        self.tracks: dict[int, Track] = {}
        self.asr_q: "queue.Queue[tuple]" = queue.Queue()
        self.log = open(self.dir / "live.jsonl", "a", encoding="utf-8")
        self.simulate = simulate
        self.proc = None
        if simulate:
            threading.Thread(target=self._simulate, daemon=True).start()
        else:
            args = [str(HELPER)] + (["--mic"] if mic else []) + (["--system"] if system else [])
            if mic and system:
                args.append("--aec")
            self.proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE, bufsize=0)
            threading.Thread(target=self._read, daemon=True).start()
            threading.Thread(target=self._stderr, daemon=True).start()
        threading.Thread(target=self._asr_worker, daemon=True).start()

    # --- input

    def _track(self, idx: int) -> Track:
        if idx not in self.tracks:
            self.tracks[idx] = Track(idx, self.dir)
        return self.tracks[idx]

    def _read(self) -> None:
        f = self.proc.stdout
        while True:
            head = f.read(5)
            if not head or len(head) < 5:
                break
            idx, n = head[0], struct.unpack("<I", head[1:])[0]
            body = b""
            while len(body) < 4 * n:
                chunk = f.read(4 * n - len(body))
                if not chunk:
                    break
                body += chunk
            self.feed(idx, np.frombuffer(body, np.float32))
        self._finish()

    def _stderr(self) -> None:
        for line in self.proc.stderr:
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            if ev.get("event") == "error":
                self.error = ev.get("message", "capture failed")

    def _simulate(self) -> None:
        """Development: plays a file into the mic track at real time."""
        from .audio import load
        pcm = load(self.simulate)
        step = SR // 10
        t0 = time.time()
        for i in range(0, len(pcm), step):
            if self.stopping:
                break
            self.feed(0, pcm[i:i + step])
            wait = t0 + (i + step) / SR - time.time()
            if wait > 0:
                time.sleep(wait)
        self._finish()

    def feed(self, idx: int, x: np.ndarray) -> None:
        if idx not in TRACKS or not len(x):
            return
        t = self._track(idx)
        t.wav.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        with t.lock:
            t.audio.append(x.copy())
        t.samples += len(x)
        t.level = 0.7 * t.level + 0.3 * float(np.sqrt(np.mean(x ** 2)))
        t.pending = np.concatenate([t.pending, x])
        while len(t.pending) >= t.win:
            t.vad.accept_waveform(t.pending[:t.win])
            t.pending = t.pending[t.win:]
        self._collect(t)
        now = t.samples / SR
        if t.vad.is_speech_detected() and now - t.last_partial >= PARTIAL_EVERY:
            t.last_partial = now
            self.asr_q.put(("partial", idx, t.last_end, None))

    def _collect(self, t: Track) -> None:
        while not t.vad.empty():
            s = t.vad.front
            start, pcm = s.start, np.array(s.samples, np.float32)
            t.vad.pop()
            t.last_end = start + len(pcm)
            self.asr_q.put(("final", t.idx, start, pcm))
            t.trim()

    def _finish(self) -> None:
        for t in self.tracks.values():
            t.vad.flush()
            self._collect(t)
            t.wav.close()
        self.asr_q.put(None)

    # --- transcription

    def _asr_worker(self) -> None:
        from .jobs import asr_model
        while True:
            item = self.asr_q.get()
            if item is None:
                break
            kind, idx, start, pcm = item
            if kind == "partial":
                # Skip stale partials: a newer one (or a final) for this track is already queued.
                if any(q and q[1] == idx for q in list(self.asr_q.queue)):
                    continue
                pcm = self.tracks[idx].tail(start)
                if len(pcm) < SR // 2:
                    continue
            try:
                words = asr_model().transcribe(pcm)
            except Exception as e:
                self.error = f"transcription: {e}"
                continue
            off = start / SR
            text = " ".join(w.text for w in words)
            if kind == "partial":
                self.partials[idx] = {"track": TRACKS[idx], "start": round(off, 2), "text": text}
                continue
            self.partials.pop(idx, None)
            if not text:
                continue
            ev = {"seq": len(self.events), "track": TRACKS[idx], "start": round(off, 2),
                  "end": round(off + len(pcm) / SR, 2), "text": text,
                  "words": [{"text": w.text, "start": round(w.start + off, 3), "end": round(w.end + off, 3),
                             "conf": round(float(w.conf), 3)} for w in words]}
            self.events.append(ev)
            self.log.write(json.dumps(ev, ensure_ascii=False) + "\n")
            self.log.flush()
        self.log.close()
        self._save()

    def _save(self) -> None:
        """Tracks → FLAC next to the meeting, then queue the full pipeline."""
        from . import jobs
        from .audio import load, save_flac
        for name in TRACKS.values():
            w = self.dir / f"{name}.wav"
            if w.exists():
                if w.stat().st_size > 44 + SR:  # over ~0.5 s
                    save_flac(load(w), self.dir / f"{name}.flac")
                w.unlink()
        dur = max([t.samples for t in self.tracks.values()] + [0]) / SR
        if dur < 1:
            store.update_meeting(self.pid, self.mid, status="error", duration=dur,
                                 error=self.error or "Nothing was recorded.")
            self.done = True
            return
        store.update_meeting(self.pid, self.mid, duration=round(dur, 2), status="queued")
        if self.target != self.pid:
            from .server import move
            self.mid = move(self.pid, self.mid, self.target, summarize_after=False)
            self.pid = self.target
        jobs.submit(self.pid, self.mid)
        self.done = True        # last: the page reads pid/mid once this is set

    # --- control

    def stop(self) -> None:
        if self.stopping:
            return
        self.stopping = True
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGINT)

    def state(self, since: int = 0) -> dict:
        return {
            "pid": self.pid, "mid": self.mid, "title": self.title, "started": self.started, "target": self.target,
            "elapsed": round(max([t.samples for t in self.tracks.values()] + [0]) / SR, 1),
            "levels": {TRACKS[i]: round(t.level, 4) for i, t in self.tracks.items()},
            "tracks": [n for n, on in (("mic", self.mic), ("system", self.system)) if on],
            "events": self.events[since:], "partials": list(self.partials.values()),
            "error": self.error, "stopping": self.stopping, "done": self.done,
        }


def start(pid: str, title: str, mic: bool, system: bool, simulate: str | None = None) -> Session:
    global _session
    with _lock:
        if active():
            raise RuntimeError("Already recording.")
        if not simulate and not HELPER.exists():
            raise RuntimeError("Capture helper missing: run capture/build.sh")
        _session = Session(pid, title, mic, system, simulate if os.environ.get("FOLIO_DEV") else None)
        return _session
