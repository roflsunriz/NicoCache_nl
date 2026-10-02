# 検証手順

## リリース版・日付の整合

v1.9.2 のローカル検証では、以下を確認した。

- 版・日付ガードの13回帰ケース、CI／Release接続の契約テスト、正規5 JARビルド、
  生成JARの表示定数・manifest検証が成功した。
- 機能テストは初回に条件付き取得の1件で304期待／200応答となった。失敗記録を保持し、
  ソース変更なしの再試験で27件成功した。独立worktreeのv1.9.1も27件成功した。
  初回失敗の原因は未特定であり、成功扱いへの書き換えや期待値の緩和はしていない。

`./tests/release/test-release-version.ps1`で13ケースを検証する。タグ不一致、表示の日付・版の
更新漏れ、不正な日付、CHANGELOG・Unix版数の不一致、欠落/古いmanifest、manifestだけを
正しい値にした古いclassを拒否し、未リリース開発と閏日の正常系も確認する。
`./check-release-version.ps1 -ReleaseTag v1.9.2 -JarPath ./NicoCache_nl.jar`で正規ビルド後の
実JARを確認する。通常CIの必須build-and-testとReleaseの生成前・生成後・各OS梱包後へ接続し、
`test-release-workflow.ps1`で接続契約も検証する。manifestは表示定数から自動生成する。
ソース変更ごとに版番号や日付を増分せず、リリース情報の不一致をエラーとして拒否する。

2026-10-02の表示調査では、公開main・v1.9.1タグ・配布JARは`2026-10-01 (v1.9.1)`で一致した。
導入JARと稼働APIは旧版表記であり、公開版の更新漏れではなかった。本番のJAR・プロセス・
設定・キャッシュ・認証はこの作業では変更していない。

## Issue #20: watchV4のキャッシュ開始

watchV4の初期HTML (`data.response.$watchV4.data`) と、配信情報だけを更新する
`/v4/watch/<動画ID>` をfixtureで再現した。修正前の `c504796` では動画IDがnullとなり、
master playlistにキャッシュ用キーが付かなかった。修正後は初期HTMLから動画情報と
全配信グループの品質を取得し、有効なHLS URLを動画IDに関連付ける。
更新APIの不透明な `hls.url` 値やHTTPエラーは既存の有効な関連付けを壊さない。
従来のDOMAND/access-rights経路も回帰検証した。

### 自動テスト

- `./test-functional.ps1 -LibraryDirectory ./.test-work/build-dependencies -KeepWorkDir`:
  機能テスト27項目、Extension ABI 1482項目（削除0）、拡張サンプルのコンパイルが成功。
  コンパイルは `-Xlint:all -Werror` で検証した。
- `./build-javac.ps1 -LibraryDirectory ./.test-work/build-dependencies -Clean`:
  本体・起動管理・診断・更新・ビルドの5 JARの生成が成功。
- `dareka.GuiEndToEndTestMain` を既存E2Eと同じクラスパスで個別実行:
  隔離設定・テスト用キャッシュでGUI 10項目が成功し、JVM終了とstderr 0バイトを確認。
  320×200、1024×768の生成プレビューも確認した。
- 一括 `test-e2e.ps1` は `bare LF delimiters` のHTTP拒否検証が失敗
  （拒否を期待するリクエストに200応答）。修正前 `c504796` の隔離worktreeでも
  同じ1項目が失敗した。今回のwatchV4変更とは別のHTTP解析の既存問題として残し、
  修正・スキップ・成功扱いにはしていない。一括実行で到達しなかったGUIは上記で個別検証した。

### 実動画の回帰検証

2026-09-30から2026-10-01にかけ、Issue本文の公開動画2本で確認した。
匿名の初期HTMLとmedia更新API、実master playlistを修正JARへメモリ内で渡し、
動画ID・HLS関連付け・キャッシュキー・音声/映像playlistの書換えが成功した。
初期HTMLにある署名付きURLをブラウザー更新なしで直接取得する補助試験は403となった。
この結果は成功扱いにしていない。

利用者の承認を得て既存JARを復旧可能に退避し、検証済みの修正版へ置換した。
既存ランチャー・設定で正常停止と再起動を行い、ready応答200、稼働状態、deadlock 0を確認した。
設定・PAC・証明書15ファイルのハッシュは適用前後および実利用検証後で一致した。
通常Firefoxの既存プロキシ・証明書・認証状態を使い、公開動画を再生して完成までキャッシュした。
プレイヤーの先読み範囲内でシークし、取得済み区間の欠落がないことを完成ファイルで確認した。

| 動画ID | 完成キャッシュID | APIサイズ（byte） | 音声・映像セグメント数（各） | 映像長（秒） |
| --- | --- | ---: | ---: | ---: |
| sm46857867 | sm46857867[720p,192].hls | 373440215 | 209 | 1254.483 |
| sm46859043 | sm46859043[720p,128].hls | 547009252 | 312 | 1871.467 |

両キャッシュのmaster、音声/映像playlist、初期化segment、全segmentの存在と非0サイズ、
ENDLIST、暗号鍵参照の除去を確認した。APIは `complete=true`、`caching=false`、
`legacyLow=false`、preferredが完成IDと一致した。
通常の再読込後、各動画の先頭・中間・末尾を各4秒再生し、6地点すべてで時刻進行、
readyState 4、error null、226～238フレームの増加とローカルキャッシュ配信の要求を確認した。
再生速度は等速に戻して確認した。検証専用ウィンドウだけを閉じ、元のブラウザーとプロキシを維持した。

ネットワーク切断による実環境のオフライン再生は未実行（隔離fixtureでは上流停止後の配信を検証済み）。
証明書の追加・検証例外・ネットワーク設定変更・既存キャッシュ削除は行っていない。
認証情報・署名付きURL・API本文・画面画像・動画本体・復旧用JARはコミット対象に含めていない。
復旧情報と詳細なローカル証跡はgit管理外の `.test-work/` に保持した。

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

`.github/workflows/dependabot-automation.yml` を actionlint で検査し、必須の `CI` と、変更パスに応じて起動する Standalone Updater・Unix Packages・Windows Installer の表示名が一致することを確認する。Dependabot の patch／minor／major かつ起動した全 PR チェック成功の場合だけ取り込み、古い SHA・再失敗は残す。

実際の Dependabot PR がまだない場合、動作経路は未検証として扱う。実 PR 発生後に自動化ジョブ、CI の再試行、マージ結果を確認する。

大量の Dependabot PR により CI 完了より分類が遅れる場合でも、分類後の `workflow_dispatch` が現在の PR 番号と head SHA を照合して再評価する。別の作成者、古い SHA、未完了の CI はマージしない。
