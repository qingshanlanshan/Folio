"""Optional: Claude (Anthropic API) instead of the local model for summaries, transcript fixes and
questions. Only text is sent (transcripts, project notes, glossary, people notes); audio and speech
recognition stay on this Mac. Projects marked "keep on this Mac" never use it.

The API key is kept in ~/Folio/anthropic-key (readable only by you), or taken from ANTHROPIC_API_KEY.
"""
import os
import threading
import time

from . import runtime, store

KEY_FILE = store.HOME / "anthropic-key"
USAGE_FILE = store.HOME / "claude-usage.json"
# id: (name, $ per million input tokens, $ per million output tokens)
MODELS = {
    "claude-opus-5-5": ("Claude Opus 5.5", 4.00, 20.00),
    "claude-sonnet-5-5": ("Claude Sonnet 5.5", 2.00, 10.00),
    "claude-haiku-5-5": ("Claude Haiku 5.5", 0.10, 0.50),
}
DEFAULT_MODEL = "claude-opus-5-5"
_client = {}
_lock = threading.Lock()


class ClaudeError(RuntimeError):
    pass


# --- key

def _file_key() -> str:
    try:
        return KEY_FILE.read_text().strip()
    except OSError:
        return ""


def key_status() -> dict | None:
    k = _file_key()
    if k:
        return {"source": "folio", "hint": "…" + k[-4:]}
    if os.environ.get("ANTHROPIC_API_KEY"):
        return {"source": "environment"}
    return None


def save_key(key: str) -> None:
    store.HOME.mkdir(parents=True, exist_ok=True)
    fd = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(key.strip())
    os.chmod(KEY_FILE, 0o600)
    _client.clear()


def delete_key() -> None:
    try:
        KEY_FILE.unlink()
    except FileNotFoundError:
        pass
    _client.clear()


def client():
    import anthropic
    k = _file_key()
    with _lock:
        if k not in _client:
            _client.clear()
            _client[k] = anthropic.Anthropic(api_key=k) if k else anthropic.Anthropic()
        return _client[k]


# --- usage and cost

def usage() -> dict:
    return store.read_json(USAGE_FILE, default={"months": {}})


def _record(model: str, inp: int, out: int) -> None:
    name, pin, pout = MODELS.get(model, (model, 4.0, 20.0))
    cost = inp / 1e6 * pin + out / 1e6 * pout
    with _lock:
        u = usage()
        m = u["months"].setdefault(time.strftime("%Y-%m"), {"requests": 0, "input": 0, "output": 0, "usd": 0.0})
        m["requests"] += 1
        m["input"] += inp
        m["output"] += out
        m["usd"] = round(m["usd"] + cost, 4)
        store.write_json(USAGE_FILE, u)


# --- calls

def model() -> str:
    m = runtime.settings().get("claudeModel") or DEFAULT_MODEL
    return m if m in MODELS else DEFAULT_MODEL


def name() -> str:
    return MODELS[model()][0]


def chat(system: str, user: str, max_tokens: int = 16000, effort: str = "medium") -> str:
    import anthropic
    mid = model()
    kw = dict(model=mid, max_tokens=max_tokens, system=system,
              messages=[{"role": "user", "content": user}], output_config={"effort": effort})
    if mid != "claude-haiku-5-5":
        # If a safety check declines the request, let the API retry it on a suitable model.
        kw.update(betas=["server-side-fallback-2026-07-01"], fallbacks="default")
    try:
        with client().beta.messages.stream(**kw) as stream:
            for _ in stream.text_stream:
                runtime.check()                    # lets "Stop" interrupt mid-answer
            msg = stream.get_final_message()
    except anthropic.AuthenticationError:
        raise ClaudeError("Claude rejected the API key. Check it in Settings.")
    except anthropic.PermissionDeniedError:
        raise ClaudeError("This API key isn't allowed to use that model.")
    except anthropic.RateLimitError:
        raise ClaudeError("Claude is rate-limiting this key right now. Try again in a minute.")
    except anthropic.APIConnectionError:
        raise ClaudeError("Can't reach Claude (offline?). Switch to the local model in Settings, or try again.")
    except anthropic.APIStatusError as e:
        if e.status_code == 400 and "credit" in str(e.message).lower():
            raise ClaudeError("Your Anthropic account is out of credits.")
        raise ClaudeError(f"Claude API error {e.status_code}: {e.message}")
    _record(msg.model or mid, msg.usage.input_tokens or 0, msg.usage.output_tokens or 0)
    if msg.stop_reason == "refusal":
        raise ClaudeError("Claude declined this request.")
    return "".join(b.text for b in msg.content if b.type == "text")


def test() -> dict:
    """A tiny request to check the key and model."""
    t0 = time.time()
    out = chat("Reply with the single word: ready", "Are you there?", max_tokens=2000, effort="low")
    return {"ok": True, "reply": out.strip()[:40], "seconds": round(time.time() - t0, 1), "model": name()}
