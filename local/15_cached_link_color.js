// ==UserScript==
// @name         Nicocache_nl: キャッシュ済み動画へのリンクに品質classを追加
// @match        http://www.nicovideo.jp/*
// @match        https://www.nicovideo.jp/*
// ==/UserScript==

// - version 2026-08-06
// - このファイルのライセンスはNicoCache Licenseです.
// - リンク色をキャッシュ状況に応じてキャッシュ品質classを追加。
// - 品質classによってリンクの見た目を変更.
// - HTMLを監視して、変化をキャッチして順次に品質classを追加する.

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
    if (checkedAnchorIds.get(anchor) === id) return;

    checkedAnchorIds.set(anchor, id);
    const cached = infoCache.get(id);
    if (cached && cached.expiresAt > Date.now()) {
      applyCachePoint(anchor, getCachePoint(cached.videoInfo));
      return;
    };

    if (!pendingAnchors.has(id)) pendingAnchors.set(id, []);
    pendingAnchors.get(id).push(anchor);
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
    node.querySelectorAll("a").forEach(invokeHooks);
  };

  const start = function() {
    window.NicocacheNLVideoAnchorHooks.push(mightAddColorClassToAnchor);
    document.querySelectorAll("a").forEach(invokeHooks);
    const observer = new MutationObserver(function(mutationRecords) {
      mutationRecords.forEach(function(record) {
        record.addedNodes.forEach(processAddedNode);
      });
    });
    observer.observe(document.body, {childList: true, subtree: true});
  };

  if (document.body) {
    start();
  } else {
    document.addEventListener("DOMContentLoaded", start, {once: true});
  };
})();
