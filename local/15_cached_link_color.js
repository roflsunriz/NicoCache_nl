// ==UserScript==
// @name         Nicocache_nl: キャッシュ済み動画へのリンクに品質classを追加
// @match        http://www.nicovideo.jp/*
// @match        https://www.nicovideo.jp/*
// ==/UserScript==

// - version 2026-06-13
// - このファイルのライセンスはNicoCache Licenseです.
// - リンク色をキャッシュ状況に応じてキャッシュ品質classを追加。
// - 品質classによってリンクの見た目を変更.
// - HTMLを監視して、変化をキャッチして順次に品質classを追加する.

'use strict';

// - ページ内からAタグを探し出して動画へのリンクなら、キャッシュ状態に応じて以下のclassを
//   1つずつ追加する
// - 廃止予定class:
//     cached-v1-normal, cached-v1-economy, cached-dmc-normal, cached-dmc-economy
// - こちらに統一予定のclass:
//     nl-cached-smile-normal, nl-cached-smile-economy,
//     nl-cached-dmc-normal, nl-cached-dmc-economy

// - [func1(anchor), func2(anchor), ...]
// - anchor elementを引数にとる関数のリスト.
// - body下に追加されたanchor(aタグ)全てがこれらの関数に渡される.
window.NicocacheNLVideoAnchorHooks = window.NicocacheNLVideoAnchorHooks || [];

(() => {
  'use strict';

  // 自分でCSS書きたい場合は true を false へ.
  const enablePresetCSS = true;

  const sleep0 = function() {
    return new Promise(resolve => {
      setTimeout(resolve, 0);
    });
  };

  // css {{{
  (() => {
    if (! enablePresetCSS) {
      return;
    };
    const style = document.createElement("STYLE");
    style.innerHTML = `
.nl-cached-common {
}

:not(.VideoMediaObject-item)>.nl-cached-smile-normal:link,
.nl-cached-smile-normal:link>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-normal:link>.NC-CardTitle,
.nl-cached-smile-normal:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #C00000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-smile-normal:visited,
.nl-cached-smile-normal:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-normal:visited>.NC-CardTitle,
.nl-cached-smile-normal:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #600000}

:not(.VideoMediaObject-item)>.nl-cached-smile-economy:link,
.nl-cached-smile-economy:link>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-economy:link>.NC-CardTitle,
.nl-cached-smile-economy:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #C08000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-smile-economy:visited,
.nl-cached-smile-economy:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-smile-economy:visited>.NC-CardTitle,
.nl-cached-smile-economy:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #603000}

:not(.VideoMediaObject-item)>.nl-cached-dmc-normal:link,
.nl-cached-dmc-normal:link>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-normal:link>.NC-CardTitle,
.nl-cached-dmc-normal:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #008000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-normal:visited,
.nl-cached-dmc-normal:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-normal:visited>.NC-CardTitle,
.nl-cached-dmc-normal:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #006000}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:link,
.nl-cached-dmc-economy:link>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:link>.NC-CardTitle,
.nl-cached-dmc-economy:link>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #808000; font-weight:bold;}

:not(.VideoMediaObject-item)>.nl-cached-dmc-economy:visited,
.nl-cached-dmc-economy:visited>.MediaObject>.MediaObjectTitle,
.nl-cached-dmc-economy:visited>.NC-CardTitle,
.nl-cached-dmc-economy:visited>.NC-MediaObject-body>.NC-MediaObject-bodyTitle>.NC-MediaObjectTitle
{ color: #606000}
`;
    document.head.appendChild(style);
  })();
  // }}} css


  // a-tag オブザーバー {{{
  (() => {
    'use strict';

    const observe = function() {
      const observer = new MutationObserver(onBodyChange);
      observer.observe(document.body, {"childList": true, "subtree": true});
    };

    let promise = Promise.resolve();
    const onBodyChange = function(mutationRecords) {

      for (const mr of mutationRecords) {
        if (null !== mr.addedNodes) {
          promise = promise.then(processHooks.bind(null, mr.addedNodes));
        };
      };
    };

    const throwAsync = async function(error) {
      throw error;
    };

    const processHooks = async function(addedNodes) {

      // sleep0は負荷分散のために設置していたが不要かも.

      for (const node of addedNodes) {
        if (undefined === node.tagName) {
          // テキストノード.
          // do nothing.
        }
        else if ("A" === node.tagName) {
          for (const hook of window.NicocacheNLVideoAnchorHooks) {
            try {
              await hook(node);
            }
            catch (e) {
              throwAsync(e); // 例外を投げる. ループは継続.
            };
          };
          await sleep0();
        }
        else {
          for (const a of Array.from(node.getElementsByTagName("A"))) {
            for (const hook of window.NicocacheNLVideoAnchorHooks) {
              try {
                await hook(a);
              }
              catch (e) {
                throwAsync(e); // 例外を投げる. ループは継続.
              };
            };
            await sleep0();
          };
        };
      };
    };


    if (null === document.body) {
      document.addEventListener("DOMContentLoaded", observe);
    } else {
      observe();
    };
  })();
  // }}} a-tag オブザーバー

  function getDougaIDByAnchor(a) {
    const pickDougaID = RegExp("^[^/]*//[^/]*/(?:watch\|shorts)/([a-z][a-z][0-9]+)[#?]?.*");
    let m = a.href.match(pickDougaID);
    if (m !== null) {
      return m[1];
    };

    for (let parent = a.parentElement;
         parent !== null && parent !== document.body;
         parent = parent.parentElement) {
      // data-content-id="sm999"
      const dcid = parent.getAttribute("data-content-id");
      if (dcid !== null) {
        return dcid;
      };
    };
    return null;
  };

  // 2 {{{
  (async () => {
    const mightAddColorClassToAnchor = async function(anchor) {
      // const m = a.href.match(pickDougaID);
      // if (null === m) {
      //     return;
      // };
      if (anchor.getAttribute("data-nicoad-point") !== null) {
        return;
      };
      const id = getDougaIDByAnchor(anchor);
      if (id === null) {
        return;
      };
      // console.log("id", id, anchor);

      const response = await fetch("/cache/info/v2?" + id, {cache:"no-cache"});
      const json = await response.json();

      if (undefined === json || undefined === json[id] || undefined === json[id]["caches"]
          || 0 === json[id]["caches"].length) {
        return;
      };

      let cachePoint = 0; // 0:cacheなし, 1:旧エコ, 2:dmcエコ, 3:旧普通, 4:dmc普通
      for (const cacheName in json[id]["caches"]) {
        const cacheInfo = json[id]["caches"][cacheName];
        if (cacheInfo.complete) {
          let point = 0;
          if (cacheInfo.dmc && cacheInfo.economy) {
            point = 2;
          } else if (cacheInfo.dmc && !cacheInfo.economy) {
            point = 4;
          } else if (!cacheInfo.dmc && cacheInfo.economy) {
            point = 1;
          } else if (!cacheInfo.dmc && !cacheInfo.economy) {
            point = 3;
          };
          // 品質が複数ある場合は最も良いものを選ぶ.
          cachePoint = Math.max(point);
        };
      };

      // "nl-"なしのクラスは廃止予定.
      if (1 === cachePoint) {
        anchor.classList.add("nl-cached-common");
        anchor.classList.add("cached-v1-economy");
        anchor.classList.add("nl-cached-smile-economy");
      } else if (2 === cachePoint) {
        anchor.classList.add("nl-cached-common");
        anchor.classList.add("cached-dmc-economy");
        anchor.classList.add("nl-cached-dmc-economy");
      } else if (3 === cachePoint) {
        anchor.classList.add("nl-cached-common");
        anchor.classList.add("cached-v1-normal");
        anchor.classList.add("nl-cached-smile-normal");
      } else if (4 === cachePoint) {
        anchor.classList.add("nl-cached-common");
        anchor.classList.add("cached-dmc-normal");
        anchor.classList.add("nl-cached-dmc-normal");
      };
    };

    const firstCall = async function() {
      window.NicocacheNLVideoAnchorHooks.push(mightAddColorClassToAnchor);
      for (const a of Array.from(document.getElementsByTagName("A"))) {
        await mightAddColorClassToAnchor(a);
      };
    };

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", firstCall);
    } else {
      firstCall();
    };
  })();
  // }}} 2
})();

