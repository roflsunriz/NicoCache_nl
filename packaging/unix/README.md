# Linux/macOS パッケージ

LinuxとmacOSの配布物は、対象OS上のJDK 25 `jpackage`で生成します。`jpackage`の
ネイティブパッケージ生成はクロスプラットフォームではないため、Linux成果物はLinux
ランナー、macOS成果物はmacOSランナーでビルドします。Solarisは現在の配布対象に
含めません。

## 本体

アプリイメージの単一ランチャーは起動管理アプリである。引数なしではGUIとタスクトレイを
表示し、ヘッドレスでは同じランチャーが本体の起動、状態確認、グレイスフル停止、強制停止、
初回セットアップを処理する。ログオン時に一回だけの自動起動はmacOS LaunchAgents、
Linux XDG autostartへ登録する。

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

保存済みCMAF/Domand変換器のJARと説明書は、LinuxのアプリイメージとアプリイメージZIP、
DEB/RPMでは`tools/cmaf-to-mp4/`、macOSのアプリバンドルとアプリイメージZIP、PKG/DMGでは
`Contents/Resources/tools/cmaf-to-mp4/`に含まれます。FFmpeg本体は同梱しません。

Linuxのjpackageアプリイメージでは、JARと設定を`lib/app/`、Javaランタイムを
`lib/runtime/`に配置します。アプリケーション側の設定・データ資材はアプリイメージの
ルートへ移します。macOSでは同じ資材を`Contents/Resources/`へ配置し、macOSの
コード署名が認識できる標準アプリバンドル構造を保ちます。

Linuxのアプリイメージ、アプリイメージZIP、DEB/RPM、およびmacOSのアプリバンドル、
アプリイメージZIP、PKG/DMGには、`NicoCache_nl.jar`、`NicoCacheCA.jar`、
`NicoCacheLauncher.jar`、`NicoCacheBuild.jar`の独立アプリ4本を同じ構成で含めます。
Linuxでは`lib/app/`、macOSでは`Contents/app/`がJARの配置先です。

既存ユーザーが旧ルートから新しいユーザーデータルートへ移行する場合は、起動管理GUIの
「データルート診断」で不足項目と起動阻害要因を確認できます。初回セットアップを完了した
パッケージの新規インストールでは、完了記録が作成されるため通常この診断は不要です。
詳細は[ユーザーデータルートの診断と移行](../../documents/user-data-root.md)を参照してください。

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
