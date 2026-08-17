// NicoCache_nl cache display shared by list pages and the watch page.
// CMAF/Domand cache details come from the NicoCache REST API.
(function() {
  "use strict";

  window.NicoCache_nl = window.NicoCache_nl || {};
  if (NicoCache_nl.cacheDisplay) return;

  const SVG_NS = "http://www.w3.org/2000/svg";
  const iconClassNames = [
    "ncnl-cache-quality-uhd", "ncnl-cache-quality-fhd",
    "ncnl-cache-quality-hd", "ncnl-cache-quality-sd",
    "ncnl-cache-quality-low", "ncnl-cache-quality-legacy",
    "ncnl-cache-icon--compact",
  ];

  const asPositiveNumber = function(value) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? number : 0;
  };

  const getHeight = function(videoMode) {
    const match = String(videoMode || "").match(/^(\d+)p?/i);
    return match ? Number(match[1]) : 0;
  };

  const getQualityClass = function(videoMode) {
    const height = getHeight(videoMode);
    if (height >= 2160) return "uhd";
    if (height >= 1080) return "fhd";
    if (height >= 720) return "hd";
    if (height >= 480) return "sd";
    if (height > 0) return "low";
    return "legacy";
  };

  const completedCache = function(videoInfo, cacheId) {
    if (!videoInfo || !videoInfo.caches || !cacheId) return null;
    const cacheData = videoInfo.caches[cacheId];
    return cacheData && cacheData.complete ? {cacheId: cacheId, cacheData: cacheData} : null;
  };

  const selectCache = function(videoInfo) {
    if (!videoInfo || !videoInfo.caches) return null;

    const preferred = completedCache(videoInfo, videoInfo.preferred);
    if (preferred) return preferred;

    const completeIds = Array.isArray(videoInfo.completes) ? videoInfo.completes : [];
    for (const cacheId of completeIds) {
      const selected = completedCache(videoInfo, cacheId);
      if (selected) return selected;
    };
    for (const cacheId in videoInfo.caches) {
      const selected = completedCache(videoInfo, cacheId);
      if (selected) return selected;
    }
    return null;
  };

  const describe = function(videoInfo) {
    const selected = selectCache(videoInfo);
    if (!selected) return null;

    const cacheData = selected.cacheData;
    const isCmaf = typeof cacheData.videoMode === "string";
    const videoMode = isCmaf ? cacheData.videoMode : "";
    const audioBitrate = isCmaf ? asPositiveNumber(cacheData.audioBitrate) : 0;
    const quality = isCmaf ? getQualityClass(videoMode) : "legacy";

    const details = [];
    if (videoMode) details.push("映像 " + videoMode);
    if (audioBitrate) details.push("音声 " + audioBitrate + "kbps");
    if (!details.length) details.push("CMAF/Domand");

    return {
      cacheId: selected.cacheId,
      cacheData: cacheData,
      isCmaf: isCmaf,
      videoMode: videoMode,
      audioBitrate: audioBitrate,
      quality: quality,
      title: "NicoCache_nl キャッシュ済み: " + details.join(" / "),
    };
  };

  const createMark = function() {
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("class", "ncnl-cache-mark");
    svg.setAttribute("viewBox", "0 0 18 18");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", "M3 3.5h12v4H3zM3 10.5h12v4H3zM5.2 5.5h.1M5.2 12.5h.1m3-1 1.4 1.4 3.1-3.1");
    svg.appendChild(path);
    return svg;
  };

  const replaceLabel = function(icon, description) {
    icon.replaceChildren(createMark());
    const label = document.createElement("span");
    label.className = "ncnl-cache-quality-label";
    if (description.isCmaf) {
      const video = document.createElement("span");
      video.className = "ncnl-cache-video";
      video.textContent = description.videoMode || "CMAF";
      label.appendChild(video);
      if (description.audioBitrate) {
        const separator = document.createElement("span");
        separator.className = "ncnl-cache-separator";
        separator.textContent = "·";
        label.appendChild(separator);
        const audio = document.createElement("span");
        audio.className = "ncnl-cache-audio";
        audio.textContent = description.audioBitrate + "k";
        label.appendChild(audio);
      }
    } else {
      label.textContent = "CACHE";
    }
    icon.appendChild(label);
  };

  const updateIcon = function(icon, description, compact) {
    if (!icon || !description) return null;
    const compactMode = Boolean(compact || NicoCache_nl.showCacheQuality === false);
    const renderKey = [
      description.cacheId,
      description.quality,
      description.videoMode,
      description.audioBitrate,
      compactMode ? "1" : "0",
    ].join("|");
    if (icon.getAttribute("data-ncnl-cache-render-key") === renderKey) return icon;

    iconClassNames.forEach(function(className) { icon.classList.remove(className); });
    icon.classList.add("cacheIcon", "ncnl-cache-icon", "ncnl-cache-quality-" + description.quality);
    if (compactMode) {
      icon.classList.add("ncnl-cache-icon--compact");
    }
    icon.setAttribute("data-ncnl-cache-icon", "");
    icon.setAttribute("data-ncnl-cache-id", description.cacheId);
    icon.setAttribute("title", description.title);
    icon.setAttribute("aria-label", description.title);
    replaceLabel(icon, description);
    icon.setAttribute("data-ncnl-cache-render-key", renderKey);
    return icon;
  };

  const createIcon = function(videoInfo, compact) {
    const description = describe(videoInfo);
    if (!description) return null;
    return updateIcon(document.createElement("span"), description, compact);
  };

  const applyLinkClasses = function(element, description) {
    if (!element || !description) return;
    element.classList.add("nl-cached-common");
    element.classList.add("nl-cached-smile-normal");
    // Compatibility classes no longer imply a separate DMC color.
    element.classList.add("cached-v1-normal");
  };

  NicoCache_nl.cacheDisplay = {
    describe: describe,
    createIcon: createIcon,
    updateIcon: updateIcon,
    applyLinkClasses: applyLinkClasses,
  };
})();
