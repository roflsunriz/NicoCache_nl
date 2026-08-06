"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const indicatorSource = fs.readFileSync(
  path.join(repositoryRoot, "local", "15_cached_link_color.js"),
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

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
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

function createVideoAnchor(id) {
  const anchor = new FakeElement("a", {href: `/watch/${id}`});
  const thumbnailHost = new FakeElement("div", {"data-group": "true"});
  thumbnailHost.appendChild(new FakeElement("img"));
  anchor.appendChild(thumbnailHost);
  return {anchor, thumbnailHost};
}

function createPage(cacheInfo) {
  const body = new FakeElement("body");
  const head = new FakeElement("head");
  const cards = ["sm1", "sm2", "sm3", "sm4"].map((id) => createVideoAnchor(id));
  cards.forEach(({anchor}) => body.appendChild(anchor));
  const observers = [];
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
  const window = {};
  window.window = window;
  const context = vm.createContext({
    URL,
    console,
    document,
    fetch: async (url) => {
      requests.push(url);
      const ids = url.split("?")[1].split(",");
      return {
        ok: true,
        json: async () => Object.fromEntries(ids.map((id) => [id, cacheInfo[id] || null])),
      };
    },
    MutationObserver: FakeMutationObserver,
    Node: {ELEMENT_NODE: 1, DOCUMENT_NODE: 9},
    setTimeout,
    window,
  });
  vm.runInContext(indicatorSource, context, {
    filename: "local/15_cached_link_color.js",
  });
  return {cards, observers, requests};
}

function videoInfo({dmc, economy}) {
  return {
    caches: {
      cache: {complete: true, dmc, economy},
    },
  };
}

const flushAsyncWork = () => new Promise((resolve) => setTimeout(resolve, 20));

test("4種類のキャッシュ状態をリンク色classとサムネイルアイコンへ反映する", async () => {
  const page = createPage({
    sm1: videoInfo({dmc: false, economy: true}),
    sm2: videoInfo({dmc: true, economy: true}),
    sm3: videoInfo({dmc: false, economy: false}),
    sm4: videoInfo({dmc: true, economy: false}),
  });
  await flushAsyncWork();

  assert.deepEqual(page.requests, ["/cache/info/v2?sm1,sm2,sm3,sm4"]);
  const expected = [
    ["nl-cached-smile-economy", "economyIconImgMin"],
    ["nl-cached-dmc-economy", "dmcEconomyIconImgMin"],
    ["nl-cached-smile-normal", "cacheIconImgMin"],
    ["nl-cached-dmc-normal", "dmcCacheIconImgMin"],
  ];
  page.cards.forEach(({anchor, thumbnailHost}, index) => {
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
    sm1: videoInfo({dmc: false, economy: true}),
    sm2: videoInfo({dmc: true, economy: true}),
    sm3: videoInfo({dmc: false, economy: false}),
    sm4: videoInfo({dmc: true, economy: false}),
  });
  await flushAsyncWork();

  const {anchor, thumbnailHost} = page.cards[0];
  const observer = page.observers[0];
  anchor.setAttribute("href", "/watch/sm4");
  observer.callback([{type: "attributes", target: anchor, addedNodes: []}]);
  await flushAsyncWork();

  assert.equal(anchor.classList.contains("nl-cached-dmc-normal"), true);
  assert.equal(
    thumbnailHost.querySelector("[data-ncnl-cache-icon]").classList.contains("dmcCacheIconImgMin"),
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
    sm1: videoInfo({dmc: false, economy: true}),
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

test("キャッシュ情報取得後に遅延描画されたサムネイルへアイコンを追加する", async () => {
  const page = createPage({
    sm1: videoInfo({dmc: true, economy: false}),
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
  assert.equal(icon.classList.contains("dmcCacheIconImgMin"), true);
  assert.equal(page.requests.length, 1);
});
