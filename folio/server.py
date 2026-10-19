"""HTTP API + the web UI (static files in web/, no build step)."""
import shutil
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import claude, correct, jobs, live, people, runtime, search, store, summarize, sync, transcript, updates

jobs.AFTER.append(people.match)
jobs.HANDLERS["correct"] = correct.run
jobs.HANDLERS["summarize"] = summarize.run
jobs.HANDLERS["state"] = summarize.run_state
jobs.FOLLOW_UP["process"] = "summarize"
from .paths import ROOT

app = FastAPI(title="Folio")
AUDIO_EXT = {".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".aac", ".wma", ".aiff", ".aif", ".caf",
             ".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi", ".3gp", ".amr"}


@app.exception_handler(store.NotFound)
def _nf(request: Request, exc: store.NotFound):
    return JSONResponse({"detail": "not found"}, status_code=404)


@app.exception_handler(claude.ClaudeError)
def _claude_error(request: Request, exc: claude.ClaudeError):
    return JSONResponse({"detail": str(exc)}, status_code=502)


@app.on_event("startup")
def _start():
    store.ensure_unsorted()
    jobs.start()
    runtime.start()
    updates.start()
    sync.start()


# --- projects

class ProjectIn(BaseModel):
    name: str | None = None
    knowledge: str | None = None
    glossary: str | None = None
    localOnly: bool | None = None


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
    members = [{"id": p["id"], "name": p["name"], "notes": p.get("notes", ""), "voiceCount": len(p["voices"]), "me": p.get("me", False)}
               for p in people.all_people() if pid in p["projects"]]
    return {**store.get_project(pid), "meetings": store.list_meetings(pid), "jobs": _jobs_for(pid),
            "state": summarize.state(pid), "todos": summarize.todos(pid), "people": members}


class MemberIn(BaseModel):
    person: str | None = None      # an existing person
    name: str | None = None        # or a new one


@app.post("/api/projects/{pid}/people")
def add_member(pid: str, body: MemberIn):
    store.project_dir(pid)
    if body.person:
        p = people.get(body.person)
    elif (body.name or "").strip():
        p = people.create(body.name, pid)
    else:
        raise HTTPException(400, "person or name")
    if pid not in p["projects"]:
        people.update(p["id"], projects=p["projects"] + [pid])
    return project(pid)


@app.delete("/api/projects/{pid}/people/{person}")
def remove_member(pid: str, person: str):
    p = people.get(person)
    people.update(person, projects=[x for x in p["projects"] if x != pid])
    return project(pid)


class StateIn(BaseModel):
    text: str


@app.put("/api/projects/{pid}/state")
def edit_state(pid: str, body: StateIn):
    summarize.save_state(pid, body.text, "edited by you")
    return project(pid)


class StateUpdateIn(BaseModel):
    mode: str = "note"              # "note": revise with what changed; "rebuild": from all meetings
    note: str = ""
    addToKnowledge: bool = True


@app.post("/api/projects/{pid}/state/update")
def update_state(pid: str, body: StateUpdateIn):
    store.project_dir(pid)
    if body.mode not in ("note", "rebuild") or (body.mode == "note" and not body.note.strip()):
        raise HTTPException(400, "Describe what changed.")
    meta = store.read_json(store.project_dir(pid) / "project.json")
    if meta.pop("stateError", None):
        store.write_json(store.project_dir(pid) / "project.json", meta)
    jobs.submit(pid, "_state", "state", mode=body.mode, note=body.note, addToKnowledge=body.addToKnowledge)
    return project(pid)


@app.get("/api/projects/{pid}/state/history")
def state_history(pid: str):
    return summarize.state_history(pid)


class RestoreIn(BaseModel):
    file: str


@app.post("/api/projects/{pid}/state/restore")
def restore_state(pid: str, body: RestoreIn):
    summarize.restore_state(pid, body.file)
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
    return store.update_project(pid, body.name, body.knowledge, body.glossary, body.localOnly)


@app.delete("/api/projects/{pid}")
def delete_project(pid: str):
    if pid == store.UNSORTED:
        raise HTTPException(400, "Unsorted can't be deleted.")
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
    store.update_meeting(pid, mid, title=body.title, date=body.date,
                         titleSource="user" if body.title else None)
    return meeting(pid, mid)


def move(pid: str, mid: str, to: str, summarize_after: bool = True) -> str:
    """Files a recording in another project, with its voice prints and to-dos."""
    if pid == to:
        return mid
    store.project_dir(to)
    if f"{pid}/{mid}" in jobs.status():
        raise HTTPException(409, "This recording is being processed; move it when that finishes.")
    new_mid = store.move_meeting(pid, mid, to)
    people.retarget(pid, mid, to, new_mid)
    summarize.move_todos(pid, mid, to, new_mid)
    d = store.meeting_dir(to, new_mid)
    # Summarize again with the new project's context (its state and to-dos update too).
    if summarize_after and to != store.UNSORTED and (d / "transcript.json").exists():
        jobs.submit(to, new_mid, "summarize")
    return new_mid


class MoveIn(BaseModel):
    to: str


@app.post("/api/projects/{pid}/meetings/{mid}/move")
def move_meeting(pid: str, mid: str, body: MoveIn):
    return {"project": body.to, "meeting": move(pid, mid, body.to)}


class SummaryIn(BaseModel):
    overview: str = ""
    topics: list[dict] = []
    decisions: list[str] = []
    action_items: list[dict] = []
    progress: list[dict] = []
    open_questions: list[str] = []


@app.put("/api/projects/{pid}/meetings/{mid}/summary")
def edit_summary(pid: str, mid: str, body: SummaryIn):
    summarize.save_edited(pid, mid, body.model_dump())
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
    project: str = store.UNSORTED
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


class LiveTargetIn(BaseModel):
    project: str


@app.patch("/api/live")
def live_target(body: LiveTargetIn):
    s = live.active()
    if not s:
        raise HTTPException(404, "Not recording")
    store.project_dir(body.project)
    s.target = body.project
    return s.state(len(s.events))


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


# --- app: activity, models, windows, quit

KIND = {"process": "Transcribing", "speakers": "Finding speakers", "correct": "Fixing transcript",
        "summarize": "Summarizing", "state": "Updating project state"}


@app.get("/api/status")
def app_status(window: str = ""):
    runtime.window_seen(window)
    tasks = []
    for key, st in jobs.status().items():
        pid, mid = key.split("/", 1)
        try:
            pname = store.get_project(pid)["name"]
            title = "Where things stand" if mid == "_state" else \
                store.read_json(store.meeting_dir(pid, mid) / "meeting.json")["title"]
        except store.NotFound:
            title, pname = mid, pid
        tasks.append({"key": key, "project": pid, "meeting": mid, "title": title, "projectName": pname,
                      "kind": st["kind"], "label": KIND.get(st["kind"], st["kind"]), "step": st["step"],
                      "progress": st["progress"], "running": bool(st.get("running")),
                      "stopping": bool(st.get("stopping")), "queued": st.get("queued", 0)})
    tasks.sort(key=lambda x: (not x["running"], x["queued"]))
    s = live.active()
    rec = None
    if s:
        st = s.state(len(s.events))
        rec = {k: st[k] for k in ("pid", "mid", "title", "elapsed", "tracks", "stopping")}
    return {"recording": rec, "tasks": tasks, "models": runtime.models(), "memory": runtime.memory(),
            "settings": runtime.settings(), "windows": runtime.windows_open(), "app": updates.info(),
            "syncRev": sync._status["rev"]}


class WindowIn(BaseModel):
    id: str
    closing: bool = False


@app.post("/api/window")
async def window_event(request: Request):
    # Sent by sendBeacon when a tab closes, so the body arrives as text.
    try:
        body = WindowIn.model_validate_json(await request.body())
    except ValueError:
        raise HTTPException(400, "body")
    runtime.window_seen(body.id, body.closing)
    return {"ok": True}


class CancelIn(BaseModel):
    key: str | None = None        # "project/meeting"; none = everything


@app.post("/api/tasks/stop")
def stop_tasks(body: CancelIn):
    keys = [body.key] if body.key else list(jobs.status())
    return {"stopped": [k for k in keys if jobs.cancel(k)]}


class UnloadIn(BaseModel):
    id: str | None = None         # none = all models


@app.post("/api/models/unload")
def unload_models(body: UnloadIn):
    try:
        return {"unloaded": runtime.unload(body.id), "memory": runtime.memory()}
    except RuntimeError:
        raise HTTPException(409, "That model is in use right now. Stop the task using it, or try again when it finishes.")


class SettingsIn(BaseModel):
    autoUnloadMinutes: int | None = None
    notifications: bool | None = None
    aiEngine: str | None = None          # "local" | "claude"
    claudeModel: str | None = None


@app.get("/api/settings")
def get_settings():
    month = claude.usage()["months"].get(time.strftime("%Y-%m"), {"requests": 0, "usd": 0.0})
    return {**runtime.settings(), "claudeKey": claude.key_status(), "claudeModels":
            [{"id": k, "name": v[0], "in": v[1], "out": v[2]} for k, v in claude.MODELS.items()],
            "claudeUsage": {"month": month, "all": claude.usage()["months"]}}


@app.put("/api/settings")
def edit_settings(body: SettingsIn):
    if body.aiEngine not in (None, "local", "claude") or (body.claudeModel and body.claudeModel not in claude.MODELS):
        raise HTTPException(400, "setting")
    runtime.save_settings(**body.model_dump())
    return get_settings()


class KeyIn(BaseModel):
    key: str = ""


@app.put("/api/settings/claude-key")
def set_claude_key(body: KeyIn):
    k = body.key.strip()
    if not k:
        claude.delete_key()
    elif not k.startswith("sk-ant-") or len(k) < 30:
        raise HTTPException(400, "That doesn't look like an Anthropic API key (they start with sk-ant-).")
    else:
        claude.save_key(k)
    return get_settings()


@app.post("/api/settings/claude-test")
def test_claude():
    if not claude.key_status():
        raise HTTPException(409, "Add an API key first.")
    try:
        return claude.test()
    except claude.ClaudeError as e:
        raise HTTPException(502, str(e))


class QuitIn(BaseModel):
    force: bool = False


# --- sync (your own Cloudflare R2 bucket)

class SyncConnectIn(BaseModel):
    account: str
    bucket: str
    keyId: str
    secret: str


class SyncJoinIn(BaseModel):
    code: str


def _sync(fn):
    try:
        return fn()
    except sync.SyncError as e:
        raise HTTPException(400, str(e))


@app.get("/api/sync")
def sync_status():
    return sync.status()


@app.post("/api/sync/connect")
def sync_connect(body: SyncConnectIn):
    return _sync(lambda: sync.connect(body.account, body.bucket, body.keyId, body.secret))


@app.post("/api/sync/join")
def sync_join(body: SyncJoinIn):
    return _sync(lambda: sync.join(body.code))


@app.post("/api/sync/now")
def sync_now():
    sync.kick()
    return sync.status()


@app.get("/api/sync/code")
def sync_code():
    return _sync(lambda: {"code": sync.code()})


@app.delete("/api/sync")
def sync_off():
    sync.disconnect()
    return sync.status()


@app.post("/api/update/check")
def update_check():
    return updates.check()


@app.post("/api/update/install")
def update_install():
    try:
        updates.install()
    except RuntimeError as e:
        raise HTTPException(409, str(e))
    return updates.info()


@app.post("/api/quit")
def quit_folio(body: QuitIn):
    runtime.quit(body.force)
    return {"ok": True}


app.mount("/", StaticFiles(directory=ROOT / "web", html=True), name="web")
