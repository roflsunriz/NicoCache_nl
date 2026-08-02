// - 2024-09-06, 2026-08-02.
// - watchページ 再生リスト のキャッシュアイコンの表示＆色変え＆進捗表示.
// - nlFilters/20_watchFilter.txtに"watchページ 再生リスト のキャッシュアイコン
//   の表示＆色変え＆進捗表示"という名前で直書きされていたjavascript.
// - 旧watchページ用イベントは互換性のため残し、現行watchページの
//   [data-decoration-video-id]にもキャッシュアイコンを追加する.

(function() {

  if(window.NicoCache_nl === undefined
     || NicoCache_nl.watch === undefined
     || NicoCache_nl.watch === null) {
    return;
  };

  var _NextPlayVideoContainer = null;
  var infoCache = {};

  const $XHR = function(aUrl, resolve) {
    var httpObj = NicoCache_nl.xhr();
    httpObj.open('GET', aUrl, true);
    httpObj.onload = function() { resolve(httpObj); };
    // httpObj.onerror = function() { reject(httpObj.error); };
    httpObj.send();
  };

  const removeElement = function(element) {
    if (!element || !element.parentElement) return;
    element.parentElement.removeChild(element);
  };

  const initProgress = function(aPlaylistItemListItem, smid) {
    if (aPlaylistItemListItem._nl_initProgress) return;
    aPlaylistItemListItem._nl_initProgress = true;
    if (infoCache[smid]) {
      if (infoCache[smid].cachings[0] == null) {
        var cacheData = infoCache[smid].caches[infoCache[smid].preferredHTML5];
        if (cacheData && cacheData.complete) return;
      }
    }

    var aRouterLink = aPlaylistItemListItem.getElementsByClassName('RouterLink')[0];
    aRouterLink.getElementsByClassName('PlaylistItem-figureDuration')[0]
      .insertAdjacentHTML("afterEnd", '<span class="cacheInfo"></span>');
    var cacheIcon = aRouterLink.getElementsByClassName('cacheIcon')[0];
    if (cacheIcon) cacheIcon.parentElement.removeChild(cacheIcon);
    initProgress.info = aRouterLink.getElementsByClassName('cacheInfo')[0];

    var cachingId, cacheId;
    var stopPolling = function() {
      clearInterval(initProgress.timer);
      initProgress.timer = null;
    };
    if (initProgress.timer) return;
    initProgress.timer = setInterval(function() {
      NicoCache_nl.watch.addEventListenerOnce("videoChanged", function() {
        // 動画が切り替えられた
        removeElement(initProgress.info);
        stopPolling();
        return;
      });
      $XHR('/cache/info/v2?' + smid, function(resp) {
        if (resp.status != 200) {
          stopPolling();
          return;
        }
        var json = JSON.parse(resp.responseText);
        for (var id in json) { infoCache[id] = json[id]; }
        if (json[smid] == null) return;
        var caching = json[smid].cachings[0];
        if (caching == null) {
          if (cacheId == null) {
            cacheId = json[smid].preferredHTML5;
          }
          var cacheData = json[smid].caches[cacheId];
          if (!cacheData) return;
          if (!cacheData.complete) return;
          stopPolling();
          setTimeout(function() {
            $XHR('/cache/info/v2?' + smid, function(resp) {
              var json = JSON.parse(resp.responseText);
              removeElement(initProgress.info);
              insertCacheIconItem(aRouterLink, json[smid].caches[cacheId]);
            });
          }, 1000);
        } else {
          if (cachingId != caching) {
            cachingId = caching;
            // cachdId = stripSrcId(cachingId)
            var m = caching.match(/^(\w+\[.*\])(?:\w+(\.\w+))?$/);
            if (m) {
              cacheId = m[1] + m[2];
            } else {
              cacheId = cachingId;
            }
          }
          var cacheData = json[smid].caches[cachingId];
          if (cacheData == null || cacheData.cachingSize == 0) return;

          var ratio = cacheData.cachingSize / cacheData.size;
          initProgress.info.innerText = Math.floor(ratio * 100) + '%';
        }
      });
    }, 1000);
  };

  const clearCacheIconItem = function(aRouterLink) {
    removeElement(aRouterLink.getElementsByClassName('cacheIcon')[0]);
    if (aRouterLink.classList.contains("PlaylistItem-bodyTitle")
        || aRouterLink.classList.contains("NextPlayVideoContainer-title")
        || aRouterLink.classList.contains("VideoMediaObject-title")) {
      aRouterLink.style.color = null;
    } else if (aRouterLink.parentElement.classList.contains("UadVideoItem")) {
      aRouterLink.getElementsByClassName('UadVideoItem-title')[0].style.color = null;
    }
  };

  const insertCacheIconItem = function(aRouterLink, cacheData) {
    if (!cacheData) return;
    if (aRouterLink.getElementsByClassName('cacheIcon').length) return;

    var
    cache = ''
    ,color = '';
    if (!cacheData.dmc) {
      if (!cacheData.economy) {
        cache = 'cache';
        color = '#C00000';
      } else {
        cache = 'economy';
        color = '#C08000';
      }
    } else {
      if (!cacheData.economy) {
        cache = 'dmcCache';
        color = '#008000';
      } else {
        cache = 'dmcEconomy';
        color = '#808000';
      }
    }

    if (aRouterLink.classList.contains("PlaylistItem-figure")
        || aRouterLink.classList.contains("NextPlayVideoContainer-figure")) {
      aRouterLink.getElementsByClassName('Thumbnail')[0].insertAdjacentHTML("afterEnd",
          '<div class="cacheIcon ' + cache + 'IconImgMin"></div>');
    } else if (aRouterLink.classList.contains("VideoMediaObject-figure")) {
      aRouterLink.getElementsByClassName('VideoMediaObject-thumbnail')[0].insertAdjacentHTML("afterEnd",
          '<div class="cacheIcon ' + cache + 'IconImg"></div>');
    } else if (aRouterLink.classList.contains("PlaylistItem-bodyTitle")
               || aRouterLink.classList.contains("NextPlayVideoContainer-title")
               || aRouterLink.classList.contains("VideoMediaObject-title")) {
      aRouterLink.style.color = color;
    } else if (aRouterLink.parentElement.classList.contains("UadVideoItem")) {
      aRouterLink.getElementsByClassName('UadVideoItem-title')[0].style.color = color;
      aRouterLink.getElementsByClassName('UadVideoItem-thumbnail')[0].insertAdjacentHTML("afterEnd",
          '<div class="cacheIcon ' + cache + 'IconImgMin"></div>');
    }
  };

  const insertCacheIconItemQueue = function(smid, aRouterLink) {
    if (!insertCacheIconItemQueue.queue)
      insertCacheIconItemQueue.queue = [];
    insertCacheIconItemQueue.queue.push([smid, aRouterLink]);
    insertCacheIconItemQueue.running = true;
    setTimeout(function() {
      insertCacheIconItemQueue.running = false;
      var myQueue = insertCacheIconItemQueue.queue;
      insertCacheIconItemQueue.queue = [];

      if (myQueue.length == 0) return;
      var dup = {};
      var
      smids = myQueue.map(function(x) { return x[0]; })
        .filter(function(el, i, a) { return !(el in infoCache) && !(el in dup) && (dup[el] = 1); })
      ,F = function(smid, aElem, info) {
        var smidData = info[smid];
        if (!smidData) return;
        var cacheData = smidData.preferredHTML5 ? smidData.caches[smidData.preferredHTML5] : null;
        insertCacheIconItem(aElem, cacheData);
      };
      if (smids.length) {
        $XHR('/cache/info/v2?' + smids.join(','), function(value) {
          var info = JSON.parse(value.responseText);
          for (var id in info) { infoCache[id] = info[id]; }
          myQueue.map(function(x) { return F(x[0], x[1], infoCache); });
        });
      } else {
        myQueue.map(function(x) { return F(x[0], x[1], infoCache); });
      }
    }, 0);
  };

  // 現行watchページ(2026-08-02)ではPlaylistItemList/WatchRecommendationが廃止され、
  // 関連動画が[data-decoration-video-id]のdivとして描画される。旧イベントが発火しない
  // ページでも、同じ/cache/info/v2 APIからキャッシュ済みアイコンを表示する。
  const currentWatchItemSelector =
    '[data-anchor-page="watch"][data-anchor-href*="/watch/"][data-decoration-video-id]';
  const currentWatchItems = new WeakSet();
  const currentWatchQueue = new Map();
  var currentWatchQueueTimer = null;

  const getPreferredCacheData = function(videoInfo) {
    if (!videoInfo || !videoInfo.caches) return null;
    var preferred = videoInfo.preferredHTML5 || videoInfo.preferred;
    if (preferred && videoInfo.caches[preferred]) return videoInfo.caches[preferred];
    for (var id in videoInfo.caches) {
      if (videoInfo.caches[id] && videoInfo.caches[id].complete)
        return videoInfo.caches[id];
    }
    return null;
  };

  const getCurrentWatchCacheClass = function(cacheData) {
    if (!cacheData || !cacheData.complete) return null;
    if (cacheData.dmc) return cacheData.economy ? 'dmcEconomy' : 'dmcCache';
    return cacheData.economy ? 'economy' : 'cache';
  };

  const insertCurrentWatchCacheIcon = function(item, cacheData) {
    var cache = getCurrentWatchCacheClass(cacheData);
    if (!cache || !item.isConnected || item.querySelector('.cacheIcon')) return;
    var thumbnail = item.querySelector('a[href*="/watch/"] img[src*="/thumbnails/"]');
    if (!thumbnail) return;

    var cacheIcon = document.createElement('div');
    cacheIcon.className = 'cacheIcon ' + cache + 'IconImgMin';
    thumbnail.insertAdjacentElement('afterend', cacheIcon);
  };

  const flushCurrentWatchQueue = function() {
    currentWatchQueueTimer = null;
    var queue = new Map(currentWatchQueue);
    currentWatchQueue.clear();
    var smids = Array.from(queue.keys());
    if (smids.length === 0) return;

    $XHR('/cache/info/v2?' + smids.join(','), function(resp) {
      if (!resp || resp.status != 200) return;
      var json;
      try {
        json = JSON.parse(resp.responseText);
      } catch (error) {
        return;
      }
      for (var id in json) infoCache[id] = json[id];
      smids.forEach(function(smid) {
        var cacheData = getPreferredCacheData(json[smid] || infoCache[smid]);
        (queue.get(smid) || []).forEach(function(item) {
          insertCurrentWatchCacheIcon(item, cacheData);
        });
      });
    });
  };

  const enqueueCurrentWatchItem = function(item) {
    if (!item || currentWatchItems.has(item)) return;
    var smid = item.getAttribute('data-decoration-video-id');
    if (!smid || !/^\w{2}\d+$/.test(smid)) return;
    if (!item.querySelector('img[src*="/thumbnails/"]')) return;

    currentWatchItems.add(item);
    if (!currentWatchQueue.has(smid)) currentWatchQueue.set(smid, []);
    currentWatchQueue.get(smid).push(item);
    if (currentWatchQueueTimer === null)
      currentWatchQueueTimer = setTimeout(flushCurrentWatchQueue, 0);
  };

  const scanCurrentWatchItems = function(root) {
    if (!root || (root.nodeType !== Node.ELEMENT_NODE
        && root.nodeType !== Node.DOCUMENT_NODE)) return;
    if (root.matches && root.matches(currentWatchItemSelector))
      enqueueCurrentWatchItem(root);
    if (root.closest) {
      var parent = root.closest(currentWatchItemSelector);
      if (parent) enqueueCurrentWatchItem(parent);
    }
    if (root.querySelectorAll) {
      root.querySelectorAll(currentWatchItemSelector).forEach(enqueueCurrentWatchItem);
    }
  };

  const startCurrentWatchObserver = function() {
    scanCurrentWatchItems(document);
    var observer = new MutationObserver(function(mutations) {
      mutations.forEach(function(mutation) {
        mutation.addedNodes.forEach(scanCurrentWatchItems);
      });
    });
    observer.observe(document.body, {childList: true, subtree: true});
  };

  if (document.body) {
    startCurrentWatchObserver();
  } else {
    document.addEventListener('DOMContentLoaded', startCurrentWatchObserver, {once: true});
  }

  const clearCacheIcons = function(RouterLinkContainer) {
    var RouterLink = Array.from(RouterLinkContainer.getElementsByClassName('RouterLink'));
    RouterLink.forEach(function(E) {
      if (!E._nl_insertCacheIcon) return;
      E._nl_insertCacheIcon = false;
      clearCacheIconItem(E);
    });
  };

  const insertCacheIcons = function(RouterLinkContainer) {
    var RouterLink = Array.from(RouterLinkContainer.getElementsByClassName('RouterLink'));
    RouterLink.forEach(function(E) {
      if (E._nl_insertCacheIcon) return;
      var smid = E.getAttribute('href').split('?')[0].split('/').slice(-1)[0];
      if (!smid.match(/^\w{2}\d+$/)) return;
      E._nl_insertCacheIcon = true;
      if (smid.match(/^\d/)) {
        if (!E.querySelector("img").classList.contains("LazyImage")) {
          insertCacheIconItemQueue(smid, E);
        } else {
          var observer = new MutationObserver(function() {
            var thumb = E.querySelector("img");
            if (!thumb || thumb.classList.contains("LazyImage")) return;
            observer.disconnect();
            var smid = thumb.getAttribute("src").match(/(?:\?i=|\/thumbnails\/\d+\/)(\d+)/)[1];
            insertCacheIconItemQueue(smid, E);
          });
          observer.observe(E, {childList: true, subtree: true});
        }
      } else {
        insertCacheIconItemQueue(smid, E);
      }
    });
  };

  const initPlaylist = function(PlaylistItemList) {
    // キャッシュアイコン
    insertCacheIcons(PlaylistItemList);

    var observer = new MutationObserver(function (mutation) {
      insertCacheIcons(PlaylistItemList);
    });
    observer.observe(PlaylistItemList, {childList: true, subtree: true});

    // 進捗表示
    var F = function(videoId) {
      var
      F2 = function(mutation) {
        var playingItem = document.getElementsByClassName('PlaylistItemList-item currentItem');
        if (!playingItem.item(0)) return;
        initProgress(playingItem[0], videoId);
      }
      ,observer = new MutationObserver(F2);
      observer.observe(PlaylistItemList, {childList: true, subtree: true});
      F2();
    };
    F(NicoCache_nl.watch.getVideoID());
    NicoCache_nl.watch.addEventListener("videoChanged", F);
  };

  const initNextPlayVideo = function(NextPlayVideoContainer) {
    // キャッシュアイコン
    insertCacheIcons(NextPlayVideoContainer);
    // 同じ要素が使いまわされるので
    // videoChangedで片付けてからinsertCacheIconsをやりなおす
    _NextPlayVideoContainer = NextPlayVideoContainer;
  };

  const initWatchRecommendation = function(WatchRecommendation) {
    // キャッシュアイコン
    insertCacheIcons(WatchRecommendation);

    var observer = new MutationObserver(function (mutation) {
      insertCacheIcons(WatchRecommendation);
    });
    observer.observe(WatchRecommendation, {childList: true, subtree: true});
  };

  const initUad = function(UadVideosContainer) {
    // キャッシュアイコン
    insertCacheIcons(UadVideosContainer);

    observer = new MutationObserver(function (mutation) {
      insertCacheIcons(UadVideosContainer);
    });
    observer.observe(UadVideosContainer, {childList: true, subtree: true});
  };


  NicoCache_nl.watch.addEventListener('videoChanged', function() {
    if (!_NextPlayVideoContainer) return;
    clearCacheIcons(_NextPlayVideoContainer);
    insertCacheIcons(_NextPlayVideoContainer);
  });

  NicoCache_nl.watch.addEventListener('spawnNextPlayVideoContainer', function(NextPlayVideoContainer) {
    initNextPlayVideo(NextPlayVideoContainer);
  });

  NicoCache_nl.watch.addEventListener('spawnWatchRecommendation', function(WatchRecommendation) {
    initWatchRecommendation(WatchRecommendation);
  });

  NicoCache_nl.watch.addEventListener('spawnPlaylistItemList', function(PlaylistItemList) {
    initPlaylist(PlaylistItemList);
  });

  NicoCache_nl.watch.addEventListener('spawnUadVideosContainer', function(UadVideosContainer) {
    initUad(UadVideosContainer);
  });

})();
