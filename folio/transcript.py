"""transcript.json: a flat list of timed words, each with a speaker, plus the speaker names.

    {"version": 1, "language": "en", "asr": "...", "words": [{"text", "start", "end", "conf", "speaker"}],
     "speakers": {"0": {"name": null}}, "edited": false}

Paragraphs are derived (speaker changes, long pauses), so edits only ever touch words.
"""
import re

SENTENCE_END = re.compile(r"[.?!…]['\")\]]*$")


def build(words, asr: str, language: str = "en") -> dict:
    speakers = sorted({w.speaker for w in words if w.speaker is not None})
    return {
        "version": 1, "language": language, "asr": asr, "edited": False,
        "words": [{"text": w.text, "start": round(w.start, 3), "end": round(w.end, 3),
                   "conf": round(float(w.conf), 3), "speaker": w.speaker} for w in words],
        "speakers": {str(s): {"name": None} for s in speakers},
    }


def paragraphs(t: dict, max_len: float = 60.0, gap: float = 2.0) -> list[dict]:
    """[{speaker, start, end, first, last}] with word indices (inclusive)."""
    out: list[dict] = []
    words = t.get("words", [])
    for i, w in enumerate(words):
        p = out[-1] if out else None
        new = (p is None or w["speaker"] != p["speaker"] or w["start"] - words[i - 1]["end"] > gap
               or (w["start"] - p["start"] > max_len and SENTENCE_END.search(words[i - 1]["text"])))
        if new:
            out.append({"speaker": w["speaker"], "start": w["start"], "end": w["end"], "first": i, "last": i})
        else:
            p["end"], p["last"] = w["end"], i
    return out


def replace_span(t: dict, first: int, last: int, text: str) -> dict:
    """Replaces words[first..last] with the edited text. A word-level diff keeps the original timing of
    every unchanged word; changed runs share the time of the words they replace."""
    from difflib import SequenceMatcher
    words = t["words"]
    old = words[first:last + 1]
    if not old:
        raise ValueError("empty span")
    new_tokens = text.split()
    if [w["text"] for w in old] == new_tokens:
        return t
    spk = old[0]["speaker"]
    made: list[dict] = []
    ops = SequenceMatcher(None, [w["text"] for w in old], new_tokens, autojunk=False).get_opcodes()
    for tag, i1, i2, j1, j2 in ops:
        if tag == "equal":
            made += old[i1:i2]
            continue
        if j1 == j2:          # deleted
            continue
        if i1 < i2:           # replaced: spread over the replaced words' time
            a, b = old[i1]["start"], old[i2 - 1]["end"]
        else:                 # inserted: squeeze between the neighbours
            a = old[i1 - 1]["end"] if i1 > 0 else old[0]["start"]
            b = old[i1]["start"] if i1 < len(old) else old[-1]["end"]
        toks = new_tokens[j1:j2]
        total = sum(len(x) + 1 for x in toks)
        cur = a
        for tok in toks:
            dur = max(b - a, 0) * (len(tok) + 1) / total
            made.append({"text": tok, "start": round(cur, 3), "end": round(cur + dur, 3), "conf": 1.0,
                         "speaker": spk, "edited": True})
            cur += dur
    t["words"] = words[:first] + made + words[last + 1:]
    t["edited"] = True
    return t


def set_speaker(t: dict, first: int, last: int, speaker: int) -> dict:
    for w in t["words"][first:last + 1]:
        w["speaker"] = speaker
    t["speakers"].setdefault(str(speaker), {"name": None})
    t["edited"] = True
    return t


def speaker_name(t: dict, spk) -> str:
    if spk is None:
        return "Unknown"
    name = (t.get("speakers", {}).get(str(spk)) or {}).get("name")
    return name or f"Speaker {chr(65 + int(spk)) if int(spk) < 26 else spk}"


def _clock(s: float, srt: bool = False) -> str:
    h, m, sec = int(s // 3600), int(s % 3600 // 60), s % 60
    if srt:
        return f"{h:02d}:{m:02d}:{int(sec):02d},{int(sec % 1 * 1000):03d}"
    return f"{h}:{m:02d}:{int(sec):02d}" if h else f"{m}:{int(sec):02d}"


def export(t: dict, fmt: str, title: str = "") -> str:
    words = t.get("words", [])
    paras = paragraphs(t)
    text = lambda p: " ".join(w["text"] for w in words[p["first"]:p["last"] + 1])
    if fmt == "srt":
        out, n = [], 1
        for p in paragraphs(t, max_len=6.0, gap=0.8):
            out.append(f"{n}\n{_clock(p['start'], True)} --> {_clock(p['end'], True)}\n"
                       f"{speaker_name(t, p['speaker'])}: {text(p)}\n")
            n += 1
        return "\n".join(out)
    if fmt == "md":
        head = f"# {title}\n\n" if title else ""
        return head + "\n\n".join(f"**{speaker_name(t, p['speaker'])}** [{_clock(p['start'])}]  \n{text(p)}"
                                  for p in paras) + "\n"
    return "\n\n".join(f"[{_clock(p['start'])}] {speaker_name(t, p['speaker'])}: {text(p)}" for p in paras) + "\n"
