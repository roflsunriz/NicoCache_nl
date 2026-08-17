"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const cacheDisplaySource = fs.readFileSync(
  path.join(repositoryRoot, "local", "ncnl_cache_display.js"),
  "utf8",
);
const indicatorSource = fs.readFileSync(
  path.join(repositoryRoot, "local", "15_cached_link_color.js"),
  "utf8",
);
const watchIndicatorSource = fs.readFileSync(
  path.join(repositoryRoot, "local", "20_watchpage.js"),
  "utf8",
);

class FakeClassList {
  constructor(element) {
    this.element = element;
    this.values = new Set();
  }

  add(...names) {
    names.forEach((name) => this.values.add(name));
  }

  remove(...names) {
    names.forEach((name) => this.values.delete(name));
  }

  contains(name) {
    return this.values.has(name);
  }

  toString() {
    return Array.from(this.values).join(" ");
  }
}

class FakeElement {
  constructor(tagName, attributes = {}) {
    this.tagName = tagName.toUpperCase();
    this.nodeType = 1;
    this.parentElement = null;
    this.children = [];
    this.classList = new FakeClassList(this);
    this.attributes = new Map(Object.entries(attributes));
    this.isConnected = true;
    this.innerHTML = "";
    this.clientWidth = 0;
  }

  get id() {
    return this.getAttribute("id") || "";
  }

  set id(value) {
    this.setAttribute("id", value);
  }

  get href() {
    const href = this.getAttribute("href");
    return href ? new URL(href, "https://www.nicovideo.jp/search/test").href : "";
  }

  get className() {
    return this.classList.toString();
  }

  set className(value) {
    this.classList.values = new Set(String(value).split(/\s+/).filter(Boolean));
  }

  getBoundingClientRect() {
    return {width: this.clientWidth};
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.children.forEach((child) => {
      child.parentElement = null;
      child.isConnected = false;
    });
    this.children = [];
    children.forEach((child) => this.appendChild(child));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  matches(selector) {
    if (selector === "a") return this.tagName === "A";
    if (selector === "img") return this.tagName === "IMG";
    if (selector === "[data-group]") return this.hasAttribute("data-group");
    if (selector === "[data-ncnl-cache-icon]") {
      return this.hasAttribute("data-ncnl-cache-icon");
    }
    if (selector === ".cacheIcon") return this.classList.contains("cacheIcon");
    return false;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const selectors = selector.split(",").map((part) => part.trim());
    const matches = [];
    const visit = (element) => {
      for (const child of element.children) {
        if (selectors.some((part) => child.matches(part))) matches.push(child);
        visit(child);
      }
    };
    visit(this);
    return matches;
  }

  closest(selector) {
    for (let element = this; element; element = element.parentElement) {
      if (element.matches(selector)) return element;
    }
    return null;
  }

  remove() {
    if (!this.parentElement) return;
    this.parentElement.children = this.parentElement.children.filter((child) => child !== this);
    this.parentElement = null;
    this.isConnected = false;
  }
}

function createVideoAnchor(id, thumbnailWidth = 160) {
  const anchor = new FakeElement("a", {href: `/watch/${id}`});
  const thumbnailHost = new FakeElement("div", {"data-group": "true"});
  thumbnailHost.clientWidth = thumbnailWidth;
  thumbnailHost.appendChild(new FakeElement("img"));
  anchor.appendChild(thumbnailHost);
  return {anchor, thumbnailHost};
}

function createPage(cacheInfo, thumbnailWidths = {}) {
  const body = new FakeElement("body");
  const head = new FakeElement("head");
  const cards = ["sm1", "sm2", "sm3", "sm4"].map(
    (id) => createVideoAnchor(id, thumbnailWidths[id] || 160),
  );
  cards.forEach(({anchor}) => body.appendChild(anchor));
  const observers = [];
  const resizeObservers = [];
  const requests = [];

  class FakeMutationObserver {
    constructor(callback) {
      this.callback = callback;
      observers.push(this);
    }

    observe(target, options) {
      this.target = target;
      this.options = options;
    }
  }

  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      this.targets = new Set();
      resizeObservers.push(this);
    }

    observe(target) {
      this.targets.add(target);
    }

    unobserve(target) {
      this.targets.delete(target);
    }
  }

  const document = {
    body,
    head,
    addEventListener() {},
    createElement(tagName) {
      return new FakeElement(tagName);
    },
    getElementById(id) {
      return head.querySelectorAll("style").find((element) => element.id === id) || null;
    },
    querySelectorAll(selector) {
      return body.querySelectorAll(selector);
    },
  };
  const NicoCache_nl = {};
  const window = {NicoCache_nl};
  window.window = window;
  const context = vm.createContext({
    URL,
    console,
    document,
    fetch: async (url, init) => {
      requests.push({url, init});
      const ids = JSON.parse(init.body).videoIds;
      return {
        ok: true,
        json: async () => Object.fromEntries(ids.map((id) => [id, cacheInfo[id] || null])),
      };
    },
    MutationObserver: FakeMutationObserver,
    NicoCache_nl,
    ResizeObserver: FakeResizeObserver,
    Node: {ELEMENT_NODE: 1, DOCUMENT_NODE: 9},
    setTimeout,
    window,
  });
  context.document.createElementNS = (namespace, tagName) => new FakeElement(tagName);
  vm.runInContext(cacheDisplaySource, context, {
    filename: "local/ncnl_cache_display.js",
  });
  vm.runInContext(indicatorSource, context, {
    filename: "local/15_cached_link_color.js",
  });
  return {cards, observers, resizeObservers, requests};
}

function videoInfo({videoMode = "1080p", audioBitrate = 192, legacyLow = false} = {}) {
  const cache = {complete: true, videoMode, audioBitrate, legacyLow};
  return {
    caches: {
      cache,
    },
    preferred: "cache",
  };
}

const flushAsyncWork = () => new Promise((resolve) => setTimeout(resolve, 20));

test("v3のCMAF品質とキャッシュなしをリンク色classとアイコンへ反映する", async () => {
  const page = createPage({
    sm1: videoInfo({videoMode: "360p-lowest", audioBitrate: 64, legacyLow: true}),
    sm2: videoInfo({videoMode: "720p", audioBitrate: 192}),
    sm3: null,
    sm4: videoInfo(),
  });
  await flushAsyncWork();

  assert.equal(page.requests[0].url,
    "https://nicocachenl.test/api/v1/cache-entry-queries");
  assert.deepEqual(JSON.parse(page.requests[0].init.body).videoIds,
    ["sm1", "sm2", "sm3", "sm4"]);
  const expected = [
    ["nl-cached-smile-normal", "ncnl-cache-quality-low"],
    ["nl-cached-smile-normal", "ncnl-cache-quality-hd"],
    null,
    ["nl-cached-smile-normal", "ncnl-cache-quality-fhd"],
  ];
  page.cards.forEach(({anchor, thumbnailHost}, index) => {
    if (expected[index] === null) {
      assert.equal(anchor.classList.contains("nl-cached-common"), false);
      assert.equal(thumbnailHost.querySelector("[data-ncnl-cache-icon]"), null);
      return;
    }
    assert.equal(anchor.classList.contains(expected[index][0]), true);
    const icon = thumbnailHost.querySelector("[data-ncnl-cache-icon]");
    assert.ok(icon);
    assert.equal(icon.classList.contains(expected[index][1]), true);
    assert.equal(thumbnailHost.classList.contains("ncnl-cache-thumbnail-host"), true);
  });

  const observer = page.observers[0];
  assert.equal(observer.options.attributes, true);
  assert.ok(observer.options.attributeFilter.includes("href"));
});

test("SPAで既存リンクのhrefが変わるとclassとアイコンを更新・除去する", async () => {
  const page = createPage({
    sm1: videoInfo({videoMode: "360p-lowest", audioBitrate: 64, legacyLow: true}),
    sm2: videoInfo({videoMode: "720p"}),
    sm3: null,
    sm4: videoInfo(),
  });
  await flushAsyncWork();

  const {anchor, thumbnailHost} = page.cards[0];
  const observer = page.observers[0];
  anchor.setAttribute("href", "/watch/sm4");
  observer.callback([{type: "attributes", target: anchor, addedNodes: []}]);
  await flushAsyncWork();

  assert.equal(anchor.classList.contains("nl-cached-smile-normal"), true);
  assert.equal(
    thumbnailHost.querySelector("[data-ncnl-cache-icon]").classList.contains(
      "ncnl-cache-quality-fhd",
    ),
    true,
  );

  anchor.setAttribute("href", "/watch/sm99");
  observer.callback([{type: "attributes", target: anchor, addedNodes: []}]);
  await flushAsyncWork();

  assert.equal(anchor.classList.contains("nl-cached-common"), false);
  assert.equal(thumbnailHost.querySelector("[data-ncnl-cache-icon]"), null);
});

test("従来のnlFilterが追加したアイコンとは重複しない", async () => {
  const page = createPage({
    sm1: videoInfo({videoMode: "360p-lowest", audioBitrate: 64, legacyLow: true}),
    sm2: null,
    sm3: null,
    sm4: null,
  });
  const legacyIcon = new FakeElement("div");
  legacyIcon.classList.add("cacheIcon", "economyIconImgMin");
  page.cards[0].anchor.appendChild(legacyIcon);
  await flushAsyncWork();

  assert.equal(page.cards[0].anchor.querySelectorAll(".cacheIcon").length, 1);
  assert.equal(legacyIcon.hasAttribute("data-ncnl-cache-icon"), false);
});

test("サムネイル幅が120px未満ならCアイコンへ切り替え、リサイズにも追従する", async () => {
  const page = createPage({
    sm1: videoInfo(),
    sm2: null,
    sm3: null,
    sm4: null,
  }, {sm1: 94});
  await flushAsyncWork();

  const {thumbnailHost} = page.cards[0];
  const icon = thumbnailHost.querySelector("[data-ncnl-cache-icon]");
  assert.equal(icon.classList.contains("ncnl-cache-icon--compact"), true);

  thumbnailHost.clientWidth = 120;
  page.resizeObservers[0].callback([{target: thumbnailHost}]);
  assert.equal(icon.classList.contains("ncnl-cache-icon--compact"), false);
});

test("キャッシュ情報取得後に遅延描画されたサムネイルへアイコンを追加する", async () => {
  const page = createPage({
    sm1: videoInfo(),
    sm2: null,
    sm3: null,
    sm4: null,
  });
  const {anchor, thumbnailHost} = page.cards[0];
  anchor.children = [];
  thumbnailHost.parentElement = null;
  await flushAsyncWork();
  assert.equal(anchor.querySelector(".cacheIcon"), null);

  anchor.appendChild(thumbnailHost);
  page.observers[0].callback([{
    type: "childList",
    target: anchor,
    addedNodes: [thumbnailHost],
  }]);
  await flushAsyncWork();

  const icon = thumbnailHost.querySelector("[data-ncnl-cache-icon]");
  assert.ok(icon);
  assert.equal(icon.classList.contains("ncnl-cache-quality-fhd"), true);
  assert.equal(page.requests.length, 1);
});

function createWatchPage(thumbnailWidth) {
  const itemSelector =
    '[data-anchor-page="watch"][data-anchor-href*="/watch/"][data-decoration-video-id]';
  const thumbnailSelector = 'a[href*="/watch/"] img[src*="/thumbnails/"]';
  const item = new FakeElement("article", {
    "data-anchor-page": "watch",
    "data-anchor-href": "/watch/sm1",
    "data-decoration-video-id": "sm1",
  });
  const thumbnail = new FakeElement("img", {src: "/thumbnails/sm1/1.M"});
  thumbnail.clientWidth = thumbnailWidth;
  item.appendChild(thumbnail);

  item.matches = (selector) => selector === itemSelector;
  item.querySelector = (selector) => {
    if (selector === thumbnailSelector) return thumbnail;
    if (selector === ":scope .cacheIcon") {
      return item.children.find((child) => child.classList.contains("cacheIcon")) || null;
    }
    return null;
  };
  item.querySelectorAll = (selector) => selector === ":scope .cacheIcon"
    ? item.children.filter((child) => child.classList.contains("cacheIcon"))
    : [];
  thumbnail.insertAdjacentElement = (position, icon) => {
    assert.equal(position, "afterend");
    item.appendChild(icon);
  };

  const requests = [];
  const resizeObservers = [];
  class FakeMutationObserver {
    constructor(callback) {
      this.callback = callback;
    }

    observe(target, options) {
      this.target = target;
      this.options = options;
    }
  }
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      resizeObservers.push(this);
    }

    observe(target) {
      this.target = target;
    }

    unobserve(target) {
      if (this.target === target) this.target = null;
    }
  }

  const body = new FakeElement("body");
  const document = {
    nodeType: 9,
    body,
    addEventListener() {},
    createElement(tagName) {
      return new FakeElement(tagName);
    },
    querySelectorAll(selector) {
      return selector === itemSelector ? [item] : [];
    },
  };
  const NicoCache_nl = {};
  const window = {NicoCache_nl};
  window.window = window;
  const context = vm.createContext({
    console,
    document,
    fetch: async (url, init) => {
      requests.push({url, init});
      return {
        ok: true,
        json: async () => ({sm1: videoInfo()}),
      };
    },
    MutationObserver: FakeMutationObserver,
    NicoCache_nl,
    Node: {ELEMENT_NODE: 1, DOCUMENT_NODE: 9},
    ResizeObserver: FakeResizeObserver,
    setTimeout,
    window,
  });
  context.document.createElementNS = (namespace, tagName) => new FakeElement(tagName);
  vm.runInContext(cacheDisplaySource, context, {
    filename: "local/ncnl_cache_display.js",
  });
  vm.runInContext(watchIndicatorSource, context, {
    filename: "local/20_watchpage.js",
  });
  return {item, requests, resizeObservers, thumbnail};
}

test("視聴ページの関連動画もサムネイル幅に応じてアイコンを切り替える", async () => {
  const page = createWatchPage(160);
  await flushAsyncWork();

  assert.equal(page.requests[0].url,
    "https://nicocachenl.test/api/v1/cache-entry-queries");
  assert.deepEqual(JSON.parse(page.requests[0].init.body).videoIds, ["sm1"]);
  const icon = page.item.querySelector(":scope .cacheIcon");
  assert.equal(icon.classList.contains("ncnl-cache-quality-fhd"), true);
  assert.equal(icon.classList.contains("ncnl-cache-icon--compact"), false);

  page.thumbnail.clientWidth = 94;
  page.resizeObservers[0].callback([{target: page.thumbnail}]);
  assert.equal(icon.classList.contains("ncnl-cache-icon--compact"), true);
});
