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
      popups: document.querySelectorAll('a[href*="/watch/"], a[data-anchor-href*="/watch/"]').length
    }, "*");
  }

  function addSpaCard() {
    const target = document.querySelector("[data-lab-spa-target]") || document.body;
    const index = target.querySelectorAll("[data-lab-spa-added]").length + 10;
    const card = document.createElement("article");
    card.dataset.labSpaAdded = "";
    const fixture = document.documentElement.dataset.fixture;
    const anime = fixture === "anime";
    if (fixture === "watch") {
      card.className = "video-card Pressable";
      card.dataset.anchorPage = "watch";
      card.dataset.anchorHref = `/watch/sm${index}`;
      card.dataset.decorationVideoId = `sm${index}`;
      card.innerHTML = `<a href="/watch/sm${index}"><img src="/thumbnails/sm${index}.svg" alt=""></a>
        <a href="/watch/sm${index}">SPAで追加した動画 sm${index}</a>`;
    } else {
      card.className = "video-card";
      card.innerHTML = `<a ${anime ? `href="https://www.nicovideo.jp/watch/sm${index}"` : `data-anchor-area="main" href="/watch/sm${index}"`}>
        <img src="/thumbnails/sm${index}.svg" alt=""><span>SPAで追加した動画 sm${index}</span></a>`;
    };
    target.append(card);
    setTimeout(notifyState, 80);
  }

  function wait(milliseconds) {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
  }

  function hover(anchor) {
    anchor.dispatchEvent(new MouseEvent("mouseover", {
      bubbles: true,
      clientX: Math.round(anchor.getBoundingClientRect().left + 8),
      clientY: Math.round(anchor.getBoundingClientRect().top + 8)
    }));
  }

  function leave(anchor) {
    anchor.dispatchEvent(new MouseEvent("mouseout", {
      bubbles: true,
      relatedTarget: document.body
    }));
  }

  async function runPopThumbProbe() {
    const anchors = Array.from(document.querySelectorAll('a[href*="/watch/"]'))
      .filter(anchor => !anchor.hasAttribute("onmouseenter"));
    if (anchors.length < 3 || typeof window.popThumb2 !== "function") {
      document.documentElement.dataset.popthumbProbeStatus = "unavailable";
      return;
    }

    window.__ncnlPopThumbTestUrl = (domain, path) =>
      `/thumbnails/sm9.svg?probe=${encodeURIComponent(`${domain}/${path}`)}`;

    hover(anchors[0]);
    await wait(180);
    const afterFirst = {...window.__ncnlOverlibMetrics};
    leave(anchors[0]);

    hover(anchors[0]);
    await wait(180);
    const afterRepeat = {...window.__ncnlOverlibMetrics};
    leave(anchors[0]);

    hover(anchors[2]);
    leave(anchors[2]);
    await wait(180);
    const afterCancelled = {...window.__ncnlOverlibMetrics};

    hover(anchors[1]);
    await wait(180);
    const afterSecondId = {...window.__ncnlOverlibMetrics};

    for (let index = 0; index < 11; index++) {
      window.popThumb2(`sm${100 + index}`, 0, 0, anchors[1], {clientX: 20, clientY: 20});
    }
    await wait(80);
    const afterLruLimit = {...window.__ncnlOverlibMetrics};
    const retainedPanels = document.querySelectorAll("#overDiv .ncnl-overlib-panel").length;

    const passed =
      afterFirst.entriesCreated === 1 && afterFirst.framesCreated === 1 &&
      afterRepeat.entriesCreated === 1 && afterRepeat.framesCreated === 1 && afterRepeat.entriesReused === 1 &&
      afterCancelled.entriesCreated === 1 && afterCancelled.framesCreated === 1 && afterCancelled.cancelled >= 1 &&
      afterSecondId.entriesCreated === 2 && afterSecondId.framesCreated === 2 &&
      afterLruLimit.entriesCreated === 13 && afterLruLimit.entriesEvicted === 1 && retainedPanels === 12;

    document.documentElement.dataset.popthumbProbeStatus = passed ? "passed" : "failed";
    document.documentElement.dataset.popthumbProbeMetrics = JSON.stringify({
      afterFirst,
      afterRepeat,
      afterSecondId,
      afterCancelled,
      afterLruLimit,
      retainedPanels,
      popThumb: window.__ncnlPopThumbMetrics
    });
    window.__ncnlPopThumbTestUrl = undefined;
    if (!passed) console.error("popThumb performance probe failed", document.documentElement.dataset.popthumbProbeMetrics);
  }

  window.addEventListener("message", event => {
    if (event.data?.type === "nlfilter-lab" && event.data.action === "spa-add") addSpaCard();
  });
  document.addEventListener("DOMContentLoaded", () => {
    const parameters = new URLSearchParams(location.search);
    const spaAdd = Math.min(20, Math.max(0, Number.parseInt(parameters.get("spaAdd") || "0", 10) || 0));
    for (let index = 0; index < spaAdd; index++) addSpaCard();
    notifyState();
    new MutationObserver(() => setTimeout(notifyState, 0)).observe(document.body, { childList: true, subtree: true });
    if (parameters.get("popThumbProbe") === "true") runPopThumbProbe();
  });
})();
