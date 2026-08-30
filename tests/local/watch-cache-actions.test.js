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
    this.hidden = false;
    this._rect = {left: 0, top: 0, width: 0, height: 36};
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

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  get previousElementSibling() {
    if (!this.parentElement) return null;
    const index = this.parentElement.children.indexOf(this);
    return index > 0 ? this.parentElement.children[index - 1] : null;
  }

  get nextElementSibling() {
    if (!this.parentElement) return null;
    const index = this.parentElement.children.indexOf(this);
    return index >= 0 ? this.parentElement.children[index + 1] || null : null;
  }

  get isConnected() {
    let element = this;
    while (element.parentElement) element = element.parentElement;
    return element === this.ownerDocument.body || element === this.ownerDocument.documentElement;
  }

  appendChild(child) {
    if (child.parentElement) {
      child.parentElement.children = child.parentElement.children.filter((item) => item !== child);
    }
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  insertBefore(child, reference) {
    if (child.parentElement) {
      child.parentElement.children = child.parentElement.children.filter((item) => item !== child);
    }
    child.parentElement = this;
    const index = reference ? this.children.indexOf(reference) : -1;
    if (index < 0) this.children.push(child);
    else this.children.splice(index, 0, child);
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

  matches(selector) {
    return selector.startsWith(".")
      && this.className?.split(/\s+/).includes(selector.slice(1));
  }

  getBoundingClientRect() {
    const marginMatch = String(this.style.marginLeft || "").match(/\+\s*(\d+(?:\.\d+)?)px\)/);
    const styleLeft = String(this.style.left || "").match(/^(-?\d+(?:\.\d+)?)px$/);
    const left = styleLeft ? Number(styleLeft[1])
      : this._rect.left + (marginMatch ? Number(marginMatch[1]) : 0);
    const width = this.id === "ncnl_common_header_menu" ? 89 : this._rect.width;
    return {left, top: this._rect.top, right: left + width,
      bottom: this._rect.top + this._rect.height, width, height: this._rect.height};
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

function createAnchor(document, href, text = "") {
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.setAttribute("href", href);
  anchor.textContent = text;
  return anchor;
}

function populateHeader(document, commonHeader, mode) {
  const root = document.createElement("div");
  root.className = "nico-CommonHeaderRoot";
  const serviceNavigation = document.createElement("div");
  serviceNavigation.appendChild(createAnchor(document,
    "https://www.nicovideo.jp/video_top?cmnhd_ref=pos%3Dheader_servicelink", "動画"));
  root.appendChild(serviceNavigation);
  const accountNavigation = document.createElement("div");
  root.appendChild(accountNavigation);

  let accountItem;
  let registerItem;
  if (mode === "logged-in") {
    accountNavigation.appendChild(document.createElement("div"));
    accountItem = document.createElement("div");
    accountItem._rect = {left: 1210, top: 0, width: 62, height: 36};
    accountItem.appendChild(createAnchor(document, "https://www.nicovideo.jp/my", "アカウント"));
    accountNavigation.appendChild(accountItem);
  } else {
    accountNavigation.appendChild(createAnchor(document,
      "https://account.nicovideo.jp/login", "ログイン"));
    registerItem = document.createElement("div");
    registerItem._rect = {left: 1098, top: 0, width: 112, height: 36};
    registerItem.appendChild(createAnchor(document,
      "https://account.nicovideo.jp/register/simple", "ニコニコ会員登録"));
    accountNavigation.appendChild(registerItem);
    accountItem = document.createElement("div");
    accountItem._rect = {left: 1210, top: 0, width: 62, height: 36};
    accountNavigation.appendChild(accountItem);
  }
  commonHeader.appendChild(root);
  return {accountItem, registerItem, root};
}

function createPage({
  pathname = "/watch/sm9",
  headerMode = "logged-out",
  innerWidth = 0,
} = {}) {
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
    querySelector(selector) {
      return document.body?.querySelector(selector) || null;
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
  const header = headerMode === "pending"
    ? null
    : populateHeader(document, commonHeader, headerMode);

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
    innerWidth,
    NicoCache_nl,
    location: {pathname, href: `https://www.nicovideo.jp${pathname}`},
    addEventListener(type, listener) { windowListeners.set(type, listener); },
  };
  const observerCallbacks = [];
  const windowListeners = new Map();
  const context = vm.createContext({
    alert(message) { alerts.push(message); },
    confirm() { return true; },
    document,
    encodeURIComponent,
    fetch(url, init) {
      requests.push({url, init});
      return Promise.resolve({ok: true});
    },
    getComputedStyle() { return {marginLeft: "0px"}; },
    MutationObserver: class {
      constructor(callback) { observerCallbacks.push(callback); }
      disconnect() {}
      observe() {}
    },
    NicoCache_nl,
    requestAnimationFrame(callback) { callback(); return 1; },
    setTimeout,
    URL,
    window,
  });
  vm.runInContext(source, context, {
    filename: "local/05_nicocache_menu.js",
  });
  return {
    alerts,
    commonHeader,
    document,
    header,
    populateHeader(mode) { return populateHeader(document, commonHeader, mode); },
    requests,
    watchListeners,
    window,
    windowListeners,
    flushMutations() { observerCallbacks.forEach((callback) => callback()); },
  };
}

test("watchページのNicoCacheメニューをREST APIへ接続しSPA動画切替へ追従する", async () => {
  const page = createPage();
  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.ok(container);
  assert.equal(container.parentElement.id, "ncnl_common_header_account_host");
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
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
  assert.equal(container.parentElement.id, "ncnl_common_header_account_host");
});

test("非ログイン時は会員登録とアカウントプレースホルダーの間へ配置する", () => {
  const page = createPage({pathname: "/search/test"});
  const container = page.document.getElementById("ncnl_common_header_menu");
  const actions = container.querySelector(".ncnl-common-header-actions");
  const menuRect = container.getBoundingClientRect();
  const accountRect = page.header.accountItem.getBoundingClientRect();

  assert.equal(container.parentElement.id, "ncnl_common_header_account_host");
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
  assert.equal(page.header.registerItem.nextElementSibling, page.header.accountItem);
  assert.ok(menuRect.right <= accountRect.left);
  assert.equal(page.header.accountItem.getAttribute("data-ncnl-account-space"), null);
  assert.equal(page.header.accountItem.style.marginLeft, "");
  assert.equal(container.getAttribute("data-ncnl-video-id"), null);
  assert.equal(actions.hidden, true);
});

test("ログイン時も公式myリンクのアカウント項目直前へ配置する", () => {
  const page = createPage({pathname: "/", headerMode: "logged-in"});
  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.equal(container.parentElement.id, "ncnl_common_header_account_host");
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
  assert.equal(page.header.accountItem.getAttribute("data-ncnl-account-space"), null);
  assert.equal(page.header.accountItem.style.marginLeft, "");
});

test("狭いビューポートでは画面外のアカウント項目より手前へクランプする", () => {
  const page = createPage({pathname: "/", headerMode: "logged-in", innerWidth: 480});
  const container = page.document.getElementById("ncnl_common_header_menu");
  const rect = container.getBoundingClientRect();
  assert.ok(rect.left >= 0);
  assert.ok(rect.right <= 480);
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
});

test("公式CommonHeaderルートの生成前はDOMへ挿入せず生成後に配置する", () => {
  const page = createPage({headerMode: "pending"});
  assert.equal(page.document.getElementById("ncnl_common_header_menu"), null);
  assert.equal(page.commonHeader.children.length, 0);

  page.populateHeader("logged-out");
  page.flushMutations();
  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.ok(container);
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
});

test("CommonHeaderのホストIDが異なっても公式ルートから配置する", () => {
  const page = createPage();
  page.commonHeader.id = "common-header";
  page.document.elements.delete("CommonHeader");
  page.flushMutations();

  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.ok(container);
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
});

test("サービス固有my URLも公式cmnhd_refからアカウント項目と判定する", () => {
  const page = createPage({headerMode: "logged-in"});
  const accountAnchor = page.header.root.querySelectorAll("a[href]")
    .find((anchor) => anchor.href.includes("/my"));
  accountAnchor.href = "https://seiga.nicovideo.jp/my/?cmnhd_ref=device%3Dpc%26site%3Dseiga%26pos%3Dheader";
  accountAnchor.setAttribute("href", accountAnchor.href);
  page.flushMutations();

  const container = page.document.getElementById("ncnl_common_header_menu");
  assert.equal(container.getAttribute("data-ncnl-mounted"), "account");
});
