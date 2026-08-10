# テスト

## ローカル配信ブラウザスクリプト

Node.js 20以降の組み込みテストランナーで、`local/`から配信するJavaScriptの
ブラウザーAPI呼出規約、動画切替処理、既定キャッシュ一覧、キャッシュ品質の
リンク色・アイコン表示を検証する。

```powershell
node --test .\tests\local\*.test.js
```

## 実利用経路のE2Eテスト

次のコマンドは、テスト用に生成した製品JARを利用者と同じエントリーポイントから
別プロセスで起動し、実ソケットと実Swing Event Dispatch Threadを操作する。
事前にリポジトリ直下で
`packaging/windows/prepare-dependencies.ps1 -DestinationDirectory .test-work/build-dependencies`
を実行する。

```powershell
.\test-e2e.ps1 -LibraryDirectory .\.test-work\build-dependencies
```

HTTP側は隔離した利用者データ領域とローカル上流サーバーだけを使い、通常の
GET・HEAD・Rangeに加えて、パストラバーサル、巨大ヘッダー、矛盾する
`Content-Length`、`Transfer-Encoding`との併用、不完全送信、途中切断、
24利用者相当の並行アクセス、上流停止時の応答と復旧を期限付きで検証する。
GUI側は本体ログウィンドウを実際に作成し、安定したコンポーネント識別子、
最小・標準画面での描画、タブ、全右クリック項目、折り返し・最前面設定、
ログ上限、重複排除、キャッシュ進捗更新、Mainタブのバックログ復帰、ウィンドウ位置の
永続化、タブ別の文字列・正規表現検索、旧Debugタブ履歴の整理、専用ファイルへの履歴移行・日時保存、終了後の
ウィンドウとトレイ解放に加え、デバッグログ記録チェックボックスの切替、
`DebugMode=true`の起動反映、JAR隣接`debug.log`の作成と1 MiB上限を検証する。

失敗時のログ、スクリーンショット、隔離データを確認する場合は
`-KeepWorkDir` を指定する。E2Eは外部サイト、実キャッシュ、実設定、
証明書ストア、OSプロキシー設定には接続しない。

## 機能テスト

リポジトリ直下で次を実行する。

```powershell
.\test-functional.ps1 -LibraryDirectory .\.test-work\build-dependencies
```

テストは `.test-work/functional/` に本体、設定、キャッシュ、ローカル
Extension を隔離して作成する。実際の `cache/`、`certs/`、`data/`、
`extensions/` は使用しない。外部サイトにも接続せず、テスト内のローカル上流
HTTP サーバーを利用する。

確認範囲は次のとおりである。単なる起動確認ではなく、実ソケットの応答内容と
隔離ファイルの生成・削除結果を検証する。

- HTTP プロキシーの GET・HEAD・POST、ヘッダー・本文・ステータス、Range、
  ETag による条件付き取得、上流接続不能時の502応答、CONNECTからTLS
  ループバックしたHTTPSローカル配信
- `/local/` の本文・MIME・Range・許可メソッドと、Extension Rewriter による
  本文・レスポンスヘッダー書換え
- `DEBUG` 仮想ホストのスレッドダンプAPIと、利用者データルートへの
  UTF-8ファイル出力
- サムネイルの上流取得・ファイル保存・再利用
- nvcomment 応答の動画別 JSON 保存（外部 TLS 接続を避けるため、テスト
  Extension が同じ `CommentSavingProcessor` をローカル HTTP fixture に登録する）
- DOMAND/CMAF の access-rights、master/sub playlist、AES key、初期化 chunk、
  暗号化 media segment、復号、完成処理、上流停止後のキャッシュ再生と、
  アニメ公式動画で使われる `hlsext` 経路、署名更新前後で同名セグメントが続く
  `shlsbid` 経路の鍵・IV世代分離
- `/cache/*` の情報取得、配信、Range、削除、不正入力と既存エラー形式
- 単一 MP4・FLV・SWF、旧 DMC MP4・HLS の検索、配信、削除
- Extension と Extension2 のロード、および Processor、stopper Processor、
  Rewriter、RequestFilter、CompleteCache、イベント、終了通知

## JDK実行時互換性テスト

製品とテストを既定のTemurin 25で一度だけコンパイルし、同じクラスファイルを対象JDKで
実行する。これにより、JDK固有のコンパイラー差と、利用者が遭遇する実行時差異を分離する。
対象はJava 17/21/25に固定し、CIではTemurin、Zulu、Liberica、Microsoft、Semeru/OpenJ9、
Corretto、Oracle、Dragonwell、SapMachine、GraalVM、GraalVM Community、JetBrains Runtime、
Tencent Konaの39組み合わせで、本体機能、転送タイムアウト、初回セットアップ、
ランチャーを検証する。

```powershell
.\test-runtime-compatibility.ps1 -Mode Prepare -LibraryDirectory .\.test-work\build-dependencies
.\test-runtime-compatibility.ps1 -Mode Run -ExpectedMajor 25
```

別のJDKをローカルで試す場合は`-JavaHome`にそのJDKのルートを指定する。
実行成果物は`.test-work/runtime-compatibility/artifact/`に置かれ、テストごとの隔離領域は
成功時に削除される。`-KeepWorkDir`を指定した場合だけ保持する。

本体APIの契約だけを短時間で確認する場合は次を実行する。`/cache/info`、
`info/v2`、`ajax_info`、`echo`、検索・一覧・XML、旧API、削除・移動・LST更新の
成功系・不正入力・メソッド制限を隔離した実ソケットで検証し、`list/`からのパス脱出も
拒否されることを確認する。起動管理APIのBearer認証、status、ping、未知パス、
force-shutdownによる隔離プロセス終了も検証する。graceful-shutdownはE2Eテストで検証する。

```powershell
.\test-api.ps1
```

失敗調査で作業ファイルとログを残す場合は `-KeepWorkDir` を指定する。

## Extension ABI 互換テスト

`tests/compat/extension-api.txt` と `extension-api.sha256` は、配布済み
`NicoCache_nl.jar` に含まれる全 `dareka.*` public/protected 型を
`javap -protected -s` で正規化した基準である。同梱サンプルが使用していない型も
対象に含む。型の継承宣言と、所属型・可視性・JVM descriptor を含む各メンバーを
比較し、追加・変更と未許可の削除を失敗させる。

次の理由だけでは基準値を更新しない。

- 本体やサンプルから参照されていない
- `@Deprecated` が付いている
- 古い配信方式を示す名前である
- 既定設定で無効、または実装が no-op である

公開 API を意図的に削除する場合は、先に
`tests/compat/allowed-api-removals.txt` へ `extension-api.txt` の完全一致行と、
現行のニコニコ動画で不要と断定できる根拠、互換 shim では不十分な理由を
記録する。許可行が実差分に存在しない場合もテストは失敗する。基準は互換対象の
配布済み JAR からのみ再生成する。

```powershell
.\tests\compat\get-extension-api-hash.ps1 -JarPath .\NicoCache_nl.jar -Detailed
.\tests\compat\get-extension-api.ps1 -JarPath .\NicoCache_nl.jar
```

## 独立アプリと管理APIのヘッドレススモークテスト

`test-launcher.ps1`はWindows、Linux、macOSのCIで、引数なしでは本体を起動しないこと、
`--tray`・`--minimized`と`--start`の組み合わせ、競合オプションの拒否、各OSのログオン時
タスクが`--tray --start`を保持すること、登録失敗時に移行を再試行できることに加え、
本体停止とランチャー終了が互いの終了処理を呼ばないライフサイクル契約を検証する。

```powershell
.\test-launcher.ps1
```

`test-diagnostics.ps1`は、常駐診断アプリの認証情報・個人情報の匿名化、動画IDと動画タイトルの
保持、3回連続失敗のデバウンス、通常停止の除外、障害HTMLのCSPとエスケープ、狭い画面でも
操作できるSwing GUI、全ボタンの状態遷移、画面のない環境での隠し常駐を検証する。

```powershell
.\test-diagnostics.ps1
```

`test-e2e.ps1`は独立した`NicoCacheLauncher.jar`から本体JARを子プロセスとして起動し、
`--headless`の前景動作、ループバック限定管理APIのBearer認証、グレイスフル停止、
本体のHTTP応答、タスクトレイから本体を起動するログオンタスクの起動コマンド契約、
ユーザーデータルート診断の完全・不足・起動不可判定を確認する。さらに、正常状態を
診断アプリが認識した後に管理API断と本体強制終了を実際に発生させ、3回の外部スレッド
ダンプを含む自動HTML、クラッシュレポート、自動再起動しないこと、機密情報の除去、
動画ID・タイトルの保持を生成ファイルで検証する。これらに加え、NicoCacheGUIの
WebSocketログ配送、20,000件の非ブロッキング投入、有界キュー、メイン・拡張タブ表示を
隔離領域で確認する。タスク登録の実適用はOSごとのCIランナーで行い、
ローカル環境のタスクスケジューラーは変更しない。

Windowsの実タスクスケジューラー連携は、次のCI専用試験で一意なタスクを登録し、
`schtasks /Query /XML ONE`で全タスクから対象のデータルートを含む定義を抽出し、ログオン時
トリガーと起動引数を確認した後、更新・削除して
残存がないことを検証する。GitHub Actions以外ではOS設定を変更しないため実行を拒否する。

```powershell
.\packaging\windows\test-windows-task-scheduler.ps1
```

## Windows パッケージの隔離スモークテスト

`packaging/windows/test-windows-app-image.ps1` は自己完結アプリケーションルートの
`jre/bin/java.exe -jar NicoCacheLauncher.jar` でヘッドレス初回セットアップを
実行した後、同じ入口を `--headless` 指定で専用の空きループバックポートへ
起動し、ルートのHTTP応答とバージョン文字列を確認する。ネイティブ製品ランチャーを
含まず、JARと同梱JREから起動することも検証する。常駐ランチャーと診断アプリが動作中に
別のランチャープロセスから`--headless --stop`を実行し、本体だけが停止して既存の
ランチャーPIDと診断PIDが生存することも確認する。また管理API断を故障注入し、同梱JREの
`jcmd.exe`が生存中の本体から3回のスレッドダンプを採取できること、HTMLが匿名化済みで
自己完結していること、続く計画停止を障害として誤記録しないことも検証する。テスト設定、
ログ、キャッシュは
`.test-work/windows-package/` 内に限定し、TLS MitM、証明書登録、Windows
プロキシー設定、タスク登録は行わない。終了時は起動した実行ファイルのパスと
PIDを照合し、そのPIDだけを終了する。ヘッドレス初回セットアップはテスト領域
だけにCAとサイト証明書を生成し、OSへ登録しない指定が守られることを確認して
新規生成ファイルを削除する。
証明書ストアとWindowsプロキシー設定はテスト前後のスナップショットが一致する
ことも検証する。

`packaging/windows/test-package-parity.ps1` は、共通アプリケーションルートから作成したZIPを
展開し、全ファイルとSHA-256が一致すること、およびGit追跡ファイルを同じ相対位置に
含むことを検証する。MSIも同じルートを入力として生成するため、ZIP、MSIの製品ペイロード、
アプリケーションルートの内容を同一経路で確認できる。

MSIはGitHub Actionsの一時Windowsランナーへ旧版を `msiexec /qn` で
インストールする。配布ファイルの修復、ユーザー状態を保った新版への更新、
同じスモークテスト、`msiexec /x /qn` によるアンインストールまで実行する。
導入時に本体が意図せず起動しないこと、スタートメニューの登録と削除、
試験前後で証明書・Windowsプロキシー・製品登録が一致することも確認する。
ローカル環境の製品登録を汚染しないため、MSI試験スクリプトは一時GitHub
Actionsランナー以外での実行を拒否する。

## Linux/macOS パッケージの隔離スモークテスト

`packaging/unix/test-package.ps1` はLinuxのZIP・DEB・RPMまたはmacOSのZIP・PKG・DMGを
確認し、clone相当のアプリケーションルート、直下JAR、専用`jre/`、起動スクリプトを
検証する。OSの証明書ストア、プロキシー、自動起動設定は変更せず、同梱JREから
起動管理JARのCLIを実行する。

`packaging/unix/test-standalone-updater.ps1` は同じOSの独立アップデーターから
平坦な対象ルートの検証と依存関係自己診断を実行し、ZIPとネイティブパッケージの構造を
確認する。実際の`trust`、`gsettings`、`security`、`networksetup`への変更は
実行しない。これらのOS連携を選択した実適用は、対象OSの隔離ランナーでのみ行う。

```powershell
./packaging/unix/build-package.ps1 -Platform Linux -PackageType All
./packaging/unix/test-package.ps1 -Platform Linux
./packaging/unix/build-standalone-updater.ps1 -Platform Linux -PackageType All
./packaging/unix/test-standalone-updater.ps1 -Platform Linux
```

## 初回起動ウィザード

次のコマンドはOS設定を変更せず、初回セットアップの判定、設定作成、
既存ファイル保全、失敗ロールバック、日本語と英語の辞書キー、5画面の全ボタンと
チェック項目、選択連動、戻る・次へ・キャンセル・適用、処理中の操作無効化、
単一ランチャー用ヘッドレス引数の必須項目・値・矛盾検査を検証する。あわせて、
アプリ本体と利用者データのパス解決、絶対・相対キャッシュパス、ポータブル
モード、システム資材を利用者領域へ複製しない初回セットアップを検証する。

```powershell
.\test-first-run-setup.ps1
```

`-KeepWorkDir` を指定すると、標準幅と最小幅の画面プレビューを
`.test-work/first-run-setup/preview/` に保持する。WindowsのOS連携の実処理は
`packaging/windows/test-windows-first-run.ps1` が一時GitHub Actionsランナーで
同梱`jre/bin/java.exe -jar NicoCacheLauncher.jar --setup --headless`経由で
CA登録、Windows自動プロキシー、
ログオン時起動を適用し、保存した変更前状態へ完全に復元できることを確認する。
現行環境の汚染を防ぐため、この実連携試験はGitHub Actions以外での実行を
拒否する。

## リリースワークフロー契約テスト

次のテストは、リリース時の機能テストと本体MSI検証が残っていること、および
アップロードする資産とGitHub Releaseへ公開する資産が一致することを検証する。
配布用ZIP、本体MSI、Linux/macOSパッケージ、または各ハッシュを欠落させた場合や、
独立アップデーターMSIまたはハッシュを追加し忘れた場合は失敗する。

```powershell
.\tests\release\test-release-workflow.ps1
```
