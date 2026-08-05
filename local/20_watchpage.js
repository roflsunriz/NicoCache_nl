// - 2026-08-06.
// - 現行watchページの関連動画へ、キャッシュ品質を示すアイコンを追加する.
// - 旧PlaylistItemList/WatchRecommendation系DOMは廃止されたため扱わない.

(function() {
  "use strict";

  if (!window.NicoCache_nl || typeof NicoCache_nl.get !== "function") return;
  if (window.__ncnlWatchCacheIconsInitialized) return;
  window.__ncnlWatchCacheIconsInitialized = true;

  const itemSelector =
    '[data-anchor-page="watch"][data-anchor-href*="/watch/"][data-decoration-video-id]';
  const itemIds = new WeakMap();
  const infoCache = new Map();
  const pendingItems = new Map();
  let flushTimer = null;

  const getPreferredCacheData = function(videoInfo) {
    if (!videoInfo || !videoInfo.caches) return null;
    const preferred = videoInfo.preferredHTML5 || videoInfo.preferred;
    if (preferred && videoInfo.caches[preferred]) return videoInfo.caches[preferred];
    for (const id in videoInfo.caches) {
      if (videoInfo.caches[id] && videoInfo.caches[id].complete) {
        return videoInfo.caches[id];
      };
    };
    return null;
  };

  const getCacheClass = function(cacheData) {
    if (!cacheData || !cacheData.complete) return null;
    if (cacheData.dmc) return cacheData.economy ? "dmcEconomy" : "dmcCache";
    return cacheData.economy ? "economy" : "cache";
  };

  const removeCacheIcons = function(item) {
    item.querySelectorAll(":scope .cacheIcon").forEach(function(icon) {
      icon.remove();
    });
  };

  const insertCacheIcon = function(item, cacheData) {
    const cacheClass = getCacheClass(cacheData);
    if (!cacheClass || !item.isConnected || item.querySelector(":scope .cacheIcon")) return;
    const thumbnail = item.querySelector('a[href*="/watch/"] img[src*="/thumbnails/"]');
    if (!thumbnail) return;

    const cacheIcon = document.createElement("div");
    cacheIcon.className = "cacheIcon " + cacheClass + "IconImgMin";
    thumbnail.insertAdjacentElement("afterend", cacheIcon);
  };

  const applyInfo = function(smid, item, videoInfo) {
    if (!item.isConnected || itemIds.get(item) !== smid) return;
    insertCacheIcon(item, getPreferredCacheData(videoInfo));
  };

  const flushPendingItems = function() {
    flushTimer = null;
    const batch = new Map(pendingItems);
    pendingItems.clear();
    const smids = Array.from(batch.keys());
    if (smids.length === 0) return;

    NicoCache_nl.get("/cache/info/v2?" + smids.join(","), function(response) {
      if (!response || response.status !== 200) {
        console.warn("NicoCache_nl: 視聴ページのキャッシュ情報を取得できませんでした。");
        return;
      };

      let json;
      try {
        json = JSON.parse(response.responseText);
      } catch (error) {
        console.warn("NicoCache_nl: 視聴ページのキャッシュ情報が不正です。", error);
        return;
      };

      smids.forEach(function(smid) {
        const videoInfo = Object.prototype.hasOwnProperty.call(json, smid) ? json[smid] : null;
        infoCache.set(smid, videoInfo);
        (batch.get(smid) || []).forEach(function(item) {
          applyInfo(smid, item, videoInfo);
        });
      });
    });
  };

  const enqueueItem = function(item) {
    if (!item || !item.matches(itemSelector)) return;
    const smid = item.getAttribute("data-decoration-video-id");
    if (!smid || !/^[a-z]{2}\d+$/i.test(smid)) return;
    if (!item.querySelector('a[href*="/watch/"] img[src*="/thumbnails/"]')) return;

    const previousId = itemIds.get(item);
    if (previousId === smid && item.querySelector(":scope .cacheIcon")) return;
    if (previousId && previousId !== smid) removeCacheIcons(item);
    itemIds.set(item, smid);

    if (infoCache.has(smid)) {
      applyInfo(smid, item, infoCache.get(smid));
      return;
    };

    if (!pendingItems.has(smid)) pendingItems.set(smid, []);
    pendingItems.get(smid).push(item);
    if (flushTimer === null) flushTimer = setTimeout(flushPendingItems, 0);
  };

  const scan = function(root) {
    if (!root || (root.nodeType !== Node.ELEMENT_NODE
        && root.nodeType !== Node.DOCUMENT_NODE)) return;
    if (root.matches && root.matches(itemSelector)) enqueueItem(root);
    if (root.closest) {
      const item = root.closest(itemSelector);
      if (item) enqueueItem(item);
    };
    if (root.querySelectorAll) root.querySelectorAll(itemSelector).forEach(enqueueItem);
  };

  const start = function() {
    scan(document);
    const observer = new MutationObserver(function(mutations) {
      mutations.forEach(function(mutation) {
        if (mutation.type === "attributes") scan(mutation.target);
        mutation.addedNodes.forEach(scan);
      });
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["data-decoration-video-id", "src"],
    });
  };

  if (document.body) start();
  else document.addEventListener("DOMContentLoaded", start, {once: true});
})();
