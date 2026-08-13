// - 2024-09-06, 2026-08-11, 2026-08-14.
// - 現行watchページの公式コモンヘッダーにNicoCache専用メニューを追加する.
// - CommonHeaderの生成クラスには依存せず、意味のあるルートと公式サービスリンクから挿入先を求める.

(function() {
  "use strict";

  if (window.__ncnlWatchCacheActionsInitialized) return;
  window.__ncnlWatchCacheActionsInitialized = true;

  var containerId = "ncnl_common_header_menu";
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
    if (document.getElementById("ncnl_common_header_menu_style")) return;
    var style = document.createElement("style");
    style.id = "ncnl_common_header_menu_style";
    style.textContent =
      "#" + containerId + "{display:none;}" +
      "#" + containerId + "[data-ncnl-mounted=desktop]{" +
        "position:relative;display:flex;box-sizing:border-box;height:36px;padding:0 6px;" +
        "align-items:center;color:#fff;font:400 12px/36px Avenir,Lato,-apple-system," +
        "BlinkMacSystemFont,Helvetica Neue,Hiragino Kaku Gothic ProN,Meiryo,sans-serif;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-trigger{" +
        "all:unset;box-sizing:border-box;display:flex;height:36px;align-items:center;gap:5px;" +
        "padding:0 2px;color:#fff;white-space:nowrap;cursor:pointer;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-trigger::after{" +
        "width:7px;height:7px;margin-top:-4px;border-right:2px solid #ddd;" +
        "border-bottom:2px solid #ddd;content:'';transform:rotate(45deg);" +
      "}" +
      "#" + containerId + ":hover .ncnl-common-header-trigger," +
      "#" + containerId + ":focus-within .ncnl-common-header-trigger{" +
        "background:rgba(255,255,255,.08);" +
      "}" +
      "#" + containerId + " .ncnl-common-header-trigger:focus-visible{" +
        "outline:2px solid #fff;outline-offset:-2px;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-popover{" +
        "position:absolute;top:36px;left:0;z-index:100000;box-sizing:border-box;width:329px;" +
        "visibility:hidden;opacity:0;pointer-events:none;box-shadow:0 2px 5px rgba(0,0,0,.35);" +
        "transition:visibility 0s linear 80ms,opacity 80ms linear;" +
      "}" +
      "#" + containerId + "[data-ncnl-open=true] .ncnl-common-header-popover{" +
        "visibility:visible;opacity:1;pointer-events:auto;transition-delay:0s;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-actions{" +
        "display:grid;grid-template-columns:repeat(2,minmax(0,1fr));background:#fff;" +
        "border-top:1px solid #e5e5e5;border-left:1px solid #e5e5e5;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-item{" +
        "box-sizing:border-box;display:flex;width:100%;height:40px;margin:0;padding:0 12px;" +
        "align-items:center;justify-content:space-between;border:0;border-right:1px solid #e5e5e5;" +
        "border-bottom:1px solid #e5e5e5;border-radius:0;background:#fff;color:#333;" +
        "font:400 12px/1.4 Avenir,Lato,-apple-system,BlinkMacSystemFont,Helvetica Neue," +
        "Hiragino Kaku Gothic ProN,Meiryo,sans-serif;text-align:start;text-decoration:none;" +
        "white-space:nowrap;cursor:pointer;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-item::after{" +
        "color:#999;font-size:20px;line-height:1;content:'›';" +
      "}" +
      "#" + containerId + " .ncnl-common-header-item:hover," +
      "#" + containerId + " .ncnl-common-header-item:focus-visible{background:#f4f4f4;color:#111;}" +
      "#" + containerId + " .ncnl-common-header-item[data-ncnl-action=remove]{color:#b42318;}" +
      "#" + containerId + " .ncnl-common-header-item:disabled{opacity:.55;cursor:wait;}" +
      "#" + containerId + " .ncnl-common-header-footer{" +
        "box-sizing:border-box;display:flex;height:40px;padding:0 16px;align-items:center;" +
        "justify-content:flex-end;background:#f4f4f4;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-footer a{" +
        "display:inline-flex;height:24px;align-items:center;color:#333;font-size:12px;" +
        "font-weight:700;line-height:1;text-decoration:none;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-footer a::after{" +
        "margin-left:6px;color:#999;font-size:18px;font-weight:400;content:'›';" +
      "}" +
      "#" + containerId + " .ncnl-common-header-footer a:hover," +
      "#" + containerId + " .ncnl-common-header-footer a:focus-visible{text-decoration:underline;}";
    (document.head || document.documentElement).appendChild(style);
  };

  var createActionLink = function(action, label, suffix) {
    var link = document.createElement("a");
    link.className = "ncnl-common-header-item";
    link.setAttribute("data-ncnl-action", action);
    link.setAttribute("role", "menuitem");
    link.setAttribute("download", "");
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
      if (typeof link._ncnlSuffix === "string") {
        link.href = "/cache/" + encodeURIComponent(videoId) + link._ncnlSuffix;
      }
    });
  };

  var findServiceNavigation = function(commonHeader) {
    var root = commonHeader.querySelector(".nico-CommonHeaderRoot");
    if (!root) return null;
    var anchors = root.querySelectorAll("a[href]");
    for (var i = 0; i < anchors.length; i++) {
      var href = anchors[i].getAttribute("href") || "";
      if (href.indexOf("header_servicelink") >= 0) return anchors[i].parentElement;
    }
    return null;
  };

  var findInsertionReference = function(navigation) {
    var children = navigation.children;
    var lastServiceLink = null;
    for (var i = 0; i < children.length; i++) {
      var href = children[i].getAttribute && children[i].getAttribute("href");
      if (href && href.indexOf("header_servicelink") >= 0) lastServiceLink = children[i];
    }
    return lastServiceLink ? lastServiceLink.nextElementSibling : null;
  };

  var setMenuOpen = function(container, open) {
    var trigger = container.querySelector(".ncnl-common-header-trigger");
    var popover = container.querySelector(".ncnl-common-header-popover");
    container.setAttribute("data-ncnl-open", open ? "true" : "false");
    trigger.setAttribute("aria-expanded", open ? "true" : "false");
    popover.setAttribute("aria-hidden", open ? "false" : "true");
  };

  var createMenu = function() {
    addStyles();
    var container = document.createElement("div");
    container.id = containerId;
    container.setAttribute("data-ncnl-open", "false");

    var trigger = document.createElement("button");
    trigger.type = "button";
    trigger.className = "ncnl-common-header-trigger";
    trigger.setAttribute("aria-haspopup", "menu");
    trigger.setAttribute("aria-controls", "ncnl_common_header_popover");
    trigger.setAttribute("aria-expanded", "false");
    trigger.textContent = "NicoCache";
    container.appendChild(trigger);

    var popover = document.createElement("div");
    popover.id = "ncnl_common_header_popover";
    popover.className = "ncnl-common-header-popover";
    popover.setAttribute("role", "menu");
    popover.setAttribute("aria-label", "NicoCache_nl キャッシュ操作");
    popover.setAttribute("aria-hidden", "true");

    var actions = document.createElement("div");
    actions.className = "ncnl-common-header-actions";
    actions.appendChild(createActionLink("movie", "動画保存", "/auto/movie"));
    actions.appendChild(createActionLink("comments", "コメント保存", ".comments.json"));
    actions.appendChild(createActionLink("audio", "音声のみ保存", "/auto/audio"));

    var removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "ncnl-common-header-item";
    removeButton.setAttribute("data-ncnl-action", "remove");
    removeButton.setAttribute("role", "menuitem");
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
    actions.appendChild(removeButton);
    popover.appendChild(actions);

    var footer = document.createElement("div");
    footer.className = "ncnl-common-header-footer";
    var cacheLink = document.createElement("a");
    cacheLink.href = "/cache/";
    cacheLink.setAttribute("data-ncnl-action", "manage");
    cacheLink.setAttribute("role", "menuitem");
    cacheLink.textContent = "キャッシュへ";
    footer.appendChild(cacheLink);
    popover.appendChild(footer);
    container.appendChild(popover);

    container.addEventListener("mouseenter", function() { setMenuOpen(container, true); });
    container.addEventListener("mouseleave", function() {
      if (!container.contains(document.activeElement)) setMenuOpen(container, false);
    });
    container.addEventListener("focusin", function() { setMenuOpen(container, true); });
    container.addEventListener("focusout", function() {
      setTimeout(function() {
        if (!container.contains(document.activeElement)) setMenuOpen(container, false);
      }, 0);
    });
    var openBeforePointer = false;
    trigger.addEventListener("pointerdown", function() {
      openBeforePointer = container.getAttribute("data-ncnl-open") === "true";
    });
    trigger.addEventListener("click", function(event) {
      var wasOpen = event.detail > 0
        ? openBeforePointer
        : container.getAttribute("data-ncnl-open") === "true";
      setMenuOpen(container, !wasOpen);
    });
    container.addEventListener("keydown", function(event) {
      var items = Array.prototype.slice.call(popover.querySelectorAll("[role=menuitem]"));
      var itemIndex = items.indexOf(event.target);
      if (event.key === "Escape") {
        trigger.focus();
        setMenuOpen(container, false);
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        setMenuOpen(container, true);
        if (items.length > 0) items[itemIndex < 0 ? 0 : (itemIndex + 1) % items.length].focus();
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        setMenuOpen(container, true);
        if (items.length > 0) items[itemIndex < 0 ? items.length - 1
          : (itemIndex + items.length - 1) % items.length].focus();
      } else if (event.key === "Home" && items.length > 0) {
        event.preventDefault();
        items[0].focus();
      } else if (event.key === "End" && items.length > 0) {
        event.preventDefault();
        items[items.length - 1].focus();
      }
    });
    popover.addEventListener("click", function(event) {
      if (event.target.closest("[role=menuitem]")) setMenuOpen(container, false);
    });
    return container;
  };

  document.addEventListener("pointerdown", function(event) {
    var container = document.getElementById(containerId);
    if (container && !container.contains(event.target)) setMenuOpen(container, false);
  });

  var ensureWatchListener = function() {
    if (!watchListenerInitialized && window.NicoCache_nl && NicoCache_nl.watch
        && typeof NicoCache_nl.watch.addEventListener === "function") {
      watchListenerInitialized = true;
      NicoCache_nl.watch.addEventListener("initialized", synchronizeLinks);
      NicoCache_nl.watch.addEventListener("videoChanged", synchronizeLinks);
      synchronizeLinks();
    }
  };

  var initialize = function() {
    var commonHeader = document.getElementById("CommonHeader");
    if (!commonHeader) return false;
    var navigation = findServiceNavigation(commonHeader);
    var container = document.getElementById(containerId) || createMenu();
    if (navigation) {
      navigation.insertBefore(container, findInsertionReference(navigation));
      container.setAttribute("data-ncnl-mounted", "desktop");
    } else if (!container.isConnected) {
      container.removeAttribute("data-ncnl-mounted");
      commonHeader.appendChild(container);
    }
    synchronizeLinks();
    ensureWatchListener();
    return true;
  };

  var headerObserver = new MutationObserver(function() {
    var commonHeader = document.getElementById("CommonHeader");
    var container = document.getElementById(containerId);
    var navigation = commonHeader && findServiceNavigation(commonHeader);
    ensureWatchListener();
    if (!container || (navigation && container.parentElement !== navigation)) initialize();
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
