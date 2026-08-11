"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const source = fs.readFileSync(
  path.join(repositoryRoot, "local", "05_cache_remove_button.js"),
  "utf8",
);

class FakeElement {
  constructor(tagName, document) {
    this.tagName = tagName.toUpperCase();
    this.ownerDocument = document;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.textContent = "";
    this.disabled = false;
  }

  set id(value) {
    this.attributes.set("id", value);
    this.ownerDocument.elements.set(value, this);
  }

  get id() {
    return this.attributes.get("id") || "";
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  click() {
    this.listeners.get("click")?.({target: this});
  }

  querySelectorAll(selector) {
    const found = [];
    const visit = (element) => {
      if (selector === "a[data-ncnl-action]"
          && element.tagName === "A"
          && element.attributes.has("data-ncnl-action")) {
        found.push(element);
      }
      element.children.forEach(visit);
    };
    this.children.forEach(visit);
    return found;
  }
}

function createPage() {
  const elements = new Map();
  const document = {
    elements,
    createElement(tagName) {
      return new FakeElement(tagName, document);
    },
    getElementById(id) {
      return elements.get(id) || null;
    },
  };
  document.documentElement = document.createElement("html");
  document.head = document.createElement("head");
  document.body = document.createElement("body");
  const commonHeader = document.createElement("div");
  commonHeader.id = "CommonHeader";
  document.body.appendChild(commonHeader);

  const requests = [];
  const alerts = [];
  const watchListeners = new Map();
  const NicoCache_nl = {
    get(url, callback) {
      requests.push({url, callback});
    },
    watch: {
      getVideoID() {
        return "sm9";
      },
      addEventListener(type, callback) {
        watchListeners.set(type, callback);
      },
    },
  };
  const window = {
    NicoCache_nl,
    location: {pathname: "/watch/sm9"},
    addEventListener() {},
  };
  const context = vm.createContext({
    alert(message) { alerts.push(message); },
    confirm() { return true; },
    document,
    encodeURIComponent,
    MutationObserver: class {
      disconnect() {}
      observe() {}
    },
    NicoCache_nl,
    setTimeout,
    window,
  });
  vm.runInContext(source, context, {
    filename: "local/05_cache_remove_button.js",
  });
  return {alerts, document, requests, watchListeners};
}

test("watchページの4操作を現行APIへ接続しSPA動画切替へ追従する", () => {
  const page = createPage();
  const container = page.document.getElementById("cache_remove_workaround");
  assert.ok(container);
  assert.equal(container.children.length, 4);
  assert.deepEqual(
    container.children.slice(0, 3).map((link) => link.href),
    [
      "/cache/sm9/auto/movie",
      "/cache/sm9.comments.json",
      "/cache/sm9/auto/audio",
    ],
  );
  assert.deepEqual(
    container.children.slice(0, 3).map((link) => link.getAttribute("download")),
    ["", "", ""],
  );

  const removeButton = container.children[3];
  removeButton.click();
  assert.equal(removeButton.disabled, true);
  assert.equal(page.requests[0].url, "/cache/ajax_rmall?sm9");
  page.requests[0].callback({status: 200, responseText: "OK"});
  assert.equal(removeButton.disabled, false);
  assert.deepEqual(page.alerts, ["キャッシュを削除しました: sm9"]);

  page.watchListeners.get("videoChanged")("sm10");
  assert.deepEqual(
    container.children.slice(0, 3).map((link) => link.href),
    [
      "/cache/sm10/auto/movie",
      "/cache/sm10.comments.json",
      "/cache/sm10/auto/audio",
    ],
  );
});
