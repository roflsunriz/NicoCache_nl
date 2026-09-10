# 検証手順

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

## nlFilterの通常診断

### 判定と検証対象

対象条件通過後の1セクション・1適用を単位に診断する。`Match`の一致と、本文置換・変数や
リストへの登録を別々に数え、既存の出力と副作用を確認する。

| 観点 | 期待する結果 |
| --- | --- |
| URL・RequireHeader・ContentType・StatusCode・MatchLocal・Require | 各条件の不成立だけでは通常の未一致警告を出さない |
| Match全体0件 | デバッグ無効でも`[WARN][nlFilter][MATCH_ZERO]`と出典・対象の情報を出す |
| EachLine | 全体0件はWARN、部分不一致は既存Debug有効時だけ行番号付きで表示する |
| Multi・ReplaceDelay | 反復検索の終了を失敗にせず、遅延置換の順序と結果を維持する |
| Script・Styleの通常挿入 | 実際の挿入後に成功を数え、失敗時は結合に参加した全定義へ出典付きで通知する |
| URL形式のAppend | 内部のReplace化後も元のName・Appendの行と挿入位置を報告する |
| 未定義nlVar・不正キャプチャ参照 | 未解決変数はWARN、置換例外はERRORとして理由を区別する |
| AddVariable・AddList・idGroup | 登録成功やキャッシュ条件見送りをMatch未一致と誤判定しない |
| RequestHeader・Config | URL選別の不一致・設定の未使用は警告せず、ヘッダー置換失敗のDROPを維持する |
| 構文・正規表現・EOF | 不正な数値、正規表現、未知オプション、未閉鎖ブロックを出典付きで報告する |
| 集計・並列処理 | 初回即時、60秒境界の次回発生時に追加件数を出し、別の定義・理由は別集計にする |
| 再読込・終了 | 旧世代を排出し、旧リクエストの遅延完了も記録する。終了後のloadで新世代を作らない |
| 秘匿 | URLのuserinfo・クエリ値・フラグメント、Cookie、処理本文、例外の生メッセージを通常診断へ転記しない |

`NlFilterDiagnosticsFunctionalTest`は実際の`parseFilterFile`、登録、選別、本文適用、
`onRequest`を通す。`NlFilterDiagnosticsLifecycleFunctionalTest`はloadと終了を競合させ、
閉じた診断世代が復活しないことと、旧処理の結果が出力されることを確認する。
集計の時間境界は時計を差し替え、60秒間の実時間待機に依存しない。

### 実行手順

```powershell
.\test-functional.ps1 -KeepWorkDir -LibraryDirectory .\lib
.\build-javac.ps1 -LibraryDirectory .\lib -OutputDirectory .\.test-work\nlfilter-diagnostics\build
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar source-check --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar compatibility --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar test
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar check --json
```

通常ログは既存の`Logger.warning`経路へ`[WARN]`または`[ERROR]`を含む文字列を渡す。
GUIの通常ログ表示と、既存設定に従うファイル出力を再利用する。フィルターの必須指定や
新たなデバッグ設定は追加しない。終了時の排出は`Main`の既存リソース終了処理から呼ぶ。

### 2026-09-10の確認結果

- 変更前の本体を隔離コンパイルし、通常置換の退行テストとLabの既存35件が合格した。
- 変更後の正規ビルド、`--release 11 -Xlint:all -Werror`による本体・機能テストのコンパイルが成功した。
- 本体機能テスト28件、Extension ABI 1482項目、同梱Extensionサンプルのコンパイルが合格した。
- Labの38件が合格した。明示したJARがなければ別JARへ切り替えないこと、非UTF-8の
  コンソール設定でも日本語を含む互換性JSONをUTF-8で保存できること、既存のPowerShell引数形式を
  維持することを含む。
- 隔離した候補基準でテストと本体オラクル照合を終えてから正式な基準へ反映した。
  ソース・classの22項目が`matched`、`syntax.source.status`と`syntax.productionOracle.status`も
  `matched`で、追跡フィルターの`check --json`も成功した。
- 検証済み本体JARを反映して通常再起動し、新PID・対象JAR・GUI起動モード・running状態と
  JARハッシュを確認した。稼働JARを使った既定の`source-check`も22項目が一致した。
- ループバック限定の一時フィルターとHTTP Originを使い、実プロキシ経由の応答と通常ログで
  Match全体0件、Script/Styleの挿入先なし、ファイル・行・HTTP応答情報、クエリとCookieの
  秘匿を確認した。一時フィルターを削除し、検証用Originも終了した。

### 実環境への反映と復旧

稼働中のJARを直接上書きせず、検証済みJARと元のJARを別の場所へ保存する。ランチャーの
`--headless --status`で対象PIDを照合し、`--headless --stop`で通常終了して旧PIDの終了を確認する。
本体JARを差し替えてから従来のGUIまたはヘッドレスの起動方法で復帰し、新PID・JARパス・
ready状態を確認する。復旧時も通常終了後に保存しておいた本体JARを戻す。ソースとLabの基準も
戻す場合は同じ変更前リビジョンへ揃え、基準だけを古いJARへ合わせて差異を隠さない。

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
