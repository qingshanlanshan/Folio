import numpy as np
from folio import audio, diarize
from spike.refs import CLIPS
from spike.bench import der
pcm = audio.load("/private/tmp/claude-501/-Users-jiaruiye-SomebodyMakeThis/34991108-1013-4941-a5a4-cadd95596333/scratchpad/ami80s.flac")
for sec in (33, 80):
    turns, c = diarize.diarize_spectral(pcm[: sec * 16000])
    print(sec, "s:", len(c), "speakers")
for clip in ("ami", "singlish"):
    path, _, ref = CLIPS[clip]()
    turns, c = diarize.diarize_spectral(audio.load(path))
    r = der(ref, [(t.start, t.end, t.speaker) for t in turns])
    print(clip, len(c), "speakers, confusion", round(r["conf"] * 100, 1))
