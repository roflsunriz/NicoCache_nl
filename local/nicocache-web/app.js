const API = "/api/v1";
const app = document.querySelector("#app");

const messages = {
  ja: {
    overview: "概要", cache: "キャッシュ", health: "ヘルス", diagnostics: "診断",
    threads: "スレッド", loading: "読み込み中…", ready: "利用可能",
    runtime: "ランタイム", memory: "ヒープ使用量", uptime: "稼働時間",
    threadCount: "スレッド数", deadlocks: "デッドロック", cacheEntries: "キャッシュ一覧",
    refresh: "再読み込み", complete: "完成", temporary: "一時", actions: "操作",
    delete: "削除", noEntries: "キャッシュはありません", capture: "スレッドダンプを採取",
    captureHelp: "ボタンを押したときだけ完全なスレッドダンプを作成します。",
    videoDetails: "動画キャッシュ", exportVideo: "動画保存", exportAudio: "音声保存",
    exportComments: "コメント保存", apiError: "APIの読み込みに失敗しました",
  },
  en: {
    overview: "Overview", cache: "Cache", health: "Health", diagnostics: "Diagnostics",
    threads: "Threads", loading: "Loading…", ready: "Ready", runtime: "Runtime",
    memory: "Heap used", uptime: "Uptime", threadCount: "Threads", deadlocks: "Deadlocks",
    cacheEntries: "Cache entries", refresh: "Refresh", complete: "Complete",
    temporary: "Temporary", actions: "Actions", delete: "Delete", noEntries: "No cache entries",
    capture: "Capture thread dump", captureHelp: "A full thread dump is created only on request.",
    videoDetails: "Video cache", exportVideo: "Save video", exportAudio: "Save audio",
    exportComments: "Save comments", apiError: "Failed to load the API",
  },
};
const language = navigator.language?.toLowerCase().startsWith("ja") ? "ja" : "en";
const t = (key) => messages[language][key] ?? messages.ja[key] ?? key;
document.documentElement.lang = language;
document.querySelectorAll("[data-i18n]").forEach((node) => {
  node.textContent = t(node.dataset.i18n);
});

const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (character) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[character]);
const formatBytes = (bytes) => {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let index = 0;
  let current = value;
  while (current >= 1024 && index < units.length - 1) { current /= 1024; index += 1; }
  return `${current.toLocaleString(undefined, { maximumFractionDigits: 1 })} ${units[index]}`;
};
const formatDuration = (milliseconds) => {
  const seconds = Math.max(0, Math.floor(Number(milliseconds) / 1000));
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${days}d ${hours}h ${minutes}m`;
};

const fetchJson = async (path, init) => {
  const response = await fetch(`${API}${path}`, { cache: "no-store", ...init });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message ?? `HTTP ${response.status}`);
  return data;
};
const showError = (error) => {
  app.innerHTML = `<section class="panel error-box"><h1>${escapeHtml(t("apiError"))}</h1><pre>${escapeHtml(error instanceof Error ? error.message : error)}</pre></section>`;
};
const setCurrentNav = () => {
  document.querySelectorAll("nav a").forEach((link) => {
    const current = link.getAttribute("href") === location.pathname
      || (link.getAttribute("href") !== "/" && location.pathname.startsWith(link.getAttribute("href")));
    if (current) link.setAttribute("aria-current", "page");
  });
};

const runtimeCards = (runtime) => `
  <div class="grid">
    <section class="panel"><h2>${t("memory")}</h2><div class="metric">${formatBytes(runtime.heapUsed)}</div><p class="muted">${formatBytes(runtime.heapCommitted)} / ${formatBytes(runtime.heapMax)}</p></section>
    <section class="panel"><h2>${t("uptime")}</h2><div class="metric">${formatDuration(runtime.uptimeMillis)}</div></section>
    <section class="panel"><h2>${t("threadCount")}</h2><div class="metric">${Number(runtime.threadCount).toLocaleString()}</div><p class="muted">peak ${Number(runtime.peakThreadCount).toLocaleString()}</p></section>
    <section class="panel"><h2>${t("deadlocks")}</h2><div class="metric ${runtime.deadlockedThreadCount ? "error" : "ok"}">${Number(runtime.deadlockedThreadCount).toLocaleString()}</div></section>
  </div>`;

const renderOverview = async () => {
  const [health, runtime] = await Promise.all([
    fetchJson("/health/ready"), fetchJson("/diagnostics/runtime"),
  ]);
  app.innerHTML = `<h1>NicoCache_nl</h1><p class="lead">Local cache proxy and management API</p>
    <section class="panel"><h2>${t("health")}</h2><div class="metric ok">${escapeHtml(health.status)}</div><p class="muted">${escapeHtml(health.version ?? "")}</p></section>
    <div style="height:1rem"></div>${runtimeCards(runtime)}`;
};
const renderHealth = async () => {
  const [live, ready] = await Promise.all([fetchJson("/health/live"), fetchJson("/health/ready")]);
  app.innerHTML = `<h1>${t("health")}</h1><p class="lead">Liveness and readiness</p><div class="grid">
    <section class="panel"><h2>Live</h2><div class="metric ok">${escapeHtml(live.status)}</div></section>
    <section class="panel"><h2>Ready</h2><div class="metric ok">${escapeHtml(ready.status)}</div><p class="muted">${escapeHtml(ready.version)}</p></section>
  </div>`;
};
const renderDiagnostics = async () => {
  const runtime = await fetchJson("/diagnostics/runtime");
  app.innerHTML = `<h1>${t("diagnostics")}</h1><p class="lead">${escapeHtml(runtime.javaVersion)} · ${escapeHtml(runtime.osName)} ${escapeHtml(runtime.osArch)}</p>${runtimeCards(runtime)}<pre>${escapeHtml(JSON.stringify(runtime, null, 2))}</pre>`;
};
const renderThreads = () => {
  app.innerHTML = `<h1>${t("threads")}</h1><p class="lead">${t("captureHelp")}</p><div class="actions"><button id="capture">${t("capture")}</button></div><pre id="thread-output" hidden></pre>`;
  document.querySelector("#capture").addEventListener("click", async (event) => {
    const button = event.currentTarget;
    const output = document.querySelector("#thread-output");
    button.disabled = true;
    output.hidden = false;
    output.textContent = t("loading");
    try {
      const created = await fetchJson("/diagnostic-snapshots", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
      const response = await fetch(`${API}/diagnostic-snapshots/${encodeURIComponent(created.id)}/thread-dump`, { cache: "no-store" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      output.textContent = await response.text();
    } catch (error) { output.textContent = error instanceof Error ? error.message : String(error); }
    finally { button.disabled = false; }
  });
};

const cacheRows = (entries, state) => Object.entries(entries ?? {}).map(([cacheId, raw]) => {
  const value = Array.isArray(raw) ? raw : [];
  const title = value[0] ?? cacheId;
  const size = value[2] ?? 0;
  return `<tr><td><code>${escapeHtml(cacheId)}</code></td><td>${escapeHtml(title)}</td><td>${escapeHtml(state)}</td><td>${formatBytes(size)}</td><td><button class="danger" data-delete-id="${escapeHtml(cacheId)}" data-temporary="${state === "temporary"}">${t("delete")}</button></td></tr>`;
}).join("");
const renderCache = async () => {
  const data = await fetchJson("/cache-entries");
  const rows = cacheRows(data.complete, "complete") + cacheRows(data.temporary, "temporary");
  app.innerHTML = `<h1>${t("cacheEntries")}</h1><div class="actions"><button id="refresh">${t("refresh")}</button></div>
    <div class="table-wrap"><table><thead><tr><th>ID</th><th>Title</th><th>State</th><th>Size</th><th>${t("actions")}</th></tr></thead><tbody>${rows || `<tr><td colspan="5">${t("noEntries")}</td></tr>`}</tbody></table></div>`;
  document.querySelector("#refresh").addEventListener("click", () => renderCache().catch(showError));
  document.querySelectorAll("[data-delete-id]").forEach((button) => button.addEventListener("click", async () => {
    if (!confirm(`${t("delete")}: ${button.dataset.deleteId}`)) return;
    button.disabled = true;
    const collection = button.dataset.temporary === "true" ? "temporary-cache-entries" : "cache-entries";
    try { await fetchJson(`/${collection}/${encodeURIComponent(button.dataset.deleteId)}`, { method: "DELETE" }); await renderCache(); }
    catch (error) { showError(error); }
  }));
};
const renderVideo = async (videoId) => {
  const data = await fetchJson(`/videos/${encodeURIComponent(videoId)}/cache-entries`);
  app.innerHTML = `<h1>${t("videoDetails")}: ${escapeHtml(videoId)}</h1><div class="actions">
    <a class="button" href="${API}/videos/${encodeURIComponent(videoId)}/exports/video">${t("exportVideo")}</a>
    <a class="button" href="${API}/videos/${encodeURIComponent(videoId)}/exports/audio">${t("exportAudio")}</a>
    <a class="button" href="${API}/videos/${encodeURIComponent(videoId)}/exports/comments">${t("exportComments")}</a>
  </div><pre>${escapeHtml(JSON.stringify(data, null, 2))}</pre>`;
};

const route = async () => {
  setCurrentNav();
  app.innerHTML = document.querySelector("#loading-template").innerHTML;
  if (location.pathname === "/cache") return renderCache();
  if (location.pathname === "/health") return renderHealth();
  if (location.pathname === "/diagnostics") return renderDiagnostics();
  if (location.pathname === "/diagnostics/threads") return renderThreads();
  const video = location.pathname.match(/^\/videos\/([a-z]{2}\d+)$/);
  if (video) return renderVideo(video[1]);
  return renderOverview();
};
route().catch(showError);
