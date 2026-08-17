// - 2026-08-08.
// - 現行watchページの関連動画へ、CMAF/Domand品質を示すキャッシュバッジを追加する.
// - サムネイル幅に応じて品質ラベル付き/記号だけの表示を切り替える.
// - バッジ自身のDOM更新を監視対象から除外し、同一バッチの項目を重複処理しない.
// - 旧PlaylistItemList/WatchRecommendation系DOMは廃止されたため扱わない.

(function() {
  "use strict";

  if (!window.NicoCache_nl || typeof fetch !== "function") return;
  if (!NicoCache_nl.cacheDisplay) return;
  if (window.__ncnlWatchCacheIconsInitialized) return;
  window.__ncnlWatchCacheIconsInitialized = true;

  const itemSelector =
    '[data-anchor-page="watch"][data-anchor-href*="/watch/"][data-decoration-video-id]';
  const itemIds = new WeakMap();
  const infoCache = new Map();
  const pendingItems = new Map();
  const cacheIconThumbnails = new WeakMap();
  const thumbnailCacheIcons = new WeakMap();
  const cacheIconDescriptions = new WeakMap();
  const observedThumbnails = new WeakSet();
  const fullCacheIconMinThumbnailWidth = 120;
  let flushTimer = null;

  const getThumbnailWidth = function(thumbnail) {
    if (!thumbnail) return 0;
    if (typeof thumbnail.getBoundingClientRect === "function") {
      const width = thumbnail.getBoundingClientRect().width;
      if (Number.isFinite(width) && width > 0) return width;
    };
    return Number.isFinite(thumbnail.clientWidth) ? thumbnail.clientWidth : 0;
  };

  const cacheIconResizeObserver = typeof ResizeObserver === "function"
    ? new ResizeObserver(function(entries) {
        entries.forEach(function(entry) {
          const icon = thumbnailCacheIcons.get(entry.target);
          if (!icon || !icon.isConnected) {
            cacheIconResizeObserver.unobserve(entry.target);
            observedThumbnails.delete(entry.target);
            return;
          };
          const description = cacheIconDescriptions.get(icon);
          if (!description) return;
          NicoCache_nl.cacheDisplay.updateIcon(
            icon,
            description,
            getThumbnailWidth(entry.target) < fullCacheIconMinThumbnailWidth,
          );
        });
      })
    : null;

  const removeCacheIcons = function(item) {
    item.querySelectorAll(":scope .cacheIcon").forEach(function(icon) {
      const thumbnail = cacheIconThumbnails.get(icon);
      if (thumbnail) {
        thumbnailCacheIcons.delete(thumbnail);
        if (cacheIconResizeObserver) cacheIconResizeObserver.unobserve(thumbnail);
        observedThumbnails.delete(thumbnail);
      };
      icon.remove();
    });
  };

  const insertCacheIcon = function(item, videoInfo) {
    const description = NicoCache_nl.cacheDisplay.describe(videoInfo);
    if (!description || !item.isConnected) return;
    const thumbnail = item.querySelector('a[href*="/watch/"] img[src*="/thumbnails/"]');
    if (!thumbnail) return;

    const existing = item.querySelector(":scope [data-ncnl-cache-icon]");
    const cacheIcon = existing || document.createElement("span");
    NicoCache_nl.cacheDisplay.updateIcon(
      cacheIcon,
      description,
      getThumbnailWidth(thumbnail) < fullCacheIconMinThumbnailWidth,
    );
    cacheIconDescriptions.set(cacheIcon, description);
    if (!existing) thumbnail.insertAdjacentElement("afterend", cacheIcon);
    cacheIconThumbnails.set(cacheIcon, thumbnail);
    thumbnailCacheIcons.set(thumbnail, cacheIcon);
    if (cacheIconResizeObserver && !observedThumbnails.has(thumbnail)) {
      cacheIconResizeObserver.observe(thumbnail);
      observedThumbnails.add(thumbnail);
    };
  };

  const applyInfo = function(smid, item, videoInfo) {
    if (!item.isConnected || itemIds.get(item) !== smid) return;
    insertCacheIcon(item, videoInfo);
  };

  const flushPendingItems = async function() {
    flushTimer = null;
    const batch = new Map(pendingItems);
    pendingItems.clear();
    const smids = Array.from(batch.keys());
    if (smids.length === 0) return;

    try {
      const response = await fetch("https://nicocachenl.test/api/v1/cache-entry-queries", {
        method: "POST",
        cache: "no-store",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({videoIds: smids})
      });
      if (!response.ok) {
        console.warn("NicoCache_nl: 視聴ページのキャッシュ情報を取得できませんでした。");
        return;
      };

      let json;
      try {
        json = await response.json();
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
    } catch (error) {
      console.warn("NicoCache_nl: 視聴ページのキャッシュ情報を取得できませんでした。", error);
    };
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

  const collectItems = function(root, items) {
    if (!root || (root.nodeType !== Node.ELEMENT_NODE
        && root.nodeType !== Node.DOCUMENT_NODE)) return;
    if (root.closest && root.closest("[data-ncnl-cache-icon]")) return;
    if (root.matches && root.matches(itemSelector)) items.add(root);
    if (root.closest) {
      const item = root.closest(itemSelector);
      if (item) items.add(item);
    };
    if (root.querySelectorAll) {
      root.querySelectorAll(itemSelector).forEach(function(item) { items.add(item); });
    };
  };

  const scan = function(root) {
    const items = new Set();
    collectItems(root, items);
    items.forEach(enqueueItem);
  };

  const start = function() {
    scan(document);
    const observer = new MutationObserver(function(mutations) {
      const items = new Set();
      mutations.forEach(function(mutation) {
        if (mutation.type === "attributes") collectItems(mutation.target, items);
        mutation.addedNodes.forEach(function(node) { collectItems(node, items); });
      });
      items.forEach(enqueueItem);
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
