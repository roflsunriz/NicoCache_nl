# NicoCache_nl Updater

NicoCache_nl本体と管理対象の外部依存関係を、一つの独立GUIから更新するためのアプリケーションです。

## GUI

- `NicoCache_nl` タブ
  - GitHub Releasesから最新版を取得
  - MSIとSHA-256を取得して検証
  - Windows Installerを起動
- `外部依存関係` タブ
  - Eclipse Temurin、FFmpeg、Bouncy Castle、Apache Ant、7-Zipを確認・更新
  - Adoptium APIから公開中のLTSを動的に列挙
  - NicoCache_nlで動作確認済みのLTSだけを選択可能にし、未対応LTSはグレー表示

## ビルド

```powershell
./packaging/windows/build-standalone-updater.ps1 -PackageType All
```

アップデーターは`jpackage`で専用Javaランタイムを内包するため、NicoCache_nl本体のJavaランタイムが破損していても起動できます。

対象のNicoCache_nlディレクトリを明示する場合は、次の引数を使用します。

```text
NicoCache_nl Updater.exe --app-root C:\path\to\NicoCache_nl
```
