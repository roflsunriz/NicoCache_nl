# Linux/macOS パッケージ

LinuxとmacOSの本体配布物は、対象OS上のTemurin JDK 25で生成する。`jlink`のJREと
プリコンパイル済みJARを、Git追跡ファイルがclone時と同じ相対位置にある
アプリケーションルートへ重ねる。Solarisは配布対象外である。

## 共通構成

アプリケーションルート直下には次を置く。

- Git追跡ファイル一式（`.git`、未追跡データ、シンボリックリンクを除く）
- `NicoCache_nl.jar`、`NicoCacheCA.jar`、`NicoCacheLauncher.jar`、`NicoCacheBuild.jar`
- 依存JARを置く`lib/`と、専用Javaランタイム`jre/`
- `jre/bin/java -jar NicoCacheLauncher.jar`を実行する`NicoCache_nl`
- `tools/cmaf-to-mp4/nico-cmaf-to-mp4.jar`と第三者ライセンス情報

別の`app/`、`lib/app/`、`Contents/Resources/`へアプリ資材を詰め替えない。
キャッシュ、証明書、個人設定、利用者が追加した`local/`、`nlFilters/`、Extensionは
別のユーザーデータルートへ保存する。

起動管理JARは引数なしではGUIだけを表示する。本体も起動する場合は`--start`、
タスクトレイへ格納する場合は`--tray`、最小化する場合は`--minimized`を使う。
ログオン時自動起動は`jre/bin/java -jar NicoCacheLauncher.jar --tray --start`として、
macOS LaunchAgentsまたはLinux XDG autostartへ登録する。

## ビルドと成果物

```powershell
./packaging/unix/build-package.ps1 -Platform Linux -PackageType All -AppVersion 1.2.3
./packaging/unix/test-package.ps1 -Platform Linux -AppVersion 1.2.3
```

LinuxではZIP、DEB、RPM、macOSではZIP、PKG、DMGを生成する。

```text
NicoCache_nl-<version>-linux-<arch>.<zip|deb|rpm>
NicoCache_nl-<version>-macos-<arch>.<zip|pkg|dmg>
```

ZIPはアプリケーションルートの内容を直に展開する。LinuxのDEB/RPMは同じルートを
`/opt/nicocache-nl`へ、macOSのPKGは`/Applications/NicoCache_nl`へ配置する。
DMGも同じ平坦な`NicoCache_nl`フォルダーを収録する。専用JREを含むため、成果物は
ビルドしたOSとアーキテクチャに対応する。

Linuxの初回セットアップは`trust`、GNOMEの`gsettings`、XDG autostartを使い、
macOSでは`security`、`networksetup`、LaunchAgentsを使う。利用できないサービスや
権限が必要な変更は失敗として表示し、その試行で行った変更をロールバックする。

データルートの確認と移行は[ユーザーデータルートの診断と移行](../../documents/user-data-root.md)
を参照する。

## 独立アップデーター

```powershell
./packaging/unix/build-standalone-updater.ps1 -Platform MacOS -PackageType All -AppVersion 0.2.2
./packaging/unix/test-standalone-updater.ps1 -Platform MacOS -AppVersion 0.2.2
```

独立アップデーターは本体とは別の自己完結アプリで、引き続き対象OSの`jpackage`を
使用する。本体更新ではGitHub ReleaseのOS・アーキテクチャ別ZIPをSHA-256検証して
展開し、`config.properties`、`portable.flag`、キャッシュ、証明書、利用者データ、
ローカル拡張を保持する。

外部依存関係はJava、FFmpeg、Apache Ant、7-Zip、GPAC/MP4Boxを確認し、macOSでは
Homebrew、LinuxではAPT/DNF/pacmanを明示操作で使う。Bouncy CastleはNicoCache_nl
専用ライブラリとしてSHA-256検証付きで管理する。

## CI

`.github/workflows/unix-packages.yml`がUbuntuとmacOSで本体と独立アップデーターを
生成する。本体についてはclone相当のファイル配置、JARとJRE、隔離起動、ZIP、
DEB/RPMまたはPKG/DMGの内容を検証する。
