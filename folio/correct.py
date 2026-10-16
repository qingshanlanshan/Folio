"""After-recording correction. Three modes, chosen per run:

  text   the local LLM reads the transcript with the project's glossary, people and knowledge, plus the
         recognizer's unsure words, and proposes small fixes (misheard words, misspelled names/terms).
  audio  Whisper listens to the whole recording again, prompted with glossary terms and names; where it
         disagrees with the transcript on a glossary term or an unsure word, that becomes a fix.
  both   audio first; its disagreements are then handed to the LLM as hints.

Glossary "also heard as" entries are always applied as fixes. Nothing changes the transcript until the
fix is accepted; suggestions live in corrections.json.
"""
import re
import time
import uuid
from difflib import SequenceMatcher

from . import glossary, people, store, transcript

FILLERS = {"uh", "um", "hmm", "mm", "mhm", "ah", "er", "erm", "oh", "eh"}
LOW_CONF = 0.75   # Parakeet: median 0.996, 10th percentile ~0.89
WINDOW_WORDS = 1100


def _n(w: str) -> str:
    return re.sub(r"[^\w']", "", w.lower())


def _ntoks(text: str) -> list[str]:
    return [t for t in (_n(x) for x in text.split()) if t]


def _context(pid: str, t: dict) -> tuple[list[dict], list[dict], str]:
    proj = store.get_project(pid)
    entries = glossary.parse(proj["glossary"])
    linked = {s.get("person") for s in t.get("speakers", {}).values() if s.get("person")}
    ppl = [p for p in people.all_people() if pid in p["projects"] or p["id"] in linked]
    return entries, ppl, proj["knowledge"]


# --- is a proposed fix a plausible mishearing?

def _phon(text: str) -> str:
    """Rough sound skeleton: consonants after common spelling merges, first letter kept."""
    s = re.sub(r"[^a-z]", "", text.lower())
    for a, b in (("ph", "f"), ("ck", "k"), ("q", "k"), ("x", "ks"), ("z", "s"), ("dg", "j"), ("wh", "w"),
                 ("gh", "g"), ("th", "0"), ("sh", "x"), ("ch", "x")):
        s = s.replace(a, b)
    s = re.sub(r"c(?=[eiy])", "s", s).replace("c", "k")
    if not s:
        return ""
    head, rest = s[0], re.sub(r"[aeiouyhw]", "", s[1:])
    return re.sub(r"(.)\1+", r"\1", head + rest)


_WORDS: set[str] = set()


def _known(tok: str, vocab: set[str]) -> bool:
    """A real word: in the system word list (plus simple inflections), the transcript, or the glossary."""
    if not _WORDS:
        for f in ("/usr/share/dict/words", "/usr/share/dict/propernames"):
            try:
                _WORDS.update(w.strip().lower() for w in open(f))
            except OSError:
                _WORDS.add("")
    if tok in _WORDS or tok in vocab or len(tok) <= 2:
        return True
    for suf in ("s", "es", "ed", "d", "ing", "ly", "er", "ers", "ics"):
        if tok.endswith(suf) and (tok[:-len(suf)] in _WORDS or tok[:-len(suf)] + "e" in _WORDS):
            return True
    return False


def _glue(text: str) -> str:
    return re.sub(r"[^a-z0-9]", "", text.lower())


def plausible(old: str, new: str, keyterms: set[str], vocab: set[str] = frozenset()) -> bool:
    """A fix should sound like what was transcribed and use real words. Rejects rewrites, number and
    spelling-variant changes, plurals, contractions and hyphenation, unless it brings in a glossary
    term or a name."""
    o, n = _ntoks(old), _ntoks(new)
    if not n or o == n:
        return False
    term = any(t in keyterms and t not in o for t in n)
    if len(o) == 1 and any(o[0].startswith(f) and o[0][len(f):] == _glue(new) for f in FILLERS):
        return True                                    # "uhergonomics" → "ergonomics"
    # Judge only the part that changed.
    while o and n and o[0] == n[0]:
        o, n = o[1:], n[1:]
    while o and n and o[-1] == n[-1]:
        o, n = o[:-1], n[:-1]
    if not n:
        return False                                   # only drops words
    oc, nc = [t for t in o if t not in FILLERS], [t for t in n if t not in FILLERS]
    if "".join(oc) == "".join(nc):
        # Same letters: only joining wrongly split words is a fix ("the sis" → "thesis", "L C D" → "LCD").
        return "-" not in old and "-" not in new and len(oc) > len(nc)
    if re.search(r"\d", new) and not re.search(r"\d", old):
        return False                                   # number formatting
    if any("'" in t for t in o + n):
        return False                                   # contractions: it'll / it will, it was / it's
    if not term and len(o) != len(n) and _phon(" ".join(o)) != _phon(" ".join(n)):
        return False                                   # splitting or merging different-sounding words
    if not all(_known(t, vocab | keyterms) for t in n):
        return False                                   # not a word: "plusic"
    po, pn = _phon(" ".join(o)), _phon(" ".join(n))
    if not term:
        if po == pn and len(o) == len(n):
            return False                               # spelling variant: molded/moulded
        a, b = sorted([" ".join(o), " ".join(n)], key=len)
        if a in b and len(b) - len(a) <= 2:
            return False                               # light/lights, round/around
    return SequenceMatcher(None, po, pn).ratio() >= (0.5 if term else 0.67)


# --- suggestions from the glossary's "also heard as"

def glossary_fixes(t: dict, entries: list[dict]) -> list[dict]:
    words = t["words"]
    toks = [_n(w["text"]) for w in words]
    out = []
    for e in entries:
        for v in e["heard"]:
            vt = _ntoks(v)
            if not vt:
                continue
            for i in range(len(toks) - len(vt) + 1):
                if toks[i:i + len(vt)] == vt:
                    last = i + len(vt) - 1
                    old = " ".join(w["text"] for w in words[i:last + 1])
                    tail = re.search(r"[^\w']*$", words[last]["text"]).group(0)
                    out.append(_item(i, last, old, e["term"] + tail, f"Glossary: “{v}” is usually “{e['term']}”", "glossary"))
    return out


# --- audio: listen again with Whisper

def audio_fixes(pid: str, mid: str, t: dict, entries, ppl, progress) -> tuple[list[dict], dict[int, list[str]]]:
    from .asr import Whisper
    from .jobs import load_tracks, mix
    from .llm import unload_whisper
    d = store.meeting_dir(pid, mid)
    pcm = mix(load_tracks(d))
    prompt = glossary.prompt_words(entries, [p["name"] for p in ppl])
    progress(0.05)
    try:
        alt = Whisper().transcribe(pcm, prompt=prompt or None)
    finally:
        unload_whisper()
    progress(0.9)
    keyterms = keyterms_of(entries, ppl)
    words = t["words"]
    vocab = {_n(w["text"]) for w in words}
    out, hints = [], {}
    used = 0
    mids = [(w.start + w.end) / 2 for w in alt]
    for pi, p in enumerate(transcript.paragraphs(t)):
        cur = words[p["first"]:p["last"] + 1]
        j0 = used
        while j0 < len(alt) and mids[j0] < p["start"] - 0.5:
            j0 += 1
        j1 = j0
        while j1 < len(alt) and mids[j1] <= p["end"] + 0.5:
            j1 += 1
        seg = alt[j0:j1]
        used = j1
        a, b = [_n(w["text"]) for w in cur], [_n(w.text) for w in seg]
        for tag, i1, i2, k1, k2 in SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag != "replace":
                continue  # one model hearing extra words is usually filler or hallucination
            old_t, new_t = a[i1:i2], b[k1:k2]
            if set(old_t) | set(new_t) <= FILLERS or old_t == new_t:
                continue
            old = " ".join(w["text"] for w in cur[i1:i2])
            new = " ".join(w.text for w in seg[k1:k2])
            hints.setdefault(pi, []).append(f"“{old}” (second listen: “{new}”)")
            term_hit = any(x in keyterms and x not in old_t for x in new_t)
            unsure = min(w.get("conf", 1) for w in cur[i1:i2]) < LOW_CONF
            if term_hit or (unsure and len(new_t) <= 3 and plausible(old, new, keyterms, vocab)):
                why = "Second listen heard a glossary term or name" if term_hit else "Unsure word; a second listen heard it differently"
                out.append(_item(p["first"] + i1, p["first"] + i2 - 1, old, new, why, "audio"))
    return out, hints


# --- text: the LLM proofreads

SYSTEM = """You correct speech-recognition errors in meeting transcripts.
Fix ONLY words that were clearly misheard: the transcript word makes no sense in context AND the correct word
sounds almost the same (e.g. "the sis" → "thesis", "pie torch" → "PyTorch"). Fix misspelled names and terms
using the glossary and people list.
NEVER: rephrase, fix grammar, change numbers or how they are written, change British/American spelling,
singular/plural, hyphenation, or remove filler words. Most paragraphs need no fix. When unsure, leave it.
Reply as JSON: {"fixes": [{"p": <paragraph number>, "find": "<exact text from that paragraph, 1-6 words>",
"replace": "<corrected text>", "why": "<short reason>"}]}. Reply {"fixes": []} if nothing needs fixing."""


def keyterms_of(entries, ppl) -> set[str]:
    k = {_n(x) for e in entries for x in e["term"].split()} | {_n(x) for p in ppl for x in p["name"].split()}
    k.discard("")
    return k


def text_fixes(pid: str, t: dict, entries, ppl, knowledge: str, hints: dict, progress) -> list[dict]:
    from .llm import chat_json
    keyterms = keyterms_of(entries, ppl)
    vocab = {_n(w["text"]) for w in t["words"]}
    words = t["words"]
    paras = transcript.paragraphs(t)
    gl = "\n".join(f"- {e['term']}" + (f" (often misheard as: {', '.join(e['heard'])})" if e["heard"] else "")
                   + (f": {e['note']}" if e["note"] else "") for e in entries[:80]) or "(none)"
    pl = "\n".join(f"- {p['name']}" + (f": {p['notes'][:160]}" if p.get("notes") else "") for p in ppl[:30]) or "(none)"
    head = f"Project notes:\n{knowledge[:1500] or '(none)'}\n\nGlossary:\n{gl}\n\nPeople:\n{pl}\n"
    windows, cur, n = [], [], 0
    for i, p in enumerate(paras):
        cur.append(i)
        n += p["last"] - p["first"] + 1
        if n >= WINDOW_WORDS:
            windows.append(cur); cur, n = [], 0
    if cur:
        windows.append(cur)
    out = []
    for wi, idxs in enumerate(windows):
        lines, notes = [], []
        for i in idxs:
            p = paras[i]
            ws = words[p["first"]:p["last"] + 1]
            lines.append(f"[{i}] {transcript.speaker_name(t, p['speaker'])}: " + " ".join(w["text"] for w in ws))
            unsure = [w["text"] for w in ws if w.get("conf", 1) < LOW_CONF and _n(w["text"]) not in FILLERS]
            if unsure:
                notes.append(f"[{i}] unsure: " + ", ".join(f"“{u}”" for u in unsure[:8]))
            for h in hints.get(i, [])[:6]:
                notes.append(f"[{i}] {h}")
        user = head + "\nTranscript:\n" + "\n".join(lines)
        if notes:
            user += "\n\nRecognizer notes (may help, may be wrong):\n" + "\n".join(notes)
        try:
            r = chat_json(SYSTEM, user, max_tokens=1500)
        except ValueError:
            continue
        for f in r.get("fixes", []) if isinstance(r, dict) else []:
            item = _apply_fix(words, paras, f)
            if item and plausible(item["old"], item["new"], keyterms, vocab):
                out.append(item)
        progress((wi + 1) / len(windows))
    return out


def _apply_fix(words, paras, f) -> dict | None:
    try:
        pi = int(f.get("p"))
        find, rep = str(f.get("find", "")).strip(), str(f.get("replace", "")).strip()
    except (TypeError, ValueError):
        return None
    if not (0 <= pi < len(paras)) or not find or not rep or find == rep:
        return None
    if len(find.split()) > 8 or len(rep.split()) > 2 * len(find.split()) + 2:
        return None                    # a rewrite, not a fix
    if _ntoks(find) == _ntoks(rep):
        return None                    # punctuation or case only
    p = paras[pi]
    ws = words[p["first"]:p["last"] + 1]
    text, offs, pos = "", [], 0
    for w in ws:
        offs.append((pos, pos + len(w["text"])))
        pos += len(w["text"]) + 1
    text = " ".join(w["text"] for w in ws)
    at = text.find(find)
    if at < 0:
        at = text.lower().find(find.lower())
    if at < 0:
        return None
    end = at + len(find)
    i1 = next(i for i, (a, b) in enumerate(offs) if b > at)
    i2 = max(i for i, (a, b) in enumerate(offs) if a < end)
    span = text[offs[i1][0]:offs[i2][1]]
    new = span[:at - offs[i1][0]] + rep + span[end - offs[i1][0]:]
    return _item(p["first"] + i1, p["first"] + i2, span, new, str(f.get("why", ""))[:160], "text")


# --- bookkeeping

def _item(first, last, old, new, why, source) -> dict:
    return {"id": uuid.uuid4().hex[:8], "first": first, "last": last, "old": old, "new": new.strip(),
            "why": why, "source": source, "status": "pending"}


def _merge(items: list[dict]) -> list[dict]:
    """One suggestion per span: audio/glossary win over text; drop overlaps."""
    rank = {"glossary": 0, "audio": 1, "text": 2}
    out, taken = [], set()
    for it in sorted(items, key=lambda x: (rank[x["source"]], x["first"])):
        span = set(range(it["first"], it["last"] + 1))
        if span & taken or _ntoks(it["old"]) == _ntoks(it["new"]):
            continue
        taken |= span
        out.append(it)
    return sorted(out, key=lambda x: x["first"])


def run(job: dict, key: str) -> None:
    from .jobs import set_step
    pid, mid, mode = job["pid"], job["mid"], job.get("mode", "both")
    d = store.meeting_dir(pid, mid)
    t = people.resolve(store.read_json(d / "transcript.json"))
    entries, ppl, knowledge = _context(pid, t)
    items = glossary_fixes(t, entries)
    hints: dict = {}
    if mode in ("audio", "both"):
        set_step(key, "listening again")
        found, hints = audio_fixes(pid, mid, t, entries, ppl, lambda f: set_step(key, "listening again", f))
        items += found
    if mode in ("text", "both"):
        set_step(key, "checking text")
        items += text_fixes(pid, t, entries, ppl, knowledge, hints, lambda f: set_step(key, "checking text", f))
    prev = store.read_json(d / "corrections.json", default={"runs": [], "items": []})
    rejected = {(i["old"], i["new"]) for i in prev["items"] if i["status"] == "rejected"}
    fresh = [i for i in _merge(items) if (i["old"], i["new"]) not in rejected]
    kept = [i for i in prev["items"] if i["status"] != "pending"]
    prev["items"] = kept + fresh
    prev["runs"].append({"mode": mode, "at": time.time(), "found": len(fresh)})
    store.write_json(d / "corrections.json", prev)


def locate(t: dict, it: dict) -> tuple[int, int] | None:
    """Finds the suggestion's words again (indices shift as other fixes are accepted)."""
    words = t["words"]
    old = it["old"].split()
    n = len(old)
    def at(i):
        return [w["text"] for w in words[i:i + n]] == old
    if at(it["first"]):
        return it["first"], it["first"] + n - 1
    for delta in range(1, 400):
        for i in (it["first"] - delta, it["first"] + delta):
            if 0 <= i <= len(words) - n and at(i):
                return i, i + n - 1
    return None


def decide(pid: str, mid: str, ids: list[str], accept: bool) -> dict:
    d = store.meeting_dir(pid, mid)
    c = store.read_json(d / "corrections.json", default={"runs": [], "items": []})
    t = store.read_json(d / "transcript.json")
    for it in c["items"]:
        if it["id"] not in ids or it["status"] != "pending":
            continue
        if not accept:
            it["status"] = "rejected"
            continue
        span = locate(t, it)
        if not span:
            it["status"] = "stale"
            continue
        transcript.replace_span(t, span[0], span[1], it["new"])
        it["status"] = "accepted"
    store.write_json(d / "transcript.json", t)
    store.write_json(d / "corrections.json", c)
    return c
