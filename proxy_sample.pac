// NicoCache_nlを利用するためのproxy.pacの例。
// カスタマイズする場合は、このファイルのコピーを編集してください。

function FindProxyForURL(url, host) {
  var nicocacheProxy = 'PROXY 127.0.0.1:8080';
  var normalizedHost = host.toLowerCase();
  var isHttp = url.indexOf('http:') === 0 || url.indexOf('https:') === 0;

  // NicoCache_nlの管理サイト、REST API、デバッグ用の仮想ホスト。
  if (normalizedHost === 'nicocachenl.test' || normalizedHost === 'debug') {
    return nicocacheProxy;
  }

  // ニコニコの本体、配信、コメント、画像、NicoFTをNicoCache_nlへ送る。
  var nicocacheHostPatterns = [
    '*.nicovideo.jp',
    '*.nvcomment.nicovideo.jp',
    '*.smilevideo.jp',
    '*.nimg.jp',
    '*.video.nimg.jp',
    '*.dmc.nico',
    '*.nicoft.io'
  ];
  var nicocacheHostMatched = normalizedHost === 'nicoft.io';
  for (var nicocacheIndex = 0;
       !nicocacheHostMatched && nicocacheIndex < nicocacheHostPatterns.length;
       nicocacheIndex++) {
    nicocacheHostMatched = shExpMatch(
      normalizedHost,
      nicocacheHostPatterns[nicocacheIndex]
    );
  }
  if (isHttp && nicocacheHostMatched) {
    return nicocacheProxy;
  }

  // filter-matome destroy-ads: managed block start
  var destroyAdsHost = host.toLowerCase();
  if (destroyAdsHost.charAt(destroyAdsHost.length - 1) === '.') {
    destroyAdsHost = destroyAdsHost.substring(0, destroyAdsHost.length - 1);
  }
  var destroyAdsUrl = url.toLowerCase();
  var destroyAdsExactHosts = [
    "ads.nicovideo.jp",
    "api.nicoad.nicovideo.jp",
    "analytics.twitter.com",
    "analytics.tiktok.com",
    "analytics-ipv6.tiktokw.us",
    "imasdk.googleapis.com",
    "static.ads-twitter.com",
    "tag.flvcdn.net"
  ];
  var destroyAdsHostSuffixes = [
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
    ".2mdn.net"
  ];
  var destroyAdsPathRules = [
    ["dcdn.cdn.nimg.jp", ["/nicoad/instream/"]],
    ["secure-dcdn.cdn.nimg.jp", ["/nicoad/"]],
    ["www.google.com", ["/pagead/","/ccm/"]],
    ["www.google.co.jp", ["/pagead/","/ccm/"]],
    ["s.yimg.jp", ["/images/listing/tool/cv/","/images/listing/tool/yads/"]],
    ["apm.yahoo.co.jp", ["/"]],
    ["b99.yahoo.co.jp", ["/"]],
    ["cksync.yahoo.co.jp", ["/"]],
    ["yads.c.yimg.jp", ["/"]],
    ["yads.yjtag.yahoo.co.jp", ["/"]]
  ];
  var destroyAdsMatched = false;
  var destroyAdsIndex;

  for (destroyAdsIndex = 0;
       destroyAdsIndex < destroyAdsExactHosts.length;
       destroyAdsIndex++) {
    if (destroyAdsHost === destroyAdsExactHosts[destroyAdsIndex]) {
      destroyAdsMatched = true;
      break;
    }
  }

  if (!destroyAdsMatched) {
    for (destroyAdsIndex = 0;
         destroyAdsIndex < destroyAdsHostSuffixes.length;
         destroyAdsIndex++) {
      if (dnsDomainIs(
          destroyAdsHost,
          destroyAdsHostSuffixes[destroyAdsIndex]
      )) {
        destroyAdsMatched = true;
        break;
      }
    }
  }

  if (!destroyAdsMatched) {
    var destroyAdsPathStart = destroyAdsUrl.indexOf('://');
    destroyAdsPathStart = destroyAdsUrl.indexOf('/', destroyAdsPathStart + 3);
    var destroyAdsPath = destroyAdsPathStart >= 0
      ? destroyAdsUrl.substring(destroyAdsPathStart)
      : '/';
    for (destroyAdsIndex = 0;
         destroyAdsIndex < destroyAdsPathRules.length;
         destroyAdsIndex++) {
      var destroyAdsRule = destroyAdsPathRules[destroyAdsIndex];
      if (destroyAdsHost !== destroyAdsRule[0]) {
        continue;
      }
      for (var destroyAdsPrefixIndex = 0;
           destroyAdsPrefixIndex < destroyAdsRule[1].length;
           destroyAdsPrefixIndex++) {
        if (destroyAdsPath.indexOf(
            destroyAdsRule[1][destroyAdsPrefixIndex]
        ) === 0) {
          destroyAdsMatched = true;
          break;
        }
      }
      if (destroyAdsMatched) {
        break;
      }
    }
  }

  if (destroyAdsMatched
      && (destroyAdsUrl.indexOf('http:') === 0
          || destroyAdsUrl.indexOf('https:') === 0)) {
    return 'PROXY 127.0.0.1:8080';
  }
  // filter-matome destroy-ads: managed block end

  return 'DIRECT';
}
