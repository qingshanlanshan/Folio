import sys, time
from folio import audio, diarize
from spike.bench import der
from spike.refs import CLIPS
path, _, ref = CLIPS[sys.argv[1]]()
pcm = audio.load(path)
kw = dict(num_speakers=int(sys.argv[2])) if sys.argv[2] != "auto" else dict(threshold=float(sys.argv[3]))
t = time.time(); turns = diarize.diarize(pcm, embedder="resnet34", **kw)
r = der(ref, [(x.start, x.end, x.speaker) for x in turns])
print(sys.argv[1:], f"DER {r['der']:.1%} miss {r['miss']:.1%} fa {r['fa']:.1%} conf {r['conf']:.1%} spk {r['hyp_spk']} vs {r['ref_spk']} {time.time()-t:.0f}s")
