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
const STEPS = { recording: "Recording", queued: "Queued", decoding: "Reading audio", transcribing: "Transcribing", speakers: "Finding speakers" };

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
  </nav>`;
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
  const [tab, setTab] = useState("meetings");
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
      <button class=${tab === "meetings" ? "on" : ""} onClick=${() => setTab("meetings")}>Recordings</button>
      <button class=${tab === "knowledge" ? "on" : ""} onClick=${() => setTab("knowledge")}>Knowledge & glossary</button>
    </div>
    ${tab === "meetings" ? html`<${Meetings} p=${p} load=${load} />` : html`<${Knowledge} p=${p} load=${load} />`}
  </div>`;
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

  if (!m) return html`<div class="page"></div>`;
  if (m.status === "recording") return html`<div class="page">
    <div class="crumb"><a href=${"#/p/" + encodeURIComponent(pid)}>${m.projectName}</a></div>
    <h1>${m.title}</h1>
    <${Live} m=${m} onDone=${load} />
  </div>`;

  const seek = (s) => { const a = audio.current; if (a) { a.currentTime = Math.max(0, s - 0.15); a.play(); } };
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
  const reprocess = async (kind) => {
    if (kind === "process" && t?.edited && !confirm("Transcribe again? Your text edits in this recording will be replaced.")) return;
    await api(base + "/reprocess", { json: { kind, speakers: Number(count) } });
    toast(kind === "process" ? "Transcribing again…" : "Finding speakers again…"); load();
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
        ${t ? html`
          <select value=${count} onChange=${(e) => setCount(e.target.value)} title="How many people spoke">
            <option value="-1">Speakers: auto</option>
            ${[1, 2, 3, 4, 5, 6, 7, 8].map((n) => html`<option value=${n}>${n} speaker${n > 1 ? "s" : ""}</option>`)}
          </select>
          <button class="btn" disabled=${!!m.job} onClick=${() => reprocess("speakers")}>Find speakers again</button>
          <select onChange=${(e) => { if (e.target.value) location.href = "/api" + base + "/export." + e.target.value; e.target.value = ""; }}>
            <option value="">Export…</option><option value="md">Markdown</option><option value="txt">Text</option><option value="srt">Subtitles (SRT)</option>
          </select>` : null}
        <button class="btn" disabled=${!!m.job} onClick=${() => reprocess("process")}>Transcribe again</button>
        <button class="btn danger" onClick=${remove}>Delete</button>
      </div>
    </div>

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
                return html`<span data-i=${i} class=${w.edited ? "ed" : w.conf < 0.5 ? "low" : ""} onClick=${() => seek(w.start)}>${w.text}</span>${" "}`;
              })}</div>`}
          ${editing === pi ? null : html`<button class="edit" title="Edit text" onClick=${() => setEditing(pi)}>✎</button>`}
        </div>`)}
      </div>`}

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
