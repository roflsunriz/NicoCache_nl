import { renderCacheManager } from "/assets/cache-manager.js";

const API = "/api/v1";
const app = document.querySelector("#app");

const messages = {
  ja: {
    overview: "概要", cache: "キャッシュ", health: "ヘルス", diagnostics: "診断",
    localFiles: "localファイル", localFilesLead: "ブラウザーへ配信できるファイルをフォルダーごとに表示します。",
    name: "名前", created: "作成日時", type: "種類", directory: "フォルダー",
    file: "ファイル", other: "その他", emptyDirectory: "このフォルダーは空です。",
    parentDirectory: "親フォルダー", source: "提供元", userSource: "利用者", applicationSource: "標準",
    threads: "スレッド", loading: "読み込み中…", ready: "利用可能",
    runtime: "ランタイム", memory: "ヒープ使用量", uptime: "稼働時間",
    threadCount: "スレッド数", deadlocks: "デッドロック", cacheEntries: "キャッシュ一覧",
    refresh: "再読み込み", complete: "完成", temporary: "一時", actions: "操作",
    delete: "削除", noEntries: "キャッシュはありません", capture: "スレッドダンプを採取",
    captureHelp: "ボタンを押したときだけ完全なスレッドダンプを作成します。",
    videoDetails: "動画キャッシュ", exportVideo: "動画保存", exportAudio: "音声保存",
    exportComments: "コメント保存", apiError: "APIの読み込みに失敗しました",
    cacheManagerLead: "保存済み・取得中キャッシュを検索して管理します。",
    cacheControls: "キャッシュの検索・絞り込み・並び替え", search: "検索",
    searchPlaceholder: "動画ID・タイトル・保存先で検索", state: "状態", all: "すべて",
    unavailable: "利用不可", quality: "画質", lowQuality: "低画質", unknown: "不明",
    sort: "並び替え", title: "タイトル", availability: "公開状態", size: "サイズ",
    updated: "更新日時", ascending: "昇順", descending: "降順", reset: "リセット",
    checkAvailability: "公開状態を確認", deleteTemporary: "一時キャッシュを一括削除",
    loadMore: "さらに表示", close: "閉じる", items: "件", downloading: "取得中",
    play: "再生", details: "詳細", folder: "保存先", cacheRoot: "キャッシュルート",
    author: "投稿者",
    duration: "再生時間", views: "再生数", comments: "コメント数", mylists: "マイリスト数",
    confirmDelete: "このキャッシュを削除しますか？", deleting: "削除中…",
    deleteScheduled: "取得終了後の削除を予約しました。", deleteComplete: "削除処理が完了しました。",
    confirmAvailability: "動画情報APIで公開状態を確認しますか？", checkingAvailability: "公開状態を確認中…",
    availabilityComplete: "公開状態の確認が完了しました。", success: "成功", failed: "失敗",
    noTemporary: "削除対象の一時キャッシュはありません。",
    confirmDeleteTemporary: "一時キャッシュを動画単位で一括削除しますか？",
    deleted: "削除", scheduled: "削除予約", notFound: "対象なし",
  },
  en: {
    overview: "Overview", cache: "Cache", health: "Health", diagnostics: "Diagnostics",
    localFiles: "Local files", localFilesLead: "Browse the files available to web pages through the local directory.",
    name: "Name", created: "Created", type: "Type", directory: "Folder",
    file: "File", other: "Other", emptyDirectory: "This folder is empty.",
    parentDirectory: "Parent folder", source: "Source", userSource: "User", applicationSource: "Bundled",
    threads: "Threads", loading: "Loading…", ready: "Ready", runtime: "Runtime",
    memory: "Heap used", uptime: "Uptime", threadCount: "Threads", deadlocks: "Deadlocks",
    cacheEntries: "Cache entries", refresh: "Refresh", complete: "Complete",
    temporary: "Temporary", actions: "Actions", delete: "Delete", noEntries: "No cache entries",
    capture: "Capture thread dump", captureHelp: "A full thread dump is created only on request.",
    videoDetails: "Video cache", exportVideo: "Save video", exportAudio: "Save audio",
    exportComments: "Save comments", apiError: "Failed to load the API",
    cacheManagerLead: "Search and manage completed and in-progress cache entries.",
    cacheControls: "Cache search, filters, and sorting", search: "Search",
    searchPlaceholder: "Search by video ID, title, or folder", state: "State", all: "All",
    unavailable: "Unavailable", quality: "Quality", lowQuality: "Low quality", unknown: "Unknown",
    sort: "Sort", title: "Title", availability: "Availability", size: "Size",
    updated: "Updated", ascending: "Ascending", descending: "Descending", reset: "Reset",
    checkAvailability: "Check availability", deleteTemporary: "Delete temporary cache",
    loadMore: "Show more", close: "Close", items: "items", downloading: "Downloading",
    play: "Play", details: "Details", folder: "Folder", cacheRoot: "Cache root",
    author: "Author",
    duration: "Duration", views: "Views", comments: "Comments", mylists: "My lists",
    confirmDelete: "Delete this cache entry?", deleting: "Deleting…",
    deleteScheduled: "Deletion was scheduled after the download finishes.", deleteComplete: "Deletion finished.",
    confirmAvailability: "Check availability with the video information API?", checkingAvailability: "Checking availability…",
    availabilityComplete: "Availability check finished.", success: "Succeeded", failed: "Failed",
    noTemporary: "There are no temporary cache entries to delete.",
    confirmDeleteTemporary: "Delete temporary cache entries by video?",
    deleted: "Deleted", scheduled: "Scheduled", notFound: "Not found",
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
const formatDate = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
};
const encodePath = (value) => String(value).split("/").filter(Boolean)
  .map((segment) => encodeURIComponent(segment)).join("/");

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

const renderCache = () => renderCacheManager({
  app, fetchJson, t, escapeHtml, formatBytes,
});
const localBreadcrumbs = (path) => {
  const segments = String(path).split("/").filter(Boolean);
  const links = [`<a href="/local">local</a>`];
  segments.forEach((segment, index) => {
    const partial = encodePath(segments.slice(0, index + 1).join("/"));
    links.push(`<a href="/local/${partial}">${escapeHtml(segment)}</a>`);
  });
  return links.join('<span aria-hidden="true">/</span>');
};
const renderLocalFiles = async () => {
  const suffix = location.pathname.slice("/local".length);
  const data = await fetchJson(`/local-files${suffix}`);
  const rows = data.entries.map((entry) => {
    const isDirectory = entry.kind === "directory";
    const name = isDirectory
      ? `<a href="/local/${encodePath(entry.path)}">${escapeHtml(entry.name)}/</a>`
      : `<a href="${escapeHtml(entry.url)}">${escapeHtml(entry.name)}</a>`;
    const type = isDirectory ? t("directory") : (entry.mediaType || t(entry.kind));
    const source = entry.source === "user" ? t("userSource") : t("applicationSource");
    return `<tr><td>${name}</td><td>${escapeHtml(formatDate(entry.createdAt))}</td>
      <td>${escapeHtml(formatDate(entry.modifiedAt))}</td><td>${isDirectory ? "—" : escapeHtml(formatBytes(entry.size))}</td>
      <td>${escapeHtml(type)}</td><td>${escapeHtml(source)}</td></tr>`;
  }).join("");
  const parent = data.parentPath === null ? "" : `<p><a href="/local${data.parentPath ? `/${encodePath(data.parentPath)}` : ""}">↑ ${escapeHtml(t("parentDirectory"))}</a></p>`;
  app.innerHTML = `<h1>${escapeHtml(t("localFiles"))}</h1><p class="lead">${escapeHtml(t("localFilesLead"))}</p>
    <nav class="breadcrumbs" aria-label="Breadcrumb">${localBreadcrumbs(data.path)}</nav>${parent}
    <div class="table-wrap"><table class="local-files-table"><thead><tr><th>${escapeHtml(t("name"))}</th>
      <th>${escapeHtml(t("created"))}</th><th>${escapeHtml(t("updated"))}</th><th>${escapeHtml(t("size"))}</th>
      <th>${escapeHtml(t("type"))}</th><th>${escapeHtml(t("source"))}</th></tr></thead>
      <tbody>${rows || `<tr><td colspan="6" class="empty-state">${escapeHtml(t("emptyDirectory"))}</td></tr>`}</tbody></table></div>`;
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
  if (location.pathname === "/local" || location.pathname.startsWith("/local/")) return renderLocalFiles();
  if (location.pathname === "/health") return renderHealth();
  if (location.pathname === "/diagnostics") return renderDiagnostics();
  if (location.pathname === "/diagnostics/threads") return renderThreads();
  const video = location.pathname.match(/^\/videos\/([a-z]{2}\d+)$/);
  if (video) return renderVideo(video[1]);
  return renderOverview();
};
route().catch(showError);
