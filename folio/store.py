"""Everything lives in plain folders under FOLIO_HOME (default ~/Folio), readable without the app:

    projects/<project>/project.json       name, created
    projects/<project>/project.md         what you know about the project (fed to the summary AI)
    projects/<project>/glossary.tsv       term <TAB> also heard as <TAB> note
    projects/<project>/meetings/<id>/meeting.json, original.<ext>, audio.m4a, transcript.json
"""
import json
import os
import re
import shutil
import time
from datetime import datetime
from pathlib import Path

HOME = Path(os.environ.get("FOLIO_HOME", Path.home() / "Folio"))
PROJECTS = HOME / "projects"


class NotFound(Exception):
    pass


def _slug(name: str) -> str:
    s = re.sub(r"[/\\:#?%*|\"<>\x00-\x1f]+", " ", name).strip().strip(".")
    s = re.sub(r"\s+", "-", s)[:60]
    return s or "untitled"


def _unique(parent: Path, base: str) -> Path:
    p, n = parent / base, 2
    while p.exists():
        p, n = parent / f"{base}-{n}", n + 1
    return p


def read_json(path: Path, default=None):
    try:
        return json.loads(path.read_text("utf-8"))
    except FileNotFoundError:
        if default is not None:
            return default
        raise NotFound(str(path))


def write_json(path: Path, data) -> None:
    # Write then rename, so a crash never leaves half a file.
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), "utf-8")
    tmp.replace(path)


def trash(path: Path) -> None:
    """Moves to the macOS Trash rather than deleting, so mistakes can be undone."""
    dest = _unique(Path.home() / ".Trash", f"Folio {path.name}")
    shutil.move(str(path), dest)


# --- projects

def project_dir(pid: str) -> Path:
    d = PROJECTS / pid
    if not (d / "project.json").exists() or ".." in pid or "/" in pid:
        raise NotFound(pid)
    return d


def list_projects() -> list[dict]:
    PROJECTS.mkdir(parents=True, exist_ok=True)
    out = []
    for d in PROJECTS.iterdir():
        meta = d / "project.json"
        if meta.exists():
            p = read_json(meta)
            meetings = list((d / "meetings").glob("*/meeting.json"))
            out.append({**p, "id": d.name, "meetings": len(meetings),
                        "updated": max([m.stat().st_mtime for m in meetings] + [meta.stat().st_mtime])})
    return sorted(out, key=lambda p: -p["updated"])


def create_project(name: str) -> dict:
    PROJECTS.mkdir(parents=True, exist_ok=True)
    d = _unique(PROJECTS, _slug(name))
    (d / "meetings").mkdir(parents=True)
    write_json(d / "project.json", {"name": name.strip(), "created": time.time()})
    (d / "project.md").write_text("", "utf-8")
    (d / "glossary.tsv").write_text("# term\talso heard as (comma separated)\tnote\n", "utf-8")
    return get_project(d.name)


def get_project(pid: str) -> dict:
    d = project_dir(pid)
    return {**read_json(d / "project.json"), "id": pid,
            "knowledge": (d / "project.md").read_text("utf-8") if (d / "project.md").exists() else "",
            "glossary": (d / "glossary.tsv").read_text("utf-8") if (d / "glossary.tsv").exists() else ""}


def update_project(pid: str, name: str | None = None, knowledge: str | None = None,
                   glossary: str | None = None) -> dict:
    d = project_dir(pid)
    if name is not None and name.strip():
        meta = read_json(d / "project.json")
        meta["name"] = name.strip()
        write_json(d / "project.json", meta)
    if knowledge is not None:
        (d / "project.md").write_text(knowledge, "utf-8")
    if glossary is not None:
        (d / "glossary.tsv").write_text(glossary, "utf-8")
    return get_project(pid)


# --- meetings

def meeting_dir(pid: str, mid: str) -> Path:
    d = project_dir(pid) / "meetings" / mid
    if not (d / "meeting.json").exists() or ".." in mid or "/" in mid:
        raise NotFound(mid)
    return d


def list_meetings(pid: str) -> list[dict]:
    d = project_dir(pid) / "meetings"
    out = [{**read_json(m), "id": m.parent.name} for m in d.glob("*/meeting.json")]
    return sorted(out, key=lambda m: m.get("date", 0), reverse=True)


def create_meeting(pid: str, title: str, date: float, source: str, original_name: str | None) -> tuple[str, Path]:
    base = datetime.fromtimestamp(date).strftime("%Y-%m-%d-%H%M") + "-" + _slug(title)
    d = _unique(project_dir(pid) / "meetings", base)
    d.mkdir()
    write_json(d / "meeting.json", {"title": title, "date": date, "source": source,
                                    "original": original_name, "status": "queued", "created": time.time()})
    return d.name, d


def get_meeting(pid: str, mid: str) -> dict:
    d = meeting_dir(pid, mid)
    m = {**read_json(d / "meeting.json"), "id": mid, "project": pid}
    m["transcript"] = read_json(d / "transcript.json", default={}) or None
    m["hasAudio"] = (d / "audio.m4a").exists()
    return m


def update_meeting(pid: str, mid: str, **fields) -> dict:
    d = meeting_dir(pid, mid)
    meta = read_json(d / "meeting.json")
    meta.update({k: v for k, v in fields.items() if v is not None})
    write_json(d / "meeting.json", meta)
    return meta


def original_file(d: Path) -> Path | None:
    found = [p for p in d.glob("original.*")]
    return found[0] if found else None
