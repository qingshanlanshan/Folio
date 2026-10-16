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
const STEPS = { queued: "Queued", decoding: "Reading audio", transcribing: "Transcribing", speakers: "Finding speakers" };

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
  return hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
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
    <${Sidebar} projects=${projects} pid=${pid} reload=${reload} />
    <main>
      ${mid ? html`<${Meeting} key=${pid + "/" + mid} pid=${pid} mid=${mid} />`
        : pid ? html`<${Project} key=${pid} pid=${pid} reloadProjects=${reload} />`
        : html`<${Home} projects=${projects} />`}
    </main>
  </div>`;
}

function Sidebar({ projects, pid, reload }) {
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

function Meeting({ pid, mid }) {
  const [m, setM] = useState(null);
  const [menu, setMenu] = useState(null);     // {x, y, para}
  const [editing, setEditing] = useState(null); // paragraph index
  const [count, setCount] = useState("-1");
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

  const seek = (s) => { const a = audio.current; if (a) { a.currentTime = Math.max(0, s - 0.15); a.play(); } };
  const edit = async (body) => {
    try { setM(await api(base + "/transcript", { json: body })); } catch (e) { toast(e.message); }
  };
  const rename = async (title) => { if (title.trim() && title !== m.title) setM(await api(base, { method: "PATCH", json: { title } })); };
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

    ${!t ? html`<div class="empty">${m.job || m.status !== "error" ? "The transcript will appear here when processing finishes." : "Processing failed."}</div>` : html`
      <div ref=${textRef}>
        ${m.paragraphs.map((p, pi) => html`<div class="para" key=${p.first}>
          <div class="who">
            <span class="chip" onClick=${(e) => { e.stopPropagation(); setMenu({ x: Math.min(e.clientX, innerWidth - 240), y: e.clientY, para: p }); }}>
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

    ${menu ? html`<${SpeakerMenu} menu=${menu} t=${t} edit=${edit} close=${() => setMenu(null)} />` : null}
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

function SpeakerMenu({ menu, t, edit, close }) {
  const { para } = menu;
  const [name, setName] = useState(t.speakers?.[para.speaker]?.name || "");
  const input = useRef();
  useEffect(() => { input.current?.focus(); input.current?.select(); }, []);
  const ids = Object.keys(t.speakers).map(Number);
  const next = Math.max(-1, ...ids) + 1;
  const stop = (e) => e.stopPropagation();
  return html`<div class="menu" style=${`left:${menu.x}px;top:${menu.y + 8}px`} onClick=${stop}>
    <div class="lbl">Name ${spkName(t, para.speaker)} everywhere</div>
    <form onSubmit=${(e) => { e.preventDefault(); edit({ op: "rename", speaker: para.speaker, name }); close(); }}>
      <input ref=${input} type="text" placeholder="e.g. Alex Tan" value=${name} onInput=${(e) => setName(e.target.value)} />
    </form>
    <hr />
    <div class="lbl">This paragraph was said by</div>
    ${ids.filter((s) => s !== para.speaker).map((s) => html`<div class="mi" onClick=${() => { edit({ op: "speaker", first: para.first, last: para.last, speaker: s }); close(); }}>
      <i class="dot" style=${"background:" + color(s)}></i>${spkName(t, s)}</div>`)}
    <div class="mi" onClick=${() => { edit({ op: "speaker", first: para.first, last: para.last, speaker: next }); close(); }}>
      <i class="dot" style=${"background:" + color(next)}></i>New speaker</div>
  </div>`;
}

render(html`<${App} />`, document.getElementById("app"));
