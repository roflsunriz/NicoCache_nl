// - 2024-09-06.
// - トップバーにキャッシュ削除ボタンを追加.
// - nlFilters/05_topBarFilter.txt に直書きされていたjavascript.
// - 動作していない.

(function() {
  const commonHeader = document.getElementById("CommonHeader");
  if (commonHeader === null) {
    return;
  };

  const F1 = function(mutations) {
    var elem = document.querySelector(
      "#CommonHeader>div>div>div>div>a:first-child[href^='https://www.nicovideo.jp?']"
    )?.parentElement?.parentElement;
    if (elem) {
      observer.disconnect();
      F2(elem.children[0], elem.children[1]);
    }
  }.bind(this);

  const F2 = function(left, right) {
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

  const observer = new MutationObserver(F1);
  observer.observe(commonHeader, {
    childList: true,
    subtree: true,
  });

  F1();
})();
