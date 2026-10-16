import json, sys, time
import mlx.core as mx
from mlx_lm import load, generate
from mlx_lm.sample_utils import make_sampler
from folio import audio, diarize
from folio.asr import Word
from spike.refs import CLIPS, T

clip, repo = sys.argv[1], sys.argv[2]
path, _, _ = CLIPS[clip]()
words = [Word(**w) for w in json.load(open(T / f"out/{clip}.parakeet.json"))]
turns, _ = diarize.diarize_spectral(audio.load(path), embedder="titanet")
diarize.assign_speakers(words, turns)
lines, cur = [], None
for w in words:
    if cur is None or w.speaker != cur[0]:
        cur = [w.speaker, w.start, []]; lines.append(cur)
    cur[2].append(w.text)
text = "\n".join(f"[{int(s//60):02d}:{int(s%60):02d}] Speaker {chr(65+k)}: {' '.join(ws)}" for k, s, ws in lines)
open(T / f"out/{clip}.transcript.txt", "w").write(text)
t = time.time()
model, tok = load(repo)
msgs = [{"role": "system", "content": "You write concise, accurate meeting minutes from transcripts. Never invent facts."},
        {"role": "user", "content": "Transcript:\n" + text + "\n\nWrite minutes in Markdown with sections: Summary (3-5 sentences), Decisions, Action items (owner if known), Open questions. Refer to people by their speaker label or by name if they introduce themselves."}]
prompt = tok.apply_chat_template(msgs, add_generation_prompt=True, enable_thinking=False)
print("prompt tokens", len(prompt) if isinstance(prompt, list) else len(tok.encode(prompt)), "load", round(time.time() - t, 1), "s")
t = time.time()
out = generate(model, tok, prompt, max_tokens=1200, sampler=make_sampler(temp=0.3), kv_bits=8)
print(f"generate {time.time()-t:.0f}s, peak {mx.get_peak_memory()/1e9:.1f} GB\n")
print(out)
