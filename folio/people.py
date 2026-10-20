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
    # A person is one speaker per meeting. If you already named another speaker as them, the two are
    # the same voice split in two: merge. An automatic match there was probably wrong: drop it.
    for k, other in t["speakers"].items():
        if k != speaker and other.get("person") == person:
            if not other.get("auto"):
                return merge_speakers(project, meeting, speaker, k)
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


def merge_speakers(project: str, meeting: str, src: str, dst: str) -> dict:
    """Two speakers in one recording are the same person: src's words become dst's."""
    if src == dst:
        raise ValueError("same speaker")
    d = store.meeting_dir(project, meeting)
    t = store.read_json(d / "transcript.json")
    if src not in t["speakers"] or dst not in t["speakers"]:
        raise store.NotFound(src if src not in t["speakers"] else dst)
    secs = {src: 0.0, dst: 0.0}
    for w in t["words"]:
        k = str(w["speaker"])
        if k in secs:
            secs[k] += w["end"] - w["start"]
            if k == src:
                w["speaker"] = int(dst)
    a, b = t["speakers"].pop(src), t["speakers"][dst]
    if not b.get("person") and a.get("person"):
        b.update(person=a["person"], auto=bool(a.get("auto")))
        b.pop("suggested", None)
    if not b.get("name") and a.get("name"):
        b["name"] = a["name"]
    if not b.get("person") and not b.get("suggested") and a.get("suggested"):
        b["suggested"] = a["suggested"]
    if not b.get("auto"):
        b.pop("auto", None)
    t["edited"] = True
    store.write_json(d / "transcript.json", t)
    # One voice for the merged speaker: the two prints, weighted by how long each spoke.
    voices = meeting_voices(d)
    va, vb = voices.pop(src, None), voices.get(dst)
    if va is not None and vb is not None and va.shape == vb.shape:
        ua, ub = va / (np.linalg.norm(va) + 1e-9), vb / (np.linalg.norm(vb) + 1e-9)
        voices[dst] = (ua * secs[src] + ub * secs[dst]) / max(secs[src] + secs[dst], 1e-9)
    elif va is not None:
        voices[dst] = va
    if (d / "voices.npz").exists():
        np.savez(d / "voices.npz", **voices)
    for pid in {a.get("person"), b.get("person")} - {None}:
        try:
            remove_prints_from(pid, project, meeting)
        except store.NotFound:
            pass
    if b.get("person") and not b.get("auto") and dst in voices:
        try:
            add_print(b["person"], voices[dst], {"project": project, "meeting": meeting, "speaker": dst})
        except store.NotFound:
            pass
    return t


def merge_people(src: str, dst: str) -> dict:
    """Two people are the same person: src's recordings, voice samples, names and notes move to dst."""
    if src == dst:
        raise ValueError("same person")
    a, b = get(src), get(dst)
    for proj in store.list_projects():
        for m in store.list_meetings(proj["id"]):
            d = store.meeting_dir(proj["id"], m["id"])
            t = store.read_json(d / "transcript.json", default={})
            sp = t.get("speakers", {})
            ka = next((k for k, s in sp.items() if s.get("person") == src), None)
            kb = next((k for k, s in sp.items() if s.get("person") == dst), None)
            changed = False
            for s in sp.values():
                if (s.get("suggested") or {}).get("person") == src:
                    s["suggested"]["person"] = dst; changed = True
            if ka is not None:
                sp[ka]["person"] = dst; changed = True
            if changed:
                store.write_json(d / "transcript.json", t)
            if ka is not None and kb is not None:
                merge_speakers(proj["id"], m["id"], ka, kb)
    b = get(dst)
    arr_a, arr_b = prints(src), prints(dst)
    seen = {(v.get("project"), v.get("meeting")) for v in b["voices"]}
    keep = [i for i, v in enumerate(a["voices"]) if (v.get("project"), v.get("meeting")) not in seen and i < len(arr_a)]
    if keep:
        rows = arr_a[keep]
        if not arr_b.size or arr_b.shape[1] == rows.shape[1]:
            np.save(PEOPLE / f"{dst}.voices.npy", (np.vstack([arr_b, rows]) if arr_b.size else rows).astype(np.float32))
            b["voices"] += [a["voices"][i] for i in keep]
    names = [x for x in [a["name"], *a.get("aliases", [])] if x and x.lower() != b["name"].lower()]
    b["aliases"] = b.get("aliases", []) + [x for x in names if x.lower() not in {y.lower() for y in b.get("aliases", [])}]
    b["projects"] = b["projects"] + [x for x in a["projects"] if x not in b["projects"]]
    if a.get("notes", "").strip() and a["notes"].strip() not in b.get("notes", ""):
        b["notes"] = (b.get("notes", "").rstrip() + "\n\n" + a["notes"].strip()).strip()
    b["me"] = bool(b.get("me") or a.get("me"))
    store.write_json(PEOPLE / f"{dst}.json", {k: v for k, v in b.items() if k != "id"})
    store.trash(_path(src))
    v = PEOPLE / f"{src}.voices.npy"
    if v.exists():
        store.trash(v)
    return get(dst)


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


def retarget(project: str, meeting: str, to: str, new_meeting: str) -> None:
    """After a recording moves: its voice prints point to the new place, and its people join the project."""
    d = store.meeting_dir(to, new_meeting)
    t = store.read_json(d / "transcript.json", default={})
    linked = {s.get("person") for s in t.get("speakers", {}).values() if s.get("person")}
    for p in all_people():
        changed = False
        for v in p["voices"]:
            if v.get("project") == project and v.get("meeting") == meeting:
                v["project"], v["meeting"] = to, new_meeting
                changed = True
        if p["id"] in linked and to not in p["projects"] and to != store.UNSORTED:
            p["projects"].append(to)
            changed = True
        if changed:
            store.write_json(PEOPLE / f"{p['id']}.json", {k: v for k, v in p.items() if k != "id"})


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
