// - 2024-09-06, 2026-08-11, 2026-08-14, 2026-08-29, 2026-08-30.
// - 現行ページの公式コモンヘッダーにNicoCache専用メニューを追加する.
// - CommonHeaderの生成クラスには依存せず、意味のあるルートと公式リンクから挿入先を求める.

(function() {
  "use strict";

  if (window.__ncnlCommonHeaderMenuInitialized) return;
  window.__ncnlCommonHeaderMenuInitialized = true;
  window.__ncnlWatchCacheActionsInitialized = true;

  var containerId = "ncnl_common_header_menu";
  var currentVideoId = null;
  var watchListenerInitialized = false;
  var mountedAccountItem = null;
  var accountOriginalMarginLeft = "";
  var accountBaseMarginLeft = "0px";
  var accountReservedWidth = 0;
  var positionFrame = 0;

  var getVideoId = function() {
    var match = window.location.pathname.match(/^\/watch\/([a-z]{2}\d+)(?:\/|$)/i);
    if (match) return match[1];
    if (!/^\/watch(?:\/|$)/.test(window.location.pathname)) return null;
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
      "#" + containerId + "[data-ncnl-mounted]{" +
        "position:relative;display:flex;box-sizing:border-box;height:36px;padding:0 6px;" +
        "flex:0 0 auto;align-items:center;color:#fff;font:400 12px/36px Avenir,Lato,-apple-system," +
        "BlinkMacSystemFont,Helvetica Neue,Hiragino Kaku Gothic ProN,Meiryo,sans-serif;" +
      "}" +
      "#" + containerId + "[data-ncnl-fullscreen=true]{display:none;}" +
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
        "visibility:hidden;opacity:0;pointer-events:none;background:#f4f4f4;" +
        "box-shadow:0 2px 8px rgba(0,0,0,.2);transition:none;" +
      "}" +
      "#" + containerId + "[data-ncnl-mounted=account][data-ncnl-popover-align=right] " +
      ".ncnl-common-header-popover{" +
        "right:0;left:auto;" +
      "}" +
      "#" + containerId + "[data-ncnl-mounted=account][data-ncnl-popover-align=left] " +
      ".ncnl-common-header-popover{" +
        "right:auto;left:0;" +
      "}" +
      "#" + containerId + "[data-ncnl-mounted=account]{" +
        "position:fixed;z-index:101001;" +
      "}" +
      "#" + containerId + "[data-ncnl-open=true] .ncnl-common-header-popover{" +
        "visibility:visible;opacity:1;pointer-events:auto;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-actions{" +
        "display:grid;grid-template-columns:repeat(2,minmax(0,1fr));background:#fff;" +
        "border-top:1px solid #e5e5e5;border-left:1px solid #e5e5e5;" +
      "}" +
      "#" + containerId + " .ncnl-common-header-actions[hidden]{display:none;}" +
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
    videoId = typeof videoId === "string" && videoId ? videoId : getVideoId();
    var container = document.getElementById(containerId);
    if (!container) return;
    var actions = container.querySelector(".ncnl-common-header-actions");
    var links = container.querySelectorAll("a[data-ncnl-action]");
    if (!videoId) {
      currentVideoId = null;
      container.removeAttribute("data-ncnl-video-id");
      if (actions) actions.hidden = true;
      links.forEach(function(link) {
        if (typeof link._ncnlSuffix === "string") link.removeAttribute("href");
      });
      return;
    }
    if (actions) actions.hidden = false;
    if (videoId === currentVideoId
        && container.getAttribute("data-ncnl-video-id") === videoId) return;
    currentVideoId = videoId;
    container.setAttribute("data-ncnl-video-id", videoId);
    links.forEach(function(link) {
      if (typeof link._ncnlSuffix === "string") {
        link.href = "https://nicocachenl.test/api/v1/videos/"
          + encodeURIComponent(videoId) + link._ncnlSuffix;
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

  var findAccountMenuItem = function(commonHeader) {
    var root = commonHeader.querySelector(".nico-CommonHeaderRoot");
    if (!root) return null;
    var anchors = root.querySelectorAll("a[href]");
    // CommonHeader 3.13.0はフォロー新着内にも/myリンクを追加するため、
    // ルートに最も近い候補だけをヘッダー直下のアカウント項目として扱う.
    var bestItem = null;
    var bestDepth = Number.POSITIVE_INFINITY;
    for (var i = 0; i < anchors.length; i++) {
      try {
        var url = new URL(anchors[i].href, window.location.href);
        if (url.hostname === "www.nicovideo.jp" && url.pathname === "/my") {
          var item = anchors[i].parentElement;
          if (!item || !item.parentElement || !item.previousElementSibling) continue;
          var depth = 0;
          var ancestor = item;
          while (ancestor && ancestor !== root) {
            depth++;
            ancestor = ancestor.parentElement;
          }
          if (ancestor === root && depth < bestDepth) {
            bestItem = item;
            bestDepth = depth;
          }
        }
      } catch (error) {
        // 不正なhrefは公式アカウント項目ではないため無視する.
      }
    }
    if (bestItem) return bestItem;

    // 非ログイン時は会員登録項目の直後がアカウントプレースホルダーになる.
    // 表示文言や生成クラスではなく、登録URLと実測した兄弟関係から求める.
    for (var j = 0; j < anchors.length; j++) {
      try {
        var registerUrl = new URL(anchors[j].href, window.location.href);
        if (registerUrl.hostname !== "account.nicovideo.jp"
            || !/^\/register(?:\/|$)/.test(registerUrl.pathname)) continue;
        var registerItem = anchors[j].parentElement;
        var placeholderItem = registerItem && registerItem.nextElementSibling;
        if (placeholderItem && placeholderItem.parentElement === registerItem.parentElement) {
          return placeholderItem;
        }
      } catch (error) {
        // 不正なhrefは公式会員登録項目ではないため無視する.
      }
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
    var reference = lastServiceLink ? lastServiceLink.nextElementSibling : null;
    return reference && reference.id === containerId ? reference.nextElementSibling : reference;
  };

  var findPlacement = function(commonHeader) {
    var accountItem = findAccountMenuItem(commonHeader);
    if (accountItem) {
      return {
        parent: accountItem.parentElement,
        reference: accountItem,
        mounted: "account",
      };
    }
    var navigation = findServiceNavigation(commonHeader);
    return navigation ? {
      parent: navigation,
      reference: findInsertionReference(navigation),
      mounted: "service",
    } : null;
  };

  var releaseAccountSpace = function() {
    if (!mountedAccountItem) return;
    if (accountOriginalMarginLeft) {
      mountedAccountItem.style.marginLeft = accountOriginalMarginLeft;
    } else {
      mountedAccountItem.style.removeProperty("margin-left");
    }
    mountedAccountItem.removeAttribute("data-ncnl-account-space");
    mountedAccountItem.removeAttribute("data-ncnl-account-original-margin");
    mountedAccountItem.removeAttribute("data-ncnl-account-base-margin");
    mountedAccountItem.removeAttribute("data-ncnl-account-width");
    mountedAccountItem = null;
    accountOriginalMarginLeft = "";
    accountBaseMarginLeft = "0px";
    accountReservedWidth = 0;
  };

  var reserveAccountSpace = function(accountItem, width) {
    if (mountedAccountItem !== accountItem) {
      releaseAccountSpace();
      mountedAccountItem = accountItem;
      if (accountItem.hasAttribute("data-ncnl-account-space")) {
        accountOriginalMarginLeft = accountItem.getAttribute("data-ncnl-account-original-margin") || "";
        accountBaseMarginLeft = accountItem.getAttribute("data-ncnl-account-base-margin") || "0px";
        accountReservedWidth = Number(accountItem.getAttribute("data-ncnl-account-width")) || 0;
      } else {
        accountOriginalMarginLeft = accountItem.style.marginLeft;
        accountBaseMarginLeft = getComputedStyle(accountItem).marginLeft || "0px";
      }
      accountItem.setAttribute("data-ncnl-account-space", "true");
      accountItem.setAttribute("data-ncnl-account-original-margin", accountOriginalMarginLeft);
      accountItem.setAttribute("data-ncnl-account-base-margin", accountBaseMarginLeft);
    }
    if (accountReservedWidth !== width) {
      accountReservedWidth = width;
      accountItem.style.marginLeft = "calc(" + accountBaseMarginLeft + " + " + width + "px)";
      accountItem.setAttribute("data-ncnl-account-width", String(width));
    }
  };

  var positionAccountMenu = function(container) {
    if (container.getAttribute("data-ncnl-fullscreen") === "true") {
      releaseAccountSpace();
      return;
    }
    if (!mountedAccountItem || !mountedAccountItem.isConnected
        || container.getAttribute("data-ncnl-mounted") !== "account") return;
    var width = Math.ceil(container.getBoundingClientRect().width);
    if (width <= 0) return;
    reserveAccountSpace(mountedAccountItem, width);
    var accountRect = mountedAccountItem.getBoundingClientRect();
    var left = Math.max(0, accountRect.left - width);
    var viewportWidth = Math.max(window.innerWidth || 0,
      document.documentElement.clientWidth || 0);
    if (viewportWidth > 0) {
      left = Math.min(left, Math.max(0, viewportWidth - width));
    }
    var popover = container.querySelector(".ncnl-common-header-popover");
    var popoverWidth = popover ? Math.ceil(popover.getBoundingClientRect().width) : 0;
    container.setAttribute("data-ncnl-popover-align",
      popoverWidth > 0 && left + width - popoverWidth < 0 ? "left" : "right");
    container.style.left = Math.round(left) + "px";
    container.style.top = Math.round(accountRect.top) + "px";
  };

  var scheduleAccountMenuPosition = function() {
    if (positionFrame) return;
    positionFrame = requestAnimationFrame(function() {
      positionFrame = 0;
      var container = document.getElementById(containerId);
      if (container) positionAccountMenu(container);
    });
  };

  var mountAccountMenu = function(container, accountItem) {
    container.setAttribute("data-ncnl-mounted", "account");
    if (container.parentElement !== document.body) document.body.appendChild(container);
    var width = Math.ceil(container.getBoundingClientRect().width);
    if (width > 0) reserveAccountSpace(accountItem, width);
    positionAccountMenu(container);
  };

  var clearAccountMenuPosition = function(container) {
    releaseAccountSpace();
    container.removeAttribute("data-ncnl-popover-align");
    container.style.removeProperty("left");
    container.style.removeProperty("top");
  };

  var isPlacementCurrent = function(container, placement) {
    if (!container || !placement
        || container.getAttribute("data-ncnl-mounted") !== placement.mounted) return false;
    if (placement.mounted === "account") {
      return container.parentElement === document.body && mountedAccountItem === placement.reference;
    }
    return container.parentElement === placement.parent
      && container.nextElementSibling === placement.reference;
  };

  var setMenuOpen = function(container, open) {
    var trigger = container.querySelector(".ncnl-common-header-trigger");
    var popover = container.querySelector(".ncnl-common-header-popover");
    container.setAttribute("data-ncnl-open", open ? "true" : "false");
    trigger.setAttribute("aria-expanded", open ? "true" : "false");
    popover.setAttribute("aria-hidden", open ? "false" : "true");
  };

  var synchronizeFullscreenState = function(container) {
    var fullscreen = Boolean(document.fullscreenElement);
    if (fullscreen) {
      container.setAttribute("data-ncnl-fullscreen", "true");
      container.setAttribute("aria-hidden", "true");
      setMenuOpen(container, false);
    } else {
      container.removeAttribute("data-ncnl-fullscreen");
      container.removeAttribute("aria-hidden");
    }
    return fullscreen;
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
    actions.appendChild(createActionLink("movie", "動画保存", "/exports/video"));
    actions.appendChild(createActionLink("comments", "コメント保存", "/exports/comments"));
    actions.appendChild(createActionLink("audio", "音声のみ保存", "/exports/audio"));

    var removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "ncnl-common-header-item";
    removeButton.setAttribute("data-ncnl-action", "remove");
    removeButton.setAttribute("role", "menuitem");
    removeButton.textContent = "キャッシュ削除";
    removeButton.addEventListener("click", function() {
      var videoId = getVideoId();
      if (!videoId) return;
      if (!confirm("本当にキャッシュを削除しますか？: " + videoId)) return;
      removeButton.disabled = true;
      fetch("https://nicocachenl.test/api/v1/videos/"
          + encodeURIComponent(videoId) + "/cache-entries", {method: "DELETE"})
        .then(function(response) {
          removeButton.disabled = false;
          if (response.ok) {
            alert("キャッシュを削除しました: " + videoId);
          } else {
            alert("キャッシュを削除できませんでした: " + videoId);
          }
        }).catch(function() {
          removeButton.disabled = false;
          alert("キャッシュを削除できませんでした: " + videoId);
        });
    });
    actions.appendChild(removeButton);
    popover.appendChild(actions);

    var footer = document.createElement("div");
    footer.className = "ncnl-common-header-footer";
    var cacheLink = document.createElement("a");
    cacheLink.href = "https://nicocachenl.test/cache";
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
    var placement = findPlacement(commonHeader);
    if (!placement) {
      var existingContainer = document.getElementById(containerId);
      if (existingContainer) {
        clearAccountMenuPosition(existingContainer);
        existingContainer.removeAttribute("data-ncnl-mounted");
      }
      return false;
    }
    var container = document.getElementById(containerId) || createMenu();
    var fullscreen = synchronizeFullscreenState(container);
    if (placement && placement.mounted === "account") {
      if (fullscreen) {
        container.setAttribute("data-ncnl-mounted", "account");
        if (container.parentElement !== document.body) document.body.appendChild(container);
        clearAccountMenuPosition(container);
      } else {
        mountAccountMenu(container, placement.reference);
      }
    } else {
      clearAccountMenuPosition(container);
      placement.parent.insertBefore(container, placement.reference);
      container.setAttribute("data-ncnl-mounted", placement.mounted);
    }
    synchronizeLinks();
    ensureWatchListener();
    return true;
  };

  var headerObserver = new MutationObserver(function() {
    var commonHeader = document.getElementById("CommonHeader");
    var container = document.getElementById(containerId);
    var placement = commonHeader && findPlacement(commonHeader);
    ensureWatchListener();
    synchronizeLinks();
    if (!isPlacementCurrent(container, placement)) initialize();
    else if (placement && placement.mounted === "account") scheduleAccountMenuPosition();
  });
  headerObserver.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
  });
  window.addEventListener("popstate", function() {
    setTimeout(function() { synchronizeLinks(); }, 0);
  });
  window.addEventListener("resize", scheduleAccountMenuPosition);
  window.addEventListener("scroll", scheduleAccountMenuPosition, true);
  document.addEventListener("fullscreenchange", function() {
    var container = document.getElementById(containerId);
    if (container) synchronizeFullscreenState(container);
    initialize();
  });
  initialize();
})();
