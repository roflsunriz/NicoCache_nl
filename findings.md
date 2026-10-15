# NicoCache_nl の実現手段と調査知見

作業着手前にこの文書を全文読む。共通方針と目的・行動義務・禁止事項は [AGENTS.md](AGENTS.md) を参照する。

## 共通方針に対する固有の実現手段

- 対象の共通ルール: 設計と実装方針・検証の言語別ツール選択。
- 理由: Java本体、ブラウザーへ直接配信するJavaScript/CSS、nlFilter DSLが一緒に動作し、共通のBunコマンドだけでは製品経路を検証できないため。
- 適用範囲: 本体・配信資産・nlFiltersの既存ビルドと検証。
- 代替手段・検証: 正規javacブートストラップ、機能・ABI・APIテスト、Nodeテスト、nlFilter Labと対象環境のE2Eを使う。具体的条件は移動本文に従う。
- 文書構成: 実現手段・環境・過去の調査知見はこの文書を正本とする。利用者向け導入・使用法はREADME、更新の入口はhow-to-update、実測結果はverification、変更意図はCHANGELOGに残す。共通方針の品質・秘密情報保護・ユーザー指示を免除しない。
- 移行基準: 2026-10-16の既定ブランチ `06271a5acdf91f635f3e25de98b04ea0614e5666`。以下は出典の本文を移動したもので、過去の確認日時・適用条件・未検証事項を維持する。


<a id="agents-2"></a>

移動元: [AGENTS.md](AGENTS.md)。

## このリポジトリについて

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーである。Java の本体ソース、実行 JAR、設定例、GUI、ローカル配信用ファイル、拡張機構をこのリポジトリに含む。


<a id="agents-4"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 主要な構成

- `src/`: NicoCache_nl 本体の Java ソース。ビルド時にクラスファイルが生成されることがある。
- `extensions/`: Java 拡張のサンプルとローカル拡張。生成された `.class` は管理しない。
- `local/`: ブラウザーへ配信する JavaScript、CSS、画像など。NicoCache_nl 起動中は `/local/` 以下として配信される。
- `nlFilters/`: NicoCache_nlが直接読み込む標準フィルターと、構文・互換性を確認する
  nlFilter Lab。外部管理リンクとローカル生成領域は追跡しない。
- `defaults/`: 本体が参照する既定設定群。
- `README.md`, `CHANGELOG.md`, `documents/`, `how-to-update.md`: 利用方法と変更履歴。旧版資料は `documents/archive/` に保存する。
- `cache/`, `cvcache/`, `thcache/`: 動画、変換済み動画、サムネイルのキャッシュ。生成物なので管理しない。
- `certs/`: 生成された認証局・サイト証明書などの秘密情報。内容を表示・コミットしない。
- `data/`, `list/`: 実行時データとユーザー設定。配布用サンプル以外は管理しない。
- `lib/`: 本体と証明書生成に使う依存ライブラリ。
- `build-javac.ps1`, `build-javac.sh`, `manifest-nl.mf`: 独立Javaアプリを生成する
  正規ビルドのブートストラップと定義。


<a id="agents-5"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 変更時の注意

- サムネイル保存先の必要条件は`cacheThumbnail`と`thcacheFolder`に従う。
  `DataRootInspector`の診断は書き込みを行わず、本体`ThumbProcessor2.update`は無効時に
  保存先を触らない。有効時の作成失敗は原因付きで伝え、`ref/`不在時の既存サムネイル参照
  移行を維持する。回帰は`test-launcher.ps1`と`test-functional.ps1`、実ランチャー経路は
  `test-e2e.ps1`で確認する。2026-10-03のv1.9.3隔離fixtureでは、無効・`thcache`不在の
  診断「要確認」は再現したが本体起動失敗は再現しなかった。詳細は`verification.md`を参照。

- MSI の InstallDir 復元は UI の CostInitialize より前にも実行し、明示済みの
  INSTALLFOLDER を上書きしない。実行シーケンスだけで復元すると、対話選択が
  旧配置先へ戻る。`test-windows-msi-structure.ps1` と実 MSI ライフサイクルの
  Custom / CustomExplicit を併せて検証する。ローカルの実 MSI 試験は専用の
  使い捨て VM / Windows Sandbox に限定する。同一ホストの別フォルダーは
  MSI 製品登録を隔離しない。2026-10-03 の実測は `verification.md` を参照。
  GitHub Actions 専用ガードを持つ全スクリプトはローカルで実行しない。
  専用ゲストでは元のケース関数を変更せず読み込む別ハーネスで検証できる。
  GITHUB_ACTIONS を偽装せず、ケース単体の実測と CI 全体の成功を区別する。

- 動画別RESTの`CmafCacheInfo`はHLSに限定せず、変換MP4・旧形式も返す。完成選択は
  既存の`Cache.getPreferredCachedVideo`と揃え、`format`と品質を表示に渡す。
  部分・音声のみ・低品質の状態を保持し、MP4の表示修正をHLS再取得設定の変更と混同しない。

- リリース表示の正本は`Main.VER_STRING`。`check-release-version.ps1`で最新CHANGELOGの
  版・日付、Unix配布版数、タグ、実JARの表示定数を照合する。manifestの版・日付は正規
  ビルドで自動生成する。公開ソースが正しくても導入JARが古い場合があるため、表示不一致は
  稼働API・導入JAR・公開JARを区別して調べる。本番の置換・再起動は別途の承認範囲で扱う。
- ローカルE2Eの裸LF拒否が200応答になる場合は、送受信バイトも照合する。
  2026-10-03の統合検証では、独立ループバックへの75バイトのLF要求が受信側で
  78バイトのCRLF要求へ変換された。同じ生成クラスへの直接入力は拒否したため、
  製品の入力検証不良と断定せず、remote CIと比較する。詳細は`verification.md`を参照。
- watchV4では初期HTMLの`data.response.$watchV4.data`に動画・品質情報と`media.hls.url`がある。
  `nvapi.nicovideo.jp/v4/watch/<動画ID>`の更新応答には動画IDなしの`data.media`だけの場合もある。
  旧`access-rights/hls`を前提にせず、`WatchVars`と`WatchRewriter`からCMAFのURL対応を登録する。
  品質一覧は`media.contents`の全グループを保持する。回帰は`WatchV4UnitTest`と
  `test-functional.ps1`の初期HTML・部分更新・暗号化保存・オフライン再生で確認する。
  2026-09-30の実応答では更新APIの`hls.url`に不透明な値もあり、これはURLとして登録しない。
  上記2動画の実HLS要求は初期HTMLのURLとクエリを除いたキーが一致した。詳細は`verification.md`を参照。
- ユーザー操作、設定、ビルド手順が変わる場合は、付属 README、`documents/`、変更履歴の更新要否も確認する。
- `nicocachenl.test/api/v1` REST APIを変更または利用するときは、実装と構造化エラー形式を確認する。代表的な実装は`src/dareka/processor/impl/NicoCacheWebProcessor.java`にある。キャッシュ実体と再生中CMAFは`nicocachenl.test/media/v1`の内部配信経路として扱い、旧`/cache/*`へ戻さない。
- `window.NicoCache_nl.watch` は互換ヘルパーであり、ニコニコ動画側の構造変更に影響される。動画 ID は URL や呼び出し元、再生状態は `HTMLMediaElement` などページ上の実体を優先し、このヘルパーは型と失敗時処理を確認したフォールバックとして使う。
- 用途が不明なファイルを推測で変更しない。付属文書、設定、ソース、リンク先を確認する。


<a id="agents-6"></a>

移動元: [AGENTS.md](AGENTS.md)。

## ビルド

- `build-javac.ps1` と `build-javac.sh` は、クリーンなチェックアウトから
  `NicoCacheBuild.jar`を生成して起動する唯一の正規ビルドブートストラップである。
- `build-javac.ps1` は `src/` と `NicoCache_nl.jar` を更新し得るため、依頼されたビルドまたは検証の範囲でのみ実行する。
- ビルド前に対象ソース、生成先、復旧方法を確認し、既存の `.class` や JAR を無関係に上書きしない。


<a id="agents-7"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 起動・終了・デバッグ

- GUI起動には `NicoCacheLauncher.jar` または配布物のネイティブランチャーを使い、
  実行前に対象JARの存在を確認する。
- 終了時に `Stop-Process -Name java`、`taskkill /IM java.exe` など、名前だけで全 Java プロセスを終了しない。`NicoCacheLauncher.jar --headless --stop` または `--force-stop` を利用して対象本体だけを終了する。
- 再起動は旧プロセスの終了を確認してから行い、起動後は新しい PID と対象 JAR を確認する。
- デバッグでは `NicoCacheGUI.property` の変更前の値を記録する。検証後は、継続指定がない限り元へ戻す。ログには秘密情報や個人情報が含まれ得るため、無制限に表示・コミットしない。


<a id="readme-7"></a>

移動元: [README.md](README.md)。

## 依存更新の自動処理

Dependabot は対象の依存関係を毎週確認します。patch／minor／major 更新は必須の `CI` と、変更内容に応じて起動した Standalone Updater・Unix Packages・Windows Installer を含む全 PR チェックが成功した後に自動で squash merge されます。CI の失敗ジョブは 1 回だけ再実行します。再失敗した PR は残して手動で修正します。


<a id="update-1"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 前提

- Eclipse Temurin JDK 25（`.java-version`と`build-javac.ps1`の既定値）
- Java 17/21互換性を個別に確認する場合は、対応するEclipse Temurin JDK
- PowerShell（Windowsまたは`build-javac.ps1`を使う場合）、またはPOSIX互換シェル

初回のクリーンチェックアウトでは、Javaビルドに使うBouncy Castleをロックファイルから
取得する。取得先とSHA-256はスクリプトが検証する。

```powershell
.\packaging\windows\prepare-dependencies.ps1 `
  -DestinationDirectory .\.test-work\build-dependencies
```

`build-javac.ps1`はJavaで実装した`NicoCacheBuild.jar`をブートストラップして、
本体、CA生成、起動管理、常駐診断、ビルド管理の5アプリを生成する。コンパイル済みクラスは
`.build/`へ隔離し、`src/`へ`.class`を生成しない。JDKが同じならWindows、Linux、
macOSで同じビルドアプリを実行できる。

作業前に `git status --short --branch` を確認する。`cache/`、`certs/`、
`data/`、ローカル `extensions/`、`NicoCacheGUI.property`、
`NicoCacheGUI.search-history.properties` はバックアップや
検証対象として明示された場合を除き変更しない。


<a id="update-2"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 検証

最初に機能テストと Extension ABI 互換テストを実行する。

```powershell
.\test-functional.ps1 -LibraryDirectory .\.test-work\build-dependencies
.\test-launcher.ps1
.\test-diagnostics.ps1
```

本体APIの仕様や削除処理を変更した場合は、隔離した実ソケットでAPI契約テストも実行する。

```powershell
.\test-api.ps1 -LibraryDirectory .\.test-work\build-dependencies
```

APIの一覧と呼び出し例は[本体APIリファレンス](documents/api.md)にまとめている。

次に本体をビルドする。

```powershell
.\build-javac.ps1 -LibraryDirectory .\.test-work\build-dependencies
```

Linux/macOSでは次のPOSIXラッパーも利用できる。

```sh
./build-javac.sh
```

生成物は次の5つである。

- `NicoCache_nl.jar`: 本体。`dareka.UserDataMain`を持つ。
- `NicoCacheCA.jar`: `certificate-targets.txt`を読み込む証明書生成アプリ。
- `NicoCacheLauncher.jar`: GUI、タスクトレイ、ログオン時自動起動タスク、ヘッドレスCLI。
- `NicoCacheDiagnostics.jar`: 二系統ハートビート、匿名化、障害HTML生成を行う常駐GUI。
- `NicoCacheBuild.jar`: 上記4つの実行アプリと自身を生成するビルドアプリ。

GUIを使わずに本体を常駐起動する場合は、起動管理アプリを次のように実行する。

```powershell
java -jar .\NicoCacheLauncher.jar --headless --start
java -jar .\NicoCacheLauncher.jar --headless --status
java -jar .\NicoCacheLauncher.jar --headless --stop
java -jar .\NicoCacheLauncher.jar --headless --launcher-only-stop
```

GUI、タスクトレイ、ヘッドレスCLI、直JAR、自動起動のいずれから本体を起動した場合も、
本体の共通エントリーポイントが`NicoCacheDiagnostics.jar`の起動完了を確認してから処理を
開始する。画面のない環境では診断アプリはバックグラウンドで監視を継続する。稼働中に
診断プロセスだけが終了した場合は本体が再起動する。

`--headless --stop`、`--force-stop`、起動管理画面の停止、トレイ終了、JAR置換による計画停止では、
NicoCache_nl本体と診断アプリを揃って終了し、既存の常駐ランチャーだけを残す。本体が
クラッシュまたは外部から異常終了した場合は診断アプリを残し、障害採取を継続する。
「ランチャーのみ終了」は本体と診断アプリを残したままランチャーだけを終了する。
CLIから同じ操作を行う場合は`--launcher-only-stop`を使用する。利用者データ内の
ランダムトークン付き制御情報を照合し、別プロセスのJavaを終了対象にしない。
Windowsアプリイメージ試験では、ランチャー単独で診断アプリを起動しないこと、正常停止で
本体PIDと診断PIDが終了すること、ランチャーPIDだけが生存することまで検証する。

未設定環境では先に`--setup --headless`を実行し、ユーザーデータ先とHTTPS、CA信頼、
OSプロキシー、自動起動の各`true`/`false`を明示する。GUI初回ウィザードではこの4項目を
推奨設定として初期ONにし、HTTPSの必須条件、`proxy.pac`による通常通信の性能維持、
自動起動の利便性を説明する。

自動起動はタスク名を指定してログオン時に一回だけ登録する。間隔指定はなく、
登録直後にOS側のタスク照会が失敗した場合は成功として保存しない。ログオン時は
起動管理GUIを開き、本体もGUIモードで起動する。

```powershell
java -jar .\NicoCacheLauncher.jar --headless --task-install `
  --task-name=NicoCache_nl
java -jar .\NicoCacheLauncher.jar --headless --task-update `
  --task-name=NicoCache_nl
java -jar .\NicoCacheLauncher.jar --headless --task-remove `
  --task-name=NicoCache_nl
```

Windowsではタスクスケジューラーのルート直下、macOSでは`LaunchAgents`、Linuxでは
XDG autostartへ保存する。実適用を検証するときは、作業中のユーザー設定を変更せず、
CI専用の一意なタスクを使う`packaging/windows/test-windows-task-scheduler.ps1`を実行する。

管理APIは`127.0.0.1`だけで待ち受け、`data/nicocache-control.properties`に保存した
ランダムトークンを要求する。起動管理アプリ以外から呼ぶ場合も、状態ファイルの
`host`、`port`、`token`を読み、Bearer認証を付ける。主なエンドポイントは
`GET /api/control/status`、`GET /api/control/ping`、
`POST /api/control/graceful-shutdown`、`POST /api/control/force-shutdown`である。

保存済みCMAF/Domandを単一MP4へ変換する独立ツールを変更した場合は、同ツールの
ビルドと単体テストを実行する。ツールはJava 11以上でビルドでき、実際の変換確認には
別途FFmpegと完成済みキャッシュが必要になる。`.cmfv`/`.cmfa`を含む実キャッシュで、
映像・音声ストリームが入ったMP4を生成できることまで確認する。ツールはHLS入力に
`-extension_picky 0`を付け、HLSでは意味を持たないconcat専用の`-safe 0`を付けない。

```powershell
.\tools\cmaf-to-mp4\test.ps1
```

Linux/macOSでは、対象OS上で次を実行する。

```sh
./tools/cmaf-to-mp4/test.sh
```

GUIログのタブ、検索、メニュー、履歴保存、診断アプリ、管理API、プロセス起動・停止を
変更した場合は、実Swingウィンドウと実JARを使うE2Eも実行する。診断E2Eは隔離した本体の
管理API断と強制終了を発生させ、生存中の3回のスレッドダンプ、終了前に保持したJVM
スナップショット、匿名化HTML、自動再起動なし、計画停止除外を生成物で確認する。
`-KeepWorkDir`を付けると、診断HTMLと最小サイズ・
標準サイズの確認画像を`.test-work/e2e/`へ残せる。

```powershell
.\test-e2e.ps1 -KeepWorkDir -LibraryDirectory .\.test-work\build-dependencies
```

通常のビルドはEclipse Temurin JDK 25だけを既定として使用する。Temurin 25が
見つからない場合は古いJDKへ暗黙にフォールバックせず失敗する。最小対応版などの
互換性を個別に確認する場合だけ、次のように明示する。

```powershell
.\build-javac.ps1 -JavaVersion 17 -LibraryDirectory .\.test-work\build-dependencies
```

起動時は生成した`NicoCacheLauncher.jar`を直接実行する。開発時は手元の`java`、
パッケージ版はアプリケーションルートの`jre/bin/javaw`または`jre/bin/java`を使用する。

```powershell
java -jar .\NicoCacheLauncher.jar
java -jar .\NicoCacheLauncher.jar --headless --start
```

初回起動ウィザードを変更した場合は、OS設定を変更しない専用テストも実行する。

```powershell
.\test-first-run-setup.ps1
```

画面を確認する場合は `-KeepWorkDir` を指定し、
`.test-work/first-run-setup/preview/` の5画面を確認する。結果画面は成功時と
失敗時の両方を確認する。証明書ストア、OSプロキシー、ログオン時起動の実適用試験は
ローカルで実行せず、対象OSの一時GitHub Actionsランナーへ限定する。

証明書の対象ドメインを更新する場合は `certificate-targets.txt` だけを変更する。
初回起動ウィザードと`NicoCacheCA.jar`は同じ一覧を参照するため、別々のドメイン一覧を
追加しない。ユーザーデータを移動した場合は証明書も移動先の`certs/`へ生成し、
`enableMitM=true`のまま`site.jks`がない状態で起動しない。

専用管理サイト`https://nicocachenl.test/`を初めて含む版へ更新すると、本体は既存の
`proxy.pac`を含む認識可能な利用者テキストをUTF-8（BOMなし）へ正規化してから専用ホスト
経路を追加する。変換前は`data/text-encoding-backups/v1/`へハッシュ付きで保存し、結果を
`data/text-encoding-migration-v1.properties`へ記録する。判定不能、候補が複数、形式不一致、
16 MiB超過、シンボリックリンクは変更せず、ランチャーのデータルート診断で案内する。
`certificate-targets.txt`へ`nicocachenl.test`が追加された版では、ランチャーの
データルート診断で`certs/site.targets`が専用ホストを含むことを確認する。不足時だけSITE
証明書を再生成する。既存`ca.jks`を使うため`ca.cer`の再登録は不要で、CA自体を再生成した
場合だけOSとブラウザーへ信頼登録し直す。

ビルドスクリプトはルートの5つのJARを更新するため、必要な検証が終わったら
`git status --short --branch` で生成物や無関係な差分が混入していないことを確認する。


<a id="update-3"></a>

移動元: [how-to-update.md](how-to-update.md)。

## GitHub Actions とリリース

### リリース版への更新手順

開発版からリリース版へ更新する場合は、対象の版番号とリリース日を決め、次の順序で作業する。
以下では、版番号を`<version>`、リリース日を`<release-date>`として表記する。

1. `src/dareka/Main.java` の `Main.VER_STRING` を
   `NicoCache_nl version <release-date> (v<version>)` に更新する。
2. 独立アップデーターも更新する場合は `updater/VERSION` を `<major>.<minor>.<build>`
   形式に更新する。
3. `CHANGELOG.md` の対象変更を `## [<version>] - <release-date>` の下へ整理する。
4. 継続パッケージ検証の版固定値を更新する。
   `.github/workflows/unix-packages.yml` の `APP_VERSION` を本体版へ、
   `UPDATER_VERSION` を `updater/VERSION` と同じ値へ揃える。
   `./check-release-version.ps1 -ReleaseTag "v<version>"`で表示定数・CHANGELOG・
   配布版数・タグの一致を確認する。日付はリリース日を明示し、ビルド日の自動上書きや
   src変更ごとの版番号自動増分は行わない。未リリースの変更は`Unreleased`へ記録する。
5. 機能テストとExtension ABI互換テストを実行する。

   ```powershell
   .\test-functional.ps1 -LibraryDirectory .\.test-work\build-dependencies
   .\test-launcher.ps1
   .\test-diagnostics.ps1
   ```

   JDK実行時互換性に触れる変更では、Temurinで共通テスト成果物を一度生成し、
   対象JDKで実行する。正式な対応範囲はJava 17/21/25であり、CIでは
   `actions/setup-java`の現行13配布元、計39通りをすべて検証する。

   ```powershell
   .\test-runtime-compatibility.ps1 -Mode Prepare -LibraryDirectory .\.test-work\build-dependencies
   .\test-runtime-compatibility.ps1 -Mode Run -ExpectedMajor 25
   ```

6. 正規ビルドスクリプトで本体をビルドする。

   ```powershell
   .\build-javac.ps1 -LibraryDirectory .\.test-work\build-dependencies
   .\check-release-version.ps1 -ReleaseTag "v<version>" -JarPath .\NicoCache_nl.jar
   ```

   manifestの`Implementation-Version`と`NicoCache-Release-Date`は`Main.VER_STRING`
   から自動生成される。検証はmanifestだけでなくJAR内の実際の表示定数も確認する。
   CIとReleaseが不一致をエラーとして拒否するため、手動でmanifestだけを修正しない。
   公開版が正しくても導入済みJARが古ければ稼働時の表示は変わらない。
   配布JARと導入JARのハッシュ・表示を確認し、置換と再起動は別途明示的に実施する。

7. `git status --short --branch` と `git diff --check` で、生成物や無関係な
   差分がないことを確認する。テストまたはビルドが失敗した場合はタグを作成せず、
   `.test-work/` のログを確認して原因を修正する。
8. ソース、変更履歴、テスト結果を確認したコミットを作成してから、リリースタグを
   作成・pushする。タグは `v<major>.<minor>.<build>` 形式にする。

   ```powershell
   $ReleaseVersion = Read-Host 'リリース版（例: 1.2.3）'
   git add src/dareka/Main.java updater/VERSION .github/workflows/unix-packages.yml `
     CHANGELOG.md how-to-update.md packaging/unix/README.md `
     packaging/unix/build-standalone-updater.ps1 `
     packaging/unix/test-standalone-updater.ps1 packaging/windows/README.md `
     packaging/windows/build-standalone-updater.ps1 `
     packaging/windows/test-standalone-updater.ps1 `
     updater/test/dareka/updater/NicoCacheUpdaterTest.java
   git commit -m "release: v$ReleaseVersionを公開"
   git tag "v$ReleaseVersion"
   git push origin main
   git push origin "v$ReleaseVersion"
   ```

9. GitHub Actionsのリリースワークフロー完了後、GitHub ReleaseにWindowsの
   `NicoCache_nl-<version>.zip`/MSI、LinuxのアプリイメージZIP/DEB/RPM、macOSの
   アプリイメージZIP/PKG/DMG、それぞれのSHA-256、および`updater/VERSION`に基づく
   各OSの独立アップデーター配布物が生成されていることを確認する。

タグを作成した後に検証失敗や内容間違いが判明した場合は、リリースを公開せず、
修正コミットを作成してから新しいタグ名でやり直す。既存タグの付け替えや削除は、
GitHub Releaseの状態を確認して管理者が明示的に判断する。

`main` への push、`main` 向け Pull Request、手動実行では、GitHub Actions が
既定のTemurin 25で本体をビルドし、機能テストと Extension ABI 互換テストを実行する。
加えてTemurin 25でWindows、Linux、macOSのビルド、機能、TLS、
Extension ABI、初回セットアップを検証し、WindowsインストーラーはTemurin JDK 25で生成、
隔離起動、修復、更新、アンインストールを確認する。Unixパッケージワークフローでは
Linux/macOSのアプリイメージ、ネイティブパッケージ、独立アップデーターCLIを確認する。
Temurin 17は最小対応版の明示的な互換性ジョブだけでビルドと主要テストに使用する。
さらにリリースワークフローの契約テストで、全プラットフォームの配布物と各ハッシュが
公開対象へ渡り、独立アップデーターの全プラットフォーム資産が追加されることを確認する。

リリースは `v<major>.<minor>.<build>` 形式のタグをpushすると開始する。MSIの
版番号制約に合わせ、majorとminorは0〜255、buildは0〜65535にする。例えば次の
ようにタグを作成・pushする。

```powershell
git tag v1.0.1
git push origin v1.0.1
```

独立アップデーターを変更したリリースでは、タグを作成する前に
`updater/VERSION` を `<major>.<minor>.<build>` 形式で更新する。この版番号も
majorとminorは0〜255、buildは0〜65535にする。本体とアップデーターは別々に
版管理するため、本体だけを変更したときにアップデーター版を合わせて上げない。

テストに合格すると、GitHub ReleaseにはWindowsの配布用ZIP、MSI、各SHA-256に加え、
タグのソースからTemurin JDK 25で生成・検証したLinuxの
`NicoCache_nl-<版番号>-linux-<arch>`、macOSの
`NicoCache_nl-<版番号>-macos-<arch>`のZIPとネイティブパッケージ、および
`updater/VERSION`で生成した各OSの独立アップデーター資産とSHA-256が添付される。
既存タグから再実行する場合は、Release workflow の手動実行でタグ名を指定する。


<a id="update-4"></a>

移動元: [how-to-update.md](how-to-update.md)。

## リポジトリ依存関係

GitHub Actionsは完全なコミットSHAへ固定し、Dependabotが毎週月曜日に更新を確認する。
patch／minor／majorを問わず、署名済みDependabot PRはPR用CIの全チェック成功後に
自動マージする。初回CI失敗はfailed jobsだけを1回再実行し、再失敗したPRは手動で修正する。
Bouncy Castleの更新PRは別の更新workflowで作成するため、個別に確認する。

Bouncy Castleは毎週の `Update repository dependencies` workflow がMaven Central
の公式メタデータから安定版を確認する。更新がある場合だけ
`automation/update-bouncy-castle` ブランチのPRを作成または更新し、3成果物の
版、URL、SHA-256、サイズとPOMのライセンス情報を本文へ記録する。
Brotli decoderとzstd-jniも同じ依存ロックで配布元とSHA-256を固定する。これらを
更新するときはMaven Centralの公式座標、上流ライセンス、対応OS・CPUを手動で確認する。

手元で更新有無とレポートを確認する場合は次を実行する。

```powershell
.\packaging\windows\update-dependency-lock.ps1 `
  -Mode Check `
  -ReportFile .\.test-work\dependency-update-report.md
.\packaging\windows\test-dependency-lock.ps1
.\packaging\windows\test-dependency-update.ps1
```

ロックを更新する場合は `-Mode Update` を指定する。更新後は上記2テストに加えて
本体ビルド、機能テスト、Windows AppImage・MSI生成と構造試験を実行し、
`packaging/windows/THIRD-PARTY-NOTICES.txt` のライセンス本文や著作権表示に
変更がないことも確認する。ライセンス名または公式URLが想定と異なる場合は
自動更新せず、上流の公式POMとライセンスを確認する。

更新PRの検証に失敗した場合はマージしない。自動処理が変更する管理対象は
`packaging/windows/dependency-lock.psd1` だけなので、誤更新を取り込んだ場合は
該当コミットを `git revert` し、直前のロックへ戻したうえで再検証する。


<a id="update-5"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Linux/macOS パッケージ

LinuxとmacOSのパッケージは対象OS上のTemurin JDK 25 `jlink`で専用JREを生成し、
Linuxでは`dpkg-deb`/`rpmbuild`、macOSでは`pkgbuild`/`hdiutil`で包む。
クロスプラットフォーム生成は行わない。Solarisは配布・CIの対象外とする。

```powershell
./packaging/unix/build-package.ps1 -Platform Linux -PackageType All -AppVersion 1.2.3
./packaging/unix/test-package.ps1 -Platform Linux -AppVersion 1.2.3
./packaging/unix/build-standalone-updater.ps1 -Platform Linux -PackageType All -AppVersion 0.2.2
./packaging/unix/test-standalone-updater.ps1 -Platform Linux -AppVersion 0.2.2
```

LinuxではアプリイメージZIP、DEB、RPM、macOSではアプリイメージZIP、PKG、DMGを
生成する。アプリイメージZIPは独立アップデーターが直接更新に使用し、更新時は
`config.properties`、`portable.flag`、キャッシュ、証明書、利用者データ、ローカル
資材を保持する。Linuxの証明書・プロキシー・自動起動は`trust`、GNOMEの`gsettings`、
XDG autostartを使い、macOSでは`security`、`networksetup`、`LaunchAgents`を使う。
権限や対応サービスが不足する場合は、ウィザードが今回の変更をロールバックする。
本体パッケージの作成時にはCMAF/Domand変換器JARも再ビルドされ、全OSで
アプリケーションルート直下の`tools/cmaf-to-mp4/`へ配置される。
`test-package.ps1`はアプリイメージ、ZIP、LinuxのDEB/RPM、macOSのPKGにそのJARが
含まれることも検証する。


<a id="update-6"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Windows インストーラー

リリースとWindowsインストーラーCIではTemurin JDK 25の`jlink`で専用JREを作り、
WiX Toolsetで同じアプリケーションルートをMSIにする。ルートはGit追跡ファイルを
clone時と同じ相対位置へ置き、プリコンパイル済みJARと`jre/`を直下へ重ねる。
日本語設定の読込みに必要な`jdk.charsets`を含むランタイムで、
`jre/bin/java -jar NicoCacheLauncher.jar --headless`を隔離検証する。

Windowsパッケージ版では、アプリケーションフォルダーの
`config.properties` にある `userDataRoot` で利用者データの保存先を指定する。
Windowsのパスは、設定ファイルでは`C:/Users/利用者名/Documents/NicoCache_nl`のように
スラッシュで記述するか、バックスラッシュを二重化する。起動管理アプリと本体は、
既存設定の単一バックスラッシュも移行時に正しく解釈する。
初回ウィザードは既定の「ドキュメント」内 `NicoCache_nl` を候補として表示し、
選択した絶対パスをこの設定へ保存する。`NICOCACHE_DATA_ROOT` 環境変数と
`nicocache.dataRoot` Javaシステムプロパティは使用しない。

標準の `local/`、`nlFilters/`、Extensionサンプルはアプリケーション側に保持し、
同名の利用者資材は `userDataRoot` 側から後に読み込んで上書きする。キャッシュ、
証明書、個人設定などの書き込み先は利用者データ側だけにする。更新時は
`config.properties` と利用者データを保持し、復旧時も利用者データを削除しない。
Firefoxのプロキシーは`listenPort`（既定値`8080`）へ接続する。起動管理APIの
`controlPort`／状態ファイルの`port`はブラウザー用ではない。移行先でHTTPS MitMを
有効にする場合は、`userDataRoot/certs/site.jks`が存在することと、
Firefoxへ`userDataRoot/certs/ca.cer`をインポート済みであることも確認する。
既存利用者がアプリケーション側からドキュメントなどの新しい場所へユーザーデータを
移す場合は、起動管理アプリの「データルート診断」を使う。画面ではルートの権限、初回
セットアップ用フォルダー、TLSストア、MitM証明書、PACファイル、旧レイアウトの混在を
項目別に確認できる。画面のない環境では`java -jar NicoCacheLauncher.jar --headless --check-data-root`
を実行し、終了コード`0`（完全）、`1`（起動可能だが要確認）、`2`
（起動不可）を確認する。診断は読み取り専用で、移行元の設定やキャッシュを変更しない。
同じアプリイメージを入力にするWindowsのMSIとZIPにも、ルートの
`tools/cmaf-to-mp4/nico-cmaf-to-mp4.jar`を収録する。
Windowsのアプリイメージ、MSI、ZIP、およびLinux/macOSのアプリイメージ、ZIP、
ネイティブパッケージには、5本の独立アプリJAR（`NicoCache_nl.jar`、
`NicoCacheCA.jar`、`NicoCacheLauncher.jar`、`NicoCacheDiagnostics.jar`、
`NicoCacheBuild.jar`）を同じ構成で収録する。常駐診断アプリが管理API停止時にも
スレッドダンプを取得できるよう、同梱JREには`jdk.jcmd`も含める。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
```

ZIPとMSIへ同じ内容を入れる変更を確認する場合は、共通アプリイメージとZIPを生成し、
内容一致テストを実行する。

```powershell
.\packaging\windows\build-windows-package.ps1 `
  -PackageType Zip `
  -AppVersion 1.2.3
.\packaging\windows\test-package-parity.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl `
  -ZipPath .\.test-work\windows-package\output\NicoCache_nl-1.2.3.zip
```

MSIを生成した場合は、インストールせずに内部テーブルを読み取り、デスクトップと
スタートメニューのショートカットが各1件定義されていること、および明示的な
アンインストール時にWindows設定復元が製品ファイル削除より前に実行されることを
確認する。

```powershell
.\packaging\windows\test-windows-msi-structure.ps1 `
  -MsiPath .\.test-work\windows-package\output\NicoCache_nl-<version>.msi
```

この隔離テストはログオン時起動を再現するため、製品ルートとは異なる作業
ディレクトリから単一ランチャーを起動し、作業ディレクトリに依存せず
`config.properties` の `userDataRoot` を使ってHTTP応答を継続することを確認する。

MSIのWiX定義は`packaging/windows/build-flat-package.ps1`が、共通アプリケーション
ルートの収集結果、ショートカット、固定Upgrade UUID、アンインストール前の
Windows設定復元を組み立てる。JDKまたはWiXを更新した場合はMSI構造試験と
インストール・修復・更新・アンインストールのライフサイクル試験を実行する。

ローカルではOSへインストールせず、`.test-work/windows-package/` 内の
アプリイメージだけを検証する。MSIの生成と `msiexec /qn` による無人
インストール・修復・更新・アンインストールは、一時的なGitHub Actions
ランナーで実行する。MSIの版を上げても
`packaging/windows/package-identity.psd1` の `UpgradeUuid` は変更しない。
変更すると既存版を更新できなくなる。具体的なテスト境界、依存関係更新、
完成条件は `packaging/windows/README.md` と
`packaging/windows/requirements.md` を参照する。


<a id="update-7"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Extension API を変更する場合

公開・protected API は未知の外部 Extension も利用しているものとして扱う。
削除が避けられない場合は `tests/README.md` の基準に従い、削除予定の完全修飾
シグネチャと根拠を `tests/compat/allowed-api-removals.txt` に記録してから ABI
基準を更新する。


<a id="update-8"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 旧取得設定からの移行

Smile、DMC単一ファイル、DMC-HLSの新規取得経路は廃止した。利用者の
`config.properties` に次の設定が残っていても参照されないため、更新時に削除して
よい。

- `disableStreamingWarning`
- `useSmileCacheInsteadOfDmcEconomy`
- `useSmileCacheInsteadOfDmc`
- `ignoreEmbeddedPlayer`
- `noLiveCache`
- `deletedMoviePlayMode`
- `mapFileUpdatingInterval`
- `swfConvert`
- `swfCacheV3`
- `swfConvertAll`

今回の初期設定整理では、現行ページや配信経路では動作しない、または実装上効果がなかった
次の設定も廃止した。`config.properties`に残っていても参照されないため削除してよい。

- `niconicoMode`（現行Domand・NV API・NVCommentホストを許可できない旧接続先制限）
- `localFlv`（保存済み単一ファイルの`/cache/<動画ID>.<拡張子>`配信は常時利用可能）
- `scriptOn`、`scriptTarget`、`scriptText`（旧watch HTMLへの固定スクリプト注入）
- `useSearchExtension`、`searchResultMax`、`insertSearchResultToTagPage`
  （旧検索・タグHTMLへの検索結果注入）
- `thcacheMode`、`quickThumbnailCache`（旧`tn.smilevideo.jp`用単一ファイルサムネイル）
- `thcacheFixEpoch`、`swfDebug`（旧サムネイル移行・デバッグ）
- `deletedVideoId`（廃止済みgetflv固有の削除動画例外）
- `resumeDownload`、`cacheAllocateFirst`、`reportCachingProgress`（実装上効果がなかった設定）
- `useWorkaroundForEncoding`、`useWorkaroundFastFinalize`（旧Java実装向け実験設定）
- `flv2Mp4AdaptToFlash`（Flash再生向け音声変換）

同梱Extension本体が存在しなかった`NGCommentExtension.properties`と
`nlMovieFetcher.properties`も初期設定から削除した。該当Extensionを別途導入している場合は、
そのExtensionが提供する現行設定例を利用者の`config.properties`へ明示的に移す。

既存のFLV、SWF、MP4、旧HLSキャッシュは削除しない。更新後も `/cache/` APIと
ローカルキャッシュ配信から利用できる。ここで廃止する `swfConvert*` は上流から
取得中にSWFを書き換える設定であり、保存済みSWFの読取りには影響しない。


<a id="update-9"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 復旧

機能テストが失敗した場合は `.\test-functional.ps1 -KeepWorkDir `
`-LibraryDirectory .\.test-work\build-dependencies` を実行し、
`.test-work/functional/sandbox/nicocache-functional.log` を確認する。テスト領域は
実データから独立しているため、不要になった `.test-work/functional/` は削除して
よい。

本体ビルド前の JAR が必要な場合は `NicoCache_nl.jar` を別名で退避してから
ビルドする。ユーザーのキャッシュや設定をロールバック手段として削除しない。

Windowsパッケージの生成に失敗した場合は、既存のNicoCache_nlやOS設定を
変更せず、Git管理外の `.test-work/windows-package/` だけを削除して再生成する。
証明書ストア、Windowsプロキシー設定、タスクスケジューラーを復旧操作として
変更する必要はない。

Linux/macOSパッケージの生成に失敗した場合は、既存のインストール先やOS設定を
変更せず、`.test-work/unix-package-*` と
`.test-work/standalone-updater-*` だけを削除して再生成する。アーカイブ更新が失敗した
場合は、アップデーターが作成した同一親ディレクトリ内の一時バックアップを使って
自動復旧し、復旧失敗時は削除せず診断を残す。

初回セットアップの適用に失敗した場合は、同じ試行で作成した設定とOS変更が
自動復元される。復元にも失敗した場合は
`data/setup-system-state.json` を削除せず保持し、記録された変更前状態を確認して
から `packaging/windows/runtime/first-run-setup.ps1 -Action Rollback` 相当の
復元処理を行う。ユーザーの既存設定を推測で削除しない。


<a id="update-10"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Dependabot PR の更新

前提は `.github/dependabot.yml` と PR 用の `CI` です。変更パスに応じて Standalone Updater・Unix Packages・Windows Installer も起動します。更新 PR の head SHA と `gh pr checks <PR番号>` の結果を確認してください。patch／minor／major は起動した全チェック成功後に自動取り込みされます。初回 CI 失敗は failed jobs のみを 1 回再実行し、再失敗した PR は残して手動で修正します。

設定を変えたときは `actionlint .github/workflows/dependabot-automation.yml` と実際の PR の Actions 結果を確認します。問題があれば呼び出し先の共通 workflow SHA を直前の検証済み値へ戻すコミットを push します。取り込まれた依存更新に問題があれば通常の revert コミットで復旧します。

CI 完了より Dependabot の分類が遅れる場合は、`callback_workflow_file` が指す呼び出し側 workflow を `workflow_dispatch` し、同じ PR 番号・head SHA・全チェックを再確認する。呼び出し側のファイル名を変える際はこの入力も一緒に更新する。


<a id="nlfilter-guide"></a>

移動元: [nlFilters/AGENTS.md](nlFilters/AGENTS.md)。

## nlFiltersの固有情報と操作規律

以下の相対パスは、移動前と同じくNicoCache_nlリポジトリ直下を基準にする。

# AGENTS.md

共通ルールは `COMMON-AGENTS.md` を必ず確認し、上位方針として扱う。
このファイルでは、リポジトリ直下の `nlFilters/` で管理する nlFilter 固有の補足だけを記載する。
配置先は固定パスと仮定せず、以下の相対パスはすべて NicoCache_nl リポジトリ直下を基準にする。

## このディレクトリの役割

- NicoCache_nl本体リポジトリが、実際に読み込む`nlFilters`ディレクトリを
  直接Git管理している。独立した`.git`は置かない。ここで追跡中の`.txt`を
  変更すると、作業用コピーではなく稼働環境のフィルターが変わる。
- nlFilter は、対象 URL と Content-Type に応じてレスポンス本文、HTML、JavaScript、CSS、リクエストヘッダーを変更する NicoCache_nl 専用 DSL である。
- 現在 Git 管理している主なフィルターは次のとおり。
  - `01_globalFilter.txt`: 共通の head 挿入位置と `NicoCache_nl` グローバルを用意する。
  - `05_topBarFilter.txt`: 空き容量警告とキャッシュ削除ボタンを追加する。
  - `08_MutationObserverHooks.txt`: 動的に追加される動画要素を検出する共通フックを提供する。
  - `09_thumbInfoFilterBase.txt`: サムネイルポップアップの共通スクリプトを読み込む。
  - `10_thumbInfoFilterLegacyLinks.txt`: 従来HTML/XML向けのリンク置換を提供する。
  - `11_thumbInfoFilterVideoLinks.txt`: 現行SPAを含む動的動画リンクを補完する。
  - `15_thumbInfoFilterCache.txt`: キャッシュ状態に応じたリンク色と、CMAF/Domand品質バッジを追加する。
  - `20_watchFilter.txt`: 視聴ページ向けの置換、スタイル、スクリプトを提供する。
- `.vscode/`、`evac/` は Git 管理外のローカル領域である。ユーザーが明示した場合を除き、調査対象や変更対象に含めない。

## 現行 NicoCache_nl のキャッシュ契約

- 現行の動画配信・保存経路は Domand の CMAF セグメントである。保存先がディレクトリで拡張子相当が
  `.hls` のキャッシュを、`Cache`、`VideoDescriptor`、`/cache/info/v3` を通して扱う。
- `VideoDescriptor.isDmc()` と `/cache/info/v2` の `dmc` は公開ABI・保存済みキャッシュとの
  互換名であり、「現在もDMC配信を使っている」という意味ではない。Domand/CMAFもこの値が
  `true` になるため、UI文言、アイコン、色の世代判定へそのまま使わない。
- `/cache/info/v3` の動画ごとの値には `preferred`、`cacheIds`、`cachings`、`completes`、
  `caches` がある。表示対象はまず `preferred` を使い、該当する `caches[cacheId]` の
  `complete` を確認する。
- 各 `caches[cacheId]` には `videoMode`、`audioBitrate` が直接入る。`videoMode` は
  `1080p`、`720p-mid`、`360p-lowest` などの映像品質、`audioBitrate` は音声kbpsを表す。
  フィールド欠落から架空の品質を推測しない。
- 新規CMAF/Domandキャッシュ名には`low`を付けない。保存済み`low`名は互換読み込みし、
  v3では`legacyLow`として区別する。`videoMode`内の`_low` / `-lowest`は映像品質名であり、
  ファイル名の`low`とは別概念として扱う。
- `idGroup` の `<通常$$エコノミー$$dmc通常$$dmcエコノミー>` は本体DSLが維持する旧互換分岐で、
  CMAF/Domandの解像度・音声品質を表現できない。静的置換は汎用フォールバックに留め、現行DOMの
  品質表示は `/cache/info/v3` と `local/ncnl_cache_display.js` を使う。
- キャッシュ表示の共通契約は `local/ncnl_cache_display.js`、外観は `local/nl_cacheIcon.css` に置く。
  一覧用 `local/15_cached_link_color.js` と視聴ページ用 `local/20_watchpage.js` へ品質選択や
  アイコンDOM生成を重複実装しない。
- `/cache/*` の本体API契約は `documents/api.md`、実装は
  `src/dareka/processor/impl/CacheDirProcessor.java`、キャッシュの識別・優先順位は
  `VideoDescriptor.java` と `Cache.java` / `CacheManager.java` を正とする。

## 作業前に確認するもの

- 最初にリポジトリ直下で`git status --short --branch`を実行し、
  本体を含むユーザーの未コミット変更を把握する。
- 対象ファイル内の説明、変更履歴、依存するフィルターを先に読む。`09` の共通資産、`10` の旧HTML互換、`11` の現行動的リンク、`15` のキャッシュ表示は役割を分担しているため、片方だけを見て重複実装しない。
- 公式同梱フィルターの背景と運用は `documents/Readme_nl+mod.txt`、現在の実装は `src/dareka/processor/impl/EasyRewriter.java` を参照する。本体ソースも同じリポジトリにあるが、必要性を確認せずフィルター変更へ混在させない。
- JavaScript や CSS が参照する `/local/*` の実体、`window.NicoCache_nl`、`/cache/*` API を変更・利用するときは、`local/`、`documents/api.md`、本体実装、呼び出し元を検索して契約を確認する。
- 追跡中の nlFilter を編集する前に `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 source-check` と `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check` を本体リポジトリ直下から実行し、本体パーサーソースとの基準一致と既存構文の正常性を確認する。

## nlFilter Lab の使用

- 追跡中の `.txt` を変更した作業では、最低限 `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check <対象ファイル>` と `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test` を実行する。コーディングエージェントから結果を扱う場合は `check --json` を使ってよい。
- HTML、JavaScript、CSS、SPA挙動へ影響する変更では `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless` を使う。対象に応じて `watch`、`search`、`anime` のfixture、5種類の互換キャッシュ状態、`--spa-add` を選び、`result.json`、`final.html`、`console.json`、`screenshot.png` を確認する。Labの`DMC` / `DMC_ECONOMY`モックは現行CMAF/Domandの品質メタデータを返す。生成物は `nlFilters/.cache/nlfilter-lab/` 配下に置き、Gitへ追加しない。
- 対話的な調査が必要なら `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 serve` を使う。Labは疑似実装なので、最終的な実環境確認が必要な変更では NicoCache_nl 経由の検証も省略しない。
- NicoCache_nl の nlFilter パーサーは滅多に変わらないが、変更される可能性はある。`source-check` または `check` がソース差異を報告した場合や、新しい構文・マクロを扱う場合は、`src/dareka/processor/impl/EasyRewriter.java`、`src/dareka/common/regex/JavaPattern.java`、`JavaMatcher.java`、`NestPattern.java`、`NestMatcher.java` を確認し、必要に応じて構文チェッカー、ローカルテスター、互換テストを修正する。
- `nlFilters\tools\nlfilter-lab\parser-baseline.properties` のハッシュだけを更新して差異を解消してはいけない。本体の変更内容を監査し、Lab側の修正とテストが完了した後に基準値を更新する。
- Lab自体または本体パーサーとの互換処理を変更した場合は `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json` も実行し、`source.status` と `productionOracle.status` がともに `matched` であること、残る差が `externalBoundaries` に限定されることを確認する。

## 詳細リファレンス

- [nlFiltersとは？](https://roflsunriz.github.io/setup-nicocache-nl/nl-filters/): nlFilter の目的、配置、更新、自動再読み込みを確認する入口として使う。
- [nlFiltersの文法](https://roflsunriz.github.io/setup-nicocache-nl/nl-filters-syntax/): セクション、基本フィールド、動作オプション、`idGroup`、専用コマンド、変数、サンプルを調べる標準リファレンスとして使う。記法を記憶で補わず、実際の該当節を確認する。
- [正規表現](https://roflsunriz.github.io/setup-nicocache-nl/regex/): 文字クラス、境界、先読み・後読み、グループ、数量詞と nlFilter 向け例を確認する。
- フルアクセスがある場合は同様のドキュメントが`C:\Users\UserName\Documents\setup-nicocache-nl\docs`で確認できる。（setup-nicocache-nlのリポジトリ）
- 上記ページは実用上の第一参照先、リポジトリ内の既存フィルターは互換性のある用例、`EasyRewriter.java` など現在の本体ソースは最終的な動作仕様として扱う。説明と実装が食い違う場合は、稼働中バージョンの実装を優先し、差異を報告する。
- 一般的な JavaScript 用正規表現解説や古い nlFilter 配布物だけを根拠にしない。nlFilter の本文マッチは Java の正規表現エンジンと NicoCache_nl 独自の前処理・置換を通るためである。

## Git 管理境界とシンボリックリンク

- 変更前に `git ls-files` で Git 管理対象か確認する。現在の `100_features.txt`、`101_disable_official_function.txt`、`105_premium_hide.txt` は `.gitignore` 対象であり、`C:\filter-matome\nlFilters` を参照するシンボリックリンクである。
- リンクは見かけ上このディレクトリにあっても、編集は参照先を直接変更する。`100` 番台を依頼された場合は `Get-Item -Force <path> | Format-List FullName,LinkType,Target` で毎回リンク先を確認し、`C:\filter-matome` 側の `AGENTS.md` と `nlFilters\nlFilters_編集ガイド.md` に従う。
- リンクを通常ファイルへ置換したり、参照先の内容をNicoCache_nlリポジトリへコピーしたりしない。作成、削除、張り替えが必要なら、ユーザーの依頼範囲と参照先を確認してから行う。
- `01`、`05`、`08`、`09`、`10`、`11`、`15`、`20` は NicoCache_nl の標準フィルター群である。上流版との差分を意図せず消さない。大きな独自機能を加える場合は、既存ファイルへ詰め込む前に、適用順が分かる別番号ファイルに分離できるか検討する。

## 読み込み順とファイル名

- NicoCache_nl は `nlFilters` 直下の `.txt` をファイル名の辞書順で読み込み、各ファイル内は上から順に解析する。番号は分類だけでなく適用順を表すため、リネームや新規追加では前後のフィルターとの依存関係を確認する。
- 新規ファイルは既存の番号体系に合わせ、役割が分かる名前にする。同じ番号を安易に再利用せず、辞書順を実際に列挙して意図した位置になることを確認する。
- `nlFilterIgnore` と、ファイル名に `$` を含むバックアップなどは読み込み対象外になり得る。無効化確認のためにファイル名だけを変える場合も、この挙動を踏まえる。
- 一時コピーや退避ファイルを `nlFilters` 直下へ置かない。拡張子が `.txt` のファイルは意図せず読み込まれる可能性がある。

## nlFilter 構文を編集するときの規律

- 各フィルター先頭の `# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)` を維持する。
- 基本セクションは `[Replace]`、`[Script]`、`[Style]`、`[RequestHeader]`、`[Config]` である。通常の本文置換、JavaScript挿入、CSS挿入、リクエストURL/ヘッダー書き換え、拡張から参照する設定という役割を混同しない。
- 一時的にフィルターを無効化する場合は、文法ページに従いセクション行を `#[Replace]` のようにする。ファイル名変更や本文全体のコメントアウトで読み込み順・履歴を不明瞭にしない。
- `URL` は `https://` などのプロトコルより後ろを前方一致で評価し、`FullURL` はプロトコルを含めて評価する。POSTデータ側を対象にする `POST/` 接頭辞も含め、用途に合う形式を使う。正規表現の `.` は `\.` とし、対象ホストとパス境界を必要以上に広げない。
- `ContentType` は部分一致の正規表現であり、指定時は Content-Type のないレスポンスにマッチしない。先頭の `!` による否定も可能だが、広い否定条件より必要な型の明示を優先する。
- `Require` はレスポンス本文、`RequireHeader` はリクエストヘッダーに対する追加条件である。CookieやUser-Agentを扱う場合は、値をログ、コメント、テストデータへ残さない。
- `Match<`、`Replace<`、`Append<` の本文は、単独行の `>` で閉じる。DSL の区切りと、埋め込んだ HTML/JavaScript/CSS の括弧・タグを別々に確認する。
- `EachLine = FALSE` または省略時は `Match` 内の物理改行が無視され、`Replace` 内の改行は出力へ反映される。改行自体をマッチさせる場合は `\s*` や `\r\n` を明示する。
- `Multi` は省略時、最初の1件だけを置換する。全件置換が本当に必要な場合だけ `Multi = TRUE` にする。`EachLine = TRUE` では `Match` と `Replace` の各行が同じ順番で1組になるため、行数と対応を確認する。
- `MatchLocal = TRUE` は通常のURL条件を `/local/` 配下にも適用する。`URL` 自体に `/local/` を明示した場合の挙動とは分けて考え、ローカル配信物へ意図せず全フィルターを適用しない。
- `AddList` は置換結果をLSTへ追加し、`AddVariable` はURL固有変数へ保存する。単なる本文置換ではないため、保存先、重複、更新後の利用元、秘密情報の混入を確認する。
- `$0`、`$1`、`$TS(...)`、`<nlVar:...>`、`<CRLF>`、`<$>`、`<通常$$エコノミー$$dmc通常$$dmcエコノミー>` などは NicoCache_nl が解釈する記法である。最後の4分岐名は現行配信方式の名称ではなく保存互換の内部状態である。通常の正規表現置換やテンプレート文字列だと思って書き換えない。
- `idGroup` はキャッシュ存在時だけ置換するためのキャプチャ番号である。グループを追加・削除すると番号がずれるため、`$1` などの置換参照と一緒に見直す。`<$>` または文字列内のキャッシュ種別分岐は、同じ `Replace` 内で複数使用できないという制約も確認する。
- `$NEST` はネストしたタグ向けの単独コマンドである。開始・終了タグにキャプチャグループを入れず、通常の正規表現と同じ行へ混在させない。
- `$LST("file")` はファイルを行単位の選択肢として読み込み、キャプチャグループを1つ増やす。`!` 付きは内容をエスケープせず正規表現として扱う。リストが空の場合に置換がスキップされること、動的更新されること、参照番号がずれることを考慮する。
- `$INC`、`$SET`、`AddVariable`、`<nlVar:...>` は状態を介して別の置換へ影響する。変数名の利用元を検索し、初期値、数値/文字列、URL単位か設定単位かを確認する。`$SET` の値を動的な式として扱わない。
- `$TS(path)` は `[Replace]` でローカルファイルの更新時刻をクエリへ付ける用途であり、ファイルが存在しない場合は引数がそのまま残る。`$URL0`、`$URL1` などは `URL` 条件側のキャプチャを参照し、`$0`、`$1` などの本文マッチ参照とは区別する。
- `[Debug]` は調査対象URL、マッチ、置換結果を大量にログへ出し得る。一時調査だけに使用し、必要な結果を得たら削除する。ログへCookie、本文、個人情報が出ていないか確認する。
- `#` が行頭にある行だけがコメントになる。ブロック本文の途中や行末コメントを一般的な設定ファイルと同じ感覚で追加しない。
- ファイルごとの既存の改行コードを維持し、無関係な行末変換や全体整形を混ぜない。文字コードは既存どおり UTF-8 を基本とし、BOMを不用意に追加しない。

## 正規表現を変更するときの規律

- DSLの正規表現は Java `Pattern` として解釈される前提で書く。ブラウザーのJavaScriptコンソールやRubularだけで合格しても完了とせず、JavaとNicoCache_nl独自コマンドの差を確認する。
- `.`、`?`、`+`、`*`、`(`、`)`、`[`、`]`、`{`、`}`、`^`、`$`、`|`、`\` を文字そのものとして扱う場合はエスケープする。特にドメインの `.` とクエリ区切りの `?` を見落とさない。
- キャプチャが不要なまとまりには `(?:...)` を使う。不要なキャプチャ追加は `$1`、`idGroup`、`$URL1`、`$LST` が増やす番号を連鎖的にずらす。
- Match内の後方参照は `\1`、Replace内の参照は `$1` である。先読み・後読みは境界確認に限定し、本文全体を何度も走査する複雑な入れ子にしない。
- `^`、`$`、`\b` は行・単語境界、`[\s\S]` は改行を含む任意文字という意味を意識する。部分一致でよいのか、文字列全体またはURL境界を固定すべきかをテストケースごとに決める。
- `\d`、`\w` などの短縮文字クラスを使う前に、対象が半角ASCIIだけか、日本語や全角文字を含むかを確認する。動画IDなど形式が明確な値は `[a-z]{2}\d{1,12}` のように範囲を明示する。
- 貪欲な `.*` や `[\s\S]*` を大きなHTMLへ適用すると、誤マッチとバックトラッキング負荷が増える。安定した前後境界、否定文字クラス、適切な非貪欲量指定を使い、マッチする例・しない例・長い入力を確認する。
- 正規表現だけで不安定な現行SPAのDOMを完全解析しようとしない。レスポンス置換が適切か、狭いJavaScriptフックへ責務を移すべきかを判断する。

## 埋め込み JavaScript / CSS

- グローバル汚染と他フィルターとの変数衝突を避けるため、独立した処理は IIFE などでスコープを閉じる。共有が必要なものだけを、既存契約に従って `window.NicoCache_nl` 配下へ置く。
- SPA と遅延描画を扱う場合は、初期DOMと後続Mutationの両方を処理し、二重登録・二重挿入を属性、クラス、`WeakSet` などで防ぐ。MutationObserver の監視範囲とコールバック内の探索量を最小限にする。
- DOM セレクターは、表示文言、生成ハッシュ、壊れやすい階層だけに依存させない。対象ページの現行DOMを確認し、URL、安定属性、意味のある要素を優先する。
- 複数動画の `/cache/info/v3` 取得は可能な限りまとめ、同じ動画や要素への重複リクエストを避ける。HTTPエラー、JSON不正、要素消失を扱い、失敗時に無限再試行しない。
- ページ側へ差し込むコードは、対象ブラウザーと NicoCache_nl がそのまま配信できる JavaScript/CSS にする。このリポジトリにはトランスパイルやバンドル工程がないため、ビルドで補正されると仮定しない。
- `console` 出力は障害解析に必要な警告・エラーへ限定し、動画ID、Cookie、レスポンス本文などを大量に出力しない。
- CSS は既存のキャッシュアイコンやポップアップの詳細度を確認し、`!important`、固定座標、サイト全体に波及するセレクターを最小限にする。

## 変更方法

- 依頼された不具合に対して、まず対象 URL、期待する変換、実際のレスポンスまたは DOM、既存フィルターがマッチしない理由を特定する。
- 既存機能の修正は、可能な限り対象セクションだけを小さく変更する。古いページ向け処理を、現行ページで再現できないという理由だけで削除しない。
- サイト仕様変更への対応では、確認日、旧構造、新構造、対応方針を対象ファイルのコメントへ簡潔に残す。長い調査ログやエージェント向け検証手順はソースへ埋め込まない。
- nlFilter自体には外部依存やビルド工程を追加しない。小規模なフィルター変更のたびに形式だけの `package.json` や独立文書を新設せず、利用者影響は本体ルートの`CHANGELOG.md`、対象ファイルの変更履歴コメント、日本語Conventional Commitへ記録する。
- `src/`、`config.properties`、`local/`、`nlFilter_sys.txt` も同じGit管理下だが責務は異なる。フィルター修正のついでに変更せず、本体変更が必要なら根拠と検証範囲を明確にして別コミットにする。ただしフィルターが明示的に読み込む`local/`資産は同じ表示機能として一緒に検証する。

## 検証

- 最低限、変更後に次を確認する。
  1. `git diff --check` で空白エラーを確認する。
  2. `git diff -- <対象ファイル>` で意図しない行末変換、文字化け、別セクションの変更がないことを確認する。
  3. セクション、ブロック終端、正規表現、埋め込みJavaScript/CSSの括弧と構文を確認する。
  4. 変更対象URLと、隣接する非対象URLの両方で適用範囲を確認する。
- NicoCache_nl は `nlFilterCheckInterval` に従ってファイル更新を検出し、次の対象リクエスト時にユーザーフィルターを再読み込みする。単なる再読込のために、まず本体を再起動しない。
- 実環境確認では、NicoCache_nl 経由で対象ページをハード再読み込みし、必要に応じて次を確認する。
  - NicoCache_nl ログの `Loading User Filters` と対象ファイル名、および `parse error`、`pattern error`、例外の有無。
  - 変換後DOM、対象要素、重複挿入、コンソールエラー、ネットワークリクエスト数。
  - 初期表示、SPA内遷移、戻る/進む、再描画後の挙動。
  - キャッシュあり/なし、完成/取得中、旧形式/CMAF(Domand)、複数の`videoMode`・音声kbps、旧DMCの映像bitrate、品質フィールド欠落など変更に関係する状態。
- ページ内容は外部サイトの現在状態に依存する。再現できない場合は、確認できた静的事項、未実行の実環境確認、その理由と残るリスクを最終報告へ分けて記載する。
- 実環境検証が必要でも、キャッシュ削除、ログイン状態変更、設定変更など副作用のある操作を勝手に行わない。

## NicoCache_nl の起動・終了が必要な場合

- フィルターの通常再読込で足りないことを確認してから再起動する。
- 終了前に、リポジトリ直下の `NicoCacheLauncher.jar --headless --stop` が対象にできる本体とPIDを確認する。Javaプロセスを名前だけで一括終了しない。
- 通常終了は `NicoCacheLauncher.jar --headless --stop`、起動は配布物に同梱されたランチャーを使う。JARと同梱JREの存在を先に確認し、開発用ビルドスクリプトを起動手段として流用しない。
- 強制終了はユーザーが現在の依頼で明示的に許可した場合だけ行う。終了確認が取れない状態で新しいプロセスを重ねて起動しない。
- 起動後は新しいPID、フィルター読込ログ、対象ページでの動作まで確認し、起動コマンドが成功しただけで完了としない。
