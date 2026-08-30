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
  dnsDomainIs(host, suffix) {
    return host.endsWith(suffix);
  },
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

test("専用管理ホストをNicoCache_nlへ送る", () => {
  assert.equal(
    findProxy("https://nicocachenl.test/api/v1/health/live", "nicocachenl.test"),
    "PROXY 127.0.0.1:8080",
  );
});

test("NicoFTをNicoCache_nlへ送る", () => {
  assert.equal(
    findProxy("https://nicoft.io/", "nicoft.io"),
    "PROXY 127.0.0.1:8080",
  );
  assert.equal(
    findProxy("https://www.nicoft.io/", "www.nicoft.io"),
    "PROXY 127.0.0.1:8080",
  );
});

test("destroy-adsの全ホストルールをNicoCache_nlへ送る", () => {
  const exactHosts = [
    "ads.nicovideo.jp",
    "api.nicoad.nicovideo.jp",
    "analytics.twitter.com",
    "analytics.tiktok.com",
    "analytics-ipv6.tiktokw.us",
    "imasdk.googleapis.com",
    "static.ads-twitter.com",
    "tag.flvcdn.net",
  ];
  const hostSuffixes = [
    ".ads.nicovideo.jp",
    ".doubleclick.net",
    ".googlesyndication.com",
    ".googletagmanager.com",
    ".googleadservices.com",
    ".ad-stir.com",
    ".adtdp.com",
    ".pubmatic.com",
    ".amazon-adsystem.com",
    ".adtrafficquality.google",
    ".impact-ad.jp",
    ".im-apps.net",
    ".socdm.com",
    ".rubiconproject.com",
    ".ad-delivery.net",
    ".microad.jp",
    ".adnxs.com",
    ".media.net",
    ".adingo.jp",
    ".casalemedia.com",
    ".criteo.com",
    ".openx.net",
    ".indexww.com",
    ".ladsp.com",
    ".i-mobile.co.jp",
    ".genieesspv.jp",
    ".gsspcln.jp",
    ".id5-sync.com",
    ".gmossp-sp.jp",
    ".creativecdn.com",
    ".slim02.jp",
    ".crwdcntrl.net",
    ".rlcdn.com",
    ".2mdn.net",
  ];

  for (const host of exactHosts) {
    assert.equal(
      findProxy(`https://${host}/probe`, host),
      "PROXY 127.0.0.1:8080",
      host,
    );
    assert.equal(
      findProxy(`https://${host}./probe`, `${host}.`),
      "PROXY 127.0.0.1:8080",
      `${host}.`,
    );
  }
  for (const suffix of hostSuffixes) {
    const host = `probe${suffix}`;
    assert.equal(
      findProxy(`https://${host}/probe`, host),
      "PROXY 127.0.0.1:8080",
      host,
    );
  }
});

test("destroy-adsの全パス限定ルールをNicoCache_nlへ送る", () => {
  const pathRules = [
    ["dcdn.cdn.nimg.jp", ["/nicoad/instream/"]],
    ["secure-dcdn.cdn.nimg.jp", ["/nicoad/"]],
    ["www.google.com", ["/pagead/", "/ccm/"]],
    ["www.google.co.jp", ["/pagead/", "/ccm/"]],
    ["s.yimg.jp", ["/images/listing/tool/cv/", "/images/listing/tool/yads/"]],
    ["apm.yahoo.co.jp", ["/"]],
    ["b99.yahoo.co.jp", ["/"]],
    ["cksync.yahoo.co.jp", ["/"]],
    ["yads.c.yimg.jp", ["/"]],
    ["yads.yjtag.yahoo.co.jp", ["/"]],
  ];

  for (const [host, prefixes] of pathRules) {
    for (const prefix of prefixes) {
      assert.equal(
        findProxy(`https://${host}${prefix}probe`, host),
        "PROXY 127.0.0.1:8080",
        `${host}${prefix}`,
      );
    }
  }
  assert.equal(
    findProxy("https://www.google.com/not-pagead/", "www.google.com"),
    "DIRECT",
  );
  assert.equal(
    findProxy("https://s.yimg.jp/not-yads/", "s.yimg.jp"),
    "DIRECT",
  );
});

test("destroy-ads対象でもHTTP(S)以外は直接接続する", () => {
  assert.equal(
    findProxy("ftp://analytics.twitter.com/probe", "analytics.twitter.com"),
    "DIRECT",
  );
});

test("live2を含むニコニコホストをNicoCache_nlへ送る", () => {
  assert.equal(
    findProxy("https://live2.nicovideo.jp/", "live2.nicovideo.jp"),
    "PROXY 127.0.0.1:8080",
  );
});

test("対象外ホストは直接接続する", () => {
  assert.equal(findProxy("https://example.com/", "example.com"), "DIRECT");
});
