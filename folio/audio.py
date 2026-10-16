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
