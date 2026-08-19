"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const moduleSource = fs.readFileSync(
  path.join(repositoryRoot, "local", "nicocache-web", "cache-manager.js"),
  "utf8",
);
const moduleUrl = `data:text/javascript;base64,${Buffer.from(moduleSource).toString("base64")}`;
const cacheManagerPromise = import(moduleUrl);

test("完成・一時キャッシュの異なる配列契約を正規化する", async () => {
  const {normalizeCacheResponse} = await cacheManagerPromise;
  const entries = normalizeCacheResponse({
    complete: {
      "sm20[1080p,192].hls": ["完成動画", "series", 4096, 100],
      "sm30.mp4": ["旧形式", "", 2048, 101],
    },
    temporary: {
      "sm10[360p-lowest,64].hls": ["取得中", 512, 1024, true, 102],
      "sm20[1080p,192].hls": ["一時を優先", 256, -1, false, 103],
    },
  });

  assert.equal(entries.length, 3);
  const temporary = entries.find((entry) => entry.cacheId.startsWith("sm10"));
  assert.deepEqual(
    {
      baseId: temporary.baseId,
      title: temporary.title,
      size: temporary.size,
      expectedSize: temporary.expectedSize,
      downloading: temporary.downloading,
      temporary: temporary.temporary,
      quality: temporary.quality,
    },
    {
      baseId: "sm10",
      title: "取得中",
      size: 512,
      expectedSize: 1024,
      downloading: true,
      temporary: true,
      quality: "360p · 64 kbps",
    },
  );
  assert.equal(entries.find((entry) => entry.cacheId.startsWith("sm20")).title,
    "一時を優先");
  assert.equal(entries.find((entry) => entry.cacheId === "sm30.mp4").quality,
    "unknown");
});

test("IDとタイトルの複数語検索、状態・画質フィルターを同時適用する", async () => {
  const {filterAndSortEntries, normalizeCacheResponse} = await cacheManagerPromise;
  const entries = normalizeCacheResponse({
    complete: {
      "sm30[1080p,192].hls": ["夜空 ライブ", "music", 300, 3],
      "nm2[480p,128].hls": ["夜空 解説", "lecture", 200, 2],
    },
    temporary: {
      "so10[360p,64].hls": ["夜空 ライブ", 100, 500, false, 1],
    },
  });

  assert.deepEqual(
    filterAndSortEntries(entries, {
      query: "夜空 ライブ",
      state: "complete",
      quality: "hd",
    }).map((entry) => entry.baseId),
    ["sm30"],
  );
  assert.deepEqual(
    filterAndSortEntries(entries, {query: "so10", state: "temporary"})
      .map((entry) => entry.baseId),
    ["so10"],
  );
});

test("ID優先順と選択した降順を安定して反映する", async () => {
  const {filterAndSortEntries, normalizeCacheResponse} = await cacheManagerPromise;
  const entries = normalizeCacheResponse({
    complete: {
      "so1.mp4": ["C", "", 1, 1],
      "sm20.mp4": ["B", "", 1, 1],
      "nm30.mp4": ["A", "", 1, 1],
      "sm2.mp4": ["D", "", 1, 1],
    },
    temporary: {},
  });

  assert.deepEqual(
    filterAndSortEntries(entries, {sort: "id", direction: "asc"})
      .map((entry) => entry.baseId),
    ["nm30", "sm2", "sm20", "so1"],
  );
  assert.deepEqual(
    filterAndSortEntries(entries, {sort: "id", direction: "desc"})
      .map((entry) => entry.baseId),
    ["so1", "sm20", "sm2", "nm30"],
  );
});

test("管理画面エントリーがキャッシュ管理モジュールを読み込む", () => {
  const appSource = fs.readFileSync(
    path.join(repositoryRoot, "local", "nicocache-web", "app.js"),
    "utf8",
  );
  assert.match(appSource, /from "\/assets\/cache-manager\.js"/);
  assert.match(appSource, /renderCacheManager/);
});
