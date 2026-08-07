// NicoCache_nl thumbnail popup adapter.
// Normalizes legacy paths and delegates display/caching to overlib_mini.js.
(function(global) {
  "use strict";

  global.popThumb_sy = 50;
  global.popThumb_hoverDelay = 120;

  var connectedOrigins = Object.create(null);
  var metrics = {
    calls: 0,
    preconnects: 0,
    normalizedPaths: 0
  };
  global.__ncnlPopThumbMetrics = metrics;

  function escapeAttribute(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/"/g, "&quot;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function preconnect(origin) {
    if (connectedOrigins[origin]) return;
    connectedOrigins[origin] = true;
    var link = document.createElement("link");
    link.rel = "preconnect";
    link.href = origin;
    (document.head || document.documentElement).appendChild(link);
    metrics.preconnects++;
  }

  function normalizePath(path) {
    if (/^(?:mylist|user|community|channel)\//.test(path)) {
      return "thumb_" + path;
    }
    if (/^co\d+$/i.test(path)) return "thumb_community/" + path;
    if (/^ch\d+$/i.test(path)) return "thumb_channel/" + path;
    if (/^lv\d+$/i.test(path)) return "embed/" + path;
    if (/^watch\/co\d+$/i.test(path)) return "thumb_community/" + path.substring(6);
    if (/^watch\/ch\d+$/i.test(path)) return "thumb_channel/" + path.substring(6);
    if (/^watch\/lv\d+$/i.test(path)) return "embed/" + path.substring(6);
    if (/^watch\//i.test(path)) return "thumb/" + path.substring(6);
    if (/^seiga\//i.test(path)) return "thumb/" + path.substring(6);
    return /^thumb(?:_|\/)|^embed\//.test(path) ? path : "thumb/" + path;
  }

  function describe(path) {
    var domain = "ext";
    if (/^embed\/lv\d+$/i.test(path)) domain = "live";
    else if (/^thumb_community\//i.test(path)) domain = "com";
    else if (/^thumb_channel\//i.test(path)) domain = "ch";
    else if (/^thumb\/(?:sg|im|mg)\d+$/i.test(path)) domain = "ext.seiga";

    path = path.replace(/^thumb_channel\/([^/]*)$/i, "$1/thumb_channel");
    return {
      domain: domain,
      path: path,
      width: 312,
      height: 176 + (domain === "ext" ? 20 : 0)
    };
  }

  function testOrProductionUrl(description) {
    if (typeof global.__ncnlPopThumbTestUrl === "function") {
      return global.__ncnlPopThumbTestUrl(description.domain, description.path);
    }
    return location.protocol + "//" + description.domain + ".nicovideo.jp/" + description.path;
  }

  function popThumb(path, sticky, wait, anchor, pointerEvent) {
    if (typeof global.overlib !== "function") return true;
    metrics.calls++;

    var description = describe(String(path));
    var url = testOrProductionUrl(description);
    var origin = new URL(url, location.href).origin;
    preconnect(origin);

    if (pointerEvent && typeof global.olSetPointer === "function") {
      global.olSetPointer(pointerEvent.clientX, pointerEvent.clientY);
    }

    var originalDelay = global.ol_delay;
    global.ol_hauto = 1;
    global.ol_vauto = 1;
    global.ol_offsetx = 12;
    global.ol_offsety = 8;
    global.ol_width = description.width;
    global.ol_height = description.height;
    global.ol_fgcolor = "#fff";
    global.ol_bgcolor = "#ccc";
    global.ol_textcolor = "#000";
    global.ol_capcolor = "#111";
    global.ol_border = 3;
    global.ol_close = "閉じる";
    global.ol_closeclick = 1;
    global.ol_sticky = Boolean(sticky);
    global.ol_cap = sticky ? path : "";
    global.ol_delay = wait === undefined ? (sticky ? 0 : global.popThumb_hoverDelay) : wait;
    global.ol_followmouse = 0;
    global.ol_anchor = anchor || null;
    global.ol_cachekey = "popthumb:" + url + ":" + (sticky ? "sticky" : "hover");
    global.ol_text =
      '<iframe class="ncnl-popthumb-frame" width="' + description.width +
      '" height="' + description.height + '" src="' + escapeAttribute(url) +
      '" scrolling="no" frameborder="0" loading="eager" referrerpolicy="strict-origin-when-cross-origin"' +
      ' title="Niconico thumbnail information"></iframe>';

    try {
      return global.overlib();
    } finally {
      global.ol_delay = originalDelay;
      global.ol_anchor = null;
      global.ol_cachekey = "";
    }
  }

  function popThumb2(path, sticky, wait, anchor, pointerEvent) {
    var normalized = normalizePath(String(path));
    if (normalized !== path) metrics.normalizedPaths++;
    return popThumb(normalized, sticky, wait, anchor, pointerEvent);
  }

  global.popThumb = popThumb;
  global.popThumb2 = popThumb2;
  global.popThumbPreconnect = function(path) {
    var description = describe(normalizePath(String(path)));
    var url = testOrProductionUrl(description);
    preconnect(new URL(url, location.href).origin);
  };

  if (global.NicoCache_nl && global.NicoCache_nl.watch && typeof global.nd === "function") {
    global.NicoCache_nl.watch.addEventListener("videoChanged", function() {
      global.setTimeout(global.nd, 0);
    });
  }
})(window);
