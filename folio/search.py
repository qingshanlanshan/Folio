"""Search across every project: people by name, and transcript text (optionally only what a person said)."""
from . import people, store, transcript

_cache: dict[str, tuple[float, dict]] = {}


def _transcript(d) -> dict:
    f = d / "transcript.json"
    if not f.exists():
        return {}
    mt = f.stat().st_mtime
    hit = _cache.get(str(f))
    if hit and hit[0] == mt:
        return hit[1]
    t = people.resolve(store.read_json(f))
    _cache[str(f)] = (mt, t)
    return t


def search(q: str, person: str | None = None, limit: int = 60) -> dict:
    q = q.strip().lower()
    out_people = []
    if q and not person:
        for p in people.all_people():
            names = [p["name"]] + p.get("aliases", [])
            if any(q in n.lower() for n in names):
                out_people.append({"id": p["id"], "name": p["name"], "projects": p["projects"]})
    hits = []
    for proj in store.list_projects():
        for m in store.list_meetings(proj["id"]):
            t = _transcript(store.meeting_dir(proj["id"], m["id"]))
            if not t:
                continue
            ks = {k for k, s in t["speakers"].items() if s.get("person") == person} if person else None
            words = t["words"]
            for p in transcript.paragraphs(t):
                if ks is not None and str(p["speaker"]) not in ks:
                    continue
                text = " ".join(w["text"] for w in words[p["first"]:p["last"] + 1])
                i = text.lower().find(q) if q else 0
                if i < 0:
                    continue
                a = max(0, i - 80)
                snippet = ("…" if a else "") + text[a:i + len(q) + 120] + ("…" if i + len(q) + 120 < len(text) else "")
                hits.append({"project": proj["id"], "projectName": proj["name"], "meeting": m["id"],
                             "title": m["title"], "date": m["date"], "start": p["start"],
                             "speaker": transcript.speaker_name(t, p["speaker"]), "snippet": snippet})
    hits.sort(key=lambda h: (-h["date"], h["start"]))
    return {"people": out_people, "hits": hits[:limit], "total": len(hits)}
