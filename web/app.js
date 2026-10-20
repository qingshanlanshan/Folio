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
const STEPS = { "updating state": "Updating", stopped: "Stopped", "listening again": "Listening again", "checking text": "Checking text", summarizing: "Summarizing", recording: "Recording", queued: "Queued", decoding: "Reading audio", transcribing: "Transcribing", speakers: "Finding speakers" };

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
  const [st] = useStatus();
  useEffect(() => { if (st?.syncRev) reload(); }, [st?.syncRev]);   // sync brought changes from another device
  useChanged(reload);
  const mid = route[2] === "m" ? route[3] : null;

  const home = !route[0] && !route.query.get("project");
  return html`<div class="layout">
    <${Sidebar} projects=${projects} pid=${pid} route=${route} reload=${reload} />
    <main>
      ${home ? null : html`<div class="backbar"><button class="back" onClick=${goBack}>← Back</button></div>`}
      ${mid ? html`<${Meeting} key=${pid + "/" + mid} pid=${pid} mid=${mid} at=${Number(route.query.get("t")) || null} projects=${projects} reloadProjects=${reload} />`
        : pid ? html`<${Project} key=${pid} pid=${pid} reloadProjects=${reload} />`
        : route[0] === "people" && route[1] ? html`<${Person} key=${route[1]} id=${route[1]} projects=${projects} />`
        : route[0] === "people" ? html`<${People} />`
        : route[0] === "search" ? html`<${Search} key=${route[1]} q=${route[1] || ""} />`
        : route[0] === "activity" ? html`<${Activity} />`
        : route[0] === "settings" ? html`<${Settings} />`
        : html`<${RecordPage} projects=${projects} preset=${route.query.get("project")} reloadProjects=${reload} />`}
    </main>
    <${ContextMenu} />
  </div>`;
}

// ---------- right-click menus

// openMenu(event, items): items are { label, run, danger } or null for a divider, or { heading }.
let showMenu = null;
function openMenu(e, items) { e.preventDefault(); e.stopPropagation(); showMenu && showMenu({ x: e.clientX, y: e.clientY, items }); }

// Something changed through a menu: pages listening re-read their data.
function changed() { dispatchEvent(new Event("folio-changed")); }
function useChanged(f) { useEffect(() => { addEventListener("folio-changed", f); return () => removeEventListener("folio-changed", f); }, [f]); }

function ContextMenu() {
  const [m, setM] = useState(null);
  showMenu = setM;
  useEffect(() => {
    if (!m) return;
    const close = () => setM(null);
    const key = (e) => { if (e.key === "Escape") close(); };
    setTimeout(() => { addEventListener("click", close); addEventListener("contextmenu", close); }, 0);
    addEventListener("keydown", key); addEventListener("blur", close); addEventListener("scroll", close, true);
    return () => { removeEventListener("click", close); removeEventListener("contextmenu", close); removeEventListener("keydown", key); removeEventListener("blur", close); removeEventListener("scroll", close, true); };
  }, [m]);
  if (!m) return null;
  const h = m.items.length * 30 + 12;
  return html`<div class="ctxmenu" style=${`left:${Math.min(m.x, innerWidth - 230)}px;top:${Math.max(8, Math.min(m.y, innerHeight - h - 8))}px`}>
    ${m.items.map((it) => !it ? html`<hr />` : it.heading ? html`<div class="ctxhead">${it.heading}</div>`
      : html`<button class=${it.danger ? "danger" : ""} onClick=${(e) => { e.stopPropagation(); setM(null); it.run(); }}>${it.label}</button>`)}
  </div>`;
}

const mbase = (pid, mid) => `/projects/${encodeURIComponent(pid)}/meetings/${encodeURIComponent(mid)}`;

function recordingMenu(pid, m, projects) {
  const others = (projects || []).filter((x) => x.id !== pid);
  return [
    { label: "Open", run: () => { location.hash = `#/p/${encodeURIComponent(pid)}/m/${encodeURIComponent(m.id)}`; } },
    { label: "Rename…", run: async () => {
      const t = prompt("Rename the recording", m.title);
      if (t && t.trim() && t !== m.title) { try { await api(mbase(pid, m.id), { method: "PATCH", json: { title: t.trim() } }); changed(); } catch (e) { toast(e.message); } }
    } },
    others.length ? { heading: pid === "_unsorted" ? "Move to project" : "Move to" } : false,
    ...others.map((x) => ({ label: "    " + (x.builtin ? "Unsorted" : x.name), run: async () => {
      try { await api(mbase(pid, m.id) + "/move", { json: { to: x.id } }); toast(x.builtin ? "Moved to Unsorted" : `Moved to ${x.name} — summarizing it with that project's context`); changed(); }
      catch (e) { toast(e.message); }
    } })),
    null,
    { label: "Delete…", danger: true, run: async () => {
      if (!confirm(`Delete “${m.title}”? It moves to the macOS Trash.`)) return;
      try { await api(mbase(pid, m.id), { method: "DELETE" }); changed(); if (location.hash.includes("/m/" + encodeURIComponent(m.id))) location.hash = "#/p/" + encodeURIComponent(pid); } catch (e) { toast(e.message); }
    } },
  ].filter((x) => x !== false);
}

function projectMenu(p) {
  const items = [{ label: "Open", run: () => { location.hash = "#/p/" + encodeURIComponent(p.id); } },
    { label: "New recording here", run: () => { location.hash = "#/?project=" + encodeURIComponent(p.id); } }];
  if (p.builtin) return items;
  return [...items,
    { label: "Rename…", run: async () => {
      const n = prompt("Rename the project", p.name);
      if (n && n.trim() && n !== p.name) { try { await api(`/projects/${encodeURIComponent(p.id)}`, { method: "PUT", json: { name: n.trim() } }); changed(); } catch (e) { toast(e.message); } }
    } },
    null,
    { label: "Delete…", danger: true, run: async () => {
      const n = p.meetings || 0;
      if (!confirm(`Delete the project “${p.name}”${n ? ` and its ${n} recording${n === 1 ? "" : "s"}` : ""}? Everything moves to the macOS Trash.`)) return;
      try { await api(`/projects/${encodeURIComponent(p.id)}`, { method: "DELETE" }); changed(); if (location.hash.startsWith("#/p/" + encodeURIComponent(p.id))) location.hash = "#/"; } catch (e) { toast(e.message); }
    } }];
}

function personMenu(p) {
  return [{ label: "Open", run: () => { location.hash = "#/people/" + p.id; } },
    { label: "Everything they said", run: () => { location.hash = "#/search/" + encodeURIComponent("@" + p.id); } },
    { label: "Rename…", run: async () => {
      const n = prompt("Rename", p.name);
      if (n && n.trim() && n !== p.name) { try { await api("/people/" + p.id, { method: "PUT", json: { name: n.trim() } }); changed(); } catch (e) { toast(e.message); } }
    } },
    null,
    { label: "Remove…", danger: true, run: async () => {
      if (!confirm(`Remove ${p.name}? Recordings keep their text; the speaker just becomes unnamed.`)) return;
      try { await api("/people/" + p.id, { method: "DELETE" }); changed(); if (location.hash === "#/people/" + p.id) location.hash = "#/people"; } catch (e) { toast(e.message); }
    } }];
}

// Back goes to the previous page; opened directly, it goes to the recorder.
const startLength = history.length;
function goBack() {
  if (history.length > startLength || document.referrer.startsWith(location.origin)) history.back();
  else location.hash = "#/";
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
  const builtin = (projects || []).find((p) => p.builtin);
  return html`<nav class="side">
    <a class="brand" href="#/"><img src="icon.svg" alt="" />Folio</a>
    <form onSubmit=${(e) => { e.preventDefault(); if (q.trim()) location.hash = "#/search/" + encodeURIComponent(q.trim()); }}>
      <input type="search" placeholder="Search words or names" value=${q} onInput=${(e) => setQ(e.target.value)} />
    </form>
    <div class="navgroup">
      <a class=${"nav" + (!route[0] ? " on" : "")} href="#/"><${Icon} name="record" />Record</a>
      <a class=${"nav" + (route[0] === "people" ? " on" : "")} href="#/people"><${Icon} name="people" />People</a>
    </div>
    <div class="sidelist">
      <h4>Projects</h4>
      ${builtin ? html`<a class=${"plink unsorted" + (builtin.id === pid ? " on" : "")} href=${"#/p/" + encodeURIComponent(builtin.id)} onContextMenu=${(e) => openMenu(e, projectMenu(builtin))}>
        <span>Unsorted</span><small>${builtin.meetings || ""}</small></a>` : null}
      ${(projects || []).filter((p) => !p.builtin).map((p) => html`<a class=${"plink" + (p.id === pid ? " on" : "")} href=${"#/p/" + encodeURIComponent(p.id)} onContextMenu=${(e) => openMenu(e, projectMenu(p))}>
        <span>${p.name}</span><small>${p.meetings || ""}</small></a>`)}
      ${adding
        ? html`<form onSubmit=${create}><${FocusInput} placeholder="Project or course name" value=${name}
            onInput=${(e) => setName(e.target.value)} onBlur=${create} /></form>`
        : html`<div class="plink addproj" onClick=${() => setAdding(true)}>+ New project</div>`}
    </div>
    <div class="sidefoot">
      <${UpdateLink} />
      <a class=${"nav" + (route[0] === "settings" ? " on" : "")} href="#/settings"><${Icon} name="gear" />Settings</a>
      <${StatusBar} />
    </div>
  </nav>`;
}

// Small line icons for the sidebar's fixed places, so they read differently from projects.
const ICONS = {
  record: () => html`<circle cx="12" cy="12" r="5" fill="#d23b2e" stroke="none" /><circle cx="12" cy="12" r="9" />`,
  people: () => html`<circle cx="9" cy="8" r="3.5" /><path d="M2.5 20c.5-4 3.2-6 6.5-6s6 2 6.5 6" /><circle cx="17" cy="9" r="2.5" /><path d="M16.5 14c2.8.2 4.6 2 5 5" />`,
  gear: () => html`<circle cx="12" cy="12" r="3" /><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" />`,
};
function Icon({ name }) {
  return html`<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]()}</svg>`;
}

// ---------- app status (one poller shared by the sidebar and the Activity page)

// Each tab is a window of the app. It reports in while open, and says goodbye when closed, so
// Folio knows when no window is open (and can notify you that it's still recording).
const WINDOW = (crypto.randomUUID && crypto.randomUUID()) || String(Math.random()).slice(2);
addEventListener("pagehide", () => {
  try { navigator.sendBeacon("/api/window", new Blob([JSON.stringify({ id: WINDOW, closing: true })], { type: "application/json" })); } catch {}
});

const statusListeners = new Set();
let lastStatus = null, statusTimer = null, stopped = false, seenVersion = null;
async function pollStatus() {
  clearTimeout(statusTimer);
  if (stopped) return;
  try {
    lastStatus = await api(`/status?window=${WINDOW}`);
    // Folio restarted as a new version (after an update): load the new pages.
    const v = lastStatus.app?.version;
    if (seenVersion && v && v !== seenVersion) return location.reload();
    seenVersion = v;
    statusListeners.forEach((f) => f(lastStatus));
  } catch {}
  const busy = lastStatus && (lastStatus.recording || lastStatus.tasks.length || lastStatus.app?.installing);
  statusTimer = setTimeout(pollStatus, document.hidden ? 8000 : busy ? 1500 : 4000);
}
document.addEventListener("visibilitychange", () => { if (!document.hidden) pollStatus(); });
function useStatus() {
  const [st, setSt] = useState(lastStatus);
  useEffect(() => {
    statusListeners.add(setSt);
    if (!statusTimer) pollStatus();
    return () => statusListeners.delete(setSt);
  }, []);
  return [st, pollStatus];
}

const gb = (x) => (x >= 0.1 ? `${x.toFixed(1)} GB` : `${Math.round(x * 1000)} MB`);

async function installUpdate(version) {
  if (!confirm(`Update Folio to ${version}? Folio restarts by itself; this takes a minute.`)) return;
  try { await api("/update/install", { json: {} }); toast("Updating… Folio will restart"); }
  catch (e) { toast(e.message); }
  pollStatus();
}

function UpdateLink() {
  const [st] = useStatus();
  const a = st?.app;
  if (!a || !(a.available || a.installing)) return null;
  if (a.installing) return html`<div class="updlink">${a.step || "Updating"}…</div>`;
  return html`<button class="updlink" onClick=${() => installUpdate(a.available.version)}>Update to ${a.available.version}</button>`;
}

function StatusBar() {
  const [st] = useStatus();
  if (!st) return html`<div class="statusbar"></div>`;
  const task = st.tasks.find((t) => t.running);
  const more = st.tasks.length - (task ? 1 : 0);
  return html`<a class="statusbar" href="#/activity" title="Activity: tasks, memory, quit">
    ${st.recording ? html`<div class="sline rec"><i class="recdot pulse"></i>Recording ${clock(st.recording.elapsed)}</div>` : null}
    ${task ? html`<div class="sline">${task.stopping ? "Stopping…" : `${STEPS[task.step] || task.label} ${task.progress ? Math.round(task.progress * 100) + "%" : ""}`}
      ${more ? html`<small> +${more} waiting</small>` : null}</div>` : null}
    ${!st.recording && !task ? html`<div class="sline idle">Idle${st.models.length ? ` · ${gb(st.memory.modelsGB)} in memory` : ""}</div>` : null}
    <div class="sline small">Activity & quit ›</div>
  </a>`;
}

function Activity() {
  const [st, refresh] = useStatus();
  const [busy, setBusy] = useState("");
  const call = async (label, path, body) => {
    setBusy(label);
    try { await api(path, { json: body }); } catch (e) { toast(e.message); }
    setBusy(""); refresh();
  };
  const quit = async (force) => {
    const rec = st?.recording, n = st?.tasks.length || 0;
    const msg = force
      ? "Force quit Folio now? Everything stops immediately. Audio already recorded is recovered next time; tasks in progress need starting again."
      : rec ? `Quit Folio? “${rec.title}” is being recorded: it will be stopped, saved, and transcribed next time you open Folio.`
      : n ? `Quit Folio? ${n} task${n > 1 ? "s are" : " is"} still running or waiting. Transcriptions continue next time; fixes and summaries need starting again.`
      : "Quit Folio? It stops running until you open it again.";
    if (!confirm(msg)) return;
    stopped = true;
    try { await api("/quit", { json: { force } }); } catch {}
    document.getElementById("app").innerHTML = `<div class="stopped"><img src="icon.svg" alt="">
      <h1>Folio has quit</h1><p>To start again, open Folio.</p></div>`;
  };
  if (!st) return html`<div class="page"></div>`;
  const s = st.settings;
  const meetingLink = (pid, mid) => `#/p/${encodeURIComponent(pid)}/m/${encodeURIComponent(mid)}`;
  return html`<div class="page activity">
    <h1>Activity</h1>
    <div class="sub">Closing this tab is like closing a window: recording and processing keep going. Quit stops Folio.</div>

    <h3>Recording</h3>
    ${st.recording ? html`<div class="card row">
        <i class="recdot pulse"></i><b>${st.recording.title}</b><span class="elapsed">${clock(st.recording.elapsed)}</span>
        <span class="spacer"></span>
        <a class="btn" href=${meetingLink(st.recording.pid, st.recording.mid)}>Open</a>
        <button class="btn rec" disabled=${st.recording.stopping || !!busy} onClick=${() => call("rec", "/live/stop", {})}>■ Stop recording</button>
      </div>` : html`<div class="hint">Not recording.</div>`}

    <h3>Tasks</h3>
    ${st.tasks.length ? html`<div class="mlist">${st.tasks.map((t) => html`<div class="mrow task">
        <a class="t" href=${meetingLink(t.project, t.meeting)}>${t.title}</a>
        <span class="row">
          <span class="badge">${t.stopping ? "Stopping…" : t.running ? `${STEPS[t.step] || t.label}${t.progress ? " " + Math.round(t.progress * 100) + "%" : ""}` : "Waiting"}</span>
          <button class="btn" disabled=${t.stopping} onClick=${() => call(t.key, "/tasks/stop", { key: t.key })}>${t.running ? "Stop" : "Remove"}</button>
        </span>
        <span class="m">${t.label} · ${t.projectName}</span>
        ${t.running ? html`<div class="bar"><i style=${`width:${Math.round((t.progress || 0) * 100)}%`}></i></div>` : null}
      </div>`)}</div>
      ${st.tasks.length > 1 ? html`<div class="row" style="margin-top:8px"><button class="btn" onClick=${() => call("all", "/tasks/stop", {})}>Stop all tasks</button></div>` : null}
      <div class="hint">Stop takes effect at the next checkpoint (usually within seconds; a “listen again” pass may need up to a minute). Stopped transcriptions can be started again from the recording.</div>`
      : html`<div class="hint">Nothing running.</div>`}

    <h3>Memory</h3>
    <div class="card">
      ${st.models.length ? st.models.map((m) => html`<div class="row modelrow">
          <span><b>${m.name}</b> <small>${m.purpose}</small></span><span class="spacer"></span>
          <span class="m">${gb(m.gb)}</span>
          <button class="btn" disabled=${!!busy} onClick=${() => call(m.id, "/models/unload", { id: m.id })}>Unload</button></div>`)
        : html`<div class="hint" style="margin:0">No models loaded. They load when needed (a few seconds) and use several GB while loaded.</div>`}
      <div class="row" style="margin-top:10px">
        <span class="m">Folio is using about ${gb(st.memory.totalGB)} in total.</span><span class="spacer"></span>
        ${st.models.length > 1 ? html`<button class="btn" disabled=${!!busy} onClick=${() => call("all", "/models/unload", {})}>Unload all</button>` : null}
      </div>
      <label class="row" style="margin-top:10px">Unload models after
        <select value=${String(s.autoUnloadMinutes)} onChange=${async (e) => { await api("/settings", { method: "PUT", json: { autoUnloadMinutes: Number(e.target.value) } }); refresh(); }}>
          ${[[5, "5 minutes"], [10, "10 minutes"], [30, "30 minutes"], [60, "1 hour"], [0, "never"]].map(([v, l]) => html`<option value=${String(v)}>${l}</option>`)}
        </select> idle</label>
    </div>

    <h3>Folio</h3>
    <div class="card">
      <label class="row"><input type="checkbox" checked=${s.notifications}
        onChange=${async (e) => { await api("/settings", { method: "PUT", json: { notifications: e.target.checked } }); refresh(); }} />
        Notify me when no Folio window is open (still recording, transcript ready)</label>
      <div class="row" style="margin-top:14px">
        <button class="btn primary" onClick=${() => quit(false)}>Quit Folio</button>
        <button class="btn danger" onClick=${() => quit(true)}>Force quit</button>
        <span class="hint" style="margin:0">${st.windows > 1 ? `${st.windows} Folio windows are open.` : ""}</span>
      </div>
      <div class="hint">Quit stops and saves a recording and stops tasks cleanly (unfinished transcriptions resume next time). Force quit ends everything immediately.</div>
    </div>
  </div>`;
}

const ago = (t) => {
  const s = Date.now() / 1000 - t;
  return s < 60 ? "just now" : s < 3600 ? `${Math.round(s / 60)} min ago` : s < 86400 ? `${Math.round(s / 3600)} h ago` : new Date(t * 1000).toLocaleDateString();
};

function QR({ text }) {
  const q = window.qrcode(0, "M");
  q.addData(text);
  q.make();
  return html`<div class="qr" dangerouslySetInnerHTML=${{ __html: q.createSvgTag({ cellSize: 4, margin: 2, scalable: true }) }} />`;
}

// Sync between this Mac and the phone through the user's own Cloudflare R2 bucket. Off by default.
function SyncCard() {
  const [s, setS] = useState(null);
  const [mode, setMode] = useState(null);          // "setup" | "join" | null
  const [f, setF] = useState({ account: "", bucket: "folio-sync", keyId: "", secret: "", code: "" });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [code, setCode] = useState(null);
  const load = () => api("/sync").then(setS).catch(() => {});
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, []);
  if (!s) return null;
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }));
  const go = async (path, body) => {
    setBusy(true); setErr("");
    try { setS(await api(path, { json: body })); setMode(null); setF((x) => ({ ...x, secret: "", code: "" })); }
    catch (e) { setErr(e.message); }
    setBusy(false);
  };
  const off = async () => {
    if (!confirm("Turn off sync on this Mac? Nothing is deleted, here or in your bucket. You can join again with a code.")) return;
    setCode(null); setS(await api("/sync", { method: "DELETE" }));
  };
  const showCode = async () => {
    if (code) return setCode(null);
    try { setCode((await api("/sync/code")).code); } catch (e) { toast(e.message); }
  };

  if (!s.enabled) return html`<h3>Sync</h3>
    <div class="card">
      <div class="hint" style="margin-top:0">Keep projects, recordings and people the same on this Mac and your phone, through your own
        Cloudflare R2 storage (free up to 10 GB, about 300 hours of recordings). Everything is encrypted on the device before upload, so
        Cloudflare can't read it.</div>
      ${!mode ? html`<div class="row" style="margin-top:10px">
          <span class="m">Off</span><span class="spacer"></span>
          <button class="btn" onClick=${() => setMode("join")}>Join with a code</button>
          <button class="btn primary" onClick=${() => setMode("setup")}>Set up sync</button></div>` : null}
      ${mode === "setup" ? html`<form class="syncform" onSubmit=${(e) => { e.preventDefault(); go("/sync/connect", { account: f.account, bucket: f.bucket, keyId: f.keyId, secret: f.secret }); }}>
          <ol class="steps">
            <li>In the <a href="https://dash.cloudflare.com/?to=/:account/r2/overview" target="_blank">Cloudflare dashboard → R2</a>, create a bucket, for example “folio-sync”.</li>
            <li>In R2, open <b>Manage API tokens</b> → <b>Create API token</b>. Choose <b>Object Read & Write</b> and limit it to that bucket. Copy the access key ID and secret access key it shows.</li>
            <li>Fill these in. The account ID is on the R2 overview page.</li>
          </ol>
          <label class="fld"><span>Account ID</span><input value=${f.account} onInput=${set("account")} placeholder="32 letters and digits" autocomplete="off" /></label>
          <label class="fld"><span>Bucket</span><input value=${f.bucket} onInput=${set("bucket")} autocomplete="off" /></label>
          <label class="fld"><span>Access key ID</span><input value=${f.keyId} onInput=${set("keyId")} autocomplete="off" /></label>
          <label class="fld"><span>Secret access key</span><input type="password" value=${f.secret} onInput=${set("secret")} autocomplete="off" /></label>
          ${err ? html`<div class="hint errtext">${err}</div>` : null}
          <div class="row" style="margin-top:10px"><span class="spacer"></span>
            <button type="button" class="btn" onClick=${() => { setMode(null); setErr(""); }}>Cancel</button>
            <button class="btn primary" disabled=${busy || !f.account || !f.bucket || !f.keyId || !f.secret}>${busy ? "Connecting…" : "Connect"}</button></div>
        </form>` : null}
      ${mode === "join" ? html`<form class="syncform" onSubmit=${(e) => { e.preventDefault(); go("/sync/join", { code: f.code }); }}>
          <div class="hint">On the device that already syncs, open Settings → Sync → <b>Code for another device</b>, copy the code and paste it here.</div>
          <textarea rows="3" value=${f.code} onInput=${set("code")} placeholder="folio1:…" style="width:100%;margin-top:8px"></textarea>
          ${err ? html`<div class="hint errtext">${err}</div>` : null}
          <div class="row" style="margin-top:10px"><span class="spacer"></span>
            <button type="button" class="btn" onClick=${() => { setMode(null); setErr(""); }}>Cancel</button>
            <button class="btn primary" disabled=${busy || !f.code.trim()}>${busy ? "Joining…" : "Join"}</button></div>
        </form>` : null}
    </div>`;

  return html`<h3>Sync</h3>
    <div class="card">
      <div class="row"><b>On</b><span class="m" style="margin-left:8px">bucket “${s.bucket}”</span><span class="spacer"></span>
        <span class=${"m" + (s.error ? " errtext" : "")}>${s.running ? "Syncing…" : s.error ? "Not synced" : s.last ? `Synced ${ago(s.last)}` : "Waiting…"}</span>
        <button class="btn" disabled=${s.running} onClick=${async () => { setS(await api("/sync/now", { json: {} })); setTimeout(load, 1500); }}>Sync now</button></div>
      ${s.error ? html`<div class="hint errtext">${s.error}</div>` : null}
      ${s.devices?.length ? html`<div class="devices">${s.devices.map((d) => html`<div class="row"><span>${d.platform === "android" ? "📱" : "💻"} ${d.name}${d.me ? " (this Mac)" : ""}</span>
          <span class="spacer"></span><span class="m">${d.me ? "" : `seen ${ago(d.seen)}`}</span></div>`)}</div>` : null}
      <div class="hint">Synced: projects, finished recordings (audio, transcript, summary), people and your Claude API key. Raw
        recording tracks stay on each device. Deleting on one device moves it to the Trash on the others.</div>
      <div class="row" style="margin-top:12px">
        <button class="btn" onClick=${showCode}>${code ? "Hide code" : "Code for another device"}</button><span class="spacer"></span>
        <button class="btn danger" onClick=${off}>Turn off sync</button></div>
      ${code ? html`<div class="paircode">
          <${QR} text=${code} />
          <div>
            <div class="hint" style="margin-top:0">In Folio on your phone: Settings → Sync → <b>Scan code</b>. On another Mac: <b>Join with a code</b> and paste it.</div>
            <div class="hint errtext">Anyone with this code can read and change your Folio data. Don't share it.</div>
            <button class="btn" onClick=${() => navigator.clipboard.writeText(code).then(() => toast("Code copied"))}>Copy code</button>
          </div>
        </div>` : null}
    </div>`;
}

function UpdatesCard() {
  const [st, poll] = useStatus();
  const [checking, setChecking] = useState(false);
  const a = st?.app;
  if (!a) return null;
  const check = async () => {
    setChecking(true);
    try { await api("/update/check", { json: {} }); } catch (e) { toast(e.message); }
    setChecking(false); poll();
  };
  return html`<h3>Updates</h3>
    <div class="card">
      <div class="row"><b>Folio ${a.version}</b><span class="spacer"></span>
        ${!a.updatable ? html`<span class="m">Running from source: update with git pull</span>`
          : a.installing ? html`<span class="m">${a.step || "Updating"}…</span>`
          : a.available ? html`<button class="btn primary" onClick=${() => installUpdate(a.available.version)}>Update to ${a.available.version}</button>`
          : html`<button class="btn" disabled=${checking} onClick=${check}>${checking ? "Checking…" : "Check for updates"}</button>`}</div>
      ${a.available?.notes ? html`<div class="hint">What's new: ${a.available.notes}</div>` : null}
      ${a.updatable && !a.available && a.checked && !a.error ? html`<div class="hint">You have the latest version.</div>` : null}
      ${a.error ? html`<div class="hint errtext">${a.error}</div>` : null}
      ${a.updatable ? html`<div class="hint">Folio checks for updates twice a day and asks before installing.</div>` : null}
    </div>`;
}

function Settings() {
  const [s, setS] = useState(null);
  const [key, setKey] = useState("");
  const [test, setTest] = useState(null);
  const [testing, setTesting] = useState(false);
  const load = () => api("/settings").then(setS).catch((e) => toast(e.message));
  const [st] = useStatus();
  useEffect(() => { load(); }, [st?.syncRev]);     // sync may bring the API key from another device
  if (!s) return html`<div class="page"></div>`;
  const put = async (body) => { try { setS(await api("/settings", { method: "PUT", json: body })); } catch (e) { toast(e.message); } };
  const saveKey = async (k) => {
    try { setS(await api("/settings/claude-key", { method: "PUT", json: { key: k } })); setKey(""); setTest(null); toast(k ? "Key saved" : "Key removed"); }
    catch (e) { toast(e.message); }
  };
  const runTest = async () => {
    setTesting(true); setTest(null);
    try { setTest(await api("/settings/claude-test", { json: {} })); } catch (e) { setTest({ ok: false, error: e.message }); }
    setTesting(false); load();
  };
  const claude = s.aiEngine === "claude";
  const month = s.claudeUsage.month;
  return html`<div class="page settings">
    <h1>Settings</h1>

    <h3>AI for summaries, fixes and questions</h3>
    <div class="card">
      <label class=${"src" + (!claude ? " on" : "")}><input type="radio" name="eng" checked=${!claude} onChange=${() => put({ aiEngine: "local" })} />
        <b>On this Mac — Qwen3 8B</b><span>Free and private: nothing leaves this Mac. Slower, and less careful with details.</span></label>
      <label class=${"src" + (claude ? " on" : "")} style="margin-top:8px"><input type="radio" name="eng" checked=${claude} onChange=${() => put({ aiEngine: "claude" })} />
        <b>Claude (Anthropic API)</b><span>Better summaries and answers, uses your API credits. Transcripts and project notes are sent to Anthropic; audio never leaves this Mac. Projects marked “keep on this Mac” still use the local model.</span></label>
    </div>

    <div class=${"card" + (claude ? "" : " dim")}>
      <div class="row"><b>Claude model</b><span class="spacer"></span>
        <select value=${s.claudeModel} onChange=${(e) => put({ claudeModel: e.target.value })}>
          ${s.claudeModels.map((m) => html`<option value=${m.id}>${m.name} — $${m.in} / $${m.out} per M tokens</option>`)}
        </select></div>
      <div class="hint">Opus is the most capable; Sonnet costs half as much; Haiku is very cheap but simpler. A one-hour meeting is roughly 15k tokens of transcript.</div>

      <div class="row" style="margin-top:14px"><b>API key</b><span class="spacer"></span>
        ${s.claudeKey ? html`<span class="m">${s.claudeKey.source === "folio" ? `Saved (${s.claudeKey.hint})` : "From ANTHROPIC_API_KEY"}</span>` : html`<span class="m">Not set</span>`}</div>
      <form class="row" style="margin-top:6px" onSubmit=${(e) => { e.preventDefault(); if (key.trim()) saveKey(key.trim()); }}>
        <input type="password" autocomplete="off" style="flex:1" placeholder=${s.claudeKey ? "Replace with a new key" : "sk-ant-…"} value=${key} onInput=${(e) => setKey(e.target.value)} />
        <button class="btn primary" disabled=${!key.trim()}>Save key</button>
        ${s.claudeKey?.source === "folio" ? html`<button type="button" class="btn danger" onClick=${() => confirm("Remove the saved API key?") && saveKey("")}>Remove</button>` : null}
      </form>
      <div class="hint">Get a key at console.anthropic.com → API keys. It's stored in ~/Folio/anthropic-key, readable only by you. With sync on, your other devices get it too (encrypted).</div>
      <div class="row" style="margin-top:10px">
        <button class="btn" disabled=${!s.claudeKey || testing} onClick=${runTest}>${testing ? "Testing…" : "Test connection"}</button>
        ${test ? html`<span class=${"m" + (test.ok ? "" : " errtext")}>${test.ok ? `✓ ${test.model} answered in ${test.seconds}s` : test.error}</span>` : null}
      </div>
      <div class="row" style="margin-top:14px"><b>Usage this month</b><span class="spacer"></span>
        <span class="m">${month.requests} request${month.requests === 1 ? "" : "s"} · about $${(month.usd || 0).toFixed(2)}</span></div>
      <div class="hint">Estimated from token counts at list prices; your Anthropic console has the exact bill.</div>
    </div>

    <${SyncCard} />

    <${UpdatesCard} />

    <h3>Notifications</h3>
    <div class="card">
      <label class="row"><input type="checkbox" checked=${s.notifications} onChange=${(e) => put({ notifications: e.target.checked })} />
        Notify me when no Folio window is open (still recording, transcript ready)</label>
    </div>
  </div>`;
}

// ---------- project

function useUpload(pid, load) {
  const [uploading, setUploading] = useState(0);
  const upload = async (files) => {
    files = [...files];
    setUploading((n) => n + files.length);
    for (const f of files) {
      const fd = new FormData();
      fd.append("file", f);
      fd.append("date", String((f.lastModified || Date.now()) / 1000));
      try {
        const r = await fetch(`/api/projects/${encodeURIComponent(pid)}/import`, { method: "POST", body: fd });
        if (!r.ok) throw new Error((await r.json()).detail || r.statusText);
      } catch (e) { toast(`${f.name}: ${e.message}`); }
      setUploading((n) => n - 1);
      load && load();
    }
  };
  return [uploading, upload];
}

function Project({ pid, reloadProjects }) {
  const [p, setP] = useState(null);
  const [tab, setTab] = useState("overview");
  const [over, setOver] = useState(false);
  const input = useRef();
  const load = useCallback(() => api(`/projects/${encodeURIComponent(pid)}`).then(setP).catch((e) => toast(e.message)), [pid]);
  const [uploading, upload] = useUpload(pid, load);
  useEffect(() => { load(); }, [load]);
  useChanged(load);
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
  const remove = async () => {
    const n = p.meetings.length;
    if (!confirm(`Delete the project “${p.name}”${n ? ` and its ${n} recording${n === 1 ? "" : "s"}` : ""}? Everything moves to the macOS Trash, so you can still restore it from there.`)) return;
    try { await api(`/projects/${encodeURIComponent(pid)}`, { method: "DELETE" }); reloadProjects(); location.hash = "#/"; } catch (e) { toast(e.message); }
  };
  const actions = html`<div class="row headactions">
    <a class="btn rec" href=${"#/?project=" + encodeURIComponent(pid)}><i class="recdot"></i>New recording</a>
    <button class="btn" onClick=${() => input.current.click()}>${uploading ? `Uploading ${uploading}…` : "Import files"}</button>
    ${p.builtin ? null : html`<button class="btn danger" onClick=${remove}>Delete project</button>`}
    <input ref=${input} type="file" multiple accept="audio/*,video/*" hidden onChange=${(e) => { upload(e.target.files); e.target.value = ""; }} />
  </div>`;
  return html`<div class=${"page" + (over ? " dropping" : "")}
      onDragOver=${(e) => { e.preventDefault(); setOver(true); }} onDragLeave=${(e) => { if (e.currentTarget === e.target) setOver(false); }}
      onDrop=${(e) => { e.preventDefault(); setOver(false); upload(e.dataTransfer.files); }}>
    <div class="headrow">
      ${p.builtin ? html`<h1>Unsorted</h1>` : html`<h1><input value=${p.name} onChange=${(e) => rename(e.target.value)} title="Click to rename" /></h1>`}
      ${actions}
    </div>
    <div class="sub">${p.builtin ? "Recordings you haven't filed yet. Right-click one (or use Move…) to move it to a project; it's then summarized with that project's notes and earlier meetings." : `${p.meetings.length} recording${p.meetings.length === 1 ? "" : "s"} · drop audio or video files anywhere here to import`}</div>
    ${p.builtin ? html`<${Meetings} p=${p} load=${load} />` : html`
    <div class="tabs">
      <button class=${tab === "overview" ? "on" : ""} onClick=${() => setTab("overview")}>Overview</button>
      <button class=${tab === "meetings" ? "on" : ""} onClick=${() => setTab("meetings")}>Recordings</button>
      <button class=${tab === "knowledge" ? "on" : ""} onClick=${() => setTab("knowledge")}>Knowledge & glossary</button>
    </div>
    ${tab === "overview" ? html`<${Overview} p=${p} setP=${setP} load=${load} />`
      : tab === "meetings" ? html`<${Meetings} p=${p} load=${load} />` : html`<${Knowledge} p=${p} load=${load} />`}`}
  </div>`;
}

function Overview({ p, setP, load }) {
  const base = `/projects/${encodeURIComponent(p.id)}`;
  const [q, setQ] = useState("");
  const [ans, setAns] = useState(null);
  const [asking, setAsking] = useState(false);
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
  if (!p.meetings.length) return html`<div class="cols">
    <section><div class="empty">Record or import the first meeting — the summary of where things stand and the to-dos build up from there.</div></section>
    <section><${PeoplePanel} p=${p} setP=${setP} /></section></div>`;
  return html`
    <form class="ask" onSubmit=${ask}>
      <input type="text" placeholder="Ask this project… e.g. “What did we decide about the battery?”" value=${q} onInput=${(e) => setQ(e.target.value)} />
      <button class="btn primary" disabled=${asking}>${asking ? "Thinking…" : "Ask"}</button>
    </form>
    ${ans ? html`<div class="card answer"><div class="ans">${ans.answer.split("\n").filter(Boolean).map((l) => html`<p>${cite(l)}</p>`)}</div>
      <button class="btn" onClick=${() => setAns(null)}>Close</button></div>` : null}

    <div class="cols">
      <section>
        <${StateSection} p=${p} setP=${setP} base=${base} />
        <h3 style="margin-top:28px">To-dos <small style="font-weight:400;color:var(--muted)">${open.length} open</small></h3>
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
      <section><${PeoplePanel} p=${p} setP=${setP} /></section>
    </div>

    <h3>Recent meetings</h3>
    <div class="mlist">${p.meetings.slice(0, 5).map((m) => html`<${MeetingRow} p=${p} m=${m} job=${p.jobs[m.id]} />`)}</div>`;
}

// The project's people: who's in it (their notes help summaries; their voices are recognised).
function PeoplePanel({ p, setP }) {
  const [all, setAll] = useState(null);
  const [q, setQ] = useState("");
  const base = `/projects/${encodeURIComponent(p.id)}/people`;
  useEffect(() => { api("/people").then(setAll); }, [p.people.length]);
  const members = new Set(p.people.map((x) => x.id));
  const ql = q.trim().toLowerCase();
  const matches = ql ? (all || []).filter((x) => !members.has(x.id) && x.name.toLowerCase().includes(ql)).slice(0, 5) : [];
  const exact = (all || []).some((x) => x.name.toLowerCase() === ql);
  const add = async (body) => { try { setP({ ...(await api(base, { json: body })), jobs: p.jobs }); setQ(""); } catch (e) { toast(e.message); } };
  const remove = async (x) => {
    if (!confirm(`Remove ${x.name} from this project? They stay in People and in recordings.`)) return;
    setP({ ...(await api(`${base}/${encodeURIComponent(x.id)}`, { method: "DELETE" })), jobs: p.jobs });
  };
  return html`<h3>People <small style="font-weight:400;color:var(--muted)">${p.people.length}</small></h3>
    <div class="members">${p.people.map((x) => html`<div class="member">
      <a href=${"#/people/" + encodeURIComponent(x.id)} class="row" style="flex:1;gap:8px">
        <span class="avatar">${initials(x.name)}</span>
        <span><b>${x.name}</b>${x.me ? html` <small>(me)</small>` : null}<br /><small>${x.notes ? x.notes.slice(0, 70) : x.voiceCount ? "voice known" : "no notes yet"}</small></span></a>
      <button class="iconbtn" title="Remove from project" onClick=${() => remove(x)}>×</button></div>`)}
      ${!p.people.length ? html`<div class="hint">Add the people in this project. Naming a speaker in a recording adds them too.</div>` : null}
    </div>
    <form class="row" style="margin-top:8px" onSubmit=${(e) => { e.preventDefault(); if (matches.length === 1) add({ person: matches[0].id }); else if (ql && !exact) add({ name: q.trim() }); }}>
      <input type="text" style="flex:1" placeholder="Add a person…" value=${q} onInput=${(e) => setQ(e.target.value)} />
    </form>
    ${ql ? html`<div class="suggest">${matches.map((x) => html`<div class="mi" onClick=${() => add({ person: x.id })}><span class="avatar">${initials(x.name)}</span>${x.name}</div>`)}
      ${!exact ? html`<div class="mi" onClick=${() => add({ name: q.trim() })}><span class="avatar new">+</span>New person “${q.trim()}”</div>` : null}</div>` : null}`;
}

// "Where things stand": AI-maintained after each meeting; you can edit it, tell it what changed,
// rebuild it from all meetings, or go back to an earlier version.
function StateSection({ p, setP, base }) {
  const [mode, setMode] = useState(null);      // null | "edit" | "update" | "history"
  const [text, setText] = useState("");
  const [note, setNote] = useState("");
  const [toKnowledge, setToKnowledge] = useState(true);
  const [history, setHistory] = useState(null);
  const [open, setOpen] = useState(null);       // history file shown
  const job = p.jobs["_state"];
  const keepJobs = (x) => setP({ ...x, jobs: { ...x.jobs } });
  const save = async () => { keepJobs(await api(base + "/state", { method: "PUT", json: { text } })); setMode(null); };
  const update = async (m) => {
    try {
      keepJobs(await api(base + "/state/update", { json: { mode: m, note, addToKnowledge: toKnowledge } }));
      setMode(null); setNote("");
    } catch (e) { toast(e.message); }
  };
  const showHistory = async () => { setMode("history"); setHistory(null); setHistory(await api(base + "/state/history")); };
  const restore = async (file) => {
    if (!confirm("Go back to this version? The current one is kept in the history too.")) return;
    keepJobs(await api(base + "/state/restore", { json: { file } })); setMode(null);
  };
  const bar = !mode && !job ? html`<div class="statebar">
      <button class="linkbtn" onClick=${() => { setText(p.state); setMode("edit"); }}>✎ Edit text</button>
      <button class="linkbtn" onClick=${() => setMode("note")}>Tell the AI what changed</button>
      <button class="linkbtn" onClick=${() => setMode("rebuild")}>Rewrite from all recordings</button>
      <button class="linkbtn" onClick=${showHistory}>Earlier versions</button>
    </div>` : null;
  return html`
    <h3>Project summary</h3>
    <div class="hint" style="margin:-4px 0 8px">Where things stand. The AI updates it after each recording is summarized.</div>

    ${job ? html`<div class="card"><span class="badge">${job.step === "queued" ? "Waiting" : "Updating"}${job.progress ? " " + Math.round(job.progress * 100) + "%" : ""}</span>
      <span class="hint"> Rewriting the project summary…</span></div>` : null}
    ${p.stateError && !job ? html`<div class="hint errtext">Last update failed: ${p.stateError}</div>` : null}

    ${mode === "edit" ? html`<textarea rows="16" value=${text} onInput=${(e) => setText(e.target.value)}></textarea>
      <div class="row" style="margin-top:8px"><button class="btn primary" onClick=${save}>Save</button>
      <button class="btn" onClick=${() => setMode(null)}>Cancel</button></div>`

    : mode === "note" || mode === "rebuild" ? html`<div class="card">
      <b>${mode === "note" ? "What changed?" : "Rewrite from all recordings"}</b>
      <div class="hint" style="margin:4px 0 8px">${mode === "note"
        ? "The AI revises the summary with your note; your note wins where it disagrees. Quick."
        : "The AI writes the summary again from every recording's summary (oldest first) and the project knowledge. Use it after a big change of aim or context. A note is optional."}</div>
      <textarea rows="4" placeholder=${mode === "note" ? "e.g. We dropped the hardware prototype and now focus on the app; the deadline moved to March." : "Optional: what changed"}
        value=${note} onInput=${(e) => setNote(e.target.value)}></textarea>
      <label class="checkline"><input type="checkbox" checked=${toKnowledge} onChange=${(e) => setToKnowledge(e.target.checked)} />
        <span>Also add the note to the project knowledge, so future summaries know</span></label>
      <div class="row" style="margin-top:10px">
        <button class="btn primary" disabled=${mode === "note" && !note.trim()} onClick=${() => update(mode === "note" ? "note" : "rebuild")}>${mode === "note" ? "Update summary" : "Rewrite summary"}</button>
        <button class="btn" onClick=${() => setMode(null)}>Cancel</button>
        <span class="hint" style="margin:0">The current version is kept under Earlier versions.</span></div>
    </div>`

    : mode === "history" ? html`<div class="card">
      <div class="row"><b>Earlier versions</b><span class="spacer"></span><button class="btn" onClick=${() => setMode(null)}>Close</button></div>
      ${!history ? html`<div class="hint">Loading…</div>` : !history.length ? html`<div class="hint">No earlier versions yet.</div>`
        : history.map((v) => html`<div class="histrow">
          <div class="row"><span class="link" onClick=${() => setOpen(open === v.file ? null : v.file)}>${when(v.at)}</span>
            <small class="m">${v.reason}</small><span class="spacer"></span>
            <button class="btn" onClick=${() => restore(v.file)}>Use this version</button></div>
          ${open === v.file ? html`<div class="state small" dangerouslySetInnerHTML=${{ __html: md(v.text) }}></div>` : null}
        </div>`)}
    </div>`

    : p.state ? html`<div class="state" dangerouslySetInnerHTML=${{ __html: md(p.state) }}></div>
        ${p.stateUpdated ? html`<div class="hint">Last updated ${when(p.stateUpdated.at)} · ${p.stateUpdated.reason}</div>` : null}${bar}`
    : html`<div class="hint">Nothing yet: it's written after the first recording is summarized. You can also describe the project yourself.</div>${bar}`}`;
}

function Meetings({ p, load }) {
  const [projects, setProjects] = useState([]);
  useEffect(() => { api("/projects").then(setProjects); }, []);
  if (!p.meetings.length) return html`<div class="empty">No recordings yet. Use New recording or Import files above.</div>`;
  const base = (m) => `/projects/${encodeURIComponent(p.id)}/meetings/${encodeURIComponent(m.id)}`;
  const move = async (m, to) => {
    if (!to) return;
    try { await api(base(m) + "/move", { json: { to } }); toast(to === "_unsorted" ? "Moved to Unsorted" : "Moved — summarizing it with that project's context"); load(); }
    catch (e) { toast(e.message); }
  };
  const remove = async (m) => {
    if (!confirm(`Delete “${m.title}”? It moves to the macOS Trash.`)) return;
    try { await api(base(m), { method: "DELETE" }); load(); } catch (e) { toast(e.message); }
  };
  return html`<div class="mlist">${p.meetings.map((m) => html`<div class="mrowwrap">
    <${MeetingRow} p=${p} m=${m} job=${p.jobs[m.id]} />
    <div class="rowactions">
      <select title="Move to another project" value="" onChange=${(e) => move(m, e.target.value)} disabled=${!!p.jobs[m.id]}>
        <option value="">${p.builtin ? "Move to project…" : "Move…"}</option>
        ${projects.filter((x) => x.id !== p.id).map((x) => html`<option value=${x.id}>${x.builtin ? "Unsorted" : x.name}</option>`)}
      </select>
      <button class="iconbtn" title="Delete recording" onClick=${() => remove(m)}>🗑</button>
    </div></div>`)}</div>`;
}

const SOURCES = [
  { id: "mic", mic: true, system: false, short: "Microphone", sub: "In-person meeting, lecture or class" },
  { id: "both", mic: true, system: true, short: "Online meeting", sub: "You on the mic, others from Zoom, Teams, Meet…" },
  { id: "system", mic: false, system: true, short: "Computer audio", sub: "A webinar or video playing on this Mac" },
];

// The home page: record now, file it in a project before, during or after (or leave it Unsorted).
function RecordPage({ projects, preset, reloadProjects }) {
  const [src, setSrc] = useState(() => { try { return localStorage.getItem("folio.source") || "mic"; } catch { return "mic"; } });
  const [target, setTarget] = useState(preset || "_unsorted");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [live, setLive] = useState(null);           // /api/live state while recording
  const [events, setEvents] = useState([]);
  const [last, setLast] = useState(null);           // {pid, mid} of the recording just finished
  const [lastMeta, setLastMeta] = useState(null);
  const box = useRef();
  const stick = useRef(true);
  useEffect(() => { if (preset) setTarget(preset); }, [preset]);

  // Follow a recording in progress (also one started from another window).
  useEffect(() => {
    let since = 0, alive = true, timer, mid = null;
    const poll = async () => {
      try {
        const s = await api(`/live?since=${since}`);
        if (!alive) return;
        if (s && !s.done) {
          if (s.mid !== mid) { mid = s.mid; since = 0; setEvents([]); }
          if (s.events.length) { since += s.events.length; setEvents((e) => [...e, ...s.events]); }
          setLive(s); setTarget(s.target);
        } else {
          if (mid && s && s.done) { setLast({ pid: s.pid, mid: s.mid }); reloadProjects(); }
          if (mid) { mid = null; setLive(null); }
        }
      } catch {}
      timer = setTimeout(poll, mid ? 500 : 2000);
    };
    poll();
    return () => { alive = false; clearTimeout(timer); };
  }, []);
  useEffect(() => { if (stick.current && box.current) box.current.scrollTop = box.current.scrollHeight; }, [events, live]);

  // The finished recording: follow its processing.
  useEffect(() => {
    if (!last) return;
    let alive = true, timer;
    const poll = async () => {
      try {
        const m = await api(`/projects/${encodeURIComponent(last.pid)}/meetings/${encodeURIComponent(last.mid)}`);
        if (!alive) return;
        setLastMeta(m);
        if (m.job || m.status === "queued" || m.status === "processing") timer = setTimeout(poll, 2000);
      } catch { setLastMeta(null); }
    };
    poll();
    return () => { alive = false; clearTimeout(timer); };
  }, [last?.pid, last?.mid]);

  const start = async () => {
    const s = SOURCES.find((x) => x.id === src);
    try { localStorage.setItem("folio.source", src); } catch {}
    setBusy(true); setLast(null); setLastMeta(null);
    try {
      const simulate = new URLSearchParams(location.search).get("simulate");
      const r = await api("/live/start", { json: { project: target, title, mic: s.mic, system: s.system, simulate } });
      setLive(r); setEvents([]); setTitle("");
    } catch (e) { toast(e.message); }
    setBusy(false);
  };
  const stop = async () => { try { await api("/live/stop", { json: {} }); setLive((l) => l && { ...l, stopping: true }); } catch (e) { toast(e.message); } };
  const choose = async (pid) => {
    setTarget(pid);
    if (live && !live.done) { try { await api("/live", { method: "PATCH", json: { project: pid } }); } catch (e) { toast(e.message); } }
    else if (last) {
      try {
        const r = await api(`/projects/${encodeURIComponent(last.pid)}/meetings/${encodeURIComponent(last.mid)}/move`, { json: { to: pid } });
        setLast({ pid: r.project, mid: r.meeting }); reloadProjects();
        toast(pid === "_unsorted" ? "Moved to Unsorted" : "Filed in the project");
      } catch (e) { toast(e.message); }
    }
  };
  const recording = live && !live.done;
  const both = live && live.tracks.length === 2;
  const label = (tr) => (both ? (tr === "mic" ? "You" : "Others") : null);
  const name = (pid) => pid === "_unsorted" ? "Unsorted" : (projects || []).find((x) => x.id === pid)?.name || pid;
  const lastStatus = lastMeta && (lastMeta.job ? (STEPS[lastMeta.job.step] || lastMeta.job.step) + (lastMeta.job.progress ? ` ${Math.round(lastMeta.job.progress * 100)}%` : "")
    : lastMeta.status === "ready" ? "Ready" : lastMeta.status === "error" ? "Failed: " + lastMeta.error : STEPS[lastMeta.status] || lastMeta.status);

  return html`<div class="recpage">
    <div class="recmain" ref=${box} onScroll=${(e) => { const el = e.target; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40; }}>
      ${recording ? html`
        <div class="livetext big">
          ${!events.length && !live.partials?.length ? html`<div class="empty">Listening… text appears as people speak.</div>` : null}
          ${events.map((e) => html`<p key=${e.seq}>${label(e.track) ? html`<b class=${"who-" + e.track}>${label(e.track)}</b>` : null}
            <span class="ts" style="display:inline;margin-right:8px">${clock(e.start)}</span>${e.text}</p>`)}
          ${(live.partials || []).map((pt) => html`<p class="partial">${label(pt.track) ? html`<b class=${"who-" + pt.track}>${label(pt.track)}</b>` : null}${pt.text}</p>`)}
        </div>
        ${live.error ? html`<div class="hint err">${live.error}</div>` : null}`
      : last ? html`<div class="card lastrec">
          <div class="row"><b>${lastMeta?.title || "Recording"}</b><span class="spacer"></span>
            <a class="btn primary" href=${`#/p/${encodeURIComponent(last.pid)}/m/${encodeURIComponent(last.mid)}`}>Open</a></div>
          <div class="hint">Saved in ${name(last.pid)} · ${lastStatus || "…"}</div>
          <div class="hint">You can still change where it's filed with “Save to” below.</div>
        </div>`
      : html`<div class="recintro">
          <h1>Record</h1>
          <p class="sub">Press the button to start. Choose a project now or later, or leave it in Unsorted. Text appears as people speak; when you stop, Folio transcribes it again with speakers and writes a summary.</p>
        </div>`}
    </div>

    <div class="recpanel">
      <div class="recopts">
        <label class="fld"><span>Save to</span>
          <select value=${target} onChange=${(e) => choose(e.target.value)} disabled=${busy || live?.stopping}>
            <option value="_unsorted">Unsorted</option>
            ${(projects || []).filter((x) => !x.builtin).map((x) => html`<option value=${x.id}>${x.name}</option>`)}
          </select></label>
        <label class="fld"><span>Source</span>
          <select value=${src} onChange=${(e) => setSrc(e.target.value)} disabled=${recording}>
            ${SOURCES.map((x) => html`<option value=${x.id}>${x.short}</option>`)}
          </select></label>
        <label class="fld grow"><span>Title</span>
          <input type="text" placeholder=${recording ? live.title : "Optional — the summary names it otherwise"} value=${title} disabled=${recording}
            onInput=${(e) => setTitle(e.target.value)} onKeyDown=${(e) => e.key === "Enter" && !recording && start()} /></label>
      </div>
      <div class="recbutton">
        ${recording ? html`
          <div class="timer"><i class="recdot pulse"></i>${live.stopping ? "Saving…" : clock(live.elapsed)}</div>
          <button class="roundbtn stop" disabled=${live.stopping} onClick=${stop} title="Stop"><i></i></button>
          <div class="meters">${live.tracks.map((tr) => html`<span class="meter" title=${tr}><small>${tr === "mic" ? "Mic" : "System"}</small>
            <i style=${`width:${Math.min(100, Math.round(Math.sqrt(live.levels[tr] || 0) * 260))}%`}></i></span>`)}</div>`
        : html`
          <div class="timer muted">${SOURCES.find((x) => x.id === src).sub}</div>
          <button class="roundbtn" disabled=${busy} onClick=${start} title="Start recording"><i></i></button>
          <div class="meters hint">${busy ? "Starting…" : src !== "mic" ? "First time: macOS asks to allow system audio." : "Start recording"}</div>`}
      </div>
    </div>
  </div>`;
}

function Live({ m, onDone, compact }) {
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
    <div class=${"livetext" + (compact ? " compact" : "")} ref=${box} onScroll=${(e) => { const el = e.target; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40; }}>
      ${!events.length && !st?.partials?.length ? html`<div class="empty">Listening… text appears as people speak.</div>` : null}
      ${events.map((e) => html`<p key=${e.seq}>${label(e.track) ? html`<b class=${"who-" + e.track}>${label(e.track)}</b>` : null}
        <span class="ts" style="display:inline;margin-right:8px">${clock(e.start)}</span>${e.text}</p>`)}
      ${(st?.partials || []).map((pt) => html`<p class="partial">${label(pt.track) ? html`<b class=${"who-" + pt.track}>${label(pt.track)}</b>` : null}${pt.text}</p>`)}
    </div>
    <div class="hint">Draft transcript. After you stop, the whole recording is transcribed again with speakers.</div>
  </div>`;
}

let projectsCache = [];
function MeetingRow({ p, m, job }) {
  const status = job ? STEPS[job.step] || job.step : m.status === "error" ? "Failed" : m.status === "ready" ? null : STEPS[m.status] || m.status;
  const menu = async (e) => {
    e.preventDefault(); const at = { clientX: e.clientX, clientY: e.clientY, preventDefault() {}, stopPropagation() {} };
    projectsCache = await api("/projects").catch(() => projectsCache);
    openMenu(at, recordingMenu(p.id, m, projectsCache));
  };
  return html`<a class="mrow" href=${`#/p/${encodeURIComponent(p.id)}/m/${encodeURIComponent(m.id)}`} onContextMenu=${menu}>
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
  const setLocal = async (on) => {
    await api(`/projects/${encodeURIComponent(p.id)}`, { method: "PUT", json: { localOnly: on } });
    load();
  };
  return html`<div class="knowledge">
    <label class="row" style="font-weight:400"><input type="checkbox" checked=${!!p.localOnly} onChange=${(e) => setLocal(e.target.checked)} />
      <span><b>Keep this project on this Mac.</b> Summaries, fixes and questions always use the local model, even when Claude is chosen in <a class="src-link" href="#/settings">Settings</a>.</span></label>
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

function Meeting({ pid, mid, at, projects, reloadProjects }) {
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
    if (v === "speakers") {
      const n = prompt("How many people spoke? Leave empty to let Folio work it out.", "");
      if (n === null) return;
      reprocessWith("speakers", Number(n) > 0 ? Number(n) : -1);
    } else if (v === "summarize") {
      if (m.summary?.edited && !confirm("Summarize again? Your edits to the summary will be replaced.")) return;
      setView("summary"); summarizeNow();
    } else if (v === "srt") location.href = "/api" + base + "/export.srt";
    else if (v === "process") reprocess("process");
  };
  const fix = async (mode) => {
    setView("transcript");
    try { await api(base + "/correct", { json: { mode } }); toast("Looking for fixes…"); load(); } catch (e) { toast(e.message); }
  };
  const decide = async (ids, accept) => {
    try { setM(await api(base + "/corrections", { json: { ids, accept } })); } catch (e) { toast(e.message); }
  };
  const remove = async () => {
    if (!confirm(`Delete “${m.title}”? It moves to the macOS Trash, so you can still restore it from there.`)) return;
    await api(base, { method: "DELETE" });
    location.hash = `#/p/${encodeURIComponent(pid)}`;
  };
  const speakers = t ? Object.keys(t.speakers).map(Number) : [];

  const moveTo = async (to) => {
    if (!to || to === pid) return;
    try {
      const r = await api(base + "/move", { json: { to } });
      reloadProjects && reloadProjects();
      toast(to === "_unsorted" ? "Moved to Unsorted" : "Moved — summarizing it with that project's context");
      location.hash = `#/p/${encodeURIComponent(r.project)}/m/${encodeURIComponent(r.meeting)}`;
    } catch (e) { toast(e.message); }
  };
  return html`<div class="page">
    <div class="row meetinghead">
      <label class="fld"><span>Project</span>
        <select value=${pid} onChange=${(e) => moveTo(e.target.value)} disabled=${!!m.job} title="Move this recording to another project">
          ${(projects || []).map((x) => html`<option value=${x.id}>${x.builtin ? "Unsorted" : x.name}</option>`)}
        </select></label>
      <span class="spacer"></span>
      <button class="btn danger" onClick=${remove}>Delete recording</button>
    </div>
    <h1><input value=${m.title} onChange=${(e) => rename(e.target.value)} title="Click to rename" /></h1>
    <div class="sub">${when(m.date)}${m.duration ? " · " + clock(m.duration) : ""}${speakers.length ? ` · ${speakers.length} speaker${speakers.length === 1 ? "" : "s"}` : ""}</div>

    <div class="player">
      ${m.hasAudio ? html`<audio ref=${audio} controls preload="metadata" src=${"/api" + base + "/audio"}></audio>` : null}
      <div class="row tools">
        ${m.job ? html`<span class="badge">${STEPS[m.job.step] || m.job.step}${m.job.progress ? ` ${Math.round(m.job.progress * 100)}%` : ""}</span>`
          : m.status === "error" ? html`<span class="badge err">Failed: ${m.error}</span>`
          : m.jobError ? html`<span class="badge err" title=${m.jobError}>${m.jobError.length > 90 ? m.jobError.slice(0, 90) + "…" : m.jobError}</span>` : null}
        <span class="spacer"></span>
        ${t ? html`<button class="btn" disabled=${!!m.job} onClick=${() => fix("both")} title="Listen again with a second model and check the text with the AI, then review each fix">Fix transcript</button>
        <a class="btn" href=${"/api" + base + "/export.md"} title="Download the transcript as Markdown">Export</a>` : null}
        <select onChange=${(e) => { const v = e.target.value; e.target.value = ""; more(v); }}>
          <option value="">More…</option>
          ${t ? html`<option value="speakers" disabled=${!!m.job}>Find speakers again…</option>
          <option value="summarize" disabled=${!!m.job}>Summarize again</option>
          <option value="srt">Export subtitles (SRT)</option>` : null}
          <option value="process" disabled=${!!m.job}>Transcribe again</option>
        </select>
      </div>
    </div>

    ${t ? html`<div class="tabs">
      <button class=${tab === "summary" ? "on" : ""} onClick=${() => setView("summary")}>Summary</button>
      <button class=${tab === "transcript" ? "on" : ""} onClick=${() => setView("transcript")}>Transcript${m.corrections?.length ? html` <span class="badge">${m.corrections.length}</span>` : null}</button>
    </div>` : null}
    ${t && tab === "summary" ? html`<${Summary} m=${m} job=${m.job} summarize=${summarizeNow} save=${async (body) => {
        try { setM(await api(base + "/summary", { method: "PUT", json: body })); toast("Summary saved"); } catch (e) { toast(e.message); } }} jump=${(sec) => { setView("transcript"); setTimeout(() => {
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
    ${!t ? html`<div class="empty">${m.status === "stopped" ? html`Transcription was stopped. <button class="btn primary" onClick=${() => reprocess("process")}>Transcribe</button>`
        : m.job || m.status !== "error" ? "The transcript will appear here when processing finishes." : "Processing failed."}</div>` : html`
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

// Summary sections as editable text, one item per line.
const toLines = {
  topics: (xs) => xs.map((x) => `${x.start ? x.start + " " : ""}${x.title}${x.summary ? " — " + x.summary : ""}`).join("\n"),
  action_items: (xs) => xs.map((x) => `${x.task}${x.owner ? " — " + x.owner : ""}${x.due ? ` (due ${x.due})` : ""}`).join("\n"),
  progress: (xs) => xs.map((x) => `${x.area ? x.area + ": " : ""}${x.update}`).join("\n"),
  plain: (xs) => xs.join("\n"),
};
const lines = (t) => t.split("\n").map((x) => x.trim()).filter(Boolean);
const fromLines = {
  topics: (t) => lines(t).map((l) => { const m = l.match(/^(\d+:\d\d)?\s*(.+?)(?:\s+—\s+(.*))?$/); return { start: m[1] || "", title: m[2], summary: m[3] || "" }; }),
  action_items: (t) => lines(t).map((l) => { const due = l.match(/\s*\(due ([^)]*)\)\s*$/); if (due) l = l.slice(0, due.index); const [task, owner] = l.split(/\s+—\s+/); return { task, owner: owner || "", due: due ? due[1] : "" }; }),
  progress: (t) => lines(t).map((l) => { const i = l.indexOf(": "); return i > 0 ? { area: l.slice(0, i), update: l.slice(i + 2) } : { area: "", update: l }; }),
  plain: lines,
};

function SummaryEditor({ s, save, cancel }) {
  const [f, setF] = useState({
    overview: s.overview, topics: toLines.topics(s.topics), decisions: toLines.plain(s.decisions),
    action_items: toLines.action_items(s.action_items), progress: toLines.progress(s.progress), open_questions: toLines.plain(s.open_questions),
  });
  const set = (k) => (e) => { const v = e.target.value; setF((cur) => ({ ...cur, [k]: v })); };
  const field = (k, label, hint, rows = 4) => html`<label>${label}</label>${hint ? html`<p>${hint}</p>` : null}
    <textarea rows=${rows} value=${f[k]} onInput=${set(k)}></textarea>`;
  const submit = () => save({
    overview: f.overview.trim(), topics: fromLines.topics(f.topics), decisions: fromLines.plain(f.decisions),
    action_items: fromLines.action_items(f.action_items), progress: fromLines.progress(f.progress), open_questions: fromLines.plain(f.open_questions),
  });
  return html`<div class="knowledge summaryedit">
    ${field("overview", "Overview", null, 4)}
    ${field("topics", "Topics", "One per line: time, title — summary (e.g. 4:10 Budget — agreed to cap at 25 euros)")}
    ${field("decisions", "Decisions", "One per line")}
    ${field("action_items", "Action items", "One per line: task — owner (due date)")}
    ${field("progress", "Progress", "One per line: area: what moved forward")}
    ${field("open_questions", "Open questions", "One per line", 3)}
    <div class="row" style="margin-top:10px"><button class="btn primary" onClick=${submit}>Save</button><button class="btn" onClick=${cancel}>Cancel</button>
      <span class="hint" style="margin:0">Your edits stay until you summarize again.</span></div>
  </div>`;
}

function Summary({ m, job, summarize, jump, save }) {
  const s = m.summary;
  const [editing, setEditing] = useState(false);
  const running = job && job.kind === "summarize";
  if (!s) return html`<div class="empty">${running ? `Writing the summary… ${Math.round((job.progress || 0) * 100)}%`
    : html`No summary yet. <button class="btn primary" onClick=${summarize}>Summarize</button>`}</div>`;
  if (editing) return html`<${SummaryEditor} s=${s} cancel=${() => setEditing(false)} save=${async (body) => { await save(body); setEditing(false); }} />`;
  const done = (m.todos || []).filter((x) => x.closedBy?.meeting === m.id);
  const list = (items, f) => items.length ? html`<ul>${items.map(f)}</ul>` : null;
  return html`<div class="summary">
    <div class="row" style="justify-content:flex-end;margin-bottom:-6px"><button class="btn" disabled=${!!job} onClick=${() => setEditing(true)}>✎ Edit summary</button></div>
    <p class="overview">${s.overview}</p>
    ${s.topics.length ? html`<h4>Topics</h4>${list(s.topics, (x) => html`<li>
      ${secs(x.start) != null ? html`<span class="ts" style="display:inline" onClick=${() => jump(secs(x.start))}>${x.start}</span> ` : null}
      <b>${x.title}</b>${x.summary ? html` — ${x.summary}` : null}</li>`)}` : null}
    ${s.decisions.length ? html`<h4>Decisions</h4>${list(s.decisions, (x) => html`<li>${x}</li>`)}` : null}
    ${s.action_items.length ? html`<h4>Action items</h4>${list(s.action_items, (x) => html`<li>${x.task}${x.owner ? html` <span class="owner">${x.owner}</span>` : null}${x.due ? html` <small>due ${x.due}</small>` : null}</li>`)}` : null}
    ${done.length ? html`<h4>Done since earlier meetings</h4>${list(done, (x) => html`<li><s>${x.text}</s>${x.closedBy.note ? html` <small>${x.closedBy.note}</small>` : null}</li>`)}` : null}
    ${s.progress.length ? html`<h4>Progress</h4>${list(s.progress, (x) => html`<li>${x.area ? html`<b>${x.area}</b>: ` : null}${x.update}</li>`)}` : null}
    ${s.open_questions.length ? html`<h4>Open questions</h4>${list(s.open_questions, (x) => html`<li>${x}</li>`)}` : null}
    ${s.updatedState === false ? html`<div class="hint">This is an older recording, so it didn't change the project summary. Use “Tell the AI what changed” on the project overview for that.</div>` : null}
    <div class="row hint" style="margin-top:18px">${s.edited ? "Edited by you. " : ""}Written by ${s.writer || "Qwen3 8B on this Mac"} using the project's knowledge, summary, to-dos and earlier recordings.</div>
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
  const load = useCallback(() => api("/people").then(setList), []);
  useEffect(() => { load(); }, []);
  useChanged(load);
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
      <a class="mrow" href=${"#/people/" + p.id} onContextMenu=${(e) => openMenu(e, personMenu(p))}><span class="t"><span class="avatar">${initials(p.name)}</span> ${p.name}${p.me ? " (me)" : ""}</span>
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
      <label>In projects</label>
      <p>${(projects || []).filter((pr) => p.projects.includes(pr.id) && !pr.builtin).map((pr, i) => html`${i ? ", " : ""}<a class="src-link" href=${"#/p/" + encodeURIComponent(pr.id)}>${pr.name}</a>`)}
        ${!p.projects.filter((x) => x !== "_unsorted").length ? "None yet." : null} Add or remove people from a project in its People panel.</p>
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
