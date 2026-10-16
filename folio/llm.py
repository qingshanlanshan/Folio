"""The local language model (Qwen3 8B, 4-bit, MLX) for corrections and summaries."""
import json
import re
import threading

from .asr import GPU

MODEL = "mlx-community/Qwen3-8B-4bit"
_m = {}
_load = threading.Lock()


def _model():
    with _load:
        if "m" not in _m:
            from mlx_lm import load
            _m["m"] = load(MODEL)
        return _m["m"]


def chat(system: str, user: str, max_tokens: int = 2048, temp: float = 0.2) -> str:
    from mlx_lm import generate
    from mlx_lm.sample_utils import make_sampler
    model, tok = _model()
    prompt = tok.apply_chat_template([{"role": "system", "content": system}, {"role": "user", "content": user}],
                                     add_generation_prompt=True, enable_thinking=False)
    with GPU:
        return generate(model, tok, prompt, max_tokens=max_tokens, sampler=make_sampler(temp=temp), kv_bits=8)


def count_tokens(text: str) -> int:
    return len(_model()[1].encode(text))


def chat_json(system: str, user: str, max_tokens: int = 2048) -> dict:
    """Asks for JSON and parses the first object in the reply; one retry with a nudge."""
    last = ""
    for attempt in range(2):
        out = chat(system, user if not attempt else user + "\n\nReply with valid JSON only.", max_tokens,
                   temp=0.2 if not attempt else 0.0)
        last = out
        m = re.search(r"\{.*\}", out, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except ValueError:
                pass
    raise ValueError(f"model did not return JSON: {last[:200]}")


def unload_whisper() -> None:
    """Frees GPU memory held by Whisper's cached model (mlx_whisper keeps one)."""
    import sys
    try:
        import mlx.core as mx
        mod = sys.modules.get("mlx_whisper.transcribe")
        if mod is not None:
            mod.ModelHolder.model = None
            mod.ModelHolder.model_path = None
        mx.clear_cache()
    except Exception:
        pass
