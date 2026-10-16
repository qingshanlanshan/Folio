"""glossary.tsv: one term per line — term <TAB> also heard as (comma separated) <TAB> note. '#' starts a comment."""


def parse(text: str) -> list[dict]:
    out = []
    for line in (text or "").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = [c.strip() for c in line.split("\t")]
        if len(cols) == 1 and "," not in cols[0] and " - " in cols[0]:
            cols = [c.strip() for c in cols[0].split(" - ", 1)]   # forgiving: "term - note"
        term = cols[0]
        if not term:
            continue
        heard = [h.strip() for h in (cols[1] if len(cols) > 1 else "").split(",") if h.strip()]
        out.append({"term": term, "heard": heard, "note": cols[2] if len(cols) > 2 else ""})
    return out


def prompt_words(entries: list[dict], names: list[str], limit: int = 60) -> str:
    """Short list for Whisper's prompt (long prompts get echoed back)."""
    words = [e["term"] for e in entries] + names
    seen, out = set(), []
    for w in words:
        if w.lower() not in seen:
            seen.add(w.lower())
            out.append(w)
    return ", ".join(out[:limit])
