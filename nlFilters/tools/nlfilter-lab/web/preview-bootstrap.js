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

  async function runCacheMenuProbe() {
    await wait(160);
    const failures = [];
    const check = (condition, message) => { if (!condition) failures.push(message); };
    let menu = document.querySelector("#ncnl_common_header_menu");
    check(Boolean(menu), "NicoCacheメニューがありません");
    if (!menu) {
      document.documentElement.dataset.cacheMenuProbeStatus = "failed";
      console.error("cache menu probe failed", JSON.stringify(failures));
      return;
    }

    let trigger = menu.querySelector(".ncnl-common-header-trigger");
    let popover = menu.querySelector(".ncnl-common-header-popover");
    const item = action => menu.querySelector(`[data-ncnl-action="${action}"]`);
    const pathname = element => element ? new URL(element.href, location.href).pathname : "";
    const isVisuallyBetween = (element, before, after) => {
      if (!element || !before || !after) return false;
      const elementRect = element.getBoundingClientRect();
      const beforeRect = before.getBoundingClientRect();
      const afterRect = after.getBoundingClientRect();
      return elementRect.left >= beforeRect.right - 1 && elementRect.right <= afterRect.left + 1;
    };
    const notification = () => document.querySelector("[data-lab-notification]");
    const account = () => document.querySelector("[data-lab-account-menu]");
    check(menu.dataset.ncnlMounted === "account", "公式アカウントナビに配置されていません");
    check(menu.parentElement === document.body, "公式Reactの管理外へ配置されていません");
    check(isVisuallyBetween(menu, notification(), account()),
      "通知とアカウントメニューの間に表示されていません");
    check(menu.querySelectorAll('[role="menuitem"]').length === 5, "メニュー項目が5件ではありません");
    check(pathname(item("movie")) === "/cache/sm9/auto/movie", "動画保存URLが不正です");
    check(pathname(item("comments")) === "/cache/sm9.comments.json", "コメント保存URLが不正です");
    check(pathname(item("audio")) === "/cache/sm9/auto/audio", "音声保存URLが不正です");
    check(pathname(item("manage")) === "/cache/", "キャッシュ画面URLが不正です");
    check(item("manage") && !item("manage").hasAttribute("download"), "キャッシュ画面リンクがdownload扱いです");
    check(popover?.getAttribute("aria-hidden") === "true", "初期状態でメニューが閉じていません");

    const menuBeforeTimelineHover = menu;
    const accountNavigation = document.querySelector("[data-lab-account-nav]");
    const officialAccountControls = accountNavigation && Array.from(accountNavigation.children)
      .filter(element => element.id !== "ncnl_common_header_menu")
      .map(element => element.cloneNode(true));
    accountNavigation?.querySelector("[data-lab-timeline]")
      ?.dispatchEvent(new MouseEvent("mouseenter"));
    if (officialAccountControls) accountNavigation.replaceChildren(...officialAccountControls);
    await wait(160);
    menu = document.querySelector("#ncnl_common_header_menu");
    trigger = menu?.querySelector(".ncnl-common-header-trigger");
    popover = menu?.querySelector(".ncnl-common-header-popover");
    check(menu === menuBeforeTimelineHover,
      "フォロー新着の再描画でNicoCacheメニューが一度DOMから除去されました");
    check(document.querySelectorAll("#ncnl_common_header_menu").length === 1,
      "フォロー新着の再描画後のメニュー数が不正です");
    check(isVisuallyBetween(menu, notification(), account()),
      "フォロー新着の再描画後に通知とアカウントメニューの間へ復帰しません");

    menu.dispatchEvent(new MouseEvent("mouseenter"));
    check(trigger?.getAttribute("aria-expanded") === "true", "ホバーでメニューが開きません");
    check(popover?.getAttribute("aria-hidden") === "false", "ホバー後もpopoverが非表示です");
    const openPopoverRect = popover?.getBoundingClientRect();
    check(openPopoverRect && openPopoverRect.left >= -1 && openPopoverRect.right <= innerWidth + 1,
      "popoverがビューポートの左右からはみ出しています");
    menu.dispatchEvent(new MouseEvent("mouseleave"));
    check(trigger?.getAttribute("aria-expanded") === "false", "マウス退出でメニューが閉じません");

    trigger?.focus();
    check(trigger?.getAttribute("aria-expanded") === "true", "フォーカスでメニューが開きません");
    trigger?.dispatchEvent(new KeyboardEvent("keydown", {key: "ArrowDown", bubbles: true}));
    check(document.activeElement === item("movie"), "下矢印で先頭項目へ移動しません");
    document.activeElement?.dispatchEvent(new KeyboardEvent("keydown", {key: "ArrowDown", bubbles: true}));
    check(document.activeElement === item("comments"), "下矢印で次項目へ移動しません");
    document.activeElement?.dispatchEvent(new KeyboardEvent("keydown", {key: "ArrowUp", bubbles: true}));
    check(document.activeElement === item("movie"), "上矢印で前項目へ移動しません");
    document.activeElement?.dispatchEvent(new KeyboardEvent("keydown", {key: "End", bubbles: true}));
    check(document.activeElement === item("manage"), "Endで末尾項目へ移動しません");
    document.activeElement?.dispatchEvent(new KeyboardEvent("keydown", {key: "Home", bubbles: true}));
    check(document.activeElement === item("movie"), "Homeで先頭項目へ移動しません");
    document.activeElement?.dispatchEvent(new KeyboardEvent("keydown", {key: "Escape", bubbles: true}));
    check(trigger?.getAttribute("aria-expanded") === "false", "Escapeでメニューが閉じません");
    check(document.activeElement === trigger, "Escapeでトリガーへ戻りません");
    trigger?.blur();
    await wait(0);

    trigger?.dispatchEvent(new PointerEvent("pointerdown", {bubbles: true}));
    trigger?.dispatchEvent(new MouseEvent("click", {bubbles: true, detail: 1}));
    check(trigger?.getAttribute("aria-expanded") === "true", "クリックでメニューが開きません");
    trigger?.dispatchEvent(new PointerEvent("pointerdown", {bubbles: true}));
    trigger?.dispatchEvent(new MouseEvent("click", {bubbles: true, detail: 1}));
    check(trigger?.getAttribute("aria-expanded") === "false", "再クリックでメニューが閉じません");

    let confirms = 0;
    let mutationRequests = 0;
    const originalConfirm = window.confirm;
    const originalGet = window.NicoCache_nl?.get;
    window.confirm = () => { confirms++; return false; };
    if (window.NicoCache_nl) window.NicoCache_nl.get = () => { mutationRequests++; };
    item("remove")?.click();
    window.confirm = originalConfirm;
    if (window.NicoCache_nl) window.NicoCache_nl.get = originalGet;
    check(confirms === 1 && mutationRequests === 0, "削除キャンセル時に変更APIを呼び出しました");

    const originalUrl = location.href;
    history.pushState({}, "", "/watch/sm83");
    dispatchEvent(new PopStateEvent("popstate"));
    await wait(40);
    check(pathname(item("movie")) === "/cache/sm83/auto/movie", "SPA動画切替へ追従しません");
    history.replaceState({}, "", originalUrl);
    dispatchEvent(new PopStateEvent("popstate"));
    await wait(40);

    const root = document.querySelector("#CommonHeader .nico-CommonHeaderRoot");
    const replacement = root?.cloneNode(true);
    replacement?.querySelector("#ncnl_common_header_menu")?.remove();
    root?.replaceWith(replacement);
    await wait(160);
    menu = document.querySelector("#ncnl_common_header_menu");
    trigger = menu?.querySelector(".ncnl-common-header-trigger");
    popover = menu?.querySelector(".ncnl-common-header-popover");
    check(document.querySelectorAll("#ncnl_common_header_menu").length === 1, "ヘッダー再描画後のメニュー数が不正です");
    check(menu?.dataset.ncnlMounted === "account", "ヘッダー再描画後に公式アカウントナビへ復帰しません");
    check(menu?.parentElement === document.body, "ヘッダー再描画後に公式Reactの管理外へ復帰しません");
    check(isVisuallyBetween(menu, notification(), account()),
      "ヘッダー再描画後に通知とアカウントメニューの間へ復帰しません");
    menu?.dispatchEvent(new MouseEvent("mouseenter"));
    check(trigger?.getAttribute("aria-expanded") === "true" && popover?.getAttribute("aria-hidden") === "false",
      "ヘッダー再描画後のメニュー操作が無効です");

    const passed = failures.length === 0;
    document.documentElement.dataset.cacheMenuProbeStatus = passed ? "passed" : "failed";
    document.documentElement.dataset.cacheMenuProbeMetrics = JSON.stringify({failures});
    if (!passed) console.error("cache menu probe failed", document.documentElement.dataset.cacheMenuProbeMetrics);
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
    if (parameters.get("cacheMenuProbe") === "true") runCacheMenuProbe();
  });
})();
