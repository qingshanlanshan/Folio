"""Project-aware meeting summaries.

The summary model sees: the project's knowledge (project.md), the rolling project state (state.md, which
it maintains), open to-dos, notes on the people present, the glossary, and the last few meetings'
summaries. After each meeting it writes summary.json / summary.md, updates state.md (old versions kept in
state-history/), and updates todos.json: new action items, and items the meeting reports as done.
"""
import json
import re
import time
import uuid

from . import glossary, people, store, transcript
from . import llm
from .llm import chat, chat_json, count_tokens



def _clock(s: float) -> str:
    return f"{int(s // 60)}:{int(s % 60):02d}"


def transcript_text(t: dict) -> str:
    words = t["words"]
    return "\n".join(f"[{_clock(p['start'])}] {transcript.speaker_name(t, p['speaker'])}: "
                     + " ".join(w["text"] for w in words[p["first"]:p["last"] + 1]) for p in transcript.paragraphs(t))


def todos(pid: str) -> list[dict]:
    return store.read_json(store.project_dir(pid) / "todos.json", default={"items": []})["items"]


def save_todos(pid: str, items: list[dict]) -> None:
    store.write_json(store.project_dir(pid) / "todos.json", {"items": items})


def state(pid: str) -> str:
    f = store.project_dir(pid) / "state.md"
    return f.read_text("utf-8") if f.exists() else ""


def save_state(pid: str, text: str, reason: str) -> None:
    d = store.project_dir(pid)
    old = state(pid)
    if old.strip():
        h = d / "state-history"
        h.mkdir(exist_ok=True)
        (h / time.strftime("%Y-%m-%d-%H%M%S.md")).write_text(old, "utf-8")
    (d / "state.md").write_text(text.strip() + "\n", "utf-8")
    meta = store.read_json(d / "project.json")
    meta["stateUpdated"] = {"at": time.time(), "reason": reason}
    store.write_json(d / "project.json", meta)


def _context(pid: str, mid: str, t: dict) -> str:
    proj = store.get_project(pid)
    present = [s.get("person") for s in t["speakers"].values() if s.get("person")]
    ppl = [people.get(p) for p in present]
    gl = glossary.parse(proj["glossary"])
    open_todos = [x for x in todos(pid) if x["status"] == "open"][:40]
    date = store.read_json(store.meeting_dir(pid, mid) / "meeting.json")["date"]
    earlier = [m for m in store.list_meetings(pid) if m["date"] < date and m.get("status") == "ready"][:3]
    prev = []
    for m in reversed(earlier):
        s = store.read_json(store.meeting_dir(pid, m["id"]) / "summary.json", default={})
        if s:
            when = time.strftime("%d %b %Y", time.localtime(m["date"]))
            prev.append(f"- {m['title']} ({when}): {s.get('overview', '')}"
                        + ("" if not s.get("decisions") else " Decisions: " + "; ".join(s["decisions"][:5])))
    parts = [f"Project: {proj['name']}"]
    if proj["knowledge"].strip():
        parts.append("About the project (from the user):\n" + proj["knowledge"][:4000])
    if state(pid).strip():
        parts.append("Project state before this meeting:\n" + state(pid)[:4000])
    if prev:
        parts.append("Earlier meetings:\n" + "\n".join(prev))
    if open_todos:
        parts.append("Open to-dos (id: task — owner):\n" + "\n".join(
            f"{x['id']}: {x['text']}" + (f" — {x['owner']}" if x.get("owner") else "") for x in open_todos))
    if ppl:
        parts.append("People in this meeting:\n" + "\n".join(
            f"- {p['name']}" + (f": {p['notes'][:300]}" if p.get("notes") else "") for p in ppl))
    if gl:
        parts.append("Glossary: " + "; ".join(e["term"] + (f" ({e['note']})" if e["note"] else "") for e in gl[:60]))
    return "\n\n".join(parts)


SYSTEM = """You write meeting minutes for a project's ongoing record. Use the project context to understand
names, terms and what is going on, but report only what was said in THIS meeting's transcript. Never invent
facts, owners or dates. Speaker labels like "Speaker B" are unnamed people; keep them as they are.
Reply with JSON only."""

SCHEMA = """{
  "title": "short title for this meeting (max 8 words)",
  "overview": "3-5 sentences: purpose, what happened, outcome",
  "topics": [{"title": "...", "summary": "1-3 sentences", "start": "m:ss"}],
  "decisions": ["decision made in this meeting"],
  "action_items": [{"task": "...", "owner": "person or empty", "due": "as said, or empty"}],
  "todo_updates": [{"id": "id of an open to-do", "status": "done", "note": "evidence from this meeting"}],
  "progress": [{"area": "...", "update": "what moved forward or got stuck"}],
  "open_questions": ["unresolved question"]
}"""


def _notes_for_chunk(ctx: str, text: str) -> str:
    out = chat(SYSTEM.replace("Reply with JSON only.", ""),
               ctx + "\n\nPart of the meeting transcript:\n" + text +
               "\n\nWrite dense notes of this part: topics with times, decisions, action items with owners, "
               "progress, open questions, and any to-dos reported as done. Plain bullet points.", max_tokens=900)
    return out.strip()


def summarize(pid: str, mid: str, progress=lambda f: None) -> dict:
    d = store.meeting_dir(pid, mid)
    t = people.resolve(store.read_json(d / "transcript.json"))
    ctx = _context(pid, mid, t)
    text = transcript_text(t)
    progress(0.05)
    if count_tokens(text) > llm.context_tokens():
        # Long meeting: notes per part, then minutes from the notes.
        lines, chunks, cur, n = text.split("\n"), [], [], 0
        for line in lines:
            k = len(line) // 4
            if n + k > llm.context_tokens() * 0.8 and cur:
                chunks.append("\n".join(cur)); cur, n = [], 0
            cur.append(line); n += k
        if cur:
            chunks.append("\n".join(cur))
        notes = []
        for i, c in enumerate(chunks):
            notes.append(f"Part {i + 1}:\n" + _notes_for_chunk(ctx, c))
            progress(0.05 + 0.6 * (i + 1) / len(chunks))
        body = "Notes on the meeting, part by part:\n\n" + "\n\n".join(notes)
    else:
        body = "Transcript:\n" + text
    s = chat_json(SYSTEM, f"{ctx}\n\n{body}\n\nWrite the minutes as JSON in this shape:\n{SCHEMA}", max_tokens=2500)
    progress(0.75)
    s = _clean(s)
    s["at"] = time.time()
    s["writer"] = llm.describe()
    store.write_json(d / "summary.json", s)
    m = store.read_json(d / "meeting.json")
    (d / "summary.md").write_text(render(s, m["title"]), "utf-8")
    _apply_todos(pid, mid, s, t)
    _update_state(pid, m["title"], s)
    progress(1.0)
    return s


def _clean(s: dict) -> dict:
    out = {"title": str(s.get("title", ""))[:80], "overview": str(s.get("overview", ""))}
    for k in ("decisions", "open_questions"):
        out[k] = [str(x) for x in s.get(k, []) if str(x).strip()][:20]
    out["topics"] = [x for x in s.get("topics", []) if isinstance(x, dict) and x.get("title")][:15]
    out["action_items"] = [x for x in s.get("action_items", []) if isinstance(x, dict) and x.get("task")][:25]
    out["todo_updates"] = [x for x in s.get("todo_updates", []) if isinstance(x, dict) and x.get("id")][:25]
    out["progress"] = [x for x in s.get("progress", []) if isinstance(x, dict) and x.get("update")][:15]
    return out


def render(s: dict, title: str) -> str:
    md = [f"# {title}", "", s.get("overview", ""), ""]
    def sec(name, items):
        if items:
            md.extend([f"## {name}", *items, ""])
    sec("Topics", [f"- **{x['title']}** ({x.get('start', '')}) — {x.get('summary', '')}" for x in s["topics"]])
    sec("Decisions", [f"- {x}" for x in s["decisions"]])
    sec("Action items", [f"- [ ] {x['task']}" + (f" — {x['owner']}" if x.get("owner") else "")
                         + (f" (due {x['due']})" if x.get("due") else "") for x in s["action_items"]])
    sec("Progress", [f"- **{x.get('area', '')}**: {x['update']}" for x in s["progress"]])
    sec("Open questions", [f"- {x}" for x in s["open_questions"]])
    return "\n".join(md).strip() + "\n"


def _apply_todos(pid: str, mid: str, s: dict, t: dict) -> None:
    items = todos(pid)
    # Re-summarizing replaces the to-dos this meeting created (unless the user already touched them).
    items = [x for x in items if not (x.get("from", {}).get("meeting") == mid and not x.get("touched"))]
    for x in items:
        if x.get("closedBy", {}).get("meeting") == mid and not x.get("touched"):
            x["status"], x["closedBy"] = "open", None
    by_id = {x["id"]: x for x in items}
    for u in s["todo_updates"]:
        x = by_id.get(str(u["id"]))
        if x and str(u.get("status", "")).lower() == "done" and x["status"] == "open":
            x.update(status="done", closedBy={"meeting": mid, "note": str(u.get("note", ""))[:200]}, updated=time.time())
    names = {p["name"].lower(): p["id"] for p in people.all_people()}
    for a in s["action_items"]:
        owner = str(a.get("owner") or "").strip()
        items.append({"id": uuid.uuid4().hex[:6], "text": str(a["task"]).strip(), "owner": owner,
                      "person": names.get(owner.lower()), "due": str(a.get("due") or "").strip(),
                      "status": "open", "from": {"meeting": mid}, "created": time.time()})
    save_todos(pid, items)


STATE_SYSTEM = """You maintain a short living document describing where a project stands. Merge in what the
latest meeting changed; keep what is still true; drop what is resolved or outdated. Markdown, at most 350
words, with these sections: ## Goal, ## Current status, ## Recent decisions, ## Open issues, ## Next steps.
Only state facts found in the inputs."""


def _update_state(pid: str, title: str, s: dict) -> None:
    proj = store.get_project(pid)
    open_todos = [x for x in todos(pid) if x["status"] == "open"][:30]
    user = (f"Project: {proj['name']}\n\nAbout the project:\n{proj['knowledge'][:2500] or '(nothing yet)'}\n\n"
            f"Current state document:\n{state(pid) or '(empty: write the first version)'}\n\n"
            f"Latest meeting “{title}”:\n{json.dumps(s, ensure_ascii=False)[:6000]}\n\n"
            "Open to-dos:\n" + "\n".join(f"- {x['text']}" + (f" ({x['owner']})" if x.get("owner") else "") for x in open_todos)
            + "\n\nWrite the updated state document.")
    out = chat(STATE_SYSTEM, user, max_tokens=900, temp=0.2).strip()
    out = re.sub(r"^```(?:markdown)?\s*|\s*```$", "", out)
    if out:
        save_state(pid, out, f"after “{title}”")


# --- ask the project

ASK_SYSTEM = """You answer questions about a project from its meeting record. Use only the provided material.
Cite sources inline like [Meeting title, m:ss]. If the material doesn't answer it, say so briefly."""


def ask(pid: str, question: str) -> dict:
    from .search import search
    proj = store.get_project(pid)
    words = [w for w in re.findall(r"[\w']+", question.lower()) if len(w) > 3]
    scored = {}
    for w in set(words):
        for h in search(w, limit=400)["hits"]:
            if h["project"] != pid:
                continue
            k = (h["meeting"], h["start"])
            scored.setdefault(k, [0, h])[0] += 1
    top = sorted(scored.values(), key=lambda x: (-x[0], -x[1]["date"]))[:14]
    sums = []
    for m in store.list_meetings(pid)[:8]:
        s = store.read_json(store.meeting_dir(pid, m["id"]) / "summary.json", default={})
        if s:
            sums.append(f"- {m['title']} ({time.strftime('%d %b', time.localtime(m['date']))}): {s.get('overview', '')}")
    material = (f"Project state:\n{state(pid) or '(none)'}\n\nMeeting summaries (newest first):\n" + ("\n".join(sums) or "(none)")
                + "\n\nPassages:\n" + "\n".join(f"[{h['title']}, {_clock(h['start'])}] {h['speaker']}: {h['snippet']}" for _, h in top))
    with llm.for_project(pid):
        answer = chat(ASK_SYSTEM, f"Project: {proj['name']}\n\n{material}\n\nQuestion: {question}",
                      max_tokens=700, effort="low").strip()
    return {"answer": answer, "sources": [{"meeting": h["meeting"], "title": h["title"], "start": h["start"]} for _, h in top]}


def run(job: dict, key: str) -> None:
    from .jobs import set_step
    set_step(key, "summarizing")
    with llm.for_project(job["pid"]):
        summarize(job["pid"], job["mid"], lambda f: set_step(key, "summarizing", f))
