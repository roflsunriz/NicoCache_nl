// ==UserScript==
// @name         Nicocache_nl: 動画リンクへキャッシュ状態を表示
// @match        http://www.nicovideo.jp/*
// @match        https://www.nicovideo.jp/*
// ==/UserScript==

// - version 2026-08-08
// - このファイルのライセンスはNicoCache Licenseです.
// - 2026-08-06: サムネイル幅に応じた詳細/記号キャッシュ表示の切替を復元.
// - 2026-08-06: 現行Reactカードのサムネイルへキャッシュアイコンを追加.
// - 2026-08-08: CMAF/Domandの映像モード・音声kbpsを新しいキャッシュバッジへ表示.
// - 2026-08-08: バッジ自身のDOM更新によるMutationObserver再入を抑止.
// - キャッシュ状況に応じたclassでリンクの見た目を変更.
// - HTMLと属性を監視し、SPA遷移や遅延描画後にも表示を更新する.

'use strict';

// - ページ内からAタグを探し出して動画へのリンクなら、キャッシュ状態に応じて以下のclassを
//   1つずつ追加する
// - 廃止予定class:
//     cached-v1-normal, cached-v1-economy, cached-dmc-normal, cached-dmc-economy
// - こちらに統一予定のclass:
//     nl-cached-smile-normal, nl-cached-smile-economy,
//     nl-cached-dmc-normal, nl-cached-dmc-economy

// - [func1(anchor), func2(anchor), ...]
// - anchor elementを引数にとる関数のリスト.
// - body下に追加されたanchor(aタグ)全てがこれらの関数に渡される.
window.NicocacheNLVideoAnchorHooks = window.NicocacheNLVideoAnchorHooks || [];

(() => {
  'use strict';

  if (window.__ncnlCachedLinkColorInitialized) return;
  window.__ncnlCachedLinkColorInitialized = true;

  // 自分でCSS書きたい場合は true を false へ.
  const enablePresetCSS = true;
  const cacheDisplay = window.NicoCache_nl && window.NicoCache_nl.cacheDisplay;
  if (!cacheDisplay) return;

  // css {{{
  (() => {
    if (! enablePresetCSS) {
      return;
    };
    const style = document.createElement("STYLE");
    style.innerHTML = `
.nl-cached-common {
}

:not(.VideoMediaObject-item)>.nl-cached-smile-normal:link,
.nl-cached-smile-normal:link>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-normal:link>.NC-CardTitle,
.nl-cached-smile-normal:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #C00000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-smile-normal:visited,
.nl-cached-smile-normal:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-normal:visited>.NC-CardTitle,
.nl-cached-smile-normal:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #600000}

:not(.VideoMediaObject-item)>.nl-cached-smile-economy:link,
.nl-cached-smile-economy:link>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-economy:link>.NC-CardTitle,
.nl-cached-smile-economy:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #C08000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-smile-economy:visited,
.nl-cached-smile-economy:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-economy:visited>.NC-CardTitle,
.nl-cached-smile-economy:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #603000}

:not(.VideoMediaObject-item)>.nl-cached-dmc-normal:link,
.nl-cached-dmc-normal:link>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-normal:link>.NC-CardTitle,
.nl-cached-dmc-normal:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
  { color: #C00000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-normal:visited,
.nl-cached-dmc-normal:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-normal:visited>.NC-CardTitle,
.nl-cached-dmc-normal:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
  { color: #600000}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:link,
.nl-cached-dmc-economy:link>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:link>.NC-CardTitle,
.nl-cached-dmc-economy:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
  { color: #C08000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:visited,
.nl-cached-dmc-economy:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:visited>.NC-CardTitle,
.nl-cached-dmc-economy:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
  { color: #603000}
`;
    const appendStyle = function() {
      if (document.head && !document.getElementById("nl-cached-link-color-style")) {
        style.id = "nl-cached-link-color-style";
        document.head.appendChild(style);
      };
    };
    if (document.head) {
      appendStyle();
    } else {
      document.addEventListener("DOMContentLoaded", appendStyle, {once: true});
    };
  })();
  // }}} css


  function getDougaIDByAnchor(a) {
    const pickDougaID = RegExp("^[^/]*//[^/]*/(?:watch\|shorts)/([a-z][a-z][0-9]+)[#?]?.*");
    let m = a.href.match(pickDougaID);
    if (m !== null) {
      return m[1];
    };

    for (let parent = a.parentElement;
         parent !== null && parent !== document.body;
         parent = parent.parentElement) {
      // data-content-id="sm999"
      const dcid = parent.getAttribute("data-content-id");
      if (dcid !== null && /^[a-z]{2}\d+$/i.test(dcid)) {
        return dcid;
      };
    };
    return null;
  };

  const cacheClassNames = [
    "nl-cached-common",
    "cached-v1-normal", "cached-v1-economy",
    "cached-dmc-normal", "cached-dmc-economy",
    "nl-cached-smile-normal", "nl-cached-smile-economy",
    "nl-cached-dmc-normal", "nl-cached-dmc-economy",
  ];
  const cacheIconClassNames = [
    "cacheIconImg", "economyIconImg",
    "dmcCacheIconImg", "dmcEconomyIconImg",
    "cacheIconImgMin", "economyIconImgMin",
    "dmcCacheIconImgMin", "dmcEconomyIconImgMin",
  ];
  // 94px級では記号だけ、120px以上では品質ラベルも表示する.
  const fullCacheIconMinThumbnailWidth = 120;
  const checkedAnchorIds = new WeakMap();
  const iconDescriptions = new WeakMap();
  const observedThumbnailHosts = new WeakSet();
  const infoCache = new Map();
  const pendingAnchors = new Map();
  const cacheInfoTTL = 5000;
  const maxIdsPerRequest = 100;
  let flushTimer = null;

  const applyCacheDescription = function(anchor, description) {
    cacheClassNames.forEach(function(className) {
      anchor.classList.remove(className);
    });
    if (description) cacheDisplay.applyLinkClasses(anchor, description);
    applyCacheIcon(anchor, description);
  };

  const getThumbnailWidth = function(thumbnail) {
    if (!thumbnail) return 0;
    if (typeof thumbnail.getBoundingClientRect === "function") {
      const width = thumbnail.getBoundingClientRect().width;
      if (Number.isFinite(width) && width > 0) return width;
    };
    return Number.isFinite(thumbnail.clientWidth) ? thumbnail.clientWidth : 0;
  };

  const updateCacheIcon = function(icon, description, thumbnailHost) {
    cacheIconClassNames.forEach(function(className) {
      icon.classList.remove(className);
    });
    cacheDisplay.updateIcon(
      icon,
      description,
      getThumbnailWidth(thumbnailHost) < fullCacheIconMinThumbnailWidth,
    );
    iconDescriptions.set(icon, description);
  };

  const cacheIconResizeObserver = typeof ResizeObserver === "function"
    ? new ResizeObserver(function(entries) {
        entries.forEach(function(entry) {
          const icon = entry.target.querySelector("[data-ncnl-cache-icon]");
          if (!icon) {
            cacheIconResizeObserver.unobserve(entry.target);
            observedThumbnailHosts.delete(entry.target);
            return;
          };
          const description = iconDescriptions.get(icon);
          if (description) updateCacheIcon(icon, description, entry.target);
        });
      })
    : null;

  const findThumbnailHost = function(anchor) {
    const modernHost = anchor.querySelector("[data-group]");
    if (modernHost) return modernHost;

    const thumbnail = anchor.querySelector([
      ".Thumbnail-image",
      ".NC-Thumbnail-image",
      "[data-background-image]",
      "[style*='background-image']",
      "img[src*='/thumbnails/']",
      "img[src*='smile']",
      "picture img",
      "img",
    ].join(","));
    if (!thumbnail) return null;
    if (!thumbnail.matches("img")) return thumbnail;

    return thumbnail.parentElement && thumbnail.parentElement !== anchor
      ? thumbnail.parentElement
      : anchor;
  };

  function applyCacheIcon(anchor, description) {
    const ownIcon = anchor.querySelector("[data-ncnl-cache-icon]");
    const legacyIcon = anchor.querySelector(".cacheIcon")
      || (anchor.nextElementSibling
          && anchor.nextElementSibling.classList.contains("cacheIcon")
          ? anchor.nextElementSibling : null);
    if (!description) {
      if (ownIcon) {
        if (cacheIconResizeObserver && ownIcon.parentElement) {
          cacheIconResizeObserver.unobserve(ownIcon.parentElement);
          observedThumbnailHosts.delete(ownIcon.parentElement);
        };
        ownIcon.remove();
      };
      if (legacyIcon && !legacyIcon.hasAttribute("data-ncnl-cache-icon")) legacyIcon.remove();
      return;
    };

    // 品質情報を持たない応答書き換え時のフォールバックを品質バッジへ更新する.
    if (legacyIcon && !legacyIcon.hasAttribute("data-ncnl-cache-icon")) legacyIcon.remove();

    const thumbnailHost = findThumbnailHost(anchor);
    if (!thumbnailHost) return;

    if (ownIcon && ownIcon.parentElement !== thumbnailHost) {
      if (cacheIconResizeObserver && ownIcon.parentElement) {
        cacheIconResizeObserver.unobserve(ownIcon.parentElement);
        observedThumbnailHosts.delete(ownIcon.parentElement);
      };
      ownIcon.remove();
    };
    const existingIcon = thumbnailHost.querySelector("[data-ncnl-cache-icon]");

    const icon = existingIcon || document.createElement("span");
    updateCacheIcon(icon, description, thumbnailHost);
    thumbnailHost.classList.add("ncnl-cache-thumbnail-host");
    if (!existingIcon) thumbnailHost.appendChild(icon);
    if (cacheIconResizeObserver && !observedThumbnailHosts.has(thumbnailHost)) {
      cacheIconResizeObserver.observe(thumbnailHost);
      observedThumbnailHosts.add(thumbnailHost);
    };
  };

  const requestCacheInfo = async function(ids) {
    const response = await fetch("/cache/info/v2?" + ids.join(","), {cache: "no-cache"});
    if (!response.ok) throw new Error("HTTP " + response.status);
    const json = await response.json();
    if (!json || typeof json !== "object") throw new Error("invalid JSON response");
    return json;
  };

  const flushPendingAnchors = async function() {
    flushTimer = null;
    const batch = new Map(pendingAnchors);
    pendingAnchors.clear();
    const ids = Array.from(batch.keys());

    try {
      for (let index = 0; index < ids.length; index += maxIdsPerRequest) {
        const chunk = ids.slice(index, index + maxIdsPerRequest);
        const json = await requestCacheInfo(chunk);
        const expiresAt = Date.now() + cacheInfoTTL;
        chunk.forEach(function(id) {
          const videoInfo = Object.prototype.hasOwnProperty.call(json, id) ? json[id] : null;
          infoCache.set(id, {videoInfo: videoInfo, expiresAt: expiresAt});
          const description = cacheDisplay.describe(videoInfo);
          (batch.get(id) || []).forEach(function(anchor) {
            if (anchor.isConnected && checkedAnchorIds.get(anchor) === id) {
              applyCacheDescription(anchor, description);
            };
          });
        });
      };
    } catch (error) {
      console.warn("NicoCache_nl: キャッシュ情報の一括取得に失敗しました。", error);
    };
  };

  const mightAddColorClassToAnchor = function(anchor) {
    if (anchor.getAttribute("data-nicoad-point") !== null) return;
    const id = getDougaIDByAnchor(anchor);
    if (id === null || !/^[a-z]{2}\d+$/i.test(id)) return;
    const cached = infoCache.get(id);
    if (checkedAnchorIds.get(anchor) === id
        && cached && cached.expiresAt > Date.now()) {
      // 遅延画像の追加後にもサムネイルホストを再探索する.
      applyCacheDescription(anchor, cacheDisplay.describe(cached.videoInfo));
      return;
    };

    checkedAnchorIds.set(anchor, id);
    if (cached && cached.expiresAt > Date.now()) {
      applyCacheDescription(anchor, cacheDisplay.describe(cached.videoInfo));
      return;
    };

    if (!pendingAnchors.has(id)) pendingAnchors.set(id, new Set());
    pendingAnchors.get(id).add(anchor);
    if (flushTimer === null) flushTimer = setTimeout(flushPendingAnchors, 0);
  };

  const invokeHooks = function(anchor) {
    window.NicocacheNLVideoAnchorHooks.forEach(function(hook) {
      Promise.resolve(hook(anchor)).catch(function(error) {
        console.warn("NicoCache_nl: 動画リンク処理に失敗しました。", error);
      });
    });
  };

  const collectAnchors = function(node, anchors) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) return;
    // 自分で生成したバッジの子要素を再処理すると、DOM再構築が監視へ戻る。
    if (node.closest && node.closest("[data-ncnl-cache-icon]")) return;
    if (node.matches("a")) anchors.add(node);
    else if (node.closest) {
      const ownerAnchor = node.closest("a");
      if (ownerAnchor) anchors.add(ownerAnchor);
    };
    node.querySelectorAll("a").forEach(function(anchor) { anchors.add(anchor); });
  };

  const start = function() {
    window.NicocacheNLVideoAnchorHooks.push(mightAddColorClassToAnchor);
    document.querySelectorAll("a").forEach(invokeHooks);
    const observer = new MutationObserver(function(mutationRecords) {
      const anchors = new Set();
      mutationRecords.forEach(function(record) {
        if (record.type === "attributes") collectAnchors(record.target, anchors);
        record.addedNodes.forEach(function(node) { collectAnchors(node, anchors); });
      });
      anchors.forEach(invokeHooks);
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["href", "data-anchor-href", "data-content-id", "src"],
    });
  };

  if (document.body) {
    start();
  } else {
    document.addEventListener("DOMContentLoaded", start, {once: true});
  };
})();
