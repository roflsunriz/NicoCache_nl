# 検証手順

## CommonHeaderのNicoCacheメニュー

### 目的

`local/05_nicocache_menu.js`が、CommonHeaderの生成タイミングやログイン状態に左右されず、
公式ヘッダーを壊さずに次の位置へ表示されることを確認する。

- ログイン時: `https://www.nicovideo.jp/my`へ移動するアカウント項目の直前。
- 非ログイン時: `https://account.nicovideo.jp/register/`以下の会員登録項目と、その直後の
  アカウントプレースホルダーの間。
- 視聴ページ: 動画保存、コメント保存、音声保存、キャッシュ削除、キャッシュ管理を表示する。
- 視聴ページ外: 動画固有操作を隠し、キャッシュ管理だけを表示する。

### 自動検証

リポジトリ直下で次を実行する。

```powershell
node --check .\local\05_nicocache_menu.js
node --test .\tests\local\watch-cache-actions.test.js
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check 05_topBarFilter.txt
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test
```

テストでは、ログイン・非ログイン、公式ルートの遅延生成、視聴ページの動画切替、全画面表示と
解除を確認する。公式ルートがまだない時点では`#CommonHeader`へ子要素を追加しないことも確認する。

### 実ページ検証

Google Chromeを専用プロファイルとraw CDPで起動し、DOMと座標を測定する。非ログイン状態では
トップ、動画トップ、ランキング、新着、検索、タグ、視聴、ユーザーの各ルートを確認する。
ログイン状態では少なくともトップ、動画トップ、検索、視聴、ユーザーを確認する。

各ページで次を確認する。

1. `.nico-CommonHeaderRoot`の生成前にNicoCacheメニューが作られていない。
2. メニューが1個だけ存在し、`data-ncnl-mounted="account"`になっている。
3. 非ログイン時は会員登録項目の右端、NicoCacheメニュー、プレースホルダーの左端がこの順に並ぶ。
4. ログイン時は`/my`のアカウント項目直前に並ぶ。
5. 視聴ページだけ`data-ncnl-video-id`を持ち、視聴外では動画固有操作が非表示になる。
6. メニューの開閉、外側クリック、キーボード移動、全画面表示と解除後の復帰が動作する。
7. CommonHeaderの再描画、SPA遷移、戻る・進む、画面幅変更後も重複や古い動画IDが残らない。

### 2026-08-30の実測根拠

公式配信資産`https://common-header.nimg.jp/3.13.0/pc/CommonHeader.min.js`を取得して整形し、
SHA-256 `b3b70878aa62c2135bc0e862c17329e03bdff3493d5c555c82d5c002f2136606`の実装を確認した。
公式資産が固定している`.nico-CommonHeaderRoot`とアカウントURLを利用し、生成される
`common-header-*`クラスや表示言語には依存しない。

非ログインではトップ、動画トップ、ランキング、新着、検索、タグ、視聴、ユーザーの8ルートで
CommonHeaderの生成と指定位置への配置を確認した。ログインではトップ、動画トップ、ユーザーで
`/my`直前への配置を確認した。ログイン済みの検索と視聴では測定時間内に公式ルート自体が
生成されない場合があったため、その状態ではDOMへ挿入せず待機し、遅延生成後に配置する挙動を
自動テストで補完する。

### 復旧

問題が発生した場合は`nlFilters/05_topBarFilter.txt`のメニュー読込URLと
`local/05_nicocache_menu.js`を直前のコミットへ戻し、上記の自動検証と実ページ検証を再実行する。
