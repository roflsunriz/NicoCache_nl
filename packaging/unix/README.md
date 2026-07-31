# Linux/macOS パッケージ

LinuxとmacOSの配布物は、対象OS上のJDK 25 `jpackage`で生成します。`jpackage`の
ネイティブパッケージ生成はクロスプラットフォームではないため、Linux成果物はLinux
ランナー、macOS成果物はmacOSランナーでビルドします。Solarisは現在の配布対象に
含めません。

## 本体

```powershell
./packaging/unix/build-package.ps1 -Platform Linux -PackageType All -AppVersion 0.1.0
./packaging/unix/test-package.ps1 -Platform Linux -AppVersion 0.1.0
```

LinuxではアプリイメージZIP、DEB、RPM、macOSではアプリイメージZIP、PKG、DMGを
生成します。成果物名は次の形式です。

```text
NicoCache_nl-<version>-linux-<arch>.<zip|deb|rpm>
NicoCache_nl-<version>-macos-<arch>.<zip|pkg|dmg>
```

アプリイメージのランチャーとJavaランタイムを含むため、配布物はビルドしたOSと
アーキテクチャに対応します。Linuxアプリイメージのランチャーは
`NicoCache_nl/bin/NicoCache_nl`、macOSアプリバンドルのランチャーは
`NicoCache_nl.app/Contents/MacOS/NicoCache_nl`です。Linuxの初回セットアップは`trust`、GNOMEの
`gsettings`、ユーザー自動起動デスクトップエントリーを使用し、macOSでは
`security`、`networksetup`、`LaunchAgents`を使用します。利用できないOSサービス
や権限が必要な変更は、ウィザードで失敗として表示し、同じ試行で行った変更を
ロールバックします。

macOSの`jpackage`は0をメジャー版にできないため、0.xの公開版を指定した場合も
配布ファイル名と`NicoCache_nl.version`には公開版を保持し、バンドル内部の版だけを
macOSが受け付ける値へ変換します。

## 独立アップデーター

```powershell
./packaging/unix/build-standalone-updater.ps1 -Platform MacOS -PackageType All -AppVersion 0.1.0
./packaging/unix/test-standalone-updater.ps1 -Platform MacOS -AppVersion 0.1.0
```

本体更新では、GitHub Releaseのプラットフォーム・アーキテクチャ別アプリイメージ
ZIPをSHA-256検証して展開し、`config.properties`、`portable.flag`、キャッシュ、
証明書、利用者データ、ローカル拡張を保持します。Windowsは従来どおりMSIを
Windows Installerへ渡し、Linux/macOSは安全なバックアップ・置換・ロールバックを
Java内で行います。

Unixの外部依存関係タブは、Java、FFmpeg、Apache Ant、7-Zipを確認します。root権限を
自動取得せず、導入・更新は各OSのパッケージ管理へ案内します。Bouncy Castleは
NicoCache_nl専用ライブラリとしてアップデーターがSHA-256検証付きで管理します。

## CI

`.github/workflows/unix-packages.yml`がUbuntuとmacOSで本体・アップデーターの
アプリイメージとネイティブパッケージを生成し、隔離した初回セットアップ、ZIP、
DEB/RPMまたはPKG/DMG、アップデーターCLIの自己診断を実行します。
