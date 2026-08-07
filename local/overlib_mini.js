// NicoCache_nl lightweight popup engine.
// Keeps the historical overlib()/nd()/cClick() entry points used by nlFilters,
// but builds each keyed popup only once and retains iframe browsing contexts.
(function(global) {
  "use strict";

  var document = global.document;
  var root = null;
  var activeEntry = null;
  var pendingTimer = 0;
  var hideTimer = 0;
  var positionFrame = 0;
  var pointerX = Math.round(global.innerWidth / 2);
  var pointerY = Math.round(global.innerHeight / 2);
  var entries = [];
  var entryByKey = Object.create(null);

  var metrics = {
    scheduled: 0,
    cancelled: 0,
    entriesCreated: 0,
    entriesReused: 0,
    entriesEvicted: 0,
    framesCreated: 0,
    frameLoads: 0,
    domBuildMilliseconds: 0,
    lastDomBuildMilliseconds: 0,
    lastReuseMilliseconds: 0,
    lastFrameLoadMilliseconds: 0,
    shows: 0,
    hides: 0
  };
  global.__ncnlOverlibMetrics = metrics;

  function numberValue(value, fallback) {
    var number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function positiveInteger(value, fallback) {
    return Math.max(1, Math.round(numberValue(value, fallback)));
  }

  function installStyles() {
    if (document.getElementById("ncnl-overlib-styles")) return;
    var style = document.createElement("style");
    style.id = "ncnl-overlib-styles";
    style.textContent =
      "#overDiv{position:fixed;left:0;top:0;visibility:hidden;z-index:2147483000;" +
      "contain:layout style;will-change:transform;}" +
      "#overDiv[data-visible=\"true\"]{visibility:visible;}" +
      "#overDiv .ncnl-overlib-panel[hidden]{display:none;}" +
      "#overDiv .ncnl-overlib-panel{position:relative;box-sizing:border-box;overflow:hidden;}" +
      "#overDiv .ncnl-overlib-caption{display:flex;align-items:center;justify-content:space-between;" +
      "min-height:24px;padding:2px 6px;font:700 12px/1.4 sans-serif;}" +
      "#overDiv .ncnl-overlib-close{border:0;padding:1px 4px;background:transparent;color:inherit;" +
      "font:inherit;cursor:pointer;}" +
      "#overDiv .ncnl-overlib-body{position:relative;box-sizing:border-box;overflow:hidden;}" +
      "#overDiv .ncnl-overlib-body>iframe{display:block;transform-origin:0 0;}" +
      "#overDiv .ncnl-overlib-panel[data-loading=\"true\"] .ncnl-overlib-body{visibility:hidden;}" +
      "#overDiv .ncnl-overlib-panel[data-loading=\"true\"]::after{content:\"\";position:absolute;" +
      "left:50%;top:50%;width:22px;height:22px;margin:-11px;border:3px solid rgba(127,127,127,.3);" +
      "border-top-color:#3fbdb7;border-radius:50%;animation:ncnl-overlib-spin .7s linear infinite;}" +
      "@keyframes ncnl-overlib-spin{to{transform:rotate(360deg)}}" +
      "@media(prefers-reduced-motion:reduce){#overDiv .ncnl-overlib-panel[data-loading=\"true\"]::after{" +
      "animation-duration:1.5s}}";
    (document.head || document.documentElement).appendChild(style);
  }

  function ensureRoot() {
    if (root && root.isConnected) return root;
    installStyles();
    root = document.getElementById("overDiv");
    if (!root) {
      root = document.createElement("div");
      root.id = "overDiv";
      (document.body || document.documentElement).appendChild(root);
    }
    root.setAttribute("role", "tooltip");
    root.dataset.visible = "false";
    return root;
  }

  function snapshot(args) {
    var argumentText = args.length && typeof args[0] === "string" ? args[0] : null;
    return {
      text: argumentText !== null ? argumentText : String(global.ol_text || ""),
      caption: String(global.ol_cap || ""),
      closeText: String(global.ol_close || "Close"),
      sticky: Boolean(global.ol_sticky),
      closeClick: Boolean(global.ol_closeclick),
      width: positiveInteger(global.ol_width, 200),
      height: Math.max(0, Math.round(numberValue(global.ol_height, 0))),
      border: Math.max(0, Math.round(numberValue(global.ol_border, 1))),
      offsetX: Math.round(numberValue(global.ol_offsetx, 10)),
      offsetY: Math.round(numberValue(global.ol_offsety, 10)),
      foreground: String(global.ol_fgcolor || "#fff"),
      background: String(global.ol_bgcolor || "#333"),
      textColor: String(global.ol_textcolor || "#000"),
      captionColor: String(global.ol_capcolor || "#fff"),
      delay: Math.max(0, Math.round(numberValue(global.ol_delay, 0))),
      horizontalAuto: Boolean(global.ol_hauto),
      verticalAuto: Boolean(global.ol_vauto),
      followMouse: Boolean(global.ol_followmouse),
      anchor: global.ol_anchor instanceof global.Element ? global.ol_anchor : null,
      cacheKey: String(global.ol_cachekey || "")
    };
  }

  function entryKey(state) {
    return state.cacheKey ? "key:" + state.cacheKey : "html:" + state.text + "\n" + state.caption;
  }

  function markFrameReady(entry) {
    if (entry.pendingFrames > 0) entry.pendingFrames--;
    metrics.frameLoads++;
    if (entry.pendingFrames !== 0) return;
    global.clearTimeout(entry.loadFallback);
    entry.panel.dataset.loading = "false";
    entry.readyAt = global.performance ? global.performance.now() : Date.now();
    metrics.lastFrameLoadMilliseconds = Math.round((entry.readyAt - entry.createdAt) * 100) / 100;
  }

  function createEntry(state, key) {
    var panel = document.createElement("section");
    panel.className = "ncnl-overlib-panel";
    panel.hidden = true;

    if (state.caption) {
      var caption = document.createElement("div");
      caption.className = "ncnl-overlib-caption";
      caption.textContent = state.caption;
      if (state.sticky) {
        var close = document.createElement("button");
        close.type = "button";
        close.className = "ncnl-overlib-close";
        close.textContent = state.closeText;
        close.addEventListener(state.closeClick ? "click" : "pointerenter", function() {
          global.cClick();
        });
        caption.appendChild(close);
      }
      panel.appendChild(caption);
    }

    var body = document.createElement("div");
    body.className = "ncnl-overlib-body";
    body.innerHTML = state.text;
    panel.appendChild(body);
    ensureRoot().appendChild(panel);

    var frames = body.querySelectorAll("iframe");
    var entry = {
      key: key,
      panel: panel,
      state: state,
      pendingFrames: frames.length,
      loadFallback: 0,
      createdAt: global.performance ? global.performance.now() : Date.now(),
      readyAt: 0,
      usedAt: global.performance ? global.performance.now() : Date.now()
    };

    if (frames.length) {
      panel.dataset.loading = "true";
      metrics.framesCreated += frames.length;
      for (var i = 0; i < frames.length; i++) {
        frames[i].addEventListener("load", markFrameReady.bind(null, entry), {once: true});
      }
      entry.loadFallback = global.setTimeout(function() {
        entry.pendingFrames = 0;
        panel.dataset.loading = "false";
      }, 10000);
    } else {
      panel.dataset.loading = "false";
    }

    entries.push(entry);
    entryByKey[key] = entry;
    metrics.entriesCreated++;
    evictEntries();
    return entry;
  }

  function evictEntries() {
    var maximum = positiveInteger(global.ol_cachemax, 12);
    while (entries.length > maximum) {
      var oldestIndex = 0;
      for (var i = 1; i < entries.length; i++) {
        if (entries[i].usedAt < entries[oldestIndex].usedAt) oldestIndex = i;
      }
      var removed = entries.splice(oldestIndex, 1)[0];
      if (removed === activeEntry) activeEntry = null;
      global.clearTimeout(removed.loadFallback);
      delete entryByKey[removed.key];
      removed.panel.remove();
      metrics.entriesEvicted++;
    }
  }

  function popupDimensions(state) {
    var captionHeight = state.caption ? 24 : 0;
    var availableWidth = Math.max(1, global.innerWidth - 8 - 2 * state.border);
    var availableHeight = Math.max(1, global.innerHeight - 8 - 2 * state.border - captionHeight);
    var scale = Math.min(1, availableWidth / state.width,
      state.height ? availableHeight / state.height : 1);
    return {
      scale: scale,
      contentWidth: Math.max(1, Math.floor(state.width * scale)),
      contentHeight: state.height ? Math.max(1, Math.floor(state.height * scale)) : 0,
      width: Math.max(1, Math.floor(state.width * scale)) + 2 * state.border,
      height: (state.height ? Math.max(1, Math.floor(state.height * scale)) : 0) +
        2 * state.border + captionHeight
    };
  }

  function updateEntryStyle(entry, state) {
    var panel = entry.panel;
    var caption = panel.querySelector(".ncnl-overlib-caption");
    var body = panel.querySelector(".ncnl-overlib-body");
    var dimensions = popupDimensions(state);
    panel.style.width = dimensions.width + "px";
    panel.style.minHeight = (dimensions.contentHeight ? dimensions.height : 0) + "px";
    panel.style.border = state.border + "px solid " + state.background;
    panel.style.background = state.foreground;
    panel.style.color = state.textColor;
    panel.style.pointerEvents = state.sticky ? "auto" : "none";
    if (caption) {
      caption.style.background = state.background;
      caption.style.color = state.captionColor;
    }
    body.style.width = dimensions.contentWidth + "px";
    body.style.height = dimensions.contentHeight ? dimensions.contentHeight + "px" : "auto";
    var frames = body.querySelectorAll("iframe");
    for (var i = 0; i < frames.length; i++) {
      frames[i].style.transform = dimensions.scale < 1 ? "scale(" + dimensions.scale + ")" : "none";
    }
  }

  function getPosition(state) {
    var dimensions = popupDimensions(state);
    var width = dimensions.width;
    var height = dimensions.height;
    var x;
    var y;

    if (state.anchor && state.anchor.isConnected) {
      var rect = state.anchor.getBoundingClientRect();
      x = rect.right + state.offsetX;
      y = rect.top + state.offsetY;
      if (state.horizontalAuto && x + width > global.innerWidth) x = rect.left - width - state.offsetX;
      if (state.verticalAuto && y + height > global.innerHeight) y = rect.bottom - height - state.offsetY;
    } else {
      x = pointerX + state.offsetX;
      y = pointerY + state.offsetY;
      if (state.horizontalAuto && x + width > global.innerWidth) x = pointerX - width - state.offsetX;
      if (state.verticalAuto && y + height > global.innerHeight) y = pointerY - height - state.offsetY;
    }

    var maximumY = Math.max(4, global.innerHeight - height - 4);
    var minimumY = Math.min(numberValue(global.popThumb_sy, 4), maximumY);
    return {
      x: Math.max(4, Math.min(Math.round(x), Math.max(4, global.innerWidth - width - 4))),
      y: Math.max(minimumY, Math.min(Math.round(y), maximumY))
    };
  }

  function positionActive() {
    positionFrame = 0;
    if (!activeEntry || !root || root.dataset.visible !== "true") return;
    var position = getPosition(activeEntry.state);
    root.style.transform = "translate3d(" + position.x + "px," + position.y + "px,0)";
  }

  function requestPosition() {
    if (!positionFrame) positionFrame = global.requestAnimationFrame(positionActive);
  }

  function activate(state) {
    pendingTimer = 0;
    var operationStarted = global.performance ? global.performance.now() : Date.now();
    var key = entryKey(state);
    var entry = entryByKey[key];
    if (entry) {
      metrics.entriesReused++;
      entry.state = state;
      metrics.lastReuseMilliseconds = Math.round(((global.performance ? global.performance.now() : Date.now()) - operationStarted) * 100) / 100;
    } else {
      entry = createEntry(state, key);
      metrics.lastDomBuildMilliseconds = Math.round(((global.performance ? global.performance.now() : Date.now()) - operationStarted) * 100) / 100;
      metrics.domBuildMilliseconds = Math.round((metrics.domBuildMilliseconds + metrics.lastDomBuildMilliseconds) * 100) / 100;
    }
    entry.usedAt = global.performance ? global.performance.now() : Date.now();
    updateEntryStyle(entry, state);

    if (activeEntry && activeEntry !== entry) activeEntry.panel.hidden = true;
    activeEntry = entry;
    entry.panel.hidden = false;
    ensureRoot().dataset.visible = "true";
    metrics.shows++;
    positionActive();
  }

  function cancelPending() {
    if (!pendingTimer) return;
    global.clearTimeout(pendingTimer);
    pendingTimer = 0;
    metrics.cancelled++;
  }

  function hide(force) {
    cancelPending();
    if (hideTimer) {
      global.clearTimeout(hideTimer);
      hideTimer = 0;
    }
    if (!root || root.dataset.visible !== "true") return true;
    if (!force && activeEntry && activeEntry.state.sticky) return true;
    root.dataset.visible = "false";
    metrics.hides++;
    return true;
  }

  global.overlib = function() {
    var state = snapshot(arguments);
    cancelPending();
    if (hideTimer) {
      global.clearTimeout(hideTimer);
      hideTimer = 0;
    }
    if (state.delay) {
      metrics.scheduled++;
      pendingTimer = global.setTimeout(activate.bind(null, state), state.delay);
      return false;
    }
    activate(state);
    return true;
  };

  global.nd = function(delay) {
    var milliseconds = Math.max(0, Math.round(numberValue(delay, 0)));
    if (!milliseconds) return hide(false);
    if (hideTimer) global.clearTimeout(hideTimer);
    hideTimer = global.setTimeout(function() {
      hideTimer = 0;
      hide(false);
    }, milliseconds);
    return true;
  };

  global.cClick = function() {
    hide(true);
    return false;
  };

  global.olMouseCapture = function() {
    return true;
  };

  global.olSetPointer = function(x, y) {
    pointerX = numberValue(x, pointerX);
    pointerY = numberValue(y, pointerY);
    if (activeEntry && activeEntry.state.followMouse) requestPosition();
  };

  document.addEventListener("pointermove", function(event) {
    global.olSetPointer(event.clientX, event.clientY);
  }, {passive: true});
  global.addEventListener("resize", requestPosition, {passive: true});
  global.addEventListener("scroll", requestPosition, {passive: true});

  // Historical constants/defaults retained for custom filters calling overlib directly.
  global.ABOVE = global.ABOVE || 1;
  global.BELOW = global.BELOW || 2;
  global.LEFT = global.LEFT || 3;
  global.RIGHT = global.RIGHT || 4;
  global.CENTER = global.CENTER || 5;
  global.olLoaded = 1;
  if (typeof global.ol_fgcolor === "undefined") global.ol_fgcolor = "#fff";
  if (typeof global.ol_bgcolor === "undefined") global.ol_bgcolor = "#333";
  if (typeof global.ol_textcolor === "undefined") global.ol_textcolor = "#000";
  if (typeof global.ol_capcolor === "undefined") global.ol_capcolor = "#fff";
  if (typeof global.ol_width === "undefined") global.ol_width = 200;
  if (typeof global.ol_height === "undefined") global.ol_height = 0;
  if (typeof global.ol_border === "undefined") global.ol_border = 1;
  if (typeof global.ol_offsetx === "undefined") global.ol_offsetx = 10;
  if (typeof global.ol_offsety === "undefined") global.ol_offsety = 10;
  if (typeof global.ol_delay === "undefined") global.ol_delay = 0;
  if (typeof global.ol_hauto === "undefined") global.ol_hauto = 1;
  if (typeof global.ol_vauto === "undefined") global.ol_vauto = 1;
  if (typeof global.ol_followmouse === "undefined") global.ol_followmouse = 0;
  if (typeof global.ol_cachemax === "undefined") global.ol_cachemax = 12;
})(window);
