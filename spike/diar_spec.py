import sys, time
from folio import audio, diarize
from spike.bench import der
from spike.refs import CLIPS
for clip in sys.argv[2:]:
    path, _, ref = CLIPS[clip]()
    pcm = audio.load(path)
    t = time.time(); turns, c = diarize.diarize_spectral(pcm, embedder=sys.argv[1])
    r = der(ref, [(x.start, x.end, x.speaker) for x in turns])
    print(sys.argv[1], clip, f"DER {r['der']:.1%} miss {r['miss']:.1%} fa {r['fa']:.1%} conf {r['conf']:.1%} spk {r['hyp_spk']} vs {r['ref_spk']} {time.time()-t:.0f}s")
