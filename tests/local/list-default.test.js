"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.resolve(__dirname, "..", "..", "local", "list.js.default"),
  "utf8",
);

function render(globals = {}) {
  const insertions = [];
  const context = vm.createContext({
    ...globals,
    document: {
      body: {
        insertAdjacentHTML(position, html) {
          insertions.push({position, html});
        },
      },
    },
  });
  vm.runInContext(source, context, {filename: "local/list.js.default"});
  vm.runInContext("makeCacheList()", context);
  assert.equal(insertions.length, 1);
  assert.equal(insertions[0].position, "beforeend");
  return insertions[0].html;
}

test("一時ファイルと完成キャッシュの操作リンクを表示する", () => {
  const html = render({
    ncversion: "NicoCache_nl test",
    tempList: {
      "sm9.tmp": ["一時タイトル", 25, 100, true, 0],
      "so1.tmp": ["停止中", 12, 0, false, 0],
    },
    cacheList: {
      sm10: ["完成タイトル", "", 100, 0],
      sm11: ["", "", 200, 0],
    },
  });

  assert.match(html, /NicoCache_nl test/);
  assert.match(html, /一時ファイル/);
  assert.match(html, /watch\/sm9/);
  assert.match(html, /DL中 \(25%\)/);
  assert.match(html, /rmtmp\?so1\.tmp/);
  assert.match(html, /\.\/sm10\/movie/);
  assert.match(html, /rm\?sm10/);
  assert.match(html, /title\?sm11/);
});

test("一覧データが未定義でも空の案内を表示する", () => {
  const html = render({ncversion: "NicoCache_nl test"});

  assert.doesNotMatch(html, /一時ファイル/);
  assert.match(html, /キャッシュはまだありません。/);
});
