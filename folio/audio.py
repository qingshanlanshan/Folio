"""Decode any audio or video file to 16 kHz mono float32. PyAV bundles FFmpeg, so no system install."""
from pathlib import Path

import av
import numpy as np

SR = 16000


def load(path: str | Path) -> np.ndarray:
    out = []
    with av.open(str(path)) as c:
        rs = av.AudioResampler(format="flt", layout="mono", rate=SR)
        for frame in c.decode(c.streams.audio[0]):
            out += [f.to_ndarray().reshape(-1) for f in rs.resample(frame)]
        out += [f.to_ndarray().reshape(-1) for f in rs.resample(None)]
    return np.concatenate(out).astype(np.float32) if out else np.zeros(0, np.float32)


def save_m4a(pcm: np.ndarray, path: str | Path, bitrate: int = 48000) -> None:
    """AAC copy for playback in the browser (any imported format plays the same way)."""
    with av.open(str(path), "w", format="mp4") as c:
        s = c.add_stream("aac", rate=SR, layout="mono")
        s.bit_rate = bitrate
        step = 1024
        for i in range(0, len(pcm), step):
            chunk = pcm[i:i + step].astype(np.float32).reshape(1, -1)
            f = av.AudioFrame.from_ndarray(chunk, format="fltp", layout="mono")
            f.sample_rate = SR
            f.pts = i
            for p in s.encode(f):
                c.mux(p)
        for p in s.encode(None):
            c.mux(p)


def save_flac(pcm: np.ndarray, path: str | Path) -> None:
    """Lossless copy of a recorded track (about half the size of WAV)."""
    with av.open(str(path), "w", format="flac") as c:
        s = c.add_stream("flac", rate=SR, layout="mono")
        step = 4096
        x = (np.clip(pcm, -1, 1) * 32767).astype(np.int16)
        for i in range(0, len(x), step):
            f = av.AudioFrame.from_ndarray(x[i:i + step].reshape(1, -1), format="s16", layout="mono")
            f.sample_rate = SR
            f.pts = i
            for p in s.encode(f):
                c.mux(p)
        for p in s.encode(None):
            c.mux(p)
