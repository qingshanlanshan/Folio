"""Who spoke when: pyannote segmentation + speaker embeddings + clustering, via sherpa-onnx (no torch,
no Hugging Face token). Then each word gets the speaker whose turns overlap it most (as WhisperX does)."""
from dataclasses import dataclass

import numpy as np

from .audio import SR
from .paths import MODELS

EMBEDDERS = {
    "eres2net": "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
    "resnet34": "wespeaker_en_voxceleb_resnet34_LM.onnx",
    "titanet": "nemo_en_titanet_small.onnx",
}


@dataclass
class Turn:
    start: float
    end: float
    speaker: int


def diarize(pcm: np.ndarray, embedder: str = "eres2net", threshold: float = 0.5,
            num_speakers: int = -1, progress=None) -> list[Turn]:
    import sherpa_onnx as so
    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(MODELS / "pyannote-segmentation-3-0.onnx")),
            num_threads=4),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=str(MODELS / EMBEDDERS[embedder]), num_threads=4),
        clustering=so.FastClusteringConfig(num_clusters=num_speakers, threshold=threshold),
        min_duration_on=0.3, min_duration_off=0.5)
    if not cfg.validate():
        raise RuntimeError("bad diarization config")
    sd = so.OfflineSpeakerDiarization(cfg)
    cb = (lambda done, total: progress(done / max(total, 1)) or 0) if progress else None
    segs = sd.process(pcm, callback=cb).sort_by_start_time()
    return [Turn(s.start, s.end, s.speaker) for s in segs]


def assign_speakers(words, turns: list[Turn]) -> None:
    """Sets word.speaker to the speaker with the most overlap, else the nearest turn."""
    if not turns:
        return
    starts = np.array([t.start for t in turns])
    ends = np.array([t.end for t in turns])
    spk = np.array([t.speaker for t in turns])
    mids = (starts + ends) / 2
    for w in words:
        ov = np.minimum(ends, w.end) - np.maximum(starts, w.start)
        hit = ov > 0
        if hit.any():
            totals: dict[int, float] = {}
            for s, o in zip(spk[hit], ov[hit]):
                totals[int(s)] = totals.get(int(s), 0.0) + float(o)
            w.speaker = max(totals, key=totals.get)
        else:
            w.speaker = int(spk[np.argmin(np.abs(mids - (w.start + w.end) / 2))])


# --- Spectral pipeline: VAD → sliding-window embeddings → spectral clustering with the speaker count
# picked by the eigengap (as 3D-Speaker and NeMo do). Robust across rooms without a threshold to tune.

WIN, STEP = 1.5, 0.75


def speech_regions(pcm: np.ndarray) -> list[tuple[float, float]]:
    import sherpa_onnx as so
    c = so.VadModelConfig()
    c.sample_rate = SR
    c.silero_vad.model = str(MODELS / "silero_vad.onnx")
    c.silero_vad.min_silence_duration = 0.3
    c.silero_vad.min_speech_duration = 0.25
    c.silero_vad.max_speech_duration = 30
    vad = so.VoiceActivityDetector(c, buffer_size_in_seconds=60)
    n = c.silero_vad.window_size
    out = []
    for i in range(0, len(pcm) - n + 1, n):
        vad.accept_waveform(pcm[i:i + n])
        while not vad.empty():
            s = vad.front
            out.append((s.start / SR, (s.start + len(s.samples)) / SR))
            vad.pop()
    vad.flush()
    while not vad.empty():
        s = vad.front
        out.append((s.start / SR, (s.start + len(s.samples)) / SR))
        vad.pop()
    return out


class Embedder:
    def __init__(self, name: str = "resnet34"):
        import sherpa_onnx as so
        self.ex = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(
            model=str(MODELS / EMBEDDERS[name]), num_threads=4))

    def __call__(self, pcm: np.ndarray) -> np.ndarray:
        st = self.ex.create_stream()
        st.accept_waveform(SR, pcm)
        st.input_finished()
        e = np.array(self.ex.compute(st), np.float32)
        return e / (np.linalg.norm(e) + 1e-9)


def _windows(regions):
    for a, b in regions:
        if b - a < 0.4:
            continue
        if b - a <= WIN:
            yield a, b
            continue
        t = a
        while t + WIN < b:
            yield t, t + WIN
            t += STEP
        yield max(a, b - WIN), b


def _spectral(emb: np.ndarray, max_k: int = 10, min_k: int = 1, num: int = -1) -> np.ndarray:
    from scipy.cluster.vq import kmeans2
    n = len(emb)
    if n < 4:
        return np.zeros(n, int)
    sim = emb @ emb.T
    # Keep each row's strongest links only (p-pruning), then symmetrize.
    keep = max(2, int(n * 0.1))
    A = np.zeros_like(sim)
    idx = np.argpartition(-sim, keep, axis=1)[:, :keep]
    rows = np.arange(n)[:, None]
    A[rows, idx] = sim[rows, idx]
    A = np.maximum((A + A.T) / 2, 0)
    np.fill_diagonal(A, 0)
    d = A.sum(1)
    L = np.diag(d) - A
    vals, vecs = np.linalg.eigh(L)
    if num > 0:
        k = num
    else:
        gaps = np.diff(vals[: max_k + 1])
        k = max(min_k, int(np.argmax(gaps[:max_k])) + 1)
    if k == 1:
        return np.zeros(n, int)
    X = vecs[:, :k]
    X = X / (np.linalg.norm(X, axis=1, keepdims=True) + 1e-9)
    rng = np.random.default_rng(0)
    best, best_inertia = None, np.inf
    for _ in range(5):
        cent, lab = kmeans2(X, k, minit="++", seed=rng)
        inertia = ((X - cent[lab]) ** 2).sum()
        if inertia < best_inertia:
            best, best_inertia = lab, inertia
    return best


def diarize_spectral(pcm: np.ndarray, embedder: str = "resnet34", num_speakers: int = -1,
                     min_share: float = 0.02, progress=None):
    """Returns (turns, {speaker: centroid embedding})."""
    regions = speech_regions(pcm)
    wins = list(_windows(regions))
    emb_fn = Embedder(embedder)
    embs = []
    for i, (a, b) in enumerate(wins):
        embs.append(emb_fn(pcm[int(a * SR):int(b * SR)]))
        if progress and i % 50 == 0:
            progress(i / len(wins))
    if not embs:
        return [], {}
    E = np.stack(embs)
    lab = _spectral(E, num=num_speakers)
    # Fold clusters holding under min_share of the speech into the nearest big one.
    durs = np.array([b - a for a, b in wins])
    while True:
        ids = np.unique(lab)
        share = {c: durs[lab == c].sum() / durs.sum() for c in ids}
        small = [c for c in ids if share[c] < min_share]
        if not small or len(ids) == 1:
            break
        c = min(small, key=share.get)
        cents = {o: E[lab == o].mean(0) for o in ids if o != c}
        mine = E[lab == c].mean(0)
        lab[lab == c] = max(cents, key=lambda o: cents[o] @ mine)
    # Relabel by first appearance; centroids for matching to known people.
    order = {}
    for c in lab:
        order.setdefault(int(c), len(order))
    lab = np.array([order[int(c)] for c in lab])
    cents = {}
    for c in np.unique(lab):
        m = E[lab == c].mean(0)
        cents[int(c)] = m / (np.linalg.norm(m) + 1e-9)
    # Each instant belongs to the window whose centre is nearest; then merge runs.
    mids = np.array([(a + b) / 2 for a, b in wins])
    turns: list[Turn] = []
    for a, b in regions:
        t, step = a, 0.05
        while t < b:
            spk = int(lab[np.argmin(np.abs(mids - t))])
            if turns and turns[-1].speaker == spk and t - turns[-1].end < 0.11:
                turns[-1].end = min(t + step, b)
            else:
                turns.append(Turn(t, min(t + step, b), spk))
            t += step
    return turns, cents
