// - 2024-09-06, 2026-08-11.
// - 現行watchページにキャッシュの保存・コメント保存・音声保存・削除導線を追加する.
// - CommonHeader内部の生成クラスや階層に依存せず、安定したルートへ操作群を追加する.

(function() {
  "use strict";

  if (window.__ncnlWatchCacheActionsInitialized) return;
  window.__ncnlWatchCacheActionsInitialized = true;

  var containerId = "cache_remove_workaround";
  var currentVideoId = null;
  var watchListenerInitialized = false;

  var getVideoId = function() {
    var match = window.location.pathname.match(/^\/watch\/([a-z]{2}\d+)(?:\/|$)/i);
    if (match) return match[1];
    if (window.NicoCache_nl && NicoCache_nl.watch
        && typeof NicoCache_nl.watch.getVideoID === "function") {
      return NicoCache_nl.watch.getVideoID();
    }
    return null;
  };

  var addStyles = function() {
    if (document.getElementById("ncnl_watch_cache_actions_style")) return;
    var style = document.createElement("style");
    style.id = "ncnl_watch_cache_actions_style";
    style.textContent =
      "#" + containerId + "{" +
        "position:fixed;top:calc(52px + env(safe-area-inset-top));" +
        "right:max(8px,env(safe-area-inset-right));z-index:2147483000;" +
        "display:flex;gap:4px;align-items:center;padding:5px;" +
        "border:1px solid rgba(255,255,255,.2);border-radius:7px;" +
        "background:rgba(24,24,27,.94);box-shadow:0 2px 8px rgba(0,0,0,.35);" +
      "}" +
      "#" + containerId + " a,#" + containerId + " button{" +
        "box-sizing:border-box;min-height:30px;margin:0;padding:5px 9px;" +
        "border:1px solid rgba(255,255,255,.28);border-radius:5px;" +
        "background:#3f3f46;color:#fff;font:12px/18px sans-serif;" +
        "text-decoration:none;white-space:nowrap;cursor:pointer;" +
      "}" +
      "#" + containerId + " a:hover,#" + containerId + " button:hover{" +
        "background:#52525b;" +
      "}" +
      "#" + containerId + " button[data-ncnl-action=remove]{" +
        "border-color:#dc2626;background:#991b1b;" +
      "}" +
      "#" + containerId + " button:disabled{opacity:.55;cursor:wait;}" +
      "@media(max-width:720px){#" + containerId + "{" +
        "top:auto;right:8px;bottom:calc(8px + env(safe-area-inset-bottom));" +
        "left:8px;justify-content:center;flex-wrap:wrap;" +
      "}}";
    (document.head || document.documentElement).appendChild(style);
  };

  var createDownloadLink = function(action, label, suffix) {
    var link = document.createElement("a");
    link.setAttribute("data-ncnl-action", action);
    link.setAttribute("download", "");
    link.setAttribute("aria-label", label);
    link.textContent = label;
    link._ncnlSuffix = suffix;
    return link;
  };

  var synchronizeLinks = function(videoId) {
    videoId = videoId || getVideoId();
    var container = document.getElementById(containerId);
    if (!videoId || !container) return;
    if (videoId === currentVideoId
        && container.getAttribute("data-ncnl-video-id") === videoId) return;
    currentVideoId = videoId;
    container.setAttribute("data-ncnl-video-id", videoId);
    container.querySelectorAll("a[data-ncnl-action]").forEach(function(link) {
      link.href = "/cache/" + encodeURIComponent(videoId) + link._ncnlSuffix;
    });
  };

  var initialize = function() {
    var commonHeader = document.getElementById("CommonHeader");
    if (!commonHeader) return false;
    if (document.getElementById(containerId)) {
      synchronizeLinks();
      return true;
    }

    addStyles();
    var container = document.createElement("div");
    container.id = containerId;
    container.setAttribute("role", "group");
    container.setAttribute("aria-label", "NicoCache_nl キャッシュ操作");
    container.appendChild(createDownloadLink(
      "movie", "動画保存", "/auto/movie"));
    container.appendChild(createDownloadLink(
      "comments", "コメント保存", ".comments.json"));
    container.appendChild(createDownloadLink(
      "audio", "音声のみ保存", "/auto/audio"));

    var removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.setAttribute("data-ncnl-action", "remove");
    removeButton.setAttribute("aria-label", "キャッシュ削除");
    removeButton.textContent = "キャッシュ削除";
    removeButton.addEventListener("click", function() {
      var videoId = getVideoId();
      if (!videoId || !window.NicoCache_nl
          || typeof NicoCache_nl.get !== "function") return;
      if (!confirm("本当にキャッシュを削除しますか？: " + videoId)) return;
      removeButton.disabled = true;
      NicoCache_nl.get("/cache/ajax_rmall?" + encodeURIComponent(videoId),
        function(response) {
          removeButton.disabled = false;
          if (response && response.status === 200
              && response.responseText.trim() === "OK") {
            alert("キャッシュを削除しました: " + videoId);
          } else {
            alert("キャッシュを削除できませんでした: " + videoId);
          }
        });
    });
    container.appendChild(removeButton);
    commonHeader.appendChild(container);
    synchronizeLinks();

    if (!watchListenerInitialized && window.NicoCache_nl && NicoCache_nl.watch
        && typeof NicoCache_nl.watch.addEventListener === "function") {
      watchListenerInitialized = true;
      NicoCache_nl.watch.addEventListener("videoChanged", synchronizeLinks);
    }
    return true;
  };

  var initializePending = false;
  var headerObserver = new MutationObserver(function() {
    if (document.getElementById(containerId) || initializePending) return;
    initializePending = true;
    window.requestAnimationFrame(function() {
      initializePending = false;
      initialize();
    });
  });
  headerObserver.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
  });
  window.addEventListener("popstate", function() {
    setTimeout(function() { synchronizeLinks(); }, 0);
  });
  initialize();
})();
