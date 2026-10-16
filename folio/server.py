"""HTTP API + the web UI (static files in web/, no build step)."""
import shutil
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import jobs, live, store, transcript
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
    return {**store.get_project(pid), "meetings": store.list_meetings(pid), "jobs": _jobs_for(pid)}


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
        m["paragraphs"] = transcript.paragraphs(m["transcript"])
    m["job"] = jobs.status().get(f"{pid}/{mid}")
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
    body = transcript.export(m["transcript"], fmt, m["title"])
    name = f"{m['id']}.{fmt}"
    return PlainTextResponse(body, headers={"Content-Disposition": f'attachment; filename="{name}"'})


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
