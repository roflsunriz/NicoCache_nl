(() => {
  "use strict";
  const token = location.pathname.split("/").pop();

  function addToken(url) {
    const value = String(url);
    if (!value.startsWith("/cache/")) return value;
    return `${value}${value.includes("?") ? "&" : "?"}__nlftoken=${encodeURIComponent(token)}`;
  }

  if (window.fetch) {
    const originalFetch = window.fetch.bind(window);
    window.fetch = function(input, init) {
      if (typeof input === "string") return originalFetch(addToken(input), init);
      if (input instanceof URL) return originalFetch(new URL(addToken(input.pathname + input.search), location.origin), init);
      if (input instanceof Request && new URL(input.url).pathname.startsWith("/cache/")) {
        return originalFetch(new Request(addToken(new URL(input.url).pathname + new URL(input.url).search), input), init);
      }
      return originalFetch(input, init);
    };
  }

  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    const args = Array.from(arguments);
    args[1] = addToken(url);
    return originalOpen.apply(this, args);
  };

  function serialize(value) {
    if (value instanceof Error) return `${value.name}: ${value.message}`;
    if (typeof value === "string") return value;
    try { return JSON.stringify(value); } catch (_) { return String(value); }
  }

  function report(level, values) {
    const body = new URLSearchParams({ token, level, message: values.map(serialize).join(" ") });
    fetch("/api/client-log", { method: "POST", body }).catch(() => {});
  }

  for (const level of ["log", "warn", "error"]) {
    const original = console[level].bind(console);
    console[level] = function() {
      const values = Array.from(arguments);
      report(level, values);
      original(...values);
    };
  }
  window.addEventListener("error", event => report("error", [event.message, `${event.filename}:${event.lineno}`]));
  window.addEventListener("unhandledrejection", event => report("error", ["Unhandled rejection", event.reason]));
  document.addEventListener("click", event => {
    const anchor = event.target?.closest?.("a[href]");
    if (anchor) event.preventDefault();
  }, true);

  function notifyState() {
    parent.postMessage({
      type: "nlfilter-lab-state",
      token,
      anchors: document.querySelectorAll('a[href*="/watch/"]').length,
      cacheIcons: document.querySelectorAll(".cacheIcon").length,
      popups: document.querySelectorAll("[data-ncnl-pop]").length
    }, "*");
  }

  function addSpaCard() {
    const target = document.querySelector("[data-lab-spa-target]") || document.body;
    const index = target.querySelectorAll("[data-lab-spa-added]").length + 10;
    const card = document.createElement("article");
    card.dataset.labSpaAdded = "";
    card.className = "video-card";
    const anime = document.documentElement.dataset.fixture === "anime";
    card.innerHTML = `<a ${anime ? `href="https://www.nicovideo.jp/watch/sm${index}"` : `data-anchor-area="main" href="/watch/sm${index}"`}>
      <img src="/thumbnails/sm${index}.svg" alt=""><span>SPAで追加した動画 sm${index}</span></a>`;
    target.append(card);
    setTimeout(notifyState, 80);
  }

  window.addEventListener("message", event => {
    if (event.data?.type === "nlfilter-lab" && event.data.action === "spa-add") addSpaCard();
  });
  document.addEventListener("DOMContentLoaded", () => {
    const spaAdd = Math.min(20, Math.max(0, Number.parseInt(new URLSearchParams(location.search).get("spaAdd") || "0", 10) || 0));
    for (let index = 0; index < spaAdd; index++) addSpaCard();
    notifyState();
    new MutationObserver(() => setTimeout(notifyState, 0)).observe(document.body, { childList: true, subtree: true });
  });
})();
