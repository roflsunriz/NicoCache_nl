# Responsibility boundaries

- NicoCache_nl本体は更新GUIや依存関係更新エンジンを内包しません。
- 独立アップデーターが本体更新と外部依存更新のUI、取得、ハッシュ検証、展開、バックアップ、適用、ロールバックを担当します。
- 本体更新の配布物はOSごとに選択します。WindowsはMSIをWindows Installerへ渡し、
  Linux/macOSは署名検証済みアプリイメージZIPを安全な一時領域へ展開して置換します。
- Linux/macOSのZIP更新では`config.properties`、`portable.flag`、キャッシュ、証明書、
  利用者データ、`local`、`nlFilters`、Extensionを保護し、製品ファイルだけを更新します。
- 外部依存関係の処理はアップデーター内の純Javaエンジンで行い、PowerShellや対象側のスクリプトには依存しません。
- 外部依存関係のOS差分は`DependencyProvider.forPlatform`で分離し、Windowsでは
  `WindowsDependencyManager`、Linux/macOSでは`UnixDependencyManager`を選択します。
  WindowsのWinGet、`reg.exe`、ユーザーPATH処理はWindows実装に閉じており、Updater全体を
  Windows専用へ固定しません。
- 外部依存関係は導入版・最新版・更新有無・インストール可否を行単位で保持し、
  更新チェック済みで新バージョンがある項目だけをインストール対象にします。
- インストール完了後は同じプロセスで実コマンドとローカル依存状態を再検出し、
  再起動なしにGUIの状態を更新します。
- アップデーター自身のインストール先と更新対象のNicoCache_nlルートを分離し、対象ルート外への書き込みとリンク経由の逸脱を拒否します。
- 公開中Temurin LTSはAdoptium APIから動的に取得し、NicoCache_nlで検証済みの17/21/25を選択可能にします。Java 25を推奨・既定とします。
- 配布物はSHA-256またはSHA-512を取得できる場合だけ受理し、7-ZipのGitHub Release assetsもAPIのSHA-256 digestを必須とします。
- GPACは[公式GitHub Release API](https://api.github.com/repos/gpac/gpac/releases/latest)で
  版番号を確認し、Windowsでは[WinGet公式マニフェスト](https://github.com/microsoft/winget-pkgs/tree/master/manifests/g/GPAC/GPAC)の
  `GPAC.GPAC`を優先して導入します。WinGetに存在しない依存関係だけを公式配布APIへ
  フォールバックします。
- Windows/Linux/macOSのアプリイメージ成果物は専用Javaランタイムだけを内包し、
  PowerShellスクリプトやPowerShellデータファイルを同梱しません。
- Solarisは配布・CI・アップデーターの対象外です。
