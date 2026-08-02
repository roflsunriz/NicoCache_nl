// - 2024-09-06, 2026-08-02.
// - トップバーにキャッシュ削除ボタンを追加.
// - nlFilters/05_topBarFilter.txt に直書きされていたjavascript.
// - head内で読み込まれるため、CommonHeaderの生成を待ってから初期化する.

(function() {
  const initialize = function() {
    const commonHeader = document.getElementById("CommonHeader");
    if (commonHeader === null) return false;
    if (commonHeader._ncnlCacheRemoveInitialized) return true;
    commonHeader._ncnlCacheRemoveInitialized = true;

    const F2 = function(left, right) {
      if (!right || document.getElementById("cache_remove_workaround")) return;
      var buttonContainer = document.createElement("div");
      buttonContainer.id = "cache_remove_workaround";
      right.insertBefore(buttonContainer, right.firstChild);
      var button = document.createElement("button");
      buttonContainer.appendChild(button);
      button.style.height = "36px";
      button.innerText = "キャッシュ削除";
      button.addEventListener("click", function() {
        var videoId = NicoCache_nl.watch.getVideoID();
        if (!videoId) return;
        if (confirm("本当に削除しますか？: " + videoId)) {
          NicoCache_nl.get("/cache/ajax_rmall?" + videoId);
        }
      });
    };

    const F1 = function() {
      var elem = document.querySelector(
        "#CommonHeader>div>div>div>div>a:first-child[href^='https://www.nicovideo.jp?']"
      )?.parentElement?.parentElement;
      if (elem) {
        buttonObserver.disconnect();
        F2(elem.children[0], elem.children[1]);
      }
    };

    const buttonObserver = new MutationObserver(F1);
    buttonObserver.observe(commonHeader, {
      childList: true,
      subtree: true,
    });
    F1();
    return true;
  };

  const headerObserver = new MutationObserver(function() {
    if (initialize()) headerObserver.disconnect();
  });
  headerObserver.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
  });
  if (initialize()) headerObserver.disconnect();
})();
