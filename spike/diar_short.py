import numpy as np
from folio import audio, diarize
from spike.refs import CLIPS
from spike.bench import der
# 80 s of the AMI kickoff (mostly one speaker): short recordings used to be over-split.
pcm = audio.load(CLIPS["ami"]()[0])[75 * 16000:155 * 16000]
for sec in (33, 80):
    turns, c = diarize.diarize_spectral(pcm[: sec * 16000])
    print(sec, "s:", len(c), "speakers")
for clip in ("ami", "singlish"):
    path, _, ref = CLIPS[clip]()
    turns, c = diarize.diarize_spectral(audio.load(path))
    r = der(ref, [(t.start, t.end, t.speaker) for t in turns])
    print(clip, len(c), "speakers, confusion", round(r["conf"] * 100, 1))
