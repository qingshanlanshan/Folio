"""Accuracy and speed of ASR backends and diarization on the test clips.
usage: python -m spike.bench asr parakeet ami | diar eres2net ami"""
import json
import sys
import time

import jiwer
import numpy as np
from scipy.optimize import linear_sum_assignment

from folio import asr, audio, diarize
from spike.refs import CLIPS, T, norm


def der(ref, hyp, step=0.01):
    end = max(e for _, e, _ in ref + hyp)
    n = int(end / step) + 1
    def grid(turns):
        labels = sorted({s for *_, s in turns})
        g = np.zeros((len(labels), n), bool)
        for s, e, spk in turns:
            g[labels.index(spk), int(s / step):int(e / step)] = True
        return g
    R, H = grid(ref), grid(hyp)
    overlap = R.astype(np.int32) @ H.T.astype(np.int32)
    ri, hi = linear_sum_assignment(-overlap)
    correct = np.zeros(n, np.int32)
    for r, h in zip(ri, hi):
        correct += R[r] & H[h]
    nr, nh = R.sum(0), H.sum(0)
    miss = np.maximum(nr - nh, 0).sum() / nr.sum()
    fa = np.maximum(nh - nr, 0).sum() / nr.sum()
    total = (np.maximum(nr, nh) - correct).sum() / nr.sum()
    return dict(der=total, miss=miss, fa=fa, conf=total - miss - fa, ref_spk=len(R), hyp_spk=len(H))


def main():
    kind, which, clip = sys.argv[1:4]
    path, ref_text, ref_turns = CLIPS[clip]()
    pcm = audio.load(path)
    dur = len(pcm) / audio.SR
    out = T / "out"
    out.mkdir(exist_ok=True)
    if kind == "asr":
        t0 = time.time()
        model = asr.BACKENDS[which]()
        t1 = time.time()
        words = model.transcribe(pcm)
        t2 = time.time()
        hyp = " ".join(w.text for w in words)
        json.dump([w.dict() for w in words], open(out / f"{clip}.{which}.json", "w"))
        m = jiwer.process_words(norm(ref_text), norm(hyp))
        print(f"{which} on {clip} ({dur/60:.1f} min): WER {m.wer:.1%} (sub {m.substitutions} del {m.deletions} ins {m.insertions}), "
              f"load {t1-t0:.1f}s, transcribe {t2-t1:.1f}s = {dur/(t2-t1):.0f}x realtime")
    else:
        kw = dict(embedder=which)
        if len(sys.argv) > 4:
            kw["threshold"] = float(sys.argv[4])
        t0 = time.time()
        turns = diarize.diarize(pcm, **kw)
        t1 = time.time()
        r = der(ref_turns, [(t.start, t.end, t.speaker) for t in turns])
        print(f"{which} {kw.get('threshold', 0.5)} on {clip}: DER {r['der']:.1%} (miss {r['miss']:.1%} fa {r['fa']:.1%} "
              f"confusion {r['conf']:.1%}), speakers {r['hyp_spk']} vs {r['ref_spk']}, {t1-t0:.1f}s = {dur/(t1-t0):.0f}x realtime")


if __name__ == "__main__":
    main()
