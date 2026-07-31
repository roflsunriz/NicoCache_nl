# NicoCache_nl Updater

NicoCache_nl本体と管理対象の外部依存関係を、一つの独立GUIから更新する純Javaアプリケーションです。

## GUI

- `NicoCache_nl` タブ
  - GitHub Releasesから最新版を取得
  - WindowsではMSI、Linux/macOSではOS・アーキテクチャ別アプリイメージZIPとSHA-256を取得して検証
  - WindowsではWindows Installerを起動し、Linux/macOSでは既存アプリイメージを安全に置換
- `外部依存関係` タブ
  - Eclipse Temurin、FFmpeg、Bouncy Castle、Apache Ant、7-Zipを確認・更新
  - Adoptium APIから公開中のLTSを動的に列挙
  - NicoCache_nlで動作確認済みのJava 17、21、25 LTSだけを選択可能にし、
    未対応LTSはグレー表示（推奨・既定はJava 25）
  - WindowsではWinGet標準のスコープ選択に任せ、既定ではユーザーを優先しつつ、
    パッケージがマシンスコープだけに対応する場合はWindowsの許可後にマシンへ導入
  - Windows更新後にユーザーPATHからWindowsAppsが欠落していても、登録済みの
    WinGet App Execution Aliasを絶対パスで検出して使用
  - WinGetパッケージが提供されていないApache Antなどは、公式配布APIから
    現在のWindowsユーザー用ディレクトリへ導入
  - 取得、ハッシュ検証、展開、バックアップ、置換、ロールバックをJava内で実行

Linux/macOSではOSのパッケージ管理が必要な外部依存関係を自動でroot導入しません。
表示された案内に従って導入してから再確認してください。NicoCache_nl本体を更新するときは
本体を先に終了してください。更新対象が使用中で置換できない場合、処理は失敗し、既存内容を復元します。

## 入手と検証

[GitHub Releases](https://github.com/roflsunriz/NicoCache_nl/releases) から、OSと
CPUアーキテクチャに合う`NicoCache_nl-Updater-<version>-<os>-<arch>`の配布物と
対応する`.sha256`をダウンロードします。アップデーターの版番号は本体のリリースタグ
から独立しており、`updater/VERSION`で管理します。WindowsはMSI、Linux/macOSは
PKG/DMGまたはDEB/RPMとアプリイメージZIPを公開します。

インストール前に、同じディレクトリでSHA-256を照合します。

```powershell
$package = Get-ChildItem .\NicoCache_nl-Updater-* | Where-Object { $_.Name -notmatch '\.sha256(?:\.txt)?$' } | Select-Object -First 1
$expected = (Get-Content "$($package.FullName).sha256" -Raw).Split()[0]
$actual = (Get-FileHash $package.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'アップデーター配布物のSHA-256が一致しません' }
```

## セキュリティ境界

- Updater自身のインストール先とNicoCache_nlの対象ルートを分離
- 対象ルート外への書き込み、ZIP traversal、シンボリックリンク／reparse point経由の逸脱を拒否
- SHA-256またはSHA-512が取得できない配布物を拒否
- ZIPのエントリ数と展開後サイズに上限を設定
- 同時更新をファイルロックで拒否
- PowerShellスクリプトやPowerShellデータファイルを成果物へ同梱しない

## ビルド

独立アップデーターのビルドと `jpackage` にはJDK 25を使用します。ソースの
互換性ターゲットは引き続きJava 11（`--release 11`）です。

```powershell
./packaging/windows/build-standalone-updater.ps1 -PackageType All
./packaging/unix/build-standalone-updater.ps1 -Platform Linux -PackageType All
```

アップデーターは`jpackage`で専用Javaランタイムを内包するため、NicoCache_nl本体のJavaランタイムが破損していても起動できます。

対象のNicoCache_nlディレクトリを明示する場合は、次の引数を使用します。

```text
NicoCache_nl Updater.exe --app-root C:\path\to\NicoCache_nl
./NicoCache_nl\ Updater --app-root /path/to/NicoCache_nl
```

## 自動検証用CLI

通常利用ではGUIを使います。CIでは同じパッケージ済み実行ファイルから以下を実行します。

```text
NicoCache_nl Updater.exe --self-test --app-root C:\temporary\target
./NicoCache_nl\ Updater --self-test --app-root /temporary/target
./NicoCache_nl\ Updater --dependency-check --app-root /temporary/target --java-major 25
```

`--self-test`はOSごとの依存関係処理境界と一時ファイルを使う自己診断を検証します。
アーカイブのSHA-256、ZIP traversal拒否、バックアップ、置換、失敗後の既存内容維持は
`ArchiveApplicationInstallerTest`としてCIで検証します。
