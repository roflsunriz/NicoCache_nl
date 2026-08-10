"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.resolve(__dirname, "..", "..", "proxy_sample.pac"),
  "utf8",
);

const context = vm.createContext({
  shExpMatch(host, pattern) {
    if (pattern.startsWith("*.")) {
      return host.endsWith(pattern.slice(1));
    }
    return host === pattern;
  },
});
vm.runInContext(source, context, {filename: "proxy_sample.pac"});

function findProxy(url, host) {
  context.url = url;
  context.host = host;
  return vm.runInContext("FindProxyForURL(url, host)", context);
}

test("DEBUG仮想ホストをNicoCache_nlへ送る", () => {
  assert.equal(
    findProxy("http://DEBUG:8080/debug/dump-stack", "DEBUG"),
    "PROXY 127.0.0.1:8080",
  );
  assert.equal(
    findProxy("http://debug:8080/debug/dump-stack", "debug"),
    "PROXY 127.0.0.1:8080",
  );
});

test("対象外ホストとlive2は従来どおり直接接続する", () => {
  assert.equal(findProxy("https://example.com/", "example.com"), "DIRECT");
  assert.equal(
    findProxy("https://live2.nicovideo.jp/", "live2.nicovideo.jp"),
    "DIRECT",
  );
});
