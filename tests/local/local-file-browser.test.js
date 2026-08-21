"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.resolve(__dirname, "..", "..", "local", "nicocache-web", "app.js"),
  "utf8",
).replace(/^import[^\n]+\n/, "")
  .replace("route().catch(showError);",
    "globalThis.routePromise = route().catch(showError);");

test("localファイル画面はAPI一覧を安全なフォルダーテーブルへ描画する", async () => {
  const app = {innerHTML: ""};
  const requested = [];
  const context = vm.createContext({
    console,
    location: {pathname: "/local"},
    navigator: {language: "ja-JP"},
    document: {
      documentElement: {},
      querySelector(selector) {
        if (selector === "#app") return app;
        if (selector === "#loading-template") {
          return {innerHTML: "<p>loading</p>"};
        }
        return null;
      },
      querySelectorAll() {
        return [];
      },
    },
    async fetch(url) {
      requested.push(url);
      return {
        ok: true,
        async json() {
          return {
            path: "",
            parentPath: null,
            entries: [
              {
                name: "日本語 フォルダー",
                path: "日本語 フォルダー",
                kind: "directory",
                size: null,
                createdAt: "2026-08-21T01:02:03Z",
                modifiedAt: "2026-08-21T04:05:06Z",
                mediaType: null,
                url: "https://www.nicovideo.jp/local/%E6%97%A5%E6%9C%AC%E8%AA%9E%20%E3%83%95%E3%82%A9%E3%83%AB%E3%83%80%E3%83%BC/",
                source: "application",
              },
              {
                name: "<img src=x onerror=alert(1)>.js",
                path: "<img src=x onerror=alert(1)>.js",
                kind: "file",
                size: 6144,
                createdAt: "2026-08-21T01:02:03Z",
                modifiedAt: "2026-08-21T04:05:06Z",
                mediaType: "application/x-javascript",
                url: "https://www.nicovideo.jp/local/%3Cimg%20src%3Dx%20onerror%3Dalert%281%29%3E.js",
                source: "user",
              },
            ],
          };
        },
      };
    },
  });

  vm.runInContext(source, context, {filename: "app.js"});
  await context.routePromise;

  assert.deepEqual(requested, ["/api/v1/local-files"]);
  assert.match(app.innerHTML,
    /href="\/local\/%E6%97%A5%E6%9C%AC%E8%AA%9E%20%E3%83%95%E3%82%A9%E3%83%AB%E3%83%80%E3%83%BC"/);
  assert.match(app.innerHTML, /application\/x-javascript/);
  assert.match(app.innerHTML, /6 KiB/);
  assert.match(app.innerHTML, /利用者/);
  assert.doesNotMatch(app.innerHTML, /<img src=x/);
  assert.match(app.innerHTML, /&lt;img src=x onerror=alert\(1\)&gt;\.js/);
});
