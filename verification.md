# 検証手順

## サムネイルキャッシュ保存先の不在と起動（2026-10-03）

対象報告は https://egg.5ch.io/test/read.cgi/software/1769353155/54 。本文へのアクセスは
この環境からできなかったため、依頼で示された「キャッシュ無効・`thcache`不在」の条件を
隔離fixtureで検証した。GitHub APIで確認した最新mainは
`14a255998fdbaa3f87329d3d742c3d2cfcfa2060`、最新公開版はv1.9.3だった。

修正前の正規ビルドで、無効・不在を診断が必須フォルダーの不足として扱う問題は再現した。
同条件の本体は起動し、`thcache`を作成しなかったため、報告の本体起動失敗までは再現していない。
有効時は既定の不在フォルダーを作成するが、親もない保存先では`mkdir()`の失敗を無視し、
本体が待受状態になっても`ref/`を作成できなかった。新しい起動回帰テストを未修正JARに適用し、
有効・親も不在の条件で失敗することも確認した。

診断は`cacheThumbnail=false`なら保存先を対象外とする。有効時は`thcacheFolder`を
利用者データルート基準の相対パス、または絶対パスとして解決し、キャッシュと`ref/`の
存在・種別・読み書き権限、未作成時の親を読み取り専用で確認する。作成可能な保存先は
本体起動時の自動作成を案内する。本体は無効時の設定再読込でも保存先を触らず、
有効時は親から作成し、`IOException`を原因付きの`UncheckedIOException`として伝える。
`ref/`がないときの既存サムネイルからの参照移行は維持する。

検証環境はWindows 11／Temurin 25.0.4.1、独立checkoutと専用の`.test-work/`だけを使った。
次のコマンドの`dependencies`には既存の検証用ライブラリをコピーし、依存関係は変更していない。

- `test-launcher.ps1 -KeepWorkDir`: 全検証成功。データルート診断10ケースには、無効・不在、
  無効・同名ファイル、defaults継承とユーザー設定優先、相対／絶対パス、親不在、親ファイル・
  `ref/`衝突、書込不可、日本語／英語案内、診断でファイルを作らないことを含む。
- `test-functional.ps1 -LibraryDirectory .test-work/dependencies -KeepWorkDir`: 最終29項目成功。
  起動回帰の12条件（有効／無効×不在・既存・親も不在・同名ファイル・親ファイル・`ref/`衝突）、
  設定再読込と再有効化、既存参照の移行、書込不可を含む。既存のサムネイル取得とキャッシュ再利用も
  成功。Extension ABI 1,482項目、ハッシュと同梱サンプルのコンパイル成功。
- 上記の本体・ランチャー・テストは`--release 11 -Xlint:all -Werror`でコンパイル成功。
  Windowsの書込不可はfixture限定のACL変更で作り、`Files.isWritable=false`を確認し、
  `finally`で元の権限へ復元した。テストはPOSIX権限にも対応するが、Linux/macOSとJDKの
  全互換マトリクスは今回実行していない。PC全体の権限やOS設定は変更していない。
- `build-javac.ps1 -LibraryDirectory .test-work/dependencies -Clean`: 正規5 JAR生成成功。
  `check-release-version.ps1 -JarPath NicoCache_nl.jar`: v1.9.3／2026-10-03の整合成功。
- 最終生成したランチャー／本体／診断JARを別fixtureへコピーし、無効・不在、無効・同名ファイル、
  有効・親も不在、有効・既存の4条件で`--headless --start`からHTTP応答を確認した。
  無効時の不作成、衝突ファイル・既存キャッシュの保全、有効時の`ref/`作成と、4回の
  `--headless --stop`による本体・診断の正常停止も成功。
- `test-e2e.ps1 -LibraryDirectory .test-work/dependencies -KeepWorkDir`: 11項目中10項目成功。
  実ランチャーから無効・不在の本体起動、管理API、診断、障害レポート、正常停止は成功した。
  残る1件は`malformed and ambiguous HTTP rejection`の裸のLF要求が200を返す既存問題で、
  未修正v1.9.3 JARでも同じ失敗を確認した。テストを弱めず、専用タスクの範囲外となるHTTP処理は
  変更していない。E2Eスクリプトがこの失敗で停止するため、後段のGUI検証は未実行。
  未修正JARの比較実行では、再起動時のcontrol statusファイル置換にも一度
  `AccessDeniedException`が発生した。今回の変更JARでのE2E再起動と最終4条件の起動停止では
  この追加失敗は発生していない。

稼働JAR・実config/PAC/cert/auth・実キャッシュには触れていない。公開、push、PR、
実配備、掲示板投稿は行っていない。報告の本体起動失敗を特定するには、失敗環境の
有効な`cacheThumbnail`／`thcacheFolder`と、その起動時の例外を追加で照合する必要がある。

## MP4変換後のキャッシュ済み表示

動画別RESTの`CmafCacheInfo`はHLSだけを返しており、キャッシュ索引や従来の一覧で認識される
変換MP4が`preferred`・`completes`・`caches`に出なかった。v1.9.2のクラスに対し、
小さな合成ファイル`sm991001[720p,192]_Converted.mp4`を登録すると、完成MP4にもかかわらず
`preferred=null`となる失敗を再現した。修正後は既存の完成キャッシュ選択と揃え、
形式・品質・完成状態を返す。一覧と視聴ページの共通表示はMP4もバッジに表示する。

`ConvertedCacheInfoUnitTest`で変換MP4、旧MP4、HLSとの共存、部分MP4・HLS、音声のみ、
旧low・ビットレート付き品質名、消えた実体の反映を確認する。音声のみのバッジは
「音声」とし、部分キャッシュは完成表示しない。合成ファイルの内容は識別用データであり、
MP4の実エンコードや`hls2mp4.vbs`実行の検証とは区別する。
旧形式の拡張子を省いた互換IDが複数形式で重なる場合も、IDを一意にし、
部分ファイルが完成状態を隠さず、実際の優先メディアと形式が一致することを確認した。

HLS再取得は表示用RESTとは独立している。既存の完成・非lowの単一ファイルがある場合の
保存抑制、部分・lowの場合、`workaroundNoDisableDoubleCacheImported=true`での抑制解除を
同fixtureで確認した。この判定と設定は変更していない。MP4をHLSとして配信する変更もない。

- `test-functional.ps1 -LibraryDirectory ./.test-work/build-dependencies -KeepWorkDir`:
  28項目成功、Extension ABI 1482項目（削除0）成功。実HTTPの単一動画・一括REST照会と
  変換MP4メディア応答も確認した。
- 正規`build-javac.ps1`で5 JAR生成成功。Javaコンパイルは`-Xlint:all -Werror`。
  修正段階の生成JARはv1.9.2／Unreleasedで版・日付整合を確認し、公開準備でv1.9.3へ更新した。
- ブラウザー側はNodeの仮想DOMによる一覧・視聴ページの回帰。ローカル修正段階では
  実利用ブラウザーでの確認、稼働JARの置換、実キャッシュの変換・削除、push・公開は行っていない。
  `node --test ./tests/local/*.test.js`は最終34件成功（一覧・視聴表示8件を含む）。
  音声表示の追加確認で不足していた仮想DOMのselector対応を補い、失敗記録を保持して再試験した。
  popThumb 1.1.0との隔離結合17項目成功は別担当から報告を受領した。本担当での再実施とは区別する。

## v1.9.3公開準備（2026-10-03）

表示定数・CHANGELOG・Unix配布版数をv1.9.3／2026-10-03へ揃えた。
`.test-work/release-v1.9.3/`に機能28項目、表示34項目、版数回帰13ケース、
正規5 JARビルド、workflow契約の成功ログを保持した。
公開直前にも実JARの表示定数・manifest・タグ整合、workflow契約、diff checkを再確認した。
OSVの公開パッケージ名・版による照会は5依存すべて既知脆弱性0件。
Maven CentralでBouncy Castle 1.86とBrotli decoder 0.1.2が最新版と一致することを確認した。
zstd-jniはロック版1.5.7-12に対して最新版1.5.7-20を確認したが、今回の公開・稼働JAR更新だけの
依頼範囲に従い依存更新は含めない。ロック版のOSV照会は0件である。
既存設定・PAC・証明書・認証・実キャッシュを保持し、公式配布物のハッシュを検証した後に
旧JARのバックアップを伴う通常停止・置換・再起動を行う。公開・配備の実測結果は
git管理外のローカル証跡へ保存する。

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
## 2026-10-03 Windows MSI の配置先引継ぎ調査

- 実行環境は既存 Windows Sandbox（Windows 11 Enterprise 26100、
  WDAGUtilityAccount）。ホストの製品登録と HKCU を共有しない使い捨てゲストで、
  ゲスト内 VHD に独立 NTFS ボリューム `M:`（ラベル `ReproM`）を作成した。
  共有はタスク専用の読取入力とログ出力だけに限定した。
- 公開 v1.9.1 / v1.9.3 MSI の SHA-256 はそれぞれ
  `f15a231f1ceb48e80a0a462af083562b6043c6ffe575cf5e94911c9b08f7044c` /
  `17190dab2434343a349a4c2c5b2abb54ec529dc29f972e63e603fb875225f252`。
  GitHub Release の検証値と一致したものを使用した。
- 指定なしの通常更新では `M:\nico` を保持し、旧版の設定・試験用キャッシュの
  ハッシュも一致した。旧版削除直後に試験用 MST で失敗させると、通常の
  ロールバックで v1.9.1 と InstallDir が復元され、指定なしの再実行も
  v1.9.3 を `M:\nico` に配置した。
- 既存 `M:\nico` に対して `/i <v1.9.3.msi> /qn INSTALLFOLDER="M:\explicit"`
  を指定すると、実ログの `NicoCacheRestoreInstallDir` が
  `M:\explicit` を `M:\nico\` に変更した。配布版番号ファイルと InstallDir も
  `M:\nico` を指した。明示指定を実行シーケンスが上書きする経路は再現済み。
- `M:\Config.msi` への拒否 ACL 注入だけでは更新は成功し、報告された `.rbf` の
  Error 5 は再現していない。掲示板での再実行時の入力値・具体的 C 側パス・
  原因ファイルも不明であり、明示指定の上書きと同じ経路とは断定しない。
- 旧版削除直後に待機する試験用 MST を入れ、ゲスト内の msiexec と待機プロセスを
  強制終了した。直後は InstallDir と両製品の登録がなかったが、指定なし／
  `INSTALLFOLDER="M:\nico"` 明示の再実行はいずれも `M:\nico` に v1.9.3 を配置し、
  設定と試験用キャッシュも保持した。C 側へ移る経路は、この条件では未再現。
- 診断ログは調査タスクの `isolation/output/` に保存した。オフライン Sandbox の
  Windows Installer パッケージ検証は約2分、MST 使用時はさらに約2分を要した。
  検証途中のハーネスタイムアウトと、実 MSI の最終終了コードを区別する。

### 再現手順と修正版の回帰

1. ホストで実行せず、専用 Sandbox 内で VHD の `M:` を作り、公開 v1.9.1 を
   `msiexec /i NicoCache_nl-1.9.1.msi /qn INSTALLFOLDER="M:\nico" /L*V! <log>`
   で入れる。試験用 config.properties と cache/repro-sentinel.txt を追加する。
2. 公開 v1.9.3 の指定なし更新と `INSTALLFOLDER="M:\explicit"` 更新を、
   それぞれ旧版を入れ直して比較する。後者の期待値は `M:\explicit`、
   実際は `M:\nico` だった。更新ログの PROPERTY CHANGE、HKCU InstallDir、
   Windows Installer ProductState、NicoCache_nl.version を合わせて判定した。
3. 診断用 MST だけに Type 19 の失敗、または Type 34 の待機を追加し、
   InstallExecuteSequence の1502（RemoveExistingProducts の直後）で実行する。
   通常ロールバックと、ゲスト内プロセス強制終了後の再実行を別々に観測する。
   MST は試験専用で、製品コードには追加していない。
4. 明示指定上書きが実測で再現した後に、復元条件へ `NOT INSTALLFOLDER` を加え、
   対話画面でも CostInitialize より前に既存先を復元する最小変更を行った。
   旧版削除順序や、失敗後の登録を強制的に書き戻す処理は変更していない。

- 正規ビルドで作成した試験用修正版 MSI の SHA-256:
  `1b4d1c4f832a10ce5e53c8035ce13435cb14e6fe56bf9c7dabd45e1c5a65999a`。
  MSI 構造検証と変更した PowerShell ファイルの構文検証は成功した。
- 修正版の指定なし通常更新は終了0、`M:\nico` と v1.9.3 を確認し、設定と
  試験キャッシュの SHA-256 は更新前と一致した。
- 修正版を旧版削除直後に失敗させると終了1603。v1.9.1 の ProductState=5、
  InstallDir=`M:\nico\` と両ハッシュが復元された。
- その状態から修正版の実対話画面を起動すると `M:\nico\` が初期表示された。
  画面で `M:\explicit` を選んで完了すると、終了0、登録先と実ファイルが
  `M:\explicit\`、v1.9.3 の ProductState=5 になった。実行側の復元処理は
  条件不成立でスキップされ、旧版の ProductState=-1 も確認した。
- 配置先を変更した場合、旧配置先の設定と試験キャッシュは同じハッシュで残った。
  任意の配置先変更に伴う設定・キャッシュの自動移動はこの変更には含めない。
- 対話操作待ちで最初の観測処理は10分のタイムアウトを記録した。MSI は終了させず、
  完了画面を閉じた後の実ログで client MainEngineThread=0 と製品状態を確認した。
  タイムアウト記録は削除せず、別の完了観測処理の結果も保存した。
- 修正版の証拠は `fixed-normal-upgrade*`、`fixed-failure-upgrade*`、
  `fixed-explicit-ui*`、`ui-04-install-folder*` / `ui-05-selected-explicit*`。
  元版の証拠は `normal-upgrade-explicit-other*`、`injected-fail-*`、`hardkill-*`。
  状態 JSON の未存在値 `{}` は PowerShell 5 の空値であり、配置先の値ではない。
- ホストの HKCU InstallDir・ユーザーのアンインストール登録・NicoCache サービスは
  試験前後で一致した（この環境ではいずれも未登録）。ホストに MSI を実行していない。
  全ライフサイクル CI は既存の GitHub Actions 専用ガードを維持し、ローカルでは
  実行していない。追加した CustomExplicit ケースは、後述の専用ゲストで
  元の関数を実行して確認した。CI ワークフロー全体の成功とは区別する。

### CustomExplicit 追加回帰ケースの隔離実行

- 当初未実行だった具体的理由は `test-windows-msi.ps1` の24行目のガードで、
  `GITHUB_ACTIONS=true` 以外の実行を製品登録前に拒否するためだった。
  専用 Sandbox 内でも元スクリプト全体の呼出しが同じ例外になることを実測した。
  GitHub Actions の環境変数を偽装したり、既存ガードを変更したりしていない。
- 前回停止したゲストは破棄済みなので、同じ既存 Windows Sandbox 機能から新しい
  使い捨てゲストを作成した。WDAGUtilityAccount / Microsoft Virtual Machine を
  実測で確認した別ハーネスで、元ファイルを UTF-8 で読み、PowerShell AST から
  ケース関数と依存する検証関数を変更せず読み込んだ。
  `Invoke-LocationUpgradeCase -Case CustomExplicit` をそのまま呼び出した。
- CI と同じ試験用旧版1.0.0（UseLegacyProgramsInstallPath）／新版1.0.1を
  正規 `build-windows-package.ps1 -PackageType Msi` で作成した。
  新規ソフトウェア導入、Windows 機能の変更、ホスト上の MSI 実行は不要だった。
  ホストの InstallDir・製品登録・NicoCache サービスは元の基準状態と一致した。
- 2026-10-03 13:45:18 UTC にケース全体が成功した。旧版導入、CLI 明示先への
  更新、アンインストールの終了コードはすべて0。配布版番号、HKCU InstallDir、
  スタートメニュー／デスクトップの両ショートカット、意図しないプロセスなし、
  旧配置先の除去、新配置先のアンインストール後の除去を、元の検証関数で確認した。
  ゲストのプロキシ・自動起動・証明書・登録・ショートカット状態も前後で一致した。
- 前回の実対話試験は「既存先を初期表示→画面で別の先を選択→実配置・登録」を
  検証した。追加ケースは「CLI の INSTALLFOLDER→登録・両ショートカット追従→
  旧配置先とアンインストール残骸の除去」を検証する。追加ケースは未起動で
  config.properties がない条件であり、前回の設定・キャッシュを持つ更新／失敗復元
  試験を置き換えるものではない。CI ワークフロー全体は実行していない。
- 証拠は調査タスクの `isolation/ci-case-output/` に保存した。
  `case-context.json` はガード拒否・ゲスト識別・元ファイルのハッシュを、
  `case-result.json` はケース成功と前後状態を、`msi-custom-explicit-*.log` は
  実 MSI の終了0と明示先登録を記録する。元テストファイルの SHA-256 は
  `a435eac70c9aa6b4e2124daea7881aa1b7450e648a35ebf138d7d8fdf9dfa5ef`。
  MSI のハッシュと正規ビルドログも同じ出力領域に保持した。
- 初回のビルド失敗（Git の日本語パスの読取り設定）と、ログオン時の自動開始に
  重なった補助起動のガード拒否を保存した。前者は実行プロセスの UTF-8 設定で
  解消し、後者は作業領域が既にあることを理由に後続呼出しだけを拒否した。
  先に始まったケースはそのまま成功し、元の検証条件を緩めていない。
  証拠保存後に、この追加試験用 Sandbox だけを停止した。
