/* @since 2024-03-05 NicoCache_nl システムファイル. Domand用にURLインジェクションする.
 * 2023年11月に部分導入され2024年2月から本格導入されたの動画配信仕様(Domand,CMAF,DMS)では、
 * 要求URLに動画ID(smXXX)が乗らないし各種jsonにも現れない. リファラにも乗らない.
 * nicocache_nlのサーバー側に伝えるために、要求APIを乗っ取ってURLに動画IDなど情報を乗せる.
 * searchに乗った情報はニコ動サーバーに伝わる前にDefaultRequestFilter.javaで消す.
 * 各onRequestではrequestHeader.getParameter("nicocachenl_XXX")の形式で値を取れる.
 */

// Web Workerでも動くようにwindow変数ではなくself変数を使う.

// このコードは NicoCache_nl.watch, NicoCache_nl.watch.apiData.video.id を使うこ
// とを考慮していない. 使った方が綺麗かどうかの検証をしていない.


(function() {
  'use strict';

  /*
   * - threadId: コメントスレッドidの意味. /watch/以降に現れることがある数字の羅列id.
   * - videoId: sm,nm,soで始まるようなid.
   * - 他JSがこれを読みたい場合あるかも.
   */
  const threadIdToVideoId = {};

  // - 2024-09-03: 機能していない. js-initial-watch-dataというidを持つhtml elementは
  //   jsが実行される前のhtmlにも、jsが実行された後のhtmlにも存在しない.
  /* jsonから上記連想配列を追加. */
  function initThreadIdToVideoId() {
    /* 「お探しの動画は視聴できません」ではnull. */
    const watch_data = document.getElementById("js-initial-watch-data");
    if (watch_data === null) {
      return;
    };

    const api_data = watch_data.getAttribute("data-api-data");
    if (api_data == null) {
      return; /* 異常. */
    };

    let api_json;
    try {
      api_json = JSON.parse(api_data);
    } catch (e) {
      return; /* 異常. */
    };

    if (api_json === null ||
        api_json.comment === undefined ||
        api_json.comment === null ||
        api_json.comment.threads === undefined ||
        api_json.comment.threads === null ||
        !(api_json.comment.threads instanceof Array)) {
      return; /* 正常 | 異常. */
    };

    for (const thread of api_json.comment.threads) {
      if (thread.id !== undefined &&
          thread.videoId !== undefined) {
        threadIdToVideoId[thread.id] = thread.videoId;
      };
    };
  };


  function log_bottom(s) {
    const div = document.createElement("div");
    const br = document.createElement("br");
    div.textContent = s;
    if (document.body != null) {
      document.body.appendChild(div);
      document.body.appendChild(br);
    };
  };

  function add_search(url, search) {
    if (url.includes('?')) {
      return url + '&' + search;
    };
    return url + '?' + search;
  };


  function domain_starts_with(url, x) {
    if (url.startsWith("https://" + x + "/")) {
      return true;
    };
    return url.startsWith("//" + x + "/");
  };

  /*
   * - [NicoCacheのjava側]に伝える情報を付け足したurlを返す.
   * - 動作非対象のurlはそのまま返す.
   */
  function might_add_video_info(url) {

    /*
     * if (url.includes(".key")) {log_bottom(url);};
     */

    /* インジェクション動作対象URL. 必須なのはマスターm3u8を示すURL. */

    if (domain_starts_with(url, 'asset.domand.nicovideo.jp')) {
      /*
       * - "CMAF付与情報取得失敗"エラーの出力を抑制する.
       */
      return add_search(url, 'nicocachenl_noerror=true');
    };

    if (! domain_starts_with(url, 'delivery.domand.nicovideo.jp')) {
      return url;
    };

    /*
     * embed.nicovideo.jpなどを除外する.
     */
    if (location.host === 'www.nicovideo.jp'
        && location.pathname.startsWith('/watch/')) {
      // do nothing.
    } else {
      return add_search(url, 'nicocachenl_noerror=true');
    };


    const [video_type, video_id] = (() => {
      let rest = location.pathname.substring("/watch/".length);

      if (rest.match(/^[0-9]/)) {
        /* - restはコメントスレッドID. */
        /* - それをsmXXXへ.            */
        if (undefined === threadIdToVideoId[rest]) {
          return [null, null]; /* 失敗. 異常. */
        };
        rest = threadIdToVideoId[rest];
      };

      /* 英字部と数字部に分ける. */
      const m = rest.match(/^(nm|sm|so)([0-9]+)/);
      if (m === null) {
        return [null, null];
      };
      return [m[1], m[2]];
    })();

    if (video_id === null) {
      return url;
    };

    const asearch = 'nicocachenl_video_id=' + video_id
            + '&nicocachenl_video_type=' + video_type;

    return add_search(url, asearch);
  };


  function wrap_fetch() {
    const original_fetch = self.fetch;
    const fetch = function fetch(input, ...rest) {
      if (input === undefined
          || input === null
          || input.constructor === undefined) {
        // do nothing.
      }
      else if (input.constructor.name === 'Request') {
        // console.log('fetch req url "' + input.url + '"');
        // do nothing.
      }
      else {
        /* タイムシフト予約やその削除がここを通る */
        // console.log('fetch txt url "' + input + '"');
        input = might_add_video_info(`${input}`);
      };
      return original_fetch(input, ...rest);
    };
    self.fetch = fetch;
  };


  function wrap_XMLHttpRequest_open() {
    const original_open = XMLHttpRequest.prototype.open;
    const open = function open(method, url, ...rest) {
      /*
       * - 2024年8月時点、ここを通ってdelivery.domand.nicovideo.jpへのリクエストは実行される.
       */
      url = might_add_video_info(url);
      // console.log(`xml url "${url}"`);
      return original_open.call(this, method, url, ...rest);
    };
    XMLHttpRequest.prototype.open = open;
  };

  function wrap_Request() {
    const original_Request = self.Request;
    // 2024-08 watchページはRequestを使って取得される.

    self.Request = new Proxy(Request, {
      construct: function(target, argumentsList, newTarget) {
        // console.log("//Request.[[constructor]]");
        if (0 !== argumentsList.length) {
          const url = argumentsList[0];
          // console.log(`Req url "${url}"`);
          if (null !== url && undefined === url) {
            // do nothing.
          }
          else if(url.constructor === String) {
            argumentsList[0] = might_add_video_info(url);
          };
        };
        return new target(...argumentsList);
      }
    });

  };


  if (location.pathname.startsWith("/watch/") &&
      location.host === 'www.nicovideo.jp') {
    // do nothing
  }
  else {
    // Requestやfetchの例で予想外のエラーを引き起こしていた.
    // 被害縮小のためにwatchページ以外では動作させない.
    return;
  };

  wrap_Request();
  wrap_XMLHttpRequest_open();
  wrap_fetch();

  if (document.readyState === 'complete') {
    initThreadIdToVideoId();
  } else {
    document.addEventListener("DOMContentLoaded", initThreadIdToVideoId);
  };

})();
