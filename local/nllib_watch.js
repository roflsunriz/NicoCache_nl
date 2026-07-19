// watchページ用ライブラリ
// 2021-03-20, 2024-09-06

/**
 * NicoCache_nl.watch.apiData
 *   現在の動画のapiData
 *
 * NicoCache_nl.watch.addEventListener(type, callback)
 *   イベント発生時に呼び出される関数を登録する
 *   type: イベントの種類を表す文字列
 *   callback: イベント発生時に呼び出される関数
 *   以下のイベントが存在
 *   - initialized
 *     callback()
 *     プレイヤーの初期化が(たぶん)完了したときに発生．
 *     videoが存在することを保証する．
 *   - videoChanged
 *     callback(videoId, apiData)
 *       videoId: 動画ID．threadIDではなくvideo.idの方．例: sm1234.
 *       apiData: 切替後の動画のapiData
 *     動画を切り替えた時に発生．
 *   - spawnContentTreeContainer, spawnUadVideosContainer, spawnIchibaContainer
 *     callback(container)
 *       container: container要素
 *     それぞれコンテンツツリー，ニコニ広告，ニコニコ市場の
 *     Containerが生成された後に呼び出される．
 *     内容がセットされておらず表示されていない段階でイベントが発生することに注意．
 *     これらのイベントはプレイヤーの変更の影響を受けて仕様変更される可能性が高い．
 *   - spawnPlaylistItemList, spawnNextPlayVideoContainer, spawnWatchRecommendation
 *     callback(container)
 *       container: container要素
 *     動画リストのうち，それぞれPlaylist(タグ検索・マイリスト)，次の動画，関連動画が
 *     生成された後に呼び出される．コメントリスト・動画リストの切り替えや
 *     マイリストの表示・非表示を切り替えるたびに破棄・生成されて呼び出されることに注意．
 *     NextPlayVideoContainerは動画を切り替えたときにDOMが破棄されず，
 *     テキストやリンクだけが変更されることに注意．
 *     これらのイベントはプレイヤーの変更の影響を受けて仕様変更される可能性が高い．
 *
 * NicoCache_nl.watch.addEventListenerOnce(type, callback)
 *   addEventListenerと同じだが，callbackは一度だけ呼び出される．
 *
 * NicoCache_nl.watch.removeEventListener(type, callback)
 *   イベント発生時に呼び出される関数を登録解除する
 *
 * NicoCache_nl.watch.isInitialized()
 *   initializeが完了しているかを判定する
 *   initializedイベントへのハンドラを追加するか
 *   直ちに実行するかの判定に用いる．
 *
 * NicoCache_nl.watch.getVideoID()
 *   動画IDを返す
 *   WatchJsApi.video.getVideoID()の代替用．
 *
 * :: Note ::
 * WatchJsApiは消滅しました．次の操作で代用できます．
 * - WatchJsApi.video.onVideoChanged(function(videoId) {})
 *   -> NicoCache_nl.watch.addEventListener('videoChanged', function(videoId) {})
 * - WatchJsApi.video.getVideoID()
 *   -> NicoCache_nl.watch.getVideoID()
 *
 * spawnPlaylistContainerはPlaylistが廃止されたため，もはや呼び出されません．
 */

(function() {
  "use strict";

  NicoCache_nl.watch = {
    __eventListeners: {},
    __eventListenersOnce: {},
    addEventListener: function(type, callback) {
      type = type.toLowerCase();
      if (!(type in this.__eventListeners))
        this.__eventListeners[type] = [];
      this.__eventListeners[type].push(callback);
    },
    addEventListenerOnce: function(type, callback) {
      type = type.toLowerCase();
      if (!(type in this.__eventListenersOnce))
        this.__eventListenersOnce[type] = [];
      this.__eventListenersOnce[type].push(callback);
    },
    removeEventListener: function(type, callback) {
      type = type.toLowerCase();
      if (type in this.__eventListeners) {
        var arr = this.__eventListeners[type];
        var index = arr.indexOf(callback);
        if (index >= 0) arr.splice(index, 1);
      }
      if (type in this.__eventListenersOnce) {
        var arr = this.__eventListenersOnce[type];
        var index = arr.indexOf(callback);
        if (index >= 0) arr.splice(index, 1);
      }
    },
    dispatchEvent: function(type, args) {
      type = type.toLowerCase();
      if (type in this.__eventListeners) {
        this.__eventListeners[type].forEach(function(callback) {
          setTimeout(function() { callback.apply(null, args); }, 0);
        });
      }
      if (type in this.__eventListenersOnce) {
        this.__eventListenersOnce[type].forEach(function(callback) {
          setTimeout(function() { callback.apply(null, args); }, 0);
        });
        this.__eventListenersOnce[type] = [];
      }
    },

    isInitialized: function() {
      return document.querySelector('video[data-name="video-content"]')
        !== null;
    },

    getVideoID: function() {
      return NicoCache_nl.watch.apiData.video.id;
    },
  };

  /*
   * onInitialized検出(2024-09-06)
   */
  document.addEventListener("DOMContentLoaded", function(event) {
    var body = document.querySelector("body");

    function phase1(callback) {
      var observer = new MutationObserver(function(mutation) {
        var video = document.querySelector('video[data-name="video-content"]');
        if (video !== null) {
          observer.disconnect();
          callback();
        }
      });
      observer.observe(body, {childList: true, subtree: true});
    };

    // fire event.
    function phase2() {
      NicoCache_nl.watch.dispatchEvent('initialized', []);
    };

    phase1(phase2);
  });

  /*
   * onVideoChanged検出(2024-09-06)
   */
  var internalVideoChangedCallbacks = [];

  NicoCache_nl.watch.addEventListener("initialized", function() {
    var oldvideo = document.querySelector('video[data-name="video-content"]');

    function f() {
      var video = document.querySelector('video[data-name="video-content"]');

      if (oldvideo !== video) {
        oldvideo = video;
        internalVideoChangedCallbacks.forEach(function(callback) {
          callback();
        });
      };
    };

    f(); // 初回実行.

    var observer = new MutationObserver(f);
    // ここまで遡ると動画変更時にも要素が残る.
    observer.observe(
      oldvideo.parentElement.parentElement.parentElement.parentElement.parentElement
      , {childList: true});
  });

  /*
   * spawn系イベント(2024-09-06: 動作していない)
   */
  NicoCache_nl.watch.addEventListener('initialized', function() {
    var BottomMainInViewRenderer = document.querySelector('.BottomMainContainer > .InViewRenderer');
    if (BottomMainInViewRenderer === null) {
      console.log('nllib_watch.js: BottomMainInViewRenderer===null: spawn系イベントは発火しません');
      return;
    };

    var
    prevPlayerPanelContainerContent = null, prevContentTreeContainer = null,
    prevUadVideosContainer = null, prevIchibaContainer = null,
    PlayerPanelContainerObserver = function(PlayerPanelContainerContent) {
      var
      prevPlaylistItemList = null,
      prevWatchRecommendation = null,
      prevNextPlayVideoContainer = null;
      var
      F = function() {
        var PlaylistItemList = PlayerPanelContainerContent.querySelector('.PlaylistItemList');
        if (PlaylistItemList && PlaylistItemList !== prevPlaylistItemList)
          NicoCache_nl.watch.dispatchEvent('spawnPlaylistItemList', [PlaylistItemList]);
        prevPlaylistItemList = PlaylistItemList;

        var WatchRecommendation = PlayerPanelContainerContent.querySelector('.WatchRecommendation');
        if (WatchRecommendation && WatchRecommendation !== prevWatchRecommendation)
          NicoCache_nl.watch.dispatchEvent('spawnWatchRecommendation', [WatchRecommendation]);
        prevWatchRecommendation = WatchRecommendation;

        var NextPlayVideoContainer = PlayerPanelContainerContent.querySelector('.NextPlayVideoContainer');
        if (NextPlayVideoContainer && NextPlayVideoContainer !== prevNextPlayVideoContainer)
          NicoCache_nl.watch.dispatchEvent('spawnNextPlayVideoContainer', [NextPlayVideoContainer]);
        prevNextPlayVideoContainer = NextPlayVideoContainer;
      },
      observer = new MutationObserver(F);
      F();
      observer.observe(PlayerPanelContainerContent, {childList: true, subtree: true});
    },
    F = function() {
      var PlayerPanelContainerContent = document.querySelector('.PlayerPanelContainer-content');
      if (PlayerPanelContainerContent && PlayerPanelContainerContent !== prevPlayerPanelContainerContent)
        PlayerPanelContainerObserver(PlayerPanelContainerContent);
      prevPlayerPanelContainerContent = PlayerPanelContainerContent;

      var ContentTreeContainer = document.querySelector('.Card.ContentTreeContainer');
      if (ContentTreeContainer && ContentTreeContainer !== prevContentTreeContainer)
        NicoCache_nl.watch.dispatchEvent('spawnContentTreeContainer', [ContentTreeContainer]);
      prevContentTreeContainer = ContentTreeContainer;

      var UadVideosContainer = document.querySelector('.Card.UadVideosContainer');
      if (UadVideosContainer && UadVideosContainer !== prevUadVideosContainer)
        NicoCache_nl.watch.dispatchEvent('spawnUadVideosContainer', [UadVideosContainer]);
      prevUadVideosContainer = UadVideosContainer;

      var IchibaContainer = document.querySelector('.Card.IchibaContainer');
      if (IchibaContainer && IchibaContainer !== prevIchibaContainer)
        NicoCache_nl.watch.dispatchEvent('spawnIchibaContainer', [IchibaContainer]);
      prevIchibaContainer = IchibaContainer;

      if (!PlayerPanelContainerContent || !ContentTreeContainer || !UadVideosContainer || !IchibaContainer)
        return;
      observer.disconnect();
    },
    observer = new MutationObserver(F);
    observer.observe(BottomMainInViewRenderer, {childList: true, subtree: true});
    F();
  });

  /* jsによる動画切替後のapiDataを保持する */
  var apiDataHolder = null;

  /*
   * apiDataの初回取得.
   */
  window.addEventListener("DOMContentLoaded", function() {
    // - NicoCache_nl._metaServerResponseTag = document.querySelector('meta[name="server-response"]');
    // - nlFilters/20_watchFilter.txtで上記コード埋め込まれる.
    var serverResponse = NicoCache_nl._metaServerResponseTag;
    NicoCache_nl._metaServerResponseTag = undefined;
    if (serverResponse === null) {
      console.log('nllib_watch.js: error: server-response===null');
      return;
    };

    var content = serverResponse.getAttribute('content');
    if (content === null) {
      // 異常だからログしておく.
      console.log('nllib_watch.js: error: server-response.content===null');
      return;
    };

    // 以下エラーしたらそのまま.
    var json = JSON.parse(content);

    NicoCache_nl.watch.apiData = json.data.response;
  });

  internalVideoChangedCallbacks.push(function() {
    if (apiDataHolder === null || apiDataHolder === undefined) {
      console.log('nllib_watch.js: apiDataHolder===' + apiDataHolder
          + ': no fire "videoChanged"');
      return;
    };
    NicoCache_nl.watch.apiData = apiDataHolder;
    apiDataHolder = null;
    var videoId = NicoCache_nl.watch.apiData.video.id;
    NicoCache_nl.watch.dispatchEvent('videoChanged', [videoId, NicoCache_nl.watch.apiData]);
  });

  // - fetch以外でapiDataを取得する例は必要になってから書く.
  // - 2024-09-06時点ではfetch以外不要.
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function(input, init) {

      // - https://www.nicovideo.jp/watch/sm9?rf=nvpc&rp=watch&ra=video_detail&responseType=json
      // - このようなurl以外はorigFetchする.
      // - このようなurlはfetchに噛んでapiDataを読む.
      // - 現実装はこのようなurlを要求したが、実際には動画を切り替えなかった場合に
      //   apiDataの不整合を起こす.

      if (input.hostname) {
        // URL-like object
        var hostname = input.hostname;
        var pathname = input.pathname;
        if (!hostname || hostname != 'www.nicovideo.jp'
            || !pathname || !pathname.startsWith
            || !pathname.startsWith('/watch/')
            || !pathname.includes('responseType=json')
           ) {
          return origFetch(input, init);
        };
      }
      else {
        // string and Request-like object
        var url = (typeof input === "string") ? input : input.url;
        if (!url || !url.match
            || !url.match(/(?:^|\/\/www\.nicovideo\.jp)\/watch\/.*[?&]responseType=json/)) {
          return origFetch(input, init);
        };
      };

      return new Promise(function(resolve, reject) {
        origFetch(input, init)
          .then(function(value) {
            value.text().then(function(body) {
              resolve(new Response(body, value));
              var response = JSON.parse(body);
              if (response.meta && response.meta.status == 200) {
                // apiDataHolder.video.id==='sm123'であるように参照.
                apiDataHolder = response.data.response;
              };
            }, function(reason) {
              // エラーの発生元が異なるのでrejectに渡すより吸い込んだほうが良さそう
              // reject(reason)
            });
          }, function(reason) {
            reject(reason);
          });
      });
    };
  };

})();
