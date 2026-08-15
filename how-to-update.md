# 更新・検証手順

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
```

GUI、タスクトレイ、ヘッドレスCLI、直JAR、自動起動のいずれから本体を起動した場合も、
本体の共通エントリーポイントが`NicoCacheDiagnostics.jar`の起動完了を確認してから処理を
開始する。画面のない環境では診断アプリはバックグラウンドで監視を継続する。稼働中に
診断プロセスだけが終了した場合は本体が再起動する。

`--headless --stop`、`--force-stop`、起動管理画面の停止、トレイ終了、JAR置換による計画停止では、
NicoCache_nl本体と診断アプリを揃って終了し、既存の常駐ランチャーだけを残す。本体が
クラッシュまたは外部から異常終了した場合は診断アプリを残し、障害採取を継続する。
「ランチャーのみ終了」は本体と診断アプリを残したままランチャーだけを終了する。
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

ビルドスクリプトはルートの5つのJARを更新するため、必要な検証が終わったら
`git status --short --branch` で生成物や無関係な差分が混入していないことを確認する。

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
   ```

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

## リポジトリ依存関係

GitHub Actionsは完全なコミットSHAへ固定し、Dependabotが毎週月曜日に同じ
メジャー系列の更新を確認する。メジャー更新は自動追従せず、変更内容と移行条件を
別途レビューする。DependabotとBouncy Castleの更新PRは自動マージしない。

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

## Extension API を変更する場合

公開・protected API は未知の外部 Extension も利用しているものとして扱う。
削除が避けられない場合は `tests/README.md` の基準に従い、削除予定の完全修飾
シグネチャと根拠を `tests/compat/allowed-api-removals.txt` に記録してから ABI
基準を更新する。

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
