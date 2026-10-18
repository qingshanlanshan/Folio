import { html, render, useState, useEffect, useRef, useMemo, useCallback } from "./vendor/preact-htm.js";

// ---------- helpers

const api = async (path, opts = {}) => {
  const init = { ...opts };
  if (opts.json !== undefined) {
    init.method = init.method || "POST";
    init.headers = { "content-type": "application/json" };
    init.body = JSON.stringify(opts.json);
  }
  const r = await fetch("/api" + path, init);
  if (!r.ok) {
    let msg = r.statusText;
    try { msg = (await r.json()).detail || msg; } catch {}
    throw new Error(msg);
  }
  return r.json();
};

const clock = (s) => {
  s = Math.max(0, s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = Math.floor(s % 60);
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(x).padStart(2, "0")}` : `${m}:${String(x).padStart(2, "0")}`;
};
const when = (t) => new Date(t * 1000).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
const COLORS = ["#2f7d6b", "#b5603b", "#4a6fb5", "#9a5aa8", "#b08a2e", "#3f8fa6", "#a8485f", "#6b7d3a"];
const color = (spk) => (spk == null ? "#999" : COLORS[spk % COLORS.length]);
const spkName = (t, spk) => {
  if (spk == null) return "Unknown";
  return t.speakers?.[spk]?.name || `Speaker ${spk < 26 ? String.fromCharCode(65 + spk) : spk}`;
};
const STEPS = { "listening again": "Listening again", "checking text": "Checking text", summarizing: "Summarizing", recording: "Recording", queued: "Queued", decoding: "Reading audio", transcribing: "Transcribing", speakers: "Finding speakers" };

let toastTimer;
function toast(msg) {
  let el = document.querySelector(".toast");
  if (!el) { el = document.createElement("div"); el.className = "toast"; document.body.append(el); }
  el.textContent = msg;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), 2600);
}

// `autofocus` only works on page load; this focuses an element when it mounts.
function useFocus() {
  const ref = useRef();
  useEffect(() => { ref.current?.focus(); }, []);
  return ref;
}

function useHash() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const f = () => setHash(location.hash);
    addEventListener("hashchange", f);
    return () => removeEventListener("hashchange", f);
  }, []);
  const [path, query] = hash.replace(/^#\/?/, "").split("?");
  const parts = path.split("/").map(decodeURIComponent);
  parts.query = new URLSearchParams(query || "");
  return parts;
}

// ---------- app shell

function App() {
  const route = useHash();
  const [projects, setProjects] = useState(null);
  const reload = useCallback(() => api("/projects").then(setProjects).catch((e) => toast(e.message)), []);
  const pid = route[0] === "p" ? route[1] : null;
  // Stay in sync with changes made elsewhere (another tab, the Finder).
  useEffect(() => { reload(); }, [pid]);
  useEffect(() => {
    addEventListener("focus", reload);
    return () => removeEventListener("focus", reload);
  }, []);
  const mid = route[2] === "m" ? route[3] : null;

  return html`<div class="layout">
    <${Sidebar} projects=${projects} pid=${pid} route=${route} reload=${reload} />
    <main>
      ${mid ? html`<${Meeting} key=${pid + "/" + mid} pid=${pid} mid=${mid} at=${Number(route.query.get("t")) || null} />`
        : pid ? html`<${Project} key=${pid} pid=${pid} reloadProjects=${reload} />`
        : route[0] === "people" && route[1] ? html`<${Person} key=${route[1]} id=${route[1]} projects=${projects} />`
        : route[0] === "people" ? html`<${People} />`
        : route[0] === "search" ? html`<${Search} key=${route[1]} q=${route[1] || ""} />`
        : html`<${Home} projects=${projects} />`}
    </main>
  </div>`;
}

function Sidebar({ projects, pid, route, reload }) {
  const [q, setQ] = useState(route[0] === "search" && !(route[1] || "").startsWith("@") ? route[1] : "");
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const creating = useRef(false);
  const create = async (e) => {
    e.preventDefault();
    if (creating.current) return;
    if (!name.trim()) return setAdding(false);
    creating.current = true;
    try {
      const p = await api("/projects", { json: { name } });
      setName(""); setAdding(false); await reload();
      location.hash = `#/p/${encodeURIComponent(p.id)}`;
    } catch (err) { toast(err.message); }
    creating.current = false;
  };
  return html`<nav class="side">
    <a class="brand" href="#/"><img src="icon.svg" alt="" />Folio</a>
    <form onSubmit=${(e) => { e.preventDefault(); if (q.trim()) location.hash = "#/search/" + encodeURIComponent(q.trim()); }}>
      <input type="search" placeholder="Search words or names" value=${q} onInput=${(e) => setQ(e.target.value)} />
    </form>
    <a class=${"plink" + (route[0] === "people" ? " on" : "")} href="#/people"><span>People</span></a>
    <h4>Projects</h4>
    ${(projects || []).map((p) => html`<a class=${"plink" + (p.id === pid ? " on" : "")} href=${"#/p/" + encodeURIComponent(p.id)}>
      <span>${p.name}</span><small>${p.meetings || ""}</small></a>`)}
    ${adding
      ? html`<form onSubmit=${create}><${FocusInput} placeholder="Project or course name" value=${name}
          onInput=${(e) => setName(e.target.value)} onBlur=${create} /></form>`
      : html`<div class="plink" onClick=${() => setAdding(true)} style="color:var(--muted)">+ New project</div>`}
    <div class="quit"><button class="btn" onClick=${quitFolio}>Quit Folio</button></div>
  </nav>`;
}

async function quitFolio() {
  let jobs = {}, live = null;
  try { [jobs, live] = await Promise.all([api("/jobs"), api("/live")]); } catch {}
  const busy = Object.values(jobs).map((j) => STEPS[j.step] || j.step);
  let msg = "Quit Folio? The page stops working until you open Folio.app again.";
  if (live && !live.done) msg = "A recording is in progress. Quit anyway? It will be stopped and saved, and transcribed next time you open Folio.";
  else if (busy.length) msg = `Folio is still working (${busy.join(", ")}). Quit anyway? Transcriptions continue next time; unfinished fixes or summaries need starting again.`;
  if (!confirm(msg)) return;
  try { await api("/quit", { json: {} }); } catch {}
  document.getElementById("app").innerHTML = `<div class="stopped"><img src="icon.svg" alt="">
    <h1>Folio has stopped</h1><p>You can close this tab. To start again, open Folio.app or use your Folio bookmark.</p></div>`;
}

function Home({ projects }) {
  return html`<div class="page">
    <h1>Folio</h1>
    <div class="sub">Local meeting and lecture transcripts, organised by project. Nothing leaves this Mac.</div>
    ${projects && !projects.length
      ? html`<div class="empty">Create a project (a research group, a course, a client…) in the sidebar, then import recordings into it.</div>`
      : html`<div class="mlist">${(projects || []).map((p) => html`<a class="mrow" href=${"#/p/" + encodeURIComponent(p.id)}>
          <span class="t">${p.name}</span><span class="m">${p.meetings} recording${p.meetings === 1 ? "" : "s"}</span></a>`)}</div>`}
  </div>`;
}

// ---------- project

function Project({ pid, reloadProjects }) {
  const [p, setP] = useState(null);
  const [tab, setTab] = useState("overview");
  const load = useCallback(() => api(`/projects/${encodeURIComponent(pid)}`).then(setP).catch((e) => toast(e.message)), [pid]);
  useEffect(() => { load(); }, [load]);
  const busy = p && (Object.keys(p.jobs).length || p.meetings.some((m) => m.status === "queued" || m.status === "processing"));
  useEffect(() => {
    if (!busy) return;
    const t = setInterval(load, 1500);
    return () => clearInterval(t);
  }, [busy, load]);
  if (!p) return html`<div class="page"></div>`;

  const rename = async (name) => {
    if (name.trim() && name !== p.name) {
      await api(`/projects/${encodeURIComponent(pid)}`, { method: "PUT", json: { name } });
      load(); reloadProjects();
    }
  };
  return html`<div class="page">
    <div class="crumb"><a href="#/">Projects</a></div>
    <h1><input value=${p.name} onChange=${(e) => rename(e.target.value)} /></h1>
    <div class="sub">${p.meetings.length} recording${p.meetings.length === 1 ? "" : "s"}</div>
    <div class="tabs">
      <button class=${tab === "overview" ? "on" : ""} onClick=${() => setTab("overview")}>Overview</button>
      <button class=${tab === "meetings" ? "on" : ""} onClick=${() => setTab("meetings")}>Recordings</button>
      <button class=${tab === "knowledge" ? "on" : ""} onClick=${() => setTab("knowledge")}>Knowledge & glossary</button>
    </div>
    ${tab === "overview" ? html`<${Overview} p=${p} setP=${setP} goRecordings=${() => setTab("meetings")} />`
      : tab === "meetings" ? html`<${Meetings} p=${p} load=${load} />` : html`<${Knowledge} p=${p} load=${load} />`}
  </div>`;
}

function Overview({ p, setP, goRecordings }) {
  const base = `/projects/${encodeURIComponent(p.id)}`;
  const [q, setQ] = useState("");
  const [ans, setAns] = useState(null);
  const [asking, setAsking] = useState(false);
  const [editState, setEditState] = useState(null);
  const [newTodo, setNewTodo] = useState("");
  const [showDone, setShowDone] = useState(false);
  const titles = Object.fromEntries(p.meetings.map((m) => [m.id, m.title]));
  const ask = async (e) => {
    e.preventDefault();
    if (!q.trim()) return;
    setAsking(true); setAns(null);
    try { setAns(await api(base + "/ask", { json: { question: q } })); } catch (err) { toast(err.message); }
    setAsking(false);
  };
  const todo = async (id, body) => setP({ ...(await api(`${base}/todos/${id}`, { method: "PATCH", json: body })), jobs: p.jobs });
  const addTodo = async (e) => {
    e.preventDefault();
    if (!newTodo.trim()) return;
    setP(await api(base + "/todos", { json: { text: newTodo } })); setNewTodo("");
  };
  const open = p.todos.filter((x) => x.status === "open");
  const done = p.todos.filter((x) => x.status !== "open");
  const link = (mid, label) => html`<a class="src-link" href=${`#/p/${encodeURIComponent(p.id)}/m/${encodeURIComponent(mid)}`}>${label || titles[mid] || "meeting"}</a>`;
  const cite = (text) => {
    // [Meeting title, m:ss] → link to that moment.
    const parts = text.split(/(\[[^\]]+?, \d+:\d\d\])/g);
    return parts.map((part) => {
      const mm = part.match(/^\[(.+), (\d+):(\d\d)\]$/);
      const meet = mm && p.meetings.find((x) => x.title === mm[1]);
      return meet ? html`<a class="cite" href=${`#/p/${encodeURIComponent(p.id)}/m/${encodeURIComponent(meet.id)}?t=${Number(mm[2]) * 60 + Number(mm[3])}`}>${mm[1]}, ${mm[2]}:${mm[3]}</a>` : part;
    });
  };
  if (!p.meetings.length) return html`<div class="empty">Import or record the first meeting — the overview, to-dos and project state build up from there.
    <div style="margin-top:12px"><button class="btn primary" onClick=${goRecordings}>Go to recordings</button></div></div>`;
  return html`
    <form class="ask" onSubmit=${ask}>
      <input type="text" placeholder="Ask this project… e.g. “What did we decide about the battery?”" value=${q} onInput=${(e) => setQ(e.target.value)} />
      <button class="btn primary" disabled=${asking}>${asking ? "Thinking…" : "Ask"}</button>
    </form>
    ${ans ? html`<div class="card answer"><div class="ans">${ans.answer.split("\n").filter(Boolean).map((l) => html`<p>${cite(l)}</p>`)}</div>
      <button class="btn" onClick=${() => setAns(null)}>Close</button></div>` : null}

    <div class="cols">
      <section>
        <div class="row"><h3>Where things stand</h3><span class="spacer"></span>
          ${editState == null ? html`<button class="btn" onClick=${() => setEditState(p.state)}>Edit</button>` : null}</div>
        ${editState != null ? html`<textarea rows="16" value=${editState} onInput=${(e) => setEditState(e.target.value)}></textarea>
          <div class="row" style="margin-top:8px"><button class="btn primary" onClick=${async () => { setP({ ...(await api(base + "/state", { method: "PUT", json: { text: editState } })), jobs: p.jobs }); setEditState(null); }}>Save</button>
          <button class="btn" onClick=${() => setEditState(null)}>Cancel</button></div>`
          : p.state ? html`<div class="state" dangerouslySetInnerHTML=${{ __html: md(p.state) }}></div>
            ${p.stateUpdated ? html`<div class="hint">Updated ${when(p.stateUpdated.at)} ${p.stateUpdated.reason}</div>` : null}`
          : html`<div class="hint">Written by the AI after each meeting is summarized, and kept up to date. You can edit it.</div>`}
      </section>
      <section>
        <h3>To-dos <small style="font-weight:400;color:var(--muted)">${open.length} open</small></h3>
        <div class="todos">${open.map((x) => html`<label class="todo">
          <input type="checkbox" onChange=${() => todo(x.id, { status: "done" })} />
          <span>${x.text}${x.owner ? html` <span class="owner">${x.owner}</span>` : null}${x.due ? html` <small>due ${x.due}</small>` : null}
            ${x.from?.meeting ? html`<small> · ${link(x.from.meeting)}</small>` : null}</span></label>`)}
        </div>
        <form class="row" style="margin-top:8px" onSubmit=${addTodo}><input type="text" style="flex:1" placeholder="Add a to-do" value=${newTodo} onInput=${(e) => setNewTodo(e.target.value)} /></form>
        ${done.length ? html`<div class="hint" style="cursor:pointer" onClick=${() => setShowDone(!showDone)}>${showDone ? "▾" : "▸"} ${done.length} done</div>` : null}
        ${showDone ? html`<div class="todos">${done.map((x) => html`<label class="todo done">
          <input type="checkbox" checked onChange=${() => todo(x.id, { status: "open" })} />
          <span><s>${x.text}</s>${x.closedBy?.meeting ? html`<small> · closed in ${link(x.closedBy.meeting)}</small>` : null}</span></label>`)}</div>` : null}
      </section>
    </div>

    <h3>Recent meetings</h3>
    <div class="mlist">${p.meetings.slice(0, 5).map((m) => html`<${MeetingRow} p=${p} m=${m} job=${p.jobs[m.id]} />`)}</div>`;
}

function Meetings({ p, load }) {
  const [over, setOver] = useState(false);
  const [uploading, setUploading] = useState(0);
  const input = useRef();
  const upload = async (files) => {
    files = [...files];
    setUploading((n) => n + files.length);
    for (const f of files) {
      const fd = new FormData();
      fd.append("file", f);
      fd.append("date", String((f.lastModified || Date.now()) / 1000));
      try {
        const r = await fetch(`/api/projects/${encodeURIComponent(p.id)}/import`, { method: "POST", body: fd });
        if (!r.ok) throw new Error((await r.json()).detail || r.statusText);
      } catch (e) { toast(`${f.name}: ${e.message}`); }
      setUploading((n) => n - 1);
      load();
    }
  };
  return html`
    <${RecordPanel} p=${p} />
    <div class=${"drop" + (over ? " over" : "")}
      onDragOver=${(e) => { e.preventDefault(); setOver(true); }} onDragLeave=${() => setOver(false)}
      onDrop=${(e) => { e.preventDefault(); setOver(false); upload(e.dataTransfer.files); }}>
      ${uploading ? html`Uploading ${uploading}…` : html`Drop audio or video files here, or
        <button class="btn primary" onClick=${() => input.current.click()}>Import…</button>
        <div style="font-size:12px;margin-top:6px">mp3, m4a, wav, flac, ogg, mp4, mov, webm… transcribed locally</div>`}
      <input ref=${input} type="file" multiple accept="audio/*,video/*" hidden onChange=${(e) => { upload(e.target.files); e.target.value = ""; }} />
    </div>
    ${p.meetings.length ? html`<div class="mlist">${p.meetings.map((m) => html`<${MeetingRow} p=${p} m=${m} job=${p.jobs[m.id]} />`)}</div>`
      : html`<div class="empty">No recordings yet.</div>`}`;
}

const SOURCES = [
  { id: "mic", mic: true, system: false, title: "Microphone", sub: "In-person meeting, lecture, class" },
  { id: "both", mic: true, system: true, title: "Microphone + system audio", sub: "Online meeting: you on the mic, everyone else from Zoom, Teams, Meet…" },
  { id: "system", mic: false, system: true, title: "System audio only", sub: "A webinar or video playing on this Mac" },
];

function RecordPanel({ p }) {
  const [open, setOpen] = useState(false);
  const [src, setSrc] = useState(() => { try { return localStorage.getItem("folio.source") || "mic"; } catch { return "mic"; } });
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const start = async () => {
    const s = SOURCES.find((x) => x.id === src);
    try { localStorage.setItem("folio.source", src); } catch {}
    setBusy(true);
    try {
      const simulate = new URLSearchParams(location.search).get("simulate");
      const r = await api("/live/start", { json: { project: p.id, title, mic: s.mic, system: s.system, simulate } });
      location.hash = `#/p/${encodeURIComponent(p.id)}/m/${encodeURIComponent(r.mid)}`;
    } catch (e) { toast(e.message); setBusy(false); }
  };
  if (!open) return html`<div class="row" style="margin-bottom:12px">
    <button class="btn rec" onClick=${() => setOpen(true)}><i class="recdot"></i>Record</button></div>`;
  return html`<div class="card recpanel">
    <div class="sources">${SOURCES.map((s) => html`<label class=${"src" + (src === s.id ? " on" : "")}>
      <input type="radio" name="src" checked=${src === s.id} onChange=${() => setSrc(s.id)} />
      <b>${s.title}</b><span>${s.sub}</span></label>`)}</div>
    <div class="row" style="margin-top:12px">
      <input type="text" style="flex:1" placeholder="Title (optional)" value=${title} onInput=${(e) => setTitle(e.target.value)}
        onKeyDown=${(e) => e.key === "Enter" && start()} />
      <button class="btn rec" disabled=${busy} onClick=${start}><i class="recdot"></i>Start recording</button>
      <button class="btn" onClick=${() => setOpen(false)}>Cancel</button>
    </div>
    ${src !== "mic" ? html`<div class="hint">The first time, macOS asks to allow system audio recording. Headphones avoid echo; without them Folio removes what the mic picks up from the speakers.</div>` : null}
  </div>`;
}

function Live({ m, onDone }) {
  const [st, setSt] = useState(null);
  const [events, setEvents] = useState([]);
  const [stopping, setStopping] = useState(false);
  const box = useRef();
  const stick = useRef(true);
  useEffect(() => {
    let since = 0, alive = true, timer;
    const poll = async () => {
      try {
        const s = await api(`/live?since=${since}`);
        if (!alive) return;
        if (!s || s.mid !== m.id) { onDone(); return; }
        if (s.events.length) { since += s.events.length; setEvents((e) => [...e, ...s.events]); }
        setSt(s);
        if (s.done) { onDone(); return; }
      } catch {}
      timer = setTimeout(poll, 500);
    };
    poll();
    return () => { alive = false; clearTimeout(timer); };
  }, [m.id]);
  useEffect(() => { if (stick.current && box.current) box.current.scrollTop = box.current.scrollHeight; }, [events, st]);
  const stop = async () => { setStopping(true); try { await api("/live/stop", { json: {} }); } catch (e) { toast(e.message); } };
  const both = st && st.tracks.length === 2;
  const label = (tr) => (both ? (tr === "mic" ? "You" : "Others") : null);
  return html`<div class="live">
    <div class="row livebar">
      <span class="rectag"><i class="recdot pulse"></i>${st?.stopping || stopping ? "Finishing…" : "Recording"}</span>
      <span class="elapsed">${clock(st?.elapsed || 0)}</span>
      ${(st?.tracks || []).map((tr) => html`<span class="meter" title=${tr}><small>${tr === "mic" ? "Mic" : "System"}</small>
        <i style=${`width:${Math.min(100, Math.round(Math.sqrt(st.levels[tr] || 0) * 260))}%`}></i></span>`)}
      <span class="spacer"></span>
      <button class="btn rec" disabled=${stopping || st?.stopping} onClick=${stop}>■ Stop</button>
    </div>
    ${st?.error ? html`<div class="hint err">${st.error}</div>` : null}
    <div class="livetext" ref=${box} onScroll=${(e) => { const el = e.target; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40; }}>
      ${!events.length && !st?.partials?.length ? html`<div class="empty">Listening… text appears as people speak.</div>` : null}
      ${events.map((e) => html`<p key=${e.seq}>${label(e.track) ? html`<b class=${"who-" + e.track}>${label(e.track)}</b>` : null}
        <span class="ts" style="display:inline;margin-right:8px">${clock(e.start)}</span>${e.text}</p>`)}
      ${(st?.partials || []).map((pt) => html`<p class="partial">${label(pt.track) ? html`<b class=${"who-" + pt.track}>${label(pt.track)}</b>` : null}${pt.text}</p>`)}
    </div>
    <div class="hint">Draft transcript. After you stop, the whole recording is transcribed again with speakers.</div>
  </div>`;
}

function MeetingRow({ p, m, job }) {
  const status = job ? STEPS[job.step] || job.step : m.status === "error" ? "Failed" : m.status === "ready" ? null : STEPS[m.status] || m.status;
  return html`<a class="mrow" href=${`#/p/${encodeURIComponent(p.id)}/m/${encodeURIComponent(m.id)}`}>
    <span class="t">${m.title}</span>
    ${status ? html`<span class=${"badge" + (m.status === "error" ? " err" : "")} title=${m.error || ""}>${status}${job && job.progress ? ` ${Math.round(job.progress * 100)}%` : ""}</span>`
      : html`<span class="m">${m.duration ? clock(m.duration) : ""}</span>`}
    <span class="m">${when(m.date)}${m.original && m.source === "import" ? ` · ${m.original}` : ""}</span>
    ${job ? html`<div class="bar"><i style=${`width:${Math.round((job.progress || 0) * 100)}%`}></i></div>` : null}
  </a>`;
}

function Knowledge({ p, load }) {
  const [k, setK] = useState(p.knowledge);
  const [g, setG] = useState(p.glossary);
  const dirty = k !== p.knowledge || g !== p.glossary;
  const save = async () => {
    await api(`/projects/${encodeURIComponent(p.id)}`, { method: "PUT", json: { knowledge: k, glossary: g } });
    toast("Saved"); load();
  };
  return html`<div class="knowledge">
    <label>Project knowledge</label>
    <p>Goals, background, who's who, current status — anything the summary should know. Plain Markdown, stored as project.md.</p>
    <textarea rows="10" value=${k} onInput=${(e) => setK(e.target.value)}></textarea>
    <label>Glossary</label>
    <p>One term per line: term, then a tab, then how it tends to be misheard (comma separated), then a note. Used to fix transcripts.</p>
    <textarea rows="8" value=${g} onInput=${(e) => setG(e.target.value)}
      onKeyDown=${(e) => { if (e.key === "Tab") { e.preventDefault(); document.execCommand("insertText", false, "\t"); } }}></textarea>
    <div class="row" style="margin-top:10px"><button class="btn primary" disabled=${!dirty} onClick=${save}>Save</button></div>
  </div>`;
}

// ---------- meeting

function Meeting({ pid, mid, at }) {
  const [m, setM] = useState(null);
  const [menu, setMenu] = useState(null);     // {x, y, para}
  const [editing, setEditing] = useState(null); // paragraph index
  const [count, setCount] = useState("-1");
  const [people, setPeople] = useState(null);
  useEffect(() => { api("/people").then(setPeople); }, [mid]);
  const [view, setView] = useState(at != null ? "transcript" : null);
  const audio = useRef();
  const textRef = useRef();
  const base = `/projects/${encodeURIComponent(pid)}/meetings/${encodeURIComponent(mid)}`;
  const load = useCallback(() => api(base).then(setM).catch((e) => toast(e.message)), [base]);
  useEffect(() => { load(); }, [load]);
  const busy = m && (m.job || m.status === "queued" || m.status === "processing");
  useEffect(() => {
    if (!busy) return;
    const t = setInterval(load, 1500);
    return () => clearInterval(t);
  }, [busy, load]);

  const t = m?.transcript;
  const starts = useMemo(() => (t ? t.words.map((w) => w.start) : []), [t]);
  const fixing = useMemo(() => {
    const set = new Set();
    for (const c of m?.corrections || []) for (let i = c.first; i <= c.last; i++) set.add(i);
    return set;
  }, [m?.corrections]);

  // Highlight the word being played, straight in the DOM (thousands of spans, no re-render).
  useEffect(() => {
    const a = audio.current;
    if (!a || !t) return;
    let raf, last = -1;
    const tick = () => {
      const now = a.currentTime;
      let lo = 0, hi = starts.length - 1, i = -1;
      while (lo <= hi) { const mid = (lo + hi) >> 1; if (starts[mid] <= now) { i = mid; lo = mid + 1; } else hi = mid - 1; }
      if (i !== last) {
        textRef.current?.querySelector("span.cur")?.classList.remove("cur");
        const el = i >= 0 && now <= t.words[i].end + 0.3 ? textRef.current?.querySelector(`span[data-i="${i}"]`) : null;
        el?.classList.add("cur");
        last = i;
      }
      if (!a.paused) raf = requestAnimationFrame(tick);
    };
    const play = () => { cancelAnimationFrame(raf); tick(); };
    a.addEventListener("play", play); a.addEventListener("seeked", tick); a.addEventListener("timeupdate", tick);
    return () => { cancelAnimationFrame(raf); a.removeEventListener("play", play); a.removeEventListener("seeked", tick); a.removeEventListener("timeupdate", tick); };
  }, [t, starts, m?.hasAudio]);

  useEffect(() => {
    const close = () => setMenu(null);
    addEventListener("click", close);
    return () => removeEventListener("click", close);
  }, []);

  // Opened from search: scroll to the passage and cue the audio there.
  const jumped = useRef(false);
  useEffect(() => {
    if (at == null || !t || jumped.current) return;
    jumped.current = true;
    const i = starts.findIndex((x) => x >= at - 0.01);
    setTimeout(() => {
      const el = textRef.current?.querySelector(`span[data-i="${Math.max(0, i)}"]`);
      el?.scrollIntoView({ block: "center" });
      el?.closest(".para")?.classList.add("flash");
      if (audio.current) audio.current.currentTime = Math.max(0, at - 0.15);
    }, 50);
  }, [t, at]);
  if (!m) return html`<div class="page"></div>`;
  if (m.status === "recording") return html`<div class="page">
    <div class="crumb"><a href=${"#/p/" + encodeURIComponent(pid)}>${m.projectName}</a></div>
    <h1>${m.title}</h1>
    <${Live} m=${m} onDone=${load} />
  </div>`;

  const seek = (s) => { const a = audio.current; if (a) { a.currentTime = Math.max(0, s - 0.15); a.play(); } };
  const edit = async (body) => {
    try { setM(await api(base + "/transcript", { json: body })); } catch (e) { toast(e.message); }
  };
  const rename = async (title) => { if (title.trim() && title !== m.title) setM(await api(base, { method: "PATCH", json: { title } })); };
  const act = async (kind, a, b) => {
    try {
      if (kind === "para") return edit({ op: "speaker", first: a.first, last: a.last, speaker: b });
      setM(await api(`${base}/speakers/${a}/${kind}`, { json: b }));
      if (kind === "link") api("/people").then(setPeople);
    } catch (e) { toast(e.message); }
  };
  const openMenu = (e, speaker, para) => setMenu({ x: Math.min(e.clientX, innerWidth - 250), y: e.clientY, speaker, para, project: pid });
  const reprocess = (kind) => reprocessWith(kind, Number(count));
  const reprocessWith = async (kind, speakers) => {
    if (kind === "process" && t?.edited && !confirm("Transcribe again? Your text edits in this recording will be replaced.")) return;
    await api(base + "/reprocess", { json: { kind, speakers } });
    toast(kind === "process" ? "Transcribing again…" : "Finding speakers again…"); load();
  };
  const tab = view || (m.summary || (m.job && m.job.kind === "summarize") ? "summary" : "transcript");
  const summarizeNow = async () => {
    try { await api(base + "/summarize", { json: {} }); load(); } catch (e) { toast(e.message); }
  };
  const more = (v) => {
    if (v.startsWith("spk:")) { setCount(v.slice(4)); reprocessWith("speakers", Number(v.slice(4))); }
    else if (v === "summarize") { setView("summary"); summarizeNow(); }
    else if (v === "process") reprocess("process");
    else if (v === "delete") remove();
  };
  const fix = async (mode) => {
    setView("transcript");
    try { await api(base + "/correct", { json: { mode } }); toast("Looking for fixes…"); load(); } catch (e) { toast(e.message); }
  };
  const decide = async (ids, accept) => {
    try { setM(await api(base + "/corrections", { json: { ids, accept } })); } catch (e) { toast(e.message); }
  };
  const remove = async () => {
    if (!confirm(`Move "${m.title}" to the Trash?`)) return;
    await api(base, { method: "DELETE" });
    location.hash = `#/p/${encodeURIComponent(pid)}`;
  };
  const speakers = t ? Object.keys(t.speakers).map(Number) : [];

  return html`<div class="page">
    <div class="crumb"><a href=${"#/p/" + encodeURIComponent(pid)}>${m.projectName}</a></div>
    <h1><input value=${m.title} onChange=${(e) => rename(e.target.value)} /></h1>
    <div class="sub">${when(m.date)}${m.duration ? " · " + clock(m.duration) : ""}${speakers.length ? ` · ${speakers.length} speaker${speakers.length === 1 ? "" : "s"}` : ""}</div>

    <div class="player">
      ${m.hasAudio ? html`<audio ref=${audio} controls preload="metadata" src=${"/api" + base + "/audio"}></audio>` : null}
      <div class="row tools">
        ${m.job ? html`<span class="badge">${STEPS[m.job.step] || m.job.step}${m.job.progress ? ` ${Math.round(m.job.progress * 100)}%` : ""}</span>`
          : m.status === "error" ? html`<span class="badge err">Failed: ${m.error}</span>` : null}
        <span class="spacer"></span>
        ${t ? html`<select disabled=${!!m.job} onChange=${(e) => { const v = e.target.value; e.target.value = ""; if (v) fix(v); }}>
          <option value="">Fix transcript…</option>
          <option value="text">Check text (AI, fast)</option>
          <option value="audio">Listen again (audio)</option>
          <option value="both">Both (best)</option>
        </select>
        <select onChange=${(e) => { if (e.target.value) location.href = "/api" + base + "/export." + e.target.value; e.target.value = ""; }}>
          <option value="">Export…</option><option value="md">Markdown</option><option value="txt">Text</option><option value="srt">Subtitles (SRT)</option>
        </select>` : null}
        <select onChange=${(e) => { const v = e.target.value; e.target.value = ""; more(v); }}>
          <option value="">More…</option>
          ${t ? html`<optgroup label="Find speakers again">
            <option value="spk:-1" disabled=${!!m.job}>Detect how many</option>
            ${[2, 3, 4, 5, 6, 8].map((n) => html`<option value=${"spk:" + n} disabled=${!!m.job}>${n} speakers</option>`)}
          </optgroup>
          <option value="summarize" disabled=${!!m.job}>Summarize again</option>` : null}
          <option value="process" disabled=${!!m.job}>Transcribe again</option>
          <option value="delete">Delete recording…</option>
        </select>
      </div>
    </div>

    ${t ? html`<div class="tabs">
      <button class=${tab === "summary" ? "on" : ""} onClick=${() => setView("summary")}>Summary</button>
      <button class=${tab === "transcript" ? "on" : ""} onClick=${() => setView("transcript")}>Transcript${m.corrections?.length ? html` <span class="badge">${m.corrections.length}</span>` : null}</button>
    </div>` : null}
    ${t && tab === "summary" ? html`<${Summary} m=${m} job=${m.job} summarize=${summarizeNow} jump=${(sec) => { setView("transcript"); setTimeout(() => {
        const i = starts.findIndex((x) => x >= sec - 0.01);
        textRef.current?.querySelector(`span[data-i="${Math.max(0, i)}"]`)?.scrollIntoView({ block: "center" });
        seek(sec); }, 30); }} />` : null}
    <div style=${tab === "transcript" || !t ? "" : "display:none"}>
    ${t && m.corrections?.length ? html`<${Fixes} items=${m.corrections} decide=${decide} goto=${(i) => {
        const el = textRef.current?.querySelector(`span[data-i="${i}"]`);
        el?.scrollIntoView({ block: "center" }); el?.closest(".para")?.classList.remove("flash");
        void el?.offsetWidth; el?.closest(".para")?.classList.add("flash");
      }} />` : t && m.correctionRuns?.length && !m.job ? html`<div class="hint" style="margin:-6px 0 12px">Last check (${m.correctionRuns.at(-1).mode}): ${m.correctionRuns.at(-1).found ? "all suggestions handled" : "nothing to fix"}.</div>` : null}
    ${t ? html`<${SpeakerBar} t=${t} open=${(e, k) => openMenu(e, k, null)} act=${act} />` : null}
    ${!t ? html`<div class="empty">${m.job || m.status !== "error" ? "The transcript will appear here when processing finishes." : "Processing failed."}</div>` : html`
      <div ref=${textRef}>
        ${m.paragraphs.map((p, pi) => html`<div class="para" key=${p.first}>
          <div class="who">
            <span class="chip" onClick=${(e) => { e.stopPropagation(); openMenu(e, p.speaker, p); }}>
              <i class="dot" style=${"background:" + color(p.speaker)}></i>${spkName(t, p.speaker)}</span>
            <span class="ts" onClick=${() => seek(p.start)}>${clock(p.start)}</span>
          </div>
          ${editing === pi ? html`<${ParaEditor} t=${t} p=${p} done=${() => setEditing(null)} save=${(text) => edit({ op: "text", first: p.first, last: p.last, text }).then(() => setEditing(null))} />`
            : html`<div class="text">${t.words.slice(p.first, p.last + 1).map((w, k) => {
                const i = p.first + k;
                return html`<span data-i=${i} class=${fixing.has(i) ? "fix" : w.edited ? "ed" : w.conf < 0.75 ? "low" : ""} onClick=${() => seek(w.start)}>${w.text}</span>${" "}`;
              })}</div>`}
          ${editing === pi ? null : html`<button class="edit" title="Edit text" onClick=${() => setEditing(pi)}>✎</button>`}
        </div>`)}
      </div>`}
    </div>

    ${menu ? html`<${SpeakerMenu} menu=${menu} t=${t} people=${people} act=${act} close=${() => setMenu(null)} />` : null}
  </div>`;
}

function FocusInput(props) {
  const ref = useFocus();
  return html`<input type="text" ref=${ref} ...${props} />`;
}

function ParaEditor({ t, p, save, done }) {
  const focusRef = useFocus();
  const [text, setText] = useState(t.words.slice(p.first, p.last + 1).map((w) => w.text).join(" "));
  return html`<div>
    <textarea rows=${Math.max(3, Math.ceil(text.length / 80))} value=${text} ref=${focusRef} onInput=${(e) => setText(e.target.value)}
      onKeyDown=${(e) => { if (e.key === "Escape") done(); if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) save(text); }}></textarea>
    <div class="row" style="margin-top:6px"><button class="btn primary" onClick=${() => save(text)}>Save</button>
      <button class="btn" onClick=${done}>Cancel</button><span style="color:var(--muted);font-size:12px">⌘↩ to save, Esc to cancel</span></div>
  </div>`;
}

function SpeakerMenu({ menu, t, people, act, close }) {
  const { para, speaker } = menu;
  const spk = speaker ?? para.speaker;
  const info = t.speakers?.[spk] || {};
  const [q, setQ] = useState("");
  const input = useRef();
  useEffect(() => { input.current?.focus(); }, []);
  const ids = Object.keys(t.speakers).map(Number);
  const next = Math.max(-1, ...ids) + 1;
  const ql = q.trim().toLowerCase();
  const pid = menu.project;
  const matches = (people || [])
    .filter((p) => !ql || p.name.toLowerCase().includes(ql) || (p.aliases || []).some((a) => a.toLowerCase().includes(ql)))
    .sort((a, b) => (b.projects.includes(pid) - a.projects.includes(pid)) || a.name.localeCompare(b.name))
    .slice(0, 6);
  const exact = (people || []).some((p) => p.name.toLowerCase() === ql);
  const linkTo = (body) => { act("link", spk, body); close(); };
  return html`<div class="menu" style=${`left:${menu.x}px;top:${Math.min(menu.y + 8, innerHeight - 380)}px`} onClick=${(e) => e.stopPropagation()}>
    <div class="lbl">Who is ${spkName(t, spk)}?</div>
    <form onSubmit=${(e) => { e.preventDefault(); if (matches.length && ql && !exact && matches.length === 1) linkTo({ person: matches[0].id }); else if (ql) linkTo(exact ? { person: people.find((p) => p.name.toLowerCase() === ql).id } : { name: q.trim() }); }}>
      <input ref=${input} type="text" placeholder="Type a name…" value=${q} onInput=${(e) => setQ(e.target.value)} />
    </form>
    ${matches.map((p) => html`<div class="mi" onClick=${() => linkTo({ person: p.id })}>
      <span class="avatar">${initials(p.name)}</span>${p.name}${p.id === info.person ? html`<span class="spacer"></span>✓` : null}</div>`)}
    ${ql && !exact ? html`<div class="mi" onClick=${() => linkTo({ name: q.trim() })}><span class="avatar new">+</span>New person “${q.trim()}”</div>` : null}
    ${info.person ? html`<div class="mi muted" onClick=${() => { act("unlink", spk, {}); close(); }}>Not ${info.name}</div>` : null}
    ${para ? html`<hr />
      <div class="lbl">Only this paragraph was said by</div>
      ${ids.filter((s) => s !== para.speaker).map((s) => html`<div class="mi" onClick=${() => { act("para", para, s); close(); }}>
        <i class="dot" style=${"background:" + color(s)}></i>${spkName(t, s)}</div>`)}
      <div class="mi" onClick=${() => { act("para", para, next); close(); }}>
        <i class="dot" style=${"background:" + color(next)}></i>Another speaker</div>` : null}
  </div>`;
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

// Just enough Markdown for state.md: headings, bullets, bold, paragraphs.
function md(text) {
  const out = [];
  let list = false;
  for (const raw of (text || "").split("\n")) {
    const line = esc(raw).replace(/\*\*(.+?)\*\*/g, "<b>$1</b>");
    const li = line.match(/^\s*[-*] (.*)/);
    if (li) { if (!list) { out.push("<ul>"); list = true; } out.push(`<li>${li[1]}</li>`); continue; }
    if (list) { out.push("</ul>"); list = false; }
    const h = line.match(/^(#{1,4}) (.*)/);
    if (h) out.push(`<h4>${h[2]}</h4>`);
    else if (line.trim()) out.push(`<p>${line}</p>`);
  }
  if (list) out.push("</ul>");
  return out.join("");
}

const secs = (mmss) => { const p = String(mmss || "").split(":").map(Number); return p.length === 2 && !p.some(isNaN) ? p[0] * 60 + p[1] : null; };

function Summary({ m, job, summarize, jump }) {
  const s = m.summary;
  const running = job && job.kind === "summarize";
  if (!s) return html`<div class="empty">${running ? `Writing the summary… ${Math.round((job.progress || 0) * 100)}%`
    : html`No summary yet. <button class="btn primary" onClick=${summarize}>Summarize</button>`}</div>`;
  const done = (m.todos || []).filter((x) => x.closedBy?.meeting === m.id);
  const list = (items, f) => items.length ? html`<ul>${items.map(f)}</ul>` : null;
  return html`<div class="summary">
    <p class="overview">${s.overview}</p>
    ${s.topics.length ? html`<h4>Topics</h4>${list(s.topics, (x) => html`<li>
      ${secs(x.start) != null ? html`<span class="ts" style="display:inline" onClick=${() => jump(secs(x.start))}>${x.start}</span> ` : null}
      <b>${x.title}</b> — ${x.summary}</li>`)}` : null}
    ${s.decisions.length ? html`<h4>Decisions</h4>${list(s.decisions, (x) => html`<li>${x}</li>`)}` : null}
    ${s.action_items.length ? html`<h4>Action items</h4>${list(s.action_items, (x) => html`<li>${x.task}${x.owner ? html` <span class="owner">${x.owner}</span>` : null}${x.due ? html` <small>due ${x.due}</small>` : null}</li>`)}` : null}
    ${done.length ? html`<h4>Done since earlier meetings</h4>${list(done, (x) => html`<li><s>${x.text}</s>${x.closedBy.note ? html` <small>${x.closedBy.note}</small>` : null}</li>`)}` : null}
    ${s.progress.length ? html`<h4>Progress</h4>${list(s.progress, (x) => html`<li><b>${x.area}</b>: ${x.update}</li>`)}` : null}
    ${s.open_questions.length ? html`<h4>Open questions</h4>${list(s.open_questions, (x) => html`<li>${x}</li>`)}` : null}
    <div class="row hint" style="margin-top:18px">Written on this Mac by ${"Qwen3 8B"} using the project's knowledge, state, to-dos and earlier meetings.
      <span class="spacer"></span><button class="btn" disabled=${!!job} onClick=${summarize}>${running ? "Summarizing…" : "Summarize again"}</button></div>
  </div>`;
}

const SOURCE = { glossary: "Glossary", audio: "Second listen", text: "AI check" };

function Fixes({ items, decide, goto }) {
  const [open, setOpen] = useState(true);
  return html`<div class="card fixes">
    <div class="row"><b>${items.length} suggested fix${items.length === 1 ? "" : "es"}</b>
      <span class="spacer"></span>
      <button class="btn" onClick=${() => setOpen(!open)}>${open ? "Hide" : "Show"}</button>
      <button class="btn" onClick=${() => decide(items.map((x) => x.id), false)}>Dismiss all</button>
      <button class="btn primary" onClick=${() => decide(items.map((x) => x.id), true)}>Accept all</button></div>
    ${open ? html`<div class="fixlist">${items.map((c) => html`<div class="fixrow">
      <span class="fixtext" onClick=${() => goto(c.first)}><del>${c.old}</del> → <ins>${c.new}</ins>
        <small>${SOURCE[c.source]}${c.why ? " · " + c.why : ""}</small></span>
      <button class="btn" title="Reject" onClick=${() => decide([c.id], false)}>✕</button>
      <button class="btn" title="Accept" onClick=${() => decide([c.id], true)}>✓</button></div>`)}</div>` : null}
  </div>`;
}

function SpeakerBar({ t, open, act }) {
  const secs = {};
  for (const w of t.words) if (w.speaker != null) secs[w.speaker] = (secs[w.speaker] || 0) + (w.end - w.start);
  const ids = Object.keys(t.speakers).map(Number).filter((k) => secs[k]).sort((a, b) => secs[b] - secs[a]);
  return html`<div class="spkbar">${ids.map((k) => {
    const s = t.speakers[k];
    return html`<div class="spk">
      <span class="chip" onClick=${(e) => { e.stopPropagation(); open(e, k); }}><i class="dot" style=${"background:" + color(k)}></i>${spkName(t, k)}</span>
      <small>${clock(secs[k])}${s.person && s.auto ? " · matched by voice" : ""}</small>
      ${!s.person && s.suggested ? html`<span class="sug">${s.suggested.name}?
        <button title="Yes" onClick=${() => act("link", k, { person: s.suggested.person })}>✓</button>
        <button title="No" onClick=${() => act("unlink", k, { reject: true })}>✕</button></span>` : null}
      ${s.person && s.auto ? html`<span class="sug"><button title="Confirm" onClick=${() => act("link", k, { person: s.person })}>✓</button></span>` : null}
    </div>`;
  })}</div>`;
}

// ---------- people & search

const initials = (n) => n.split(/\s+/).filter(Boolean).slice(0, 2).map((x) => x[0].toUpperCase()).join("");

function People() {
  const [list, setList] = useState(null);
  const [name, setName] = useState("");
  const load = () => api("/people").then(setList);
  useEffect(() => { load(); }, []);
  const add = async (e) => {
    e.preventDefault();
    if (!name.trim()) return;
    const p = await api("/people", { json: { name } });
    setName(""); location.hash = "#/people/" + p.id;
  };
  return html`<div class="page">
    <h1>People</h1>
    <div class="sub">Shared across projects. Name a speaker in any recording to add them; their voice is recognised next time.</div>
    <form class="row" style="margin-bottom:16px" onSubmit=${add}>
      <input type="text" placeholder="Add someone by name" value=${name} onInput=${(e) => setName(e.target.value)} />
      <button class="btn">Add</button></form>
    ${list && !list.length ? html`<div class="empty">No people yet.</div>` : html`<div class="mlist">${(list || []).map((p) => html`
      <a class="mrow" href=${"#/people/" + p.id}><span class="t"><span class="avatar">${initials(p.name)}</span> ${p.name}${p.me ? " (me)" : ""}</span>
        <span class="m">${p.voiceCount ? `${p.voiceCount} voice sample${p.voiceCount > 1 ? "s" : ""}` : "no voice yet"}</span>
        <span class="m">${p.projects.length} project${p.projects.length === 1 ? "" : "s"}${p.notes ? " · " + p.notes.slice(0, 80) : ""}</span></a>`)}</div>`}
  </div>`;
}

function Person({ id, projects }) {
  const [p, setP] = useState(null);
  const [notes, setNotes] = useState("");
  const [aliases, setAliases] = useState("");
  const load = () => api("/people/" + id).then((x) => { setP(x); setNotes(x.notes); setAliases(x.aliases.join(", ")); });
  useEffect(() => { load(); }, [id]);
  if (!p) return html`<div class="page"></div>`;
  const save = async (body) => { await api("/people/" + id, { method: "PUT", json: body }); load(); };
  const toggleProject = (pid) => save({ projects: p.projects.includes(pid) ? p.projects.filter((x) => x !== pid) : [...p.projects, pid] });
  const remove = async () => {
    if (!confirm(`Remove ${p.name}? Recordings keep their text; the speaker just becomes unnamed.`)) return;
    await api("/people/" + id, { method: "DELETE" }); location.hash = "#/people";
  };
  const dirty = notes !== p.notes || aliases !== p.aliases.join(", ");
  return html`<div class="page">
    <div class="crumb"><a href="#/people">People</a></div>
    <h1><input value=${p.name} onChange=${(e) => save({ name: e.target.value })} /></h1>
    <div class="sub">${p.voiceCount ? `${p.voiceCount} voice sample${p.voiceCount > 1 ? "s" : ""}` : "No voice sample yet: name them in a recording"}
      · <label style="cursor:pointer"><input type="checkbox" checked=${!!p.me} onChange=${(e) => save({ me: e.target.checked })} /> This is me</label></div>
    <div class="knowledge">
      <label>Projects</label>
      <div class="row">${(projects || []).map((pr) => html`<button class=${"pill" + (p.projects.includes(pr.id) ? " on" : "")} onClick=${() => toggleProject(pr.id)}>${pr.name}</button>`)}</div>
      <label>Also known as</label>
      <input type="text" style="width:100%" placeholder="Nicknames, how others call them (comma separated)" value=${aliases} onInput=${(e) => setAliases(e.target.value)} />
      <label>Notes</label>
      <p>Role, responsibilities, what they work on. The summary AI reads this.</p>
      <textarea rows="5" value=${notes} onInput=${(e) => setNotes(e.target.value)}></textarea>
      <div class="row" style="margin-top:10px">
        <button class="btn primary" disabled=${!dirty} onClick=${() => save({ notes, aliases: aliases.split(",").map((x) => x.trim()).filter(Boolean) })}>Save</button>
        <span class="spacer"></span><button class="btn danger" onClick=${remove}>Remove person</button></div>
    </div>
    <h3 style="margin-top:28px">Spoke in</h3>
    ${p.appearances.length ? html`<div class="mlist">${p.appearances.map((a) => html`
      <a class="mrow" href=${`#/p/${encodeURIComponent(a.project)}/m/${encodeURIComponent(a.meeting)}`}>
        <span class="t">${a.title}</span><span class="m">${clock(a.seconds)} spoken</span>
        <span class="m">${a.projectName} · ${when(a.date)}</span></a>`)}</div>` : html`<div class="empty">Not in any recording yet.</div>`}
    ${p.appearances.length ? html`<p><a class="btn" href=${"#/search/" + encodeURIComponent("@" + id)}>Everything ${p.name} said →</a></p>` : null}
  </div>`;
}

function Search({ q }) {
  const [r, setR] = useState(null);
  const byPerson = q.startsWith("@") ? q.slice(1) : null;
  const [who, setWho] = useState(null);
  useEffect(() => {
    setR(null);
    const params = byPerson ? `person=${encodeURIComponent(byPerson)}` : `q=${encodeURIComponent(q)}`;
    api("/search?" + params).then(setR).catch((e) => toast(e.message));
    if (byPerson) api("/people/" + byPerson).then(setWho).catch(() => {});
  }, [q]);
  const mark = (text) => {
    if (byPerson || !q) return text;
    const i = text.toLowerCase().indexOf(q.toLowerCase());
    return i < 0 ? text : html`${text.slice(0, i)}<mark>${text.slice(i, i + q.length)}</mark>${text.slice(i + q.length)}`;
  };
  return html`<div class="page">
    <h1>${byPerson ? `Said by ${who?.name || "…"}` : `“${q}”`}</h1>
    <div class="sub">${r ? `${r.total} passage${r.total === 1 ? "" : "s"}` : "Searching…"}</div>
    ${r?.people?.length ? html`<div class="row" style="margin-bottom:16px">${r.people.map((p) => html`
      <a class="pill on" href=${"#/people/" + p.id}><span class="avatar">${initials(p.name)}</span> ${p.name}</a>
      <a class="pill" href=${"#/search/" + encodeURIComponent("@" + p.id)}>What ${p.name} said</a>`)}</div>` : null}
    <div class="mlist">${(r?.hits || []).map((h) => html`
      <a class="mrow hit" href=${`#/p/${encodeURIComponent(h.project)}/m/${encodeURIComponent(h.meeting)}?t=${h.start}`}>
        <span class="t">${h.title} <small style="font-weight:400;color:var(--muted)">· ${h.projectName} · ${when(h.date)}</small></span>
        <span class="m">${clock(h.start)}</span>
        <span class="snip"><b>${h.speaker}:</b> ${mark(h.snippet)}</span></a>`)}</div>
  </div>`;
}

render(html`<${App} />`, document.getElementById("app"));
