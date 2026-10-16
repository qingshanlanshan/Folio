"""HTTP API + the web UI (static files in web/, no build step)."""
import shutil
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import correct, jobs, live, people, search, store, summarize, transcript

jobs.AFTER.append(people.match)
jobs.HANDLERS["correct"] = correct.run
jobs.HANDLERS["summarize"] = summarize.run
jobs.FOLLOW_UP["process"] = "summarize"
from .paths import ROOT

app = FastAPI(title="Folio")
AUDIO_EXT = {".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".aac", ".wma", ".aiff", ".aif", ".caf",
             ".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi", ".3gp", ".amr"}


@app.exception_handler(store.NotFound)
def _nf(request: Request, exc: store.NotFound):
    return JSONResponse({"detail": "not found"}, status_code=404)


@app.on_event("startup")
def _start():
    jobs.start()


# --- projects

class ProjectIn(BaseModel):
    name: str | None = None
    knowledge: str | None = None
    glossary: str | None = None


@app.get("/api/projects")
def projects():
    return store.list_projects()


@app.post("/api/projects")
def new_project(body: ProjectIn):
    if not (body.name or "").strip():
        raise HTTPException(400, "name required")
    return store.create_project(body.name)


@app.get("/api/projects/{pid}")
def project(pid: str):
    return {**store.get_project(pid), "meetings": store.list_meetings(pid), "jobs": _jobs_for(pid),
            "state": summarize.state(pid), "todos": summarize.todos(pid)}


class StateIn(BaseModel):
    text: str


@app.put("/api/projects/{pid}/state")
def edit_state(pid: str, body: StateIn):
    summarize.save_state(pid, body.text, "edited by you")
    return project(pid)


class TodoIn(BaseModel):
    text: str | None = None
    owner: str | None = None
    due: str | None = None
    status: str | None = None


@app.post("/api/projects/{pid}/todos")
def add_todo(pid: str, body: TodoIn):
    if not (body.text or "").strip():
        raise HTTPException(400, "text")
    items = summarize.todos(pid)
    items.append({"id": __import__("uuid").uuid4().hex[:6], "text": body.text.strip(), "owner": body.owner or "",
                  "due": body.due or "", "status": "open", "touched": True, "created": time.time()})
    summarize.save_todos(pid, items)
    return project(pid)


@app.patch("/api/projects/{pid}/todos/{tid}")
def edit_todo(pid: str, tid: str, body: TodoIn):
    items = summarize.todos(pid)
    for x in items:
        if x["id"] == tid:
            for k in ("text", "owner", "due", "status"):
                v = getattr(body, k)
                if v is not None:
                    x[k] = v
            x["touched"] = True
            x["updated"] = time.time()
    summarize.save_todos(pid, items)
    return project(pid)


@app.delete("/api/projects/{pid}/todos/{tid}")
def delete_todo(pid: str, tid: str):
    summarize.save_todos(pid, [x for x in summarize.todos(pid) if x["id"] != tid])
    return project(pid)


class AskIn(BaseModel):
    question: str


@app.post("/api/projects/{pid}/ask")
def ask(pid: str, body: AskIn):
    store.project_dir(pid)
    if not body.question.strip():
        raise HTTPException(400, "question")
    return summarize.ask(pid, body.question.strip())


@app.put("/api/projects/{pid}")
def edit_project(pid: str, body: ProjectIn):
    return store.update_project(pid, body.name, body.knowledge, body.glossary)


@app.delete("/api/projects/{pid}")
def delete_project(pid: str):
    store.trash(store.project_dir(pid))
    return {"ok": True}


def _jobs_for(pid: str) -> dict:
    return {k.split("/", 1)[1]: v for k, v in jobs.status().items() if k.startswith(pid + "/")}


# --- meetings

@app.post("/api/projects/{pid}/import")
async def import_audio(pid: str, file: UploadFile = File(...), title: str = Form(""),
                       date: float = Form(0)):
    store.project_dir(pid)
    ext = Path(file.filename or "").suffix.lower()
    if ext not in AUDIO_EXT:
        raise HTTPException(400, f"Unsupported file type {ext or '(none)'}")
    name = title.strip() or Path(file.filename).stem
    mid, d = store.create_meeting(pid, name, date or time.time(), "import", file.filename)
    with open(d / f"original{ext}", "wb") as out:
        shutil.copyfileobj(file.file, out, 1 << 20)
    jobs.submit(pid, mid)
    return {"id": mid}


@app.get("/api/projects/{pid}/meetings/{mid}")
def meeting(pid: str, mid: str):
    m = store.get_meeting(pid, mid)
    m["projectName"] = store.get_project(pid)["name"]
    if m["transcript"]:
        people.resolve(m["transcript"])
        m["paragraphs"] = transcript.paragraphs(m["transcript"])
    m["job"] = jobs.status().get(f"{pid}/{mid}")
    c = store.read_json(store.meeting_dir(pid, mid) / "corrections.json", default={"runs": [], "items": []})
    m["corrections"] = [i for i in c["items"] if i["status"] == "pending"]
    m["correctionRuns"] = c["runs"][-3:]
    m["summary"] = store.read_json(store.meeting_dir(pid, mid) / "summary.json", default={}) or None
    if m["summary"]:
        m["todos"] = [x for x in summarize.todos(pid) if x.get("from", {}).get("meeting") == mid
                      or (x.get("closedBy") or {}).get("meeting") == mid]
    return m


class MeetingIn(BaseModel):
    title: str | None = None
    date: float | None = None


@app.patch("/api/projects/{pid}/meetings/{mid}")
def edit_meeting(pid: str, mid: str, body: MeetingIn):
    store.update_meeting(pid, mid, title=body.title, date=body.date)
    return meeting(pid, mid)


@app.delete("/api/projects/{pid}/meetings/{mid}")
def delete_meeting(pid: str, mid: str):
    store.trash(store.meeting_dir(pid, mid))
    return {"ok": True}


@app.get("/api/projects/{pid}/meetings/{mid}/audio")
def meeting_audio(pid: str, mid: str):
    f = store.meeting_dir(pid, mid) / "audio.m4a"
    if not f.exists():
        raise HTTPException(404, "no audio yet")
    return FileResponse(f, media_type="audio/mp4")


class Reprocess(BaseModel):
    kind: str = "process"      # "process" (transcribe again) or "speakers" (re-detect speakers only)
    speakers: int = -1         # -1 = detect the count


@app.post("/api/projects/{pid}/meetings/{mid}/reprocess")
def reprocess(pid: str, mid: str, body: Reprocess):
    if body.kind not in ("process", "speakers"):
        raise HTTPException(400, "kind")
    jobs.submit(pid, mid, body.kind, speakers=body.speakers)
    return {"ok": True}


# --- corrections

class CorrectIn(BaseModel):
    mode: str = "both"            # text | audio | both


@app.post("/api/projects/{pid}/meetings/{mid}/summarize")
def run_summary(pid: str, mid: str):
    if not (store.meeting_dir(pid, mid) / "transcript.json").exists():
        raise HTTPException(409, "No transcript yet")
    jobs.submit(pid, mid, "summarize")
    return {"ok": True}


@app.post("/api/projects/{pid}/meetings/{mid}/correct")
def run_correction(pid: str, mid: str, body: CorrectIn):
    if body.mode not in ("text", "audio", "both"):
        raise HTTPException(400, "mode")
    if not (store.meeting_dir(pid, mid) / "transcript.json").exists():
        raise HTTPException(409, "No transcript yet")
    jobs.submit(pid, mid, "correct", mode=body.mode)
    return {"ok": True}


class DecideIn(BaseModel):
    ids: list[str]
    accept: bool


@app.post("/api/projects/{pid}/meetings/{mid}/corrections")
def decide_corrections(pid: str, mid: str, body: DecideIn):
    correct.decide(pid, mid, body.ids, body.accept)
    return meeting(pid, mid)


# --- transcript edits

class Edit(BaseModel):
    op: str                    # "text" | "speaker" | "rename"
    first: int = 0
    last: int = 0
    text: str = ""
    speaker: int = 0
    name: str | None = None


@app.post("/api/projects/{pid}/meetings/{mid}/transcript")
def edit_transcript(pid: str, mid: str, e: Edit):
    d = store.meeting_dir(pid, mid)
    t = store.read_json(d / "transcript.json")
    n = len(t["words"])
    if e.op in ("text", "speaker") and not (0 <= e.first <= e.last < n):
        raise HTTPException(400, "range")
    if e.op == "text":
        if not e.text.strip():
            raise HTTPException(400, "empty text")
        transcript.replace_span(t, e.first, e.last, e.text)
    elif e.op == "speaker":
        transcript.set_speaker(t, e.first, e.last, e.speaker)
    elif e.op == "rename":
        t["speakers"].setdefault(str(e.speaker), {})["name"] = (e.name or "").strip() or None
    else:
        raise HTTPException(400, "op")
    store.write_json(d / "transcript.json", t)
    return meeting(pid, mid)


@app.get("/api/projects/{pid}/meetings/{mid}/export.{fmt}")
def export(pid: str, mid: str, fmt: str):
    if fmt not in ("txt", "md", "srt"):
        raise HTTPException(400, "format")
    m = store.get_meeting(pid, mid)
    if not m["transcript"]:
        raise HTTPException(404, "no transcript yet")
    body = transcript.export(people.resolve(m["transcript"]), fmt, m["title"])
    name = f"{m['id']}.{fmt}"
    return PlainTextResponse(body, headers={"Content-Disposition": f'attachment; filename="{name}"'})


# --- people

class PersonIn(BaseModel):
    name: str | None = None
    aliases: list[str] | None = None
    notes: str | None = None
    projects: list[str] | None = None
    me: bool | None = None
    project: str | None = None     # on create: the project they're first seen in


@app.get("/api/people")
def list_people():
    return [{k: v for k, v in p.items() if k != "voices"} | {"voiceCount": len(p["voices"])}
            for p in people.all_people()]


@app.post("/api/people")
def new_person(body: PersonIn):
    if not (body.name or "").strip():
        raise HTTPException(400, "name required")
    p = people.create(body.name, body.project, bool(body.me))
    if body.me:
        people.update(p["id"], me=True)
    return p


@app.get("/api/people/{person}")
def person(person: str):
    p = people.get(person)
    return {**p, "voiceCount": len(p["voices"]), "appearances": people.appearances(person)}


@app.put("/api/people/{person}")
def edit_person(person: str, body: PersonIn):
    return people.update(person, name=(body.name or "").strip() or None, aliases=body.aliases,
                         notes=body.notes, projects=body.projects, me=body.me)


@app.delete("/api/people/{person}")
def delete_person(person: str):
    people.delete(person)
    return {"ok": True}


class LinkIn(BaseModel):
    person: str | None = None      # existing person id
    name: str | None = None        # or create a new person with this name
    reject: bool = False           # for unlink: also dismiss the suggestion


@app.post("/api/projects/{pid}/meetings/{mid}/speakers/{spk}/link")
def link_speaker(pid: str, mid: str, spk: str, body: LinkIn):
    person_id = body.person
    if not person_id:
        if not (body.name or "").strip():
            raise HTTPException(400, "person or name")
        person_id = people.create(body.name, pid)["id"]
    people.get(person_id)
    people.link(pid, mid, spk, person_id)
    return meeting(pid, mid)


@app.post("/api/projects/{pid}/meetings/{mid}/speakers/{spk}/unlink")
def unlink_speaker(pid: str, mid: str, spk: str, body: LinkIn):
    people.unlink(pid, mid, spk, reject=body.reject)
    return meeting(pid, mid)


@app.get("/api/search")
def do_search(q: str = "", person: str | None = None):
    return search.search(q, person)


# --- live recording

class LiveIn(BaseModel):
    project: str
    title: str = ""
    mic: bool = True
    system: bool = False
    simulate: str | None = None    # dev only (FOLIO_DEV=1): play a file in as the mic


@app.post("/api/live/start")
def live_start(body: LiveIn):
    store.project_dir(body.project)
    if not (body.mic or body.system):
        raise HTTPException(400, "Pick a source")
    title = body.title.strip() or time.strftime("Recording %d %b %H:%M")
    try:
        s = live.start(body.project, title, body.mic, body.system, body.simulate)
    except RuntimeError as e:
        raise HTTPException(409, str(e))
    return s.state()


@app.get("/api/live")
def live_state(since: int = 0):
    s = live._session
    return s.state(since) if s else None


@app.post("/api/live/stop")
def live_stop():
    s = live.active()
    if not s:
        raise HTTPException(404, "Not recording")
    s.stop()
    return {"pid": s.pid, "mid": s.mid}


@app.get("/api/jobs")
def all_jobs():
    return jobs.status()


app.mount("/", StaticFiles(directory=ROOT / "web", html=True), name="web")
