"""The language model for corrections, summaries and questions: Qwen3 8B on this Mac (default), or
Claude when it's chosen in Settings and the project isn't marked "keep on this Mac"."""
import json
import re
import threading
from contextlib import contextmanager

from . import runtime, store
from .asr import GPU

_project = threading.local()


@contextmanager
def for_project(pid: str):
    """Calls inside this block follow that project's choice of engine."""
    prev = getattr(_project, "pid", None)
    _project.pid = pid
    try:
        yield
    finally:
        _project.pid = prev


def engine() -> str:
    if runtime.settings().get("aiEngine") != "claude":
        return "local"
    pid = getattr(_project, "pid", None)
    if pid:
        try:
            if store.get_project(pid).get("localOnly"):
                return "local"
        except store.NotFound:
            pass
    return "claude"


def describe() -> str:
    from . import claude
    return claude.name() if engine() == "claude" else "Qwen3 8B on this Mac"

MODEL = "mlx-community/Qwen3-8B-4bit"
_m = {}
_load = threading.Lock()


def _model():
    with _load:
        if "m" not in _m:
            from mlx_lm import load
            _m["m"] = runtime.loading("llm", "Qwen3 8B (4-bit)", "Summaries, fixes, questions",
                                      lambda: load(MODEL), lambda: _m.pop("m", None))
        return _m["m"]


def chat(system: str, user: str, max_tokens: int = 2048, temp: float = 0.2, effort: str = "medium") -> str:
    if engine() == "claude":
        from . import claude
        # Claude thinks before answering, and thinking counts toward max_tokens: leave room.
        return claude.chat(system, user, max_tokens=max(16000, max_tokens * 4), effort=effort)
    from mlx_lm import stream_generate
    from mlx_lm.sample_utils import make_sampler
    model, tok = _model()
    prompt = tok.apply_chat_template([{"role": "system", "content": system}, {"role": "user", "content": user}],
                                     add_generation_prompt=True, enable_thinking=False)
    out = []
    with GPU:
        runtime.touch()
        # Small prefill steps with a check between them, so Stop also works while it reads the prompt.
        for r in stream_generate(model, tok, prompt, max_tokens=max_tokens, sampler=make_sampler(temp=temp), kv_bits=8,
                                 prefill_step_size=512, prompt_progress_callback=lambda *_: runtime.check()):
            out.append(r.text)
            runtime.check()                # lets "Stop" interrupt mid-answer
        runtime.touch()
    return "".join(out)


def count_tokens(text: str) -> int:
    if engine() == "claude":
        return len(text) // 4          # rough is fine: Claude's context is far larger than a meeting
    return len(_model()[1].encode(text))


def context_tokens() -> int:
    """How much transcript fits in one request before summarizing part by part."""
    return 150_000 if engine() == "claude" else 9000


def chat_json(system: str, user: str, max_tokens: int = 2048, effort: str = "medium") -> dict:
    """Asks for JSON and parses the first object in the reply; one retry with a nudge."""
    last = ""
    for attempt in range(2):
        out = chat(system, user if not attempt else user + "\n\nReply with valid JSON only.", max_tokens,
                   temp=0.2 if not attempt else 0.0, effort=effort)
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
