"""People, shared across projects:

    people/<id>.json        {name, aliases, notes, projects, me, voices: [{project, meeting, speaker, added}]}
    people/<id>.voices.npy  one voice print (speaker centroid) per row, same order as "voices"

A meeting's speakers link to people in transcript.json: speakers[k] = {name, person, auto?, suggested?}.
After each transcription, speakers are matched to known voices: a close match links automatically,
a likely one is offered as a suggestion to confirm.
"""
import re
import time
import uuid

import numpy as np

from . import store
from .diarize import SAME_VOICE

PEOPLE = store.HOME / "people"
AUTO_LINK = 0.85      # same person (see diarize.SAME_VOICE calibration)
SUGGEST = SAME_VOICE


def _path(pid: str):
    if not re.fullmatch(r"[a-z0-9-]+", pid or ""):
        raise store.NotFound(pid)
    p = PEOPLE / f"{pid}.json"
    if not p.exists():
        raise store.NotFound(pid)
    return p


def all_people() -> list[dict]:
    PEOPLE.mkdir(parents=True, exist_ok=True)
    out = [{**store.read_json(p), "id": p.stem} for p in PEOPLE.glob("*.json")]
    return sorted(out, key=lambda p: p["name"].lower())


def get(pid: str) -> dict:
    return {**store.read_json(_path(pid)), "id": pid}


def create(name: str, project: str | None = None, me: bool = False) -> dict:
    PEOPLE.mkdir(parents=True, exist_ok=True)
    pid = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:30] or "person"
    pid = f"{pid}-{uuid.uuid4().hex[:4]}"
    data = {"name": name.strip(), "aliases": [], "notes": "", "projects": [project] if project else [],
            "me": me, "voices": [], "created": time.time()}
    store.write_json(PEOPLE / f"{pid}.json", data)
    return {**data, "id": pid}


def update(pid: str, **fields) -> dict:
    p = _path(pid)
    data = store.read_json(p)
    for k in ("name", "aliases", "notes", "projects", "me"):
        if fields.get(k) is not None:
            data[k] = fields[k]
    if fields.get("me"):
        for other in all_people():   # only one "me"
            if other["id"] != pid and other.get("me"):
                update(other["id"], me=False)
    store.write_json(p, data)
    return {**data, "id": pid}


def me() -> dict | None:
    return next((p for p in all_people() if p.get("me")), None)


def prints(pid: str) -> np.ndarray:
    f = PEOPLE / f"{pid}.voices.npy"
    return np.load(f) if f.exists() else np.zeros((0, 0), np.float32)


def add_print(pid: str, vec: np.ndarray, source: dict) -> None:
    p = _path(pid)
    data = store.read_json(p)
    if any(v.get("project") == source["project"] and v.get("meeting") == source["meeting"] for v in data["voices"]):
        return  # one print per meeting is enough
    old = prints(pid)
    vec = vec / (np.linalg.norm(vec) + 1e-9)
    new = np.vstack([old, vec[None]]) if old.size else vec[None]
    np.save(PEOPLE / f"{pid}.voices.npy", new.astype(np.float32))
    data["voices"].append({**source, "added": time.time()})
    store.write_json(p, data)


def remove_prints_from(pid: str, project: str, meeting: str) -> None:
    p = _path(pid)
    data = store.read_json(p)
    keep = [i for i, v in enumerate(data["voices"]) if not (v.get("project") == project and v.get("meeting") == meeting)]
    if len(keep) == len(data["voices"]):
        return
    arr = prints(pid)
    if arr.size:
        np.save(PEOPLE / f"{pid}.voices.npy", arr[keep])
    data["voices"] = [data["voices"][i] for i in keep]
    store.write_json(p, data)


def delete(pid: str) -> None:
    p = _path(pid)
    for proj in store.list_projects():
        for m in store.list_meetings(proj["id"]):
            d = store.meeting_dir(proj["id"], m["id"])
            t = store.read_json(d / "transcript.json", default={})
            changed = False
            for s in t.get("speakers", {}).values():
                if s.get("person") == pid:
                    s.pop("person", None); s.pop("auto", None); changed = True
                if (s.get("suggested") or {}).get("person") == pid:
                    s.pop("suggested", None); changed = True
            if changed:
                store.write_json(d / "transcript.json", t)
    store.trash(p)
    v = PEOPLE / f"{pid}.voices.npy"
    if v.exists():
        store.trash(v)


# --- meetings

def meeting_voices(d) -> dict[str, np.ndarray]:
    f = d / "voices.npz"
    if not f.exists():
        return {}
    z = np.load(f)
    return {k: z[k] for k in z.files}


def link(project: str, meeting: str, speaker: str, person: str, auto: bool = False) -> dict:
    d = store.meeting_dir(project, meeting)
    t = store.read_json(d / "transcript.json")
    s = t["speakers"].setdefault(speaker, {"name": None})
    prev = s.get("person")
    if prev and prev != person:
        try:
            remove_prints_from(prev, project, meeting)
        except store.NotFound:
            pass
    # A person is one speaker per meeting: unlink them elsewhere in this meeting.
    for k, other in t["speakers"].items():
        if k != speaker and other.get("person") == person:
            other.pop("person", None); other.pop("auto", None)
    s["person"] = person
    s.pop("suggested", None)
    if auto:
        s["auto"] = True
    else:
        s.pop("auto", None)
    store.write_json(d / "transcript.json", t)
    info = get(person)
    if project not in info["projects"]:
        update(person, projects=info["projects"] + [project])
    vec = meeting_voices(d).get(speaker)
    if vec is not None and not auto:
        # Only confirmed links teach the voice model; auto links could reinforce a mistake.
        add_print(person, vec, {"project": project, "meeting": meeting, "speaker": speaker})
    return t


def unlink(project: str, meeting: str, speaker: str, reject: bool = False) -> dict:
    d = store.meeting_dir(project, meeting)
    t = store.read_json(d / "transcript.json")
    s = t["speakers"].get(speaker, {})
    if s.get("person"):
        try:
            remove_prints_from(s["person"], project, meeting)
        except store.NotFound:
            pass
    s.pop("person", None); s.pop("auto", None)
    if reject:
        s.pop("suggested", None)
    store.write_json(d / "transcript.json", t)
    return t


def match(project: str, meeting: str) -> None:
    """After transcription: link or suggest known people for each speaker."""
    d = store.meeting_dir(project, meeting)
    t = store.read_json(d / "transcript.json", default={})
    voices = meeting_voices(d)
    if not t or not voices:
        return
    people = [p for p in all_people() if p["voices"]]
    scores = []
    for k, vec in voices.items():
        if t["speakers"].get(k, {}).get("person"):
            continue
        vec = vec / (np.linalg.norm(vec) + 1e-9)
        for p in people:
            arr = prints(p["id"])
            if not arr.size or arr.shape[1] != vec.shape[0]:
                continue
            sc = float((arr @ vec).max()) + (0.02 if project in p["projects"] else 0.0)
            scores.append((sc, k, p["id"]))
    taken_s, taken_p = set(), {s.get("person") for s in t["speakers"].values() if s.get("person")}
    me_p = me()
    for sc, k, pid in sorted(scores, reverse=True):
        if k in taken_s or pid in taken_p or sc < SUGGEST:
            continue
        taken_s.add(k); taken_p.add(pid)
        if sc >= AUTO_LINK:
            t["speakers"][k].update(person=pid, auto=True)
            t["speakers"][k].pop("suggested", None)
        else:
            t["speakers"][k]["suggested"] = {"person": pid, "score": round(sc, 3)}
    # Online meetings: the mic track (speaker 0, "Me") is you.
    if me_p and t["speakers"].get("0", {}).get("name") == "Me" and "0" not in taken_s and not t["speakers"]["0"].get("person"):
        t["speakers"]["0"].update(person=me_p["id"], auto=True)
    store.write_json(d / "transcript.json", t)


def appearances(pid: str) -> list[dict]:
    out = []
    for proj in store.list_projects():
        for m in store.list_meetings(proj["id"]):
            d = store.meeting_dir(proj["id"], m["id"])
            t = store.read_json(d / "transcript.json", default={})
            ks = [k for k, s in t.get("speakers", {}).items() if s.get("person") == pid]
            if not ks:
                continue
            secs = sum(w["end"] - w["start"] for w in t["words"] if str(w["speaker"]) in ks)
            out.append({"project": proj["id"], "projectName": proj["name"], "meeting": m["id"],
                        "title": m["title"], "date": m["date"], "seconds": round(secs)})
    return sorted(out, key=lambda a: -a["date"])


def resolve(t: dict) -> dict:
    """Fills in display names from linked people (and the suggested person's name, for the UI)."""
    cache: dict[str, dict | None] = {}

    def person(pid):
        if pid not in cache:
            try:
                cache[pid] = get(pid)
            except store.NotFound:
                cache[pid] = None
        return cache[pid]

    for s in t.get("speakers", {}).values():
        p = person(s["person"]) if s.get("person") else None
        if p:
            s["name"] = p["name"]
        elif s.get("person"):
            s.pop("person")
        sug = s.get("suggested")
        if sug:
            sp = person(sug["person"])
            if sp:
                sug["name"] = sp["name"]
            else:
                s.pop("suggested")
    return t
