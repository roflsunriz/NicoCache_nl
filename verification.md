# 検証手順

## Issue #20: watchV4のキャッシュ開始

`test-functional.ps1 -LibraryDirectory .\.test-work\build-dependencies`で、watchV4の
初期HTML、全配信グループの品質、HTTPエラー・対象外URLの除外、mediaだけの更新応答、
暗号化CMAFの完成と上流停止後の再生を確認する。旧DOMANDのaccess-rights経路も残す。

2026-09-30に公開ページ`sm46857867`と`sm46859043`の非ログイン応答で
`data.response.$watchV4.data.media.contents`と`media.hls.url`を確認した。
独立したブラウザーで`/v4/watch/<動画ID>`のmediaだけの更新を観測した。
この構造を秘密情報を含まない合成fixtureへ反映し、修正前は動画IDがnullとなり
master playlistへキャッシュ用キーが付かないことを回帰テストで確認した。

修正後の機能テスト27項目、Extension ABI 1482項目（削除0）、`-Xlint:all -Werror`、
正規ビルドの5 JAR生成、`git diff --check`が成功した。修正JARを使い、独立した
非ログインブラウザーからメモリ内で渡した実公開2動画のHTML・API更新・成功プレイリストで、
動画ID、初期HLS登録、実際のHLS要求との対応、音声・映像プレイリスト形式を確認した。
更新APIの`hls.url`にはURLではない不透明値もあり、これは登録せず、有効な対応を保持する。
今回の2動画では実HLS要求と初期HTMLのURLはクエリを除いたキーが一致した。
初期HTMLの署名URLをブラウザーの更新処理なしで直接取得する補助検証は403となった。
API応答・署名URL・動画本体は成果物へ保存していない。

広いE2Eは`bare LF delimiters`のHTTP拒否で失敗した（200応答）。修正前`c504796`の
独立worktreeでも同じ1項目が失敗するため、今回のwatchV4変更による退行ではない。
この失敗で一括実行の後続GUI E2Eは実行されなかった。HTTP要求パーサーの別修正が必要で、
Issue #20の差分には含めない。この既存失敗は解消・成功扱いにしていない。

追加確認として、既にコンパイル済みの修正後クラスを使い、`dareka.GuiEndToEndTestMain`だけを
`-Djava.awt.headless=false`で個別実行した。`test-e2e.ps1`と同じクラスパスを使い、
設定・データ・プレビューは`.test-work/issue20-gui-individual/`に隔離した。
GUI 10項目がすべて合格し、stderrは0バイト、テストJVMの終了も確認した。
320×200と1024×768の生成プレビューも目視確認した。
ログは`.test-work/issue20-evidence/individual-gui.log`にある。

既に成功した機能テストの`.test-work/functional/sandbox/cache/`には
`sm900010[720p,128]_Functional CMAF.hls`が完成しており、音声・映像のsegmentが
fixtureの復号後バイト列と一致することも追加確認した。機能テストは実プロキシの別インスタンスと
loopbackの合成配信を使い、上流停止後のHTTP配信まで確認している。
fixtureのsegmentは文字列であり、ブラウザーでデコードできる実動画ではない。
GUI E2EもSwingの画面検証であり、実動画のブラウザー再生・キャッシュ完成は検証しない。

修正済み隔離プロキシを通した実動画のブラウザーキャッシュ完成は未実行。
既存の機能テストはテスト専用のCAをテストクライアント内で信頼するが、使い捨てChromeで
HTTPS MitMを通すにはテストCAの信頼設定か証明書検証の例外が別途必要になる。
これは今回未承認のセキュリティ・証明書変更に当たるため、その経路の実行は停止した。
稼働プロキシ・通常ブラウザー・OSの証明書とネットワーク設定・既存キャッシュは変更していない。

実利用プロキシへの適用には、利用者の許可を得た停止・JAR置換・再起動後に通常ブラウザーで
対象動画を再生し、保存先に完成キャッシュができることを確認する。キャッシュ削除や
証明書・通常ブラウザー設定変更は必要ない。
次に許可をまとめて確認する操作は、進行中の視聴・保存がない時間に稼働本体だけを正常停止し、
現在の`NicoCache_nl.jar`を退避して修正JARへ置換、既存ランチャーから再起動すること。
影響はその間のプロキシ通信中断と対象動画の再生・キャッシュ追加。
問題時は本体を正常停止し、退避JARを戻して同じランチャーで再起動する。
ユーザー設定・認証・既存キャッシュ・既存証明書を変更しない。pushはこの操作と分けて未承認のままにする。

## nlFilter Lab

### 自動検証

リポジトリ直下で次を実行し、すべて終了コード`0`になることを確認する。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 source-check --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless --fixture watch --cache-menu-probe
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless --fixture search --spa-add 3 --popthumb-probe
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless --fixture anime --spa-add 2 --popthumb-probe
```

`source-check`はソース改行をLFへ正規化したSHA-256とJAR内classの生バイトSHA-256を照合する。
`compatibility`では`syntax.source.status`と`syntax.productionOracle.status`がともに`matched`であることを
確認する。headlessでは`status=passed`、診断・コンソールエラーが0、`failures=[]`であることに加え、
`final.html`、`screenshot.png`、`console.json`、`render.json`が生成されることを確認する。

Windowsのシステムプロキシが有効な環境でも、LabのJavaクライアントとChromeはloopbackへ直接接続する。
再発防止テストでは到達不能な既定プロキシを一時設定し、`/api/config`へ接続できることを確認する。

### 復旧

問題が発生した場合は`nlFilters/tools/nlfilter-lab/`と`parser-baseline.properties`を直前のコミットへ
戻す。基準ハッシュだけを変更せず、本体パーサーとの`compatibility --json`照合まで再実行する。

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
解除を確認する。公式ルートがまだない時点では`#CommonHeader`へ子要素を追加しないことに加え、
`05_topBarFilter.txt`がトップ、静画、生放送、チャンネル、大百科、実況、Nアニメ、ブロマガ、
コモンズ、NicoFT、ニコニコQ、ニコニ貢献、ニコニ立体、ニュース、ニコニコ広場の全HTMLへ、
`www.nicovideo.jp`の絶対URLで`05_nicocache_menu.js`を挿入することも確認する。

### 実ページ検証

Google Chromeを専用プロファイルとraw CDPで起動し、DOMと座標を測定する。ターゲットは
前景タブとして開き、ブラウザーキャッシュとService Workerを迂回する。ページへ対象JavaScriptを
手動評価してはならず、HTML内のscriptタグ、Resource Timing、初期化フラグ、生成DOMを分けて確認する。
非ログイン状態とログイン状態の双方で、トップ、静画、生放送、チャンネル、大百科、実況、
Nアニメ、ブロマガ、コモンズ、NicoFT、ニコニコQ、ニコニ貢献、ニコニ立体、ニュース、
ニコニコ広場の15サービスを確認する。

各ページで次を確認する。

1. `.nico-CommonHeaderRoot`の生成前にNicoCacheメニューが作られていない。
2. `05_nicocache_menu.js`とfilter-matomeの`features.js`が自動読込され、両メニューが1個ずつ存在する。
3. 両メニューが`account`配置となり、`NicoCache → filter-matome → アカウント`の順に並ぶ。
4. 非ログイン時は会員登録項目、NicoCache、filter-matome、プレースホルダーの順に並ぶ。
5. ログイン時は`/my`のアカウント項目直前に並ぶ。
6. 視聴ページだけ`data-ncnl-video-id`を持ち、視聴外では動画固有操作が非表示になる。
7. メニューの開閉、外側クリック、キーボード移動、全画面表示と解除後の復帰が動作する。
8. CommonHeaderの再描画、SPA遷移、戻る・進む、画面幅変更後も重複や古い動画IDが残らない。
9. 480px、800pxなどCommonHeaderの最小幅より狭い表示でも、両メニューがビューポート内に収まる。
10. 公式アカウント項目に`data-ncnl-account-space`などの旧予約属性やインライン`margin-left`が
    残らず、公式右側flex列で通知群、NicoCache、filter-matome、アカウントが隣接し重ならない。
11. 空の`#CommonHeader`と別ホストの`.nico-CommonHeaderRoot`が併存しても公式ルートを優先し、
    scriptタグだけの状態で終了しない。

### 2026-08-31の実測根拠

公式配信資産`https://common-header.nimg.jp/3.13.0/pc/CommonHeader.min.js`を取得して整形し、
SHA-256 `b3b70878aa62c2135bc0e862c17329e03bdff3493d5c555c82d5c002f2136606`の実装を確認した。
公式資産が固定している`.nico-CommonHeaderRoot`とアカウントURLを利用し、生成される
`common-header-*`クラスや表示言語には依存しない。

ログイン済みChromeでトップと動画トップを別URLとして扱い、静画、生放送、チャンネル、大百科、
実況、Nアニメ、ブロマガ、コモンズ、NicoFT、ニコニコQ、ニコニ貢献、ニコニ立体、ニュース、
ニコニコ広場を加えた16URLについて、両スクリプトの自動読込、両メニュー、座標順を確認した。
大百科とコモンズは一括走査中の一時読込失敗後、単独再試行で合格した。800pxでは公式PC・
responsive、実況の旧36pxヘッダー、NicoFT、広場の独自44pxヘッダーを代表として画面内配置を確認した。

### 復旧

問題が発生した場合は`nlFilters/05_topBarFilter.txt`のメニュー読込URLと
`local/05_nicocache_menu.js`を直前のコミットへ戻し、上記の自動検証と実ページ検証を再実行する。

## actions/labelerの更新

### 更新元の確認

固定SHAを変更するときは、`actions/labeler`公式リポジトリのコミット署名、直前SHAとの親子関係、
リリースノート、比較差分を確認する。`98ce1450c7908643084f7487327dfa4f4bf8a367`は
`2c2a2313b245ae5cb1ddbddf76be67b266211c91`の直接の子で、`js-yaml 5.2.2`から`5.2.3`への
更新と生成済みbundle・ライセンス情報だけを含む。`js-yaml 5.2.3`では、タグとmappingの
プロトタイプ継承参照、低年号timestamp、欠落mapping値、tabで字下げされたfolded scalarが修正される。

### 互換性とセキュリティの検証

更新先SHAの公式ソースで次を実行し、すべて終了コード`0`になることを確認する。

```powershell
npm ci
npm run format-check
npm run lint
npm test
npm run build
git diff --exit-code
npm audit --omit=dev
```

更新先に同梱された`js-yaml 5.2.3`で`.github/labeler.yml`を読み、12個のラベル定義が
`actions/labeler`の`getLabelConfigResultFromObject`を通過することを確認する。
`.github/workflows/pull-request-governance.yml`だけを変更ファイルとしてglob判定し、
`area: ci`だけが一致することも確認する。リポジトリ側では次を実行する。

```powershell
.\tests\release\test-github-community-files.ps1
.\tests\release\test-release-workflow.ps1
```

`pull_request_target`はbaseブランチのworkflowを使うため、Dependabot PR上のlabelジョブ成功だけを
更新先Actionの実行証拠にしない。workflowはPR headをcheckoutせず、任意の`run`ステップを持たず、
job権限を`contents: read`と`pull-requests: write`に限定したままにする。最後にPRのCIを再実行し、
`build-and-test`、全OS/JDKジョブ、runtime compatibility matrixとgateが成功することを確認する。

### 復旧

問題が発生した場合は、workflowと契約テストの固定SHAを直前の検証済みSHAへ同時に戻し、
上記のローカル検証とPR CIを再実行する。workflowだけを戻して契約テストを緩めてはならない。

## Dependabot 自動処理（2026-09-23）

`.github/workflows/dependabot-automation.yml` を actionlint で検査し、必須の `CI` と、変更パスに応じて起動する Standalone Updater・Unix Packages・Windows Installer の表示名が一致することを確認する。Dependabot の patch／minor かつ起動した全 PR チェック成功の場合だけ取り込み、major・古い SHA・再失敗は残す。

実際の Dependabot PR がまだない場合、動作経路は未検証として扱う。実 PR 発生後に自動化ジョブ、CI の再試行、マージ結果を確認する。

大量の Dependabot PR により CI 完了より分類が遅れる場合でも、分類後の `workflow_dispatch` が現在の PR 番号と head SHA を照合して再評価する。別の作成者、古い SHA、未完了の CI はマージしない。
