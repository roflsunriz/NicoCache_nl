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

function createPage(nativeFetch, options = {}) {
  const warnings = [];
  const NicoCache_nl = {
    _metaServerResponseTag: options.serverResponse
      ? {getAttribute: () => options.serverResponse}
      : null,
    watch: null,
  };
  const window = {
    addEventListener() {},
    fetch: nativeFetch,
    location: {
      href: "https://www.nicovideo.jp/watch/sm9",
      pathname: "/watch/sm9",
    },
    NicoCache_nl,
  };
  const history = {
    pushState() {},
    replaceState() {},
  };
  const context = vm.createContext({
    URL,
    console: {
      ...console,
      warn(...args) {
        warnings.push(args);
      },
    },
    document: {
      addEventListener() {},
      body: options.initialize ? {} : null,
      querySelector(selector) {
        return options.initialize && selector === 'video[data-name="video-content"]'
          ? {}
          : null;
      },
    },
    history,
    MutationObserver: class {
      disconnect() {}
      observe() {}
    },
    NicoCache_nl,
    setTimeout,
    window,
  });
  vm.runInContext(watchLibrarySource, context, {
    filename: "local/nllib_watch.js",
  });
  return {context, history, warnings, window};
}

function watchV4Envelope(videoId, groupedContents = false) {
  const videos = [{id: "video-h264-720p", isAvailable: true}];
  const audios = [{id: "audio-aac-192kbps", isAvailable: true}];
  return {
    meta: {status: 200},
    data: {
      response: {
        $watchV4: {
          data: {
            video: {
              id: videoId,
              title: "Watch V4",
              description: "Watch V4 description",
              thumbnail: {normal: "https://example.invalid/thumbnail.jpg"},
            },
            comment: {nvComment: {threadKey: "key"}},
            tags: {items: [{name: "watch-v4"}]},
            metadata: {
              jsonLd: {
                owner: {id: "1", type: "Person", name: "Owner"},
              },
            },
            media: {
              accessRightKey: "access-key",
              contents: groupedContents
                ? [{videos, audios}]
                : {videos, audios},
            },
          },
        },
        pcweb: {},
      },
    },
  };
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

test("初期server-responseのWatch V4を互換apiDataへ正規化する", () => {
  const page = createPage(undefined, {
    initialize: true,
    serverResponse: JSON.stringify(watchV4Envelope("sm9")),
  });
  const apiData = page.context.NicoCache_nl.watch.apiData;

  assert.equal(page.warnings.length, 0);
  assert.equal(apiData.video.id, "sm9");
  assert.equal(apiData.video.description, "Watch V4 description");
  assert.equal(apiData.video.thumbnail.url,
    "https://example.invalid/thumbnail.jpg");
  assert.equal(apiData.owner.nickname, "Owner");
  assert.equal(apiData.tag.items[0].name, "watch-v4");
  assert.equal(apiData.comment.nvComment.threadKey, "key");
  assert.equal(apiData.media.domand.videos[0].id, "video-h264-720p");
  assert.equal(apiData.media.domand.accessRightKey, "access-key");
});

test("動画情報fetchを監視しSPA切替後のapiDataへ反映する", async () => {
  let page;
  const response = {
    ok: true,
    clone() {
      return {
        json: async () => ({
          ...watchV4Envelope("sm10", true),
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
  assert.equal(page.context.NicoCache_nl.watch.apiData.video.description,
    "Watch V4 description");
});

test("旧Watch応答もapiDataとして維持する", async () => {
  let page;
  const response = {
    ok: true,
    clone() {
      return {
        json: async () => ({
          meta: {status: 200},
          data: {response: {video: {id: "sm11", title: "Legacy"}}},
        }),
      };
    },
  };
  page = createPage(function() {
    return Promise.resolve(response);
  });

  await page.window.fetch(
    "https://www.nicovideo.jp/watch/sm11?responseType=json",
  );
  await new Promise((resolve) => setTimeout(resolve, 0));
  page.window.location.pathname = "/watch/sm11";
  page.history.pushState({}, "", "/watch/sm11");
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(page.context.NicoCache_nl.watch.apiData.video.title, "Legacy");
});
