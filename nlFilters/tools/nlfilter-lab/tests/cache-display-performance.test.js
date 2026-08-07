"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const repository = path.resolve(process.argv[2] || path.join(__dirname, "../../../.."));

class FakeClassList {
  constructor() {
    this.values = new Set();
  }

  add(...classNames) {
    classNames.forEach((className) => this.values.add(className));
  }

  remove(...classNames) {
    classNames.forEach((className) => this.values.delete(className));
  }

  contains(className) {
    return this.values.has(className);
  }
}

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName.toUpperCase();
    this.nodeType = 1;
    this.attributes = new Map();
    this.children = [];
    this.classList = new FakeClassList();
    this.replaceChildrenCalls = 0;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.replaceChildrenCalls++;
    this.children = [];
    children.forEach((child) => this.appendChild(child));
  }
}

function loadScript(relativePath) {
  const source = fs.readFileSync(path.join(repository, relativePath), "utf8");
  vm.runInThisContext(source, {filename: relativePath});
}

function testIdenticalBadgeUpdateIsNoOp() {
  global.document = {
    createElement: (tagName) => new FakeElement(tagName),
    createElementNS: (_namespace, tagName) => new FakeElement(tagName),
  };
  global.window = {NicoCache_nl: {showCacheQuality: true}};
  global.NicoCache_nl = window.NicoCache_nl;

  loadScript("local/ncnl_cache_display.js");

  const icon = new FakeElement("span");
  const description = {
    cacheId: "sm9[1080p,192].hls",
    isCmaf: true,
    economy: false,
    videoMode: "1080p",
    videoBitrate: 0,
    audioBitrate: 192,
    quality: "fhd",
    title: "NicoCache_nl キャッシュ済み: 映像 1080p / 音声 192kbps",
  };

  NicoCache_nl.cacheDisplay.updateIcon(icon, description, false);
  NicoCache_nl.cacheDisplay.updateIcon(icon, description, false);
  assert.equal(icon.replaceChildrenCalls, 1, "同一表示の更新でDOMを再構築しない");

  NicoCache_nl.cacheDisplay.updateIcon(icon, {...description, audioBitrate: 128}, false);
  assert.equal(icon.replaceChildrenCalls, 2, "品質が変わった場合だけDOMを更新する");
}

function testBadgeMutationDoesNotReenterAnchorHooks() {
  const observers = [];
  class FakeMutationObserver {
    constructor(callback) {
      this.callback = callback;
      observers.push(this);
    }

    observe() {
    }
  }

  global.Node = {ELEMENT_NODE: 1, DOCUMENT_NODE: 9};
  global.MutationObserver = FakeMutationObserver;
  global.ResizeObserver = undefined;
  global.document = {
    body: {},
    head: {appendChild() {}},
    createElement: () => new FakeElement("style"),
    getElementById: () => null,
    querySelectorAll: () => [],
    addEventListener() {},
  };

  let hookCalls = 0;
  global.window = {
    NicoCache_nl: {
      cacheDisplay: {
        applyLinkClasses() {},
        describe() { return null; },
        updateIcon() {},
      },
    },
    NicocacheNLVideoAnchorHooks: [() => { hookCalls++; }],
  };
  global.NicoCache_nl = window.NicoCache_nl;

  loadScript("local/15_cached_link_color.js");
  assert.equal(observers.length, 1, "リンク監視を開始する");

  const anchor = {
    href: "https://www.nicovideo.jp/other/",
    parentElement: null,
    getAttribute: () => null,
  };
  const ordinaryChild = {
    nodeType: Node.ELEMENT_NODE,
    matches: () => false,
    closest: (selector) => selector === "a" ? anchor : null,
    querySelectorAll: () => [],
  };
  observers[0].callback([
    {type: "childList", addedNodes: [ordinaryChild]},
    {type: "childList", addedNodes: [ordinaryChild]},
  ]);
  assert.equal(hookCalls, 1, "同一Mutationバッチのリンクを一度だけ処理する");

  const cacheIcon = {};
  const badgeChild = {
    nodeType: Node.ELEMENT_NODE,
    matches: () => false,
    closest: (selector) => selector === "[data-ncnl-cache-icon]" ? cacheIcon : anchor,
    querySelectorAll: () => [],
  };
  observers[0].callback([{type: "childList", addedNodes: [badgeChild]}]);
  assert.equal(hookCalls, 1, "バッジ自身のMutationをリンク処理へ戻さない");
}

testIdenticalBadgeUpdateIsNoOp();
testBadgeMutationDoesNotReenterAnchorHooks();
console.log("PASS キャッシュバッジの再描画・Mutation再入を抑止");
