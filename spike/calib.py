"""How similar are voice centroids of the same person vs different people (TitaNet)?"""
import numpy as np
from folio import audio, diarize
from spike.refs import CLIPS
from spike.bench import der

for clip in ("ami", "singlish"):
    path, _, ref = CLIPS[clip]()
    pcm = audio.load(path)
    half = len(pcm) // 2
    out = []
    for part in (pcm[:half], pcm[half:]):
        turns, cents = diarize.diarize_spectral(part, embedder="titanet")
        out.append(cents)
    a, b = out
    print(clip, "speakers per half:", len(a), len(b))
    within = [float(a[i] @ a[j]) for i in a for j in a if i < j]
    print("  different people, same half:", np.round(sorted(within), 2))
    cross = np.array([[a[i] @ b[j] for j in b] for i in a])
    print("  across halves (rows half1, cols half2):\n", np.round(cross, 2))
