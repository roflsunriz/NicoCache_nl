"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const repositoryRoot = path.resolve(__dirname, "..", "..");
const watchLibrarySource = fs.readFileSync(
  path.join(repositoryRoot, "local", "nllib_watch.js"),
  "utf8",
);

function createPage(nativeFetch) {
  const window = {
    addEventListener() {},
    fetch: nativeFetch,
    location: {
      href: "https://www.nicovideo.jp/watch/sm9",
      pathname: "/watch/sm9",
    },
  };
  const history = {
    pushState() {},
    replaceState() {},
  };
  const context = vm.createContext({
    URL,
    console,
    document: {
      addEventListener() {},
      body: null,
      querySelector() {
        return null;
      },
    },
    history,
    MutationObserver: class {
      disconnect() {}
      observe() {}
    },
    NicoCache_nl: {
      _metaServerResponseTag: null,
      watch: null,
    },
    setTimeout,
    window,
  });
  vm.runInContext(watchLibrarySource, context, {
    filename: "local/nllib_watch.js",
  });
  return {context, history, window};
}

test("fetchラッパーは呼出側のthisにかかわらずWindowをネイティブfetchへ渡す", async () => {
  const calls = [];
  let page;
  const response = {ok: false};
  const nativeFetch = function(input, init) {
    assert.equal(this, page.window);
    calls.push({input, init});
    return Promise.resolve(response);
  };
  page = createPage(nativeFetch);

  const result = await page.window.fetch.call(
    {notAWindow: true},
    "/api/example",
    {cache: "no-store"},
  );

  assert.equal(result, response);
  assert.deepEqual(calls, [
    {input: "/api/example", init: {cache: "no-store"}},
  ]);
});

test("動画情報fetchを監視しSPA切替後のapiDataへ反映する", async () => {
  let page;
  const response = {
    ok: true,
    clone() {
      return {
        json: async () => ({
          meta: {status: 200},
          data: {response: {video: {id: "sm10"}}},
        }),
      };
    },
  };
  const nativeFetch = function() {
    assert.equal(this, page.window);
    return Promise.resolve(response);
  };
  page = createPage(nativeFetch);

  await page.window.fetch.call(
    {notAWindow: true},
    "https://www.nicovideo.jp/watch/sm10?responseType=json",
  );
  await new Promise((resolve) => setTimeout(resolve, 0));

  page.window.location.pathname = "/watch/sm10";
  page.window.location.href = "https://www.nicovideo.jp/watch/sm10";
  page.history.pushState({}, "", "/watch/sm10");
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(page.context.NicoCache_nl.watch.apiData.video.id, "sm10");
});
