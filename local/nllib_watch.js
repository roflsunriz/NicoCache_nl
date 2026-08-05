// watchページ用ライブラリ
// 2021-03-20, 2024-09-06, 2026-08-06

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
 */

(function() {
  "use strict";

  if (NicoCache_nl.watch && NicoCache_nl.watch.__ncnlWatchLibrary) return;
  NicoCache_nl.watch = {
    __ncnlWatchLibrary: true,
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
      // 現行watchページではserver-responseのmetaタグが初期化時に一時生成され、
      // 公式スクリプトが読み取った直後に削除される。URLを最優先にして動画IDを取得し、
      // SPA切替後などURLから取れない場合は
      // 従来どおりapiDataをフォールバックとして参照する。
      var match = window.location.pathname.match(/^\/watch\/([a-z]{2}\d+)(?:\/|$)/i);
      if (match !== null) return match[1];

      var apiData = NicoCache_nl.watch.apiData;
      if (apiData && apiData.video && apiData.video.id)
        return apiData.video.id;
      return null;
    },
  };

  const videoIdFromLocation = function() {
    const match = window.location.pathname.match(/^\/watch\/([a-z]{2}\d+)(?:\/|$)/i);
    return match ? match[1] : null;
  };
  let lastVideoId = videoIdFromLocation();
  let pendingApiData = null;
  let locationCheckTimer = null;

  const updateForLocation = function() {
    locationCheckTimer = null;
    const videoId = videoIdFromLocation();
    if (!videoId || videoId === lastVideoId) return;
    lastVideoId = videoId;

    if (pendingApiData && pendingApiData.video && pendingApiData.video.id === videoId) {
      NicoCache_nl.watch.apiData = pendingApiData;
      pendingApiData = null;
    } else {
      NicoCache_nl.watch.apiData = {video: {id: videoId}};
    };
    NicoCache_nl.watch.dispatchEvent("videoChanged", [videoId, NicoCache_nl.watch.apiData]);
  };

  const scheduleLocationCheck = function() {
    if (locationCheckTimer === null) {
      locationCheckTimer = setTimeout(updateForLocation, 0);
    };
  };

  const initialize = function() {
    const serverResponse = NicoCache_nl._metaServerResponseTag;
    NicoCache_nl._metaServerResponseTag = undefined;
    const fallbackVideoId = videoIdFromLocation();

    if (serverResponse) {
      try {
        const json = JSON.parse(serverResponse.getAttribute("content") || "");
        if (json && json.data && json.data.response
            && json.data.response.video && json.data.response.video.id) {
          NicoCache_nl.watch.apiData = json.data.response;
        } else {
          throw new Error("server-response.data.response.video.id unavailable");
        };
      } catch (error) {
        console.warn("NicoCache_nl: 視聴ページの初期データを解析できませんでした。", error);
      };
    };
    if (!NicoCache_nl.watch.apiData && fallbackVideoId) {
      NicoCache_nl.watch.apiData = {video: {id: fallbackVideoId}};
    };

    const fireInitialized = function() {
      NicoCache_nl.watch.dispatchEvent("initialized", []);
    };
    if (NicoCache_nl.watch.isInitialized()) {
      fireInitialized();
    } else {
      const observer = new MutationObserver(function() {
        if (!NicoCache_nl.watch.isInitialized()) return;
        observer.disconnect();
        fireInitialized();
      });
      observer.observe(document.body, {childList: true, subtree: true});
    };

    const locationObserver = new MutationObserver(scheduleLocationCheck);
    locationObserver.observe(document.body, {childList: true, subtree: true});
  };

  if (document.body) initialize();
  else document.addEventListener("DOMContentLoaded", initialize, {once: true});

  ["pushState", "replaceState"].forEach(function(methodName) {
    const original = history[methodName];
    if (typeof original !== "function" || original.__ncnlWatchPatched) return;
    const patched = function() {
      const result = original.apply(this, arguments);
      scheduleLocationCheck();
      return result;
    };
    patched.__ncnlWatchPatched = true;
    history[methodName] = patched;
  });
  window.addEventListener("popstate", scheduleLocationCheck);

  if (window.fetch) {
    const originalFetch = window.fetch;
    window.fetch = function(input, init) {
      const responsePromise = originalFetch.apply(this, arguments);
      let requestUrl;
      try {
        const value = typeof input === "string" || input instanceof URL ? input : input.url;
        requestUrl = new URL(value, window.location.href);
      } catch (error) {
        return responsePromise;
      };
      if (requestUrl.hostname !== "www.nicovideo.jp"
          || !requestUrl.pathname.startsWith("/watch/")
          || requestUrl.searchParams.get("responseType") !== "json") {
        return responsePromise;
      };

      responsePromise.then(function(response) {
        if (!response.ok) return;
        response.clone().json().then(function(json) {
          const apiData = json && json.meta && json.meta.status === 200
            && json.data ? json.data.response : null;
          if (!apiData || !apiData.video || !apiData.video.id) return;
          pendingApiData = apiData;
          if (videoIdFromLocation() === apiData.video.id) {
            NicoCache_nl.watch.apiData = apiData;
            scheduleLocationCheck();
          };
        }).catch(function(error) {
          console.warn("NicoCache_nl: 動画切替データを解析できませんでした。", error);
        });
      }, function() {
        // 元のfetch利用側へ失敗を伝え、監視側では未処理の拒否を増やさない.
      });
      return responsePromise;
    };
  };

})();
