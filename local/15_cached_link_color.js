// ==UserScript==
// @name         Nicocache_nl: 動画リンクへキャッシュ状態を表示
// @match        http://www.nicovideo.jp/*
// @match        https://www.nicovideo.jp/*
// ==/UserScript==

// - version 2026-08-06
// - このファイルのライセンスはNicoCache Licenseです.
// - 2026-08-06: サムネイル幅に応じたフル/省略キャッシュアイコンの切替を復元.
// - 2026-08-06: 現行Reactカードのサムネイルへキャッシュアイコンを追加.
// - キャッシュ状況に応じた品質classでリンクの見た目を変更.
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
{ color: #008000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-normal:visited,
.nl-cached-dmc-normal:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-normal:visited>.NC-CardTitle,
.nl-cached-dmc-normal:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #006000}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:link,
.nl-cached-dmc-economy:link>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:link>.NC-CardTitle,
.nl-cached-dmc-economy:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #808000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:visited,
.nl-cached-dmc-economy:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:visited>.NC-CardTitle,
.nl-cached-dmc-economy:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #606000}
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
  // 既存表示では94px級だけをCアイコン、120px以上をフルアイコンにしている.
  const fullCacheIconMinThumbnailWidth = 120;
  const checkedAnchorIds = new WeakMap();
  const infoCache = new Map();
  const pendingAnchors = new Map();
  const cacheInfoTTL = 5000;
  const maxIdsPerRequest = 100;
  let flushTimer = null;

  const getCachePoint = function(videoInfo) {
    if (!videoInfo || !videoInfo.caches) return 0;
    let cachePoint = 0; // 0:cacheなし, 1:旧エコ, 2:dmcエコ, 3:旧普通, 4:dmc普通
    for (const cacheName in videoInfo.caches) {
      const cacheInfo = videoInfo.caches[cacheName];
      if (cacheInfo.complete) {
        let point = 0;
        if (cacheInfo.dmc && cacheInfo.economy) {
          point = 2;
        } else if (cacheInfo.dmc && !cacheInfo.economy) {
          point = 4;
        } else if (!cacheInfo.dmc && cacheInfo.economy) {
          point = 1;
        } else if (!cacheInfo.dmc && !cacheInfo.economy) {
          point = 3;
        };
        cachePoint = Math.max(point, cachePoint);
      };
    };
    return cachePoint;
  };

  const applyCachePoint = function(anchor, cachePoint) {
    cacheClassNames.forEach(function(className) {
      anchor.classList.remove(className);
    });

    // "nl-"なしのclassは既存の利用者向け互換性のため残す.
    if (1 === cachePoint) {
      anchor.classList.add("nl-cached-common", "cached-v1-economy", "nl-cached-smile-economy");
    } else if (2 === cachePoint) {
      anchor.classList.add("nl-cached-common", "cached-dmc-economy", "nl-cached-dmc-economy");
    } else if (3 === cachePoint) {
      anchor.classList.add("nl-cached-common", "cached-v1-normal", "nl-cached-smile-normal");
    } else if (4 === cachePoint) {
      anchor.classList.add("nl-cached-common", "cached-dmc-normal", "nl-cached-dmc-normal");
    };

    applyCacheIcon(anchor, cachePoint);
  };

  const getThumbnailWidth = function(thumbnail) {
    if (!thumbnail) return 0;
    if (typeof thumbnail.getBoundingClientRect === "function") {
      const width = thumbnail.getBoundingClientRect().width;
      if (Number.isFinite(width) && width > 0) return width;
    };
    return Number.isFinite(thumbnail.clientWidth) ? thumbnail.clientWidth : 0;
  };

  const getCacheIconClass = function(cachePoint, thumbnail) {
    const suffix = getThumbnailWidth(thumbnail) >= fullCacheIconMinThumbnailWidth
      ? "IconImg" : "IconImgMin";
    if (1 === cachePoint) return "economy" + suffix;
    if (2 === cachePoint) return "dmcEconomy" + suffix;
    if (3 === cachePoint) return "cache" + suffix;
    if (4 === cachePoint) return "dmcCache" + suffix;
    return null;
  };

  const updateCacheIconClass = function(icon, cachePoint, thumbnailHost) {
    const iconClass = getCacheIconClass(cachePoint, thumbnailHost);
    cacheIconClassNames.forEach(function(className) {
      icon.classList.remove(className);
    });
    if (iconClass) icon.classList.add(iconClass);
  };

  const cacheIconResizeObserver = typeof ResizeObserver === "function"
    ? new ResizeObserver(function(entries) {
        entries.forEach(function(entry) {
          const icon = entry.target.querySelector("[data-ncnl-cache-icon]");
          if (!icon) {
            cacheIconResizeObserver.unobserve(entry.target);
            return;
          };
          updateCacheIconClass(
            icon,
            Number(icon.getAttribute("data-ncnl-cache-point")),
            entry.target,
          );
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

  function applyCacheIcon(anchor, cachePoint) {
    const ownIcon = anchor.querySelector("[data-ncnl-cache-icon]");
    if (!getCacheIconClass(cachePoint, null)) {
      if (ownIcon) {
        if (cacheIconResizeObserver && ownIcon.parentElement) {
          cacheIconResizeObserver.unobserve(ownIcon.parentElement);
        };
        ownIcon.remove();
      };
      return;
    };

    // 応答HTMLを書き換える従来のnlFilterが追加したアイコンを優先する.
    const legacyIcon = anchor.querySelector(".cacheIcon")
      || (anchor.nextElementSibling
          && anchor.nextElementSibling.classList.contains("cacheIcon")
          ? anchor.nextElementSibling : null);
    if (legacyIcon && !legacyIcon.hasAttribute("data-ncnl-cache-icon")) return;

    const thumbnailHost = findThumbnailHost(anchor);
    if (!thumbnailHost) return;

    if (ownIcon && ownIcon.parentElement !== thumbnailHost) {
      if (cacheIconResizeObserver && ownIcon.parentElement) {
        cacheIconResizeObserver.unobserve(ownIcon.parentElement);
      };
      ownIcon.remove();
    };
    const existingIcon = thumbnailHost.querySelector("[data-ncnl-cache-icon]");

    const icon = existingIcon || document.createElement("span");
    updateCacheIconClass(icon, cachePoint, thumbnailHost);
    icon.classList.add("cacheIcon", "ncnl-cache-icon");
    icon.setAttribute("data-ncnl-cache-icon", "");
    icon.setAttribute("data-ncnl-cache-point", String(cachePoint));
    icon.setAttribute("aria-hidden", "true");
    thumbnailHost.classList.add("ncnl-cache-thumbnail-host");
    if (!existingIcon) thumbnailHost.appendChild(icon);
    if (cacheIconResizeObserver) cacheIconResizeObserver.observe(thumbnailHost);
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
          const cachePoint = getCachePoint(videoInfo);
          (batch.get(id) || []).forEach(function(anchor) {
            if (anchor.isConnected && checkedAnchorIds.get(anchor) === id) {
              applyCachePoint(anchor, cachePoint);
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
      applyCachePoint(anchor, getCachePoint(cached.videoInfo));
      return;
    };

    checkedAnchorIds.set(anchor, id);
    if (cached && cached.expiresAt > Date.now()) {
      applyCachePoint(anchor, getCachePoint(cached.videoInfo));
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

  const processAddedNode = function(node) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) return;
    if (node.matches("a")) invokeHooks(node);
    else if (node.closest) {
      const ownerAnchor = node.closest("a");
      if (ownerAnchor) invokeHooks(ownerAnchor);
    };
    node.querySelectorAll("a").forEach(invokeHooks);
  };

  const start = function() {
    window.NicocacheNLVideoAnchorHooks.push(mightAddColorClassToAnchor);
    document.querySelectorAll("a").forEach(invokeHooks);
    const observer = new MutationObserver(function(mutationRecords) {
      mutationRecords.forEach(function(record) {
        if (record.type === "attributes") processAddedNode(record.target);
        record.addedNodes.forEach(processAddedNode);
      });
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
