"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const source = fs.readFileSync(
  path.join(repositoryRoot, "local", "05_nicocache_menu.js"),
  "utf8",
);

class FakeElement {
  constructor(tagName, document) {
    this.tagName = tagName.toUpperCase();
    this.ownerDocument = document;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.parentElement = null;
    this.style = {
      marginLeft: "",
      removeProperty: (name) => {
        this.style[name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = "";
      },
    };
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

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  click() {
    this.listeners.get("click")?.({target: this});
  }

  contains(target) {
    return target === this || this.children.some((child) => child.contains(target));
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const found = [];
    const visit = (element) => {
      const matches = selector === "a[data-ncnl-action]"
        ? element.tagName === "A" && element.attributes.has("data-ncnl-action")
        : selector === "[role=menuitem]"
          ? element.getAttribute("role") === "menuitem"
          : selector === "a[href]"
            ? element.tagName === "A" && typeof element.href === "string"
            : selector.startsWith(".")
              ? element.className?.split(/\s+/).includes(selector.slice(1))
              : false;
      if (matches) {
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
  const documentListeners = new Map();
  const document = {
    elements,
    fullscreenElement: null,
    createElement(tagName) {
      return new FakeElement(tagName, document);
    },
    getElementById(id) {
      return elements.get(id) || null;
    },
    addEventListener(type, listener) {
      documentListeners.set(type, listener);
    },
    dispatchEvent(event) {
      documentListeners.get(event.type)?.(event);
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
    fetch(url, init) {
      requests.push({url, init});
      return Promise.resolve({ok: true});
    },
    MutationObserver: class {
      disconnect() {}
      observe() {}
    },
    NicoCache_nl,
    setTimeout,
    window,
  });
  vm.runInContext(source, context, {
    filename: "local/05_nicocache_menu.js",
  });
  return {alerts, document, requests, watchListeners};
}

test("watchページのNicoCacheメニューをREST APIへ接続しSPA動画切替へ追従する", async () => {
  const page = createPage();
  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.ok(container);
  assert.equal(container.parentElement.id, "CommonHeader");
  assert.equal(container.children[0].textContent, "NicoCache");

  const popover = page.document.getElementById("ncnl_common_header_popover");
  const actionLinks = container.querySelectorAll("a[data-ncnl-action]")
    .filter((link) => typeof link._ncnlSuffix === "string");
  assert.deepEqual(
    actionLinks.map((link) => link.href),
    [
      "https://nicocachenl.test/api/v1/videos/sm9/exports/video",
      "https://nicocachenl.test/api/v1/videos/sm9/exports/comments",
      "https://nicocachenl.test/api/v1/videos/sm9/exports/audio",
    ],
  );
  assert.deepEqual(
    actionLinks.map((link) => link.getAttribute("download")),
    ["", "", ""],
  );

  const menuItems = popover.querySelectorAll("[role=menuitem]");
  const removeButton = menuItems.find(
    (item) => item.getAttribute("data-ncnl-action") === "remove",
  );
  const manageLink = menuItems.find(
    (item) => item.getAttribute("data-ncnl-action") === "manage",
  );
  assert.equal(manageLink.href, "https://nicocachenl.test/cache");
  removeButton.click();
  assert.equal(removeButton.disabled, true);
  assert.equal(page.requests[0].url,
    "https://nicocachenl.test/api/v1/videos/sm9/cache-entries");
  assert.equal(page.requests[0].init.method, "DELETE");
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(removeButton.disabled, false);
  assert.deepEqual(page.alerts, ["キャッシュを削除しました: sm9"]);

  page.watchListeners.get("videoChanged")("sm10");
  assert.deepEqual(
    actionLinks.map((link) => link.href),
    [
      "https://nicocachenl.test/api/v1/videos/sm10/exports/video",
      "https://nicocachenl.test/api/v1/videos/sm10/exports/comments",
      "https://nicocachenl.test/api/v1/videos/sm10/exports/audio",
    ],
  );
});

test("全画面表示中はNicoCacheメニューを閉じて非表示状態にし解除時に復帰する", () => {
  const page = createPage();
  const container = page.document.getElementById("ncnl_common_header_menu");
  const trigger = container.querySelector(".ncnl-common-header-trigger");
  const popover = container.querySelector(".ncnl-common-header-popover");
  const style = page.document.getElementById("ncnl_common_header_menu_style");

  container.listeners.get("mouseenter")();
  assert.equal(trigger.getAttribute("aria-expanded"), "true");

  page.document.fullscreenElement = page.document.documentElement;
  page.document.dispatchEvent({type: "fullscreenchange"});
  assert.equal(container.getAttribute("data-ncnl-fullscreen"), "true");
  assert.equal(container.getAttribute("aria-hidden"), "true");
  assert.equal(trigger.getAttribute("aria-expanded"), "false");
  assert.equal(popover.getAttribute("aria-hidden"), "true");
  assert.match(style.textContent,
    /#ncnl_common_header_menu\[data-ncnl-fullscreen=true\]\{display:none;\}/);

  page.document.fullscreenElement = null;
  page.document.dispatchEvent({type: "fullscreenchange"});
  assert.equal(container.getAttribute("data-ncnl-fullscreen"), null);
  assert.equal(container.getAttribute("aria-hidden"), null);
  assert.equal(container.parentElement.id, "CommonHeader");
});
