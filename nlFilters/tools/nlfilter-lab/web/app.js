(() => {
  "use strict";

  const elements = {
    form: document.querySelector("#controls"),
    fixture: document.querySelector("#fixture"),
    cacheState: document.querySelector("#cache-state"),
    url: document.querySelector("#virtual-url"),
    contentType: document.querySelector("#content-type"),
    reencoded: document.querySelector("#reencoded"),
    reencodedBitrate: document.querySelector("#reencoded-bitrate"),
    apiFailure: document.querySelector("#api-failure"),
    filterList: document.querySelector("#filter-list"),
    summary: document.querySelector("#summary"),
    preview: document.querySelector("#preview"),
    render: document.querySelector("#render"),
    spaAdd: document.querySelector("#spa-add"),
    domStats: document.querySelector("#dom-stats"),
    traces: document.querySelector("#traces"),
    diagnostics: document.querySelector("#diagnostics"),
    logs: document.querySelector("#logs"),
    before: document.querySelector("#source-before"),
    after: document.querySelector("#source-after")
  };

  let config;
  let currentToken;
  let logTimer;

  const escapeHtml = value => String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

  async function initialize() {
    const response = await fetch("/api/config", { cache: "no-store" });
    if (!response.ok) throw new Error(`config: ${response.status}`);
    config = await response.json();
    elements.fixture.innerHTML = config.fixtures.map(fixture =>
      `<option value="${escapeHtml(fixture.id)}">${escapeHtml(fixture.label)}</option>`).join("");
    elements.filterList.innerHTML = config.filters.map(filter => {
      const status = filter.errors > 0
        ? `<span class="badge error">${filter.errors} errors</span>`
        : filter.productionParser === "mismatch"
          ? `<span class="badge error">parser mismatch</span>`
        : filter.warnings > 0
          ? `<span class="badge warning">${filter.warnings} warnings</span>`
          : `<span class="badge">${filter.rules} rules</span>`;
      return `<label class="filter-item"><input type="checkbox" name="file" value="${escapeHtml(filter.name)}" checked>` +
        `<span class="filter-name" title="${escapeHtml(filter.name)}">${escapeHtml(filter.name)}</span>${status}</label>`;
    }).join("");
    const errorCount = config.filters.reduce((sum, filter) => sum + filter.errors, 0);
    const warningCount = config.filters.reduce((sum, filter) => sum + filter.warnings, 0);
    elements.summary.textContent = `${config.filters.length} files · ${errorCount} errors · ${warningCount} warnings · parser ${config.parserCompatibility.status}`;
    applyFixtureDefaults();
    await renderPreview();
  }

  function applyFixtureDefaults() {
    const fixture = config.fixtures.find(item => item.id === elements.fixture.value);
    if (!fixture) return;
    elements.url.value = fixture.url;
    elements.contentType.value = fixture.contentType;
  }

  async function renderPreview() {
    elements.render.disabled = true;
    elements.render.textContent = "適用中…";
    elements.domStats.textContent = "生成中…";
    const body = new URLSearchParams();
    body.set("fixture", elements.fixture.value);
    body.set("url", elements.url.value);
    body.set("contentType", elements.contentType.value);
    body.set("cacheState", elements.cacheState.value);
    body.set("cacheApiFailure", String(elements.apiFailure.checked));
    body.set("reencoded", elements.reencoded.value);
    body.set("reencodedBitrate", elements.reencodedBitrate.value);
    elements.filterList.querySelectorAll('input[name="file"]:checked').forEach(input => body.append("file", input.value));
    try {
      const response = await fetch("/api/render", { method: "POST", body });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || `render: ${response.status}`);
      currentToken = data.token;
      elements.preview.src = data.previewUrl;
      elements.spaAdd.disabled = false;
      elements.before.value = data.original;
      elements.after.value = data.rendered;
      displayTraces(data.traces);
      displayDiagnostics(data.diagnostics);
      elements.logs.innerHTML = '<p class="empty">Consoleメッセージはありません。</p>';
      clearInterval(logTimer);
      logTimer = setInterval(refreshLogs, 1000);
    } catch (error) {
      elements.traces.innerHTML = `<div class="result-item error"><strong>プレビュー生成失敗</strong><small>${escapeHtml(error.message)}</small></div>`;
      elements.domStats.textContent = "失敗";
    } finally {
      elements.render.disabled = false;
      elements.render.textContent = "疑似適用する";
    }
  }

  function displayTraces(traces) {
    elements.traces.innerHTML = traces.length === 0
      ? '<p class="empty">URL・Content-Type・Requireに一致するルールはありません。</p>'
      : traces.map(trace => `<div class="result-item"><strong>${escapeHtml(trace.identifier)}</strong>` +
        `<small>${escapeHtml(trace.section)} · ${trace.replacements}件 · ${escapeHtml(trace.note)}</small></div>`).join("");
  }

  function displayDiagnostics(diagnostics) {
    elements.diagnostics.innerHTML = diagnostics.length === 0
      ? '<p class="empty">構文・疑似適用の診断はありません。</p>'
      : diagnostics.map(item => `<div class="result-item ${item.severity.toLowerCase()}"><strong>${escapeHtml(item.code)}</strong>` +
        `<small>${escapeHtml(item.message)}</small></div>`).join("");
  }

  async function refreshLogs() {
    if (!currentToken) return;
    try {
      const response = await fetch(`/api/logs?token=${encodeURIComponent(currentToken)}`, { cache: "no-store" });
      if (!response.ok) return;
      const data = await response.json();
      elements.logs.innerHTML = data.logs.length === 0
        ? '<p class="empty">Consoleメッセージはありません。</p>'
        : data.logs.map(log => `<div class="result-item ${log.level === "error" ? "error" : log.level === "warn" ? "warning" : ""}">` +
          `<strong>${escapeHtml(log.level)}</strong><small>${escapeHtml(log.message)}</small></div>`).join("");
    } catch (_) {
      // 次のポーリングで再試行する。
    }
  }

  document.querySelector("#select-all").addEventListener("click", () => {
    elements.filterList.querySelectorAll('input[type="checkbox"]').forEach(input => { input.checked = true; });
  });
  document.querySelector("#select-none").addEventListener("click", () => {
    elements.filterList.querySelectorAll('input[type="checkbox"]').forEach(input => { input.checked = false; });
  });
  elements.fixture.addEventListener("change", applyFixtureDefaults);
  elements.form.addEventListener("submit", event => {
    event.preventDefault();
    renderPreview();
  });
  elements.spaAdd.addEventListener("click", () => {
    elements.preview.contentWindow?.postMessage({ type: "nlfilter-lab", action: "spa-add" }, "*");
  });
  document.querySelectorAll('[role="tab"]').forEach(tab => tab.addEventListener("click", () => {
    document.querySelectorAll('[role="tab"]').forEach(item => item.setAttribute("aria-selected", String(item === tab)));
    document.querySelectorAll(".tab-panel").forEach(panel => { panel.hidden = panel.id !== tab.dataset.tab; });
  }));
  window.addEventListener("message", event => {
    if (event.source !== elements.preview.contentWindow || event.data?.type !== "nlfilter-lab-state" ||
        event.data.token !== currentToken) return;
    const state = event.data;
    elements.domStats.textContent = `${state.anchors} videos · ${state.cacheIcons} cache icons · ${state.popups} popups`;
  });
  window.addEventListener("beforeunload", () => clearInterval(logTimer));

  initialize().catch(error => {
    elements.summary.textContent = "初期化失敗";
    elements.traces.innerHTML = `<div class="result-item error"><strong>初期化失敗</strong><small>${escapeHtml(error.message)}</small></div>`;
  });
})();
