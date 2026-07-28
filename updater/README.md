# NicoCache_nl Updater

NicoCache_nl本体と管理対象の外部依存関係を、一つの独立GUIから更新する純Javaアプリケーションです。

## GUI

- `NicoCache_nl` タブ
  - GitHub Releasesから最新版を取得
  - MSIとSHA-256を取得して検証
  - Windows Installerを起動
- `外部依存関係` タブ
  - Eclipse Temurin、FFmpeg、Bouncy Castle、Apache Ant、7-Zipを確認・更新
  - Adoptium APIから公開中のLTSを動的に列挙
  - NicoCache_nlで動作確認済みのLTSだけを選択可能にし、未対応LTSはグレー表示
  - WinGet標準のスコープ選択に任せ、既定ではユーザーを優先しつつ、
    パッケージがマシンスコープだけに対応する場合はWindowsの許可後にマシンへ導入
  - WinGetパッケージが提供されていないApache Antなどは、公式配布APIから
    現在のWindowsユーザー用ディレクトリへ導入
  - 取得、ハッシュ検証、展開、バックアップ、置換、ロールバックをJava内で実行

外部依存関係を更新するときは、NicoCache_nlを先に終了してください。更新対象が使用中で置換できない場合、処理は失敗し、既存内容を復元します。

## セキュリティ境界

- Updater自身のインストール先とNicoCache_nlの対象ルートを分離
- 対象ルート外への書き込み、ZIP traversal、シンボリックリンク／reparse point経由の逸脱を拒否
- SHA-256またはSHA-512が取得できない配布物を拒否
- ZIPのエントリ数と展開後サイズに上限を設定
- 同時更新をファイルロックで拒否
- PowerShellスクリプトやPowerShellデータファイルを成果物へ同梱しない

## ビルド

```powershell
./packaging/windows/build-standalone-updater.ps1 -PackageType All
```

アップデーターは`jpackage`で専用Javaランタイムを内包するため、NicoCache_nl本体のJavaランタイムが破損していても起動できます。

対象のNicoCache_nlディレクトリを明示する場合は、次の引数を使用します。

```text
NicoCache_nl Updater.exe --app-root C:\path\to\NicoCache_nl
```

## 自動検証用CLI

通常利用ではGUIを使います。CIでは同じパッケージ済み実行ファイルから以下を実行します。

```text
NicoCache_nl Updater.exe --self-test --app-root C:\temporary\target
NicoCache_nl Updater.exe --dependency-check --app-root C:\temporary\target --java-major 21
```

`--self-test`はループバックHTTP経由のダウンロード、ハッシュ一致／不一致、ZIP展開、ZIP traversal拒否、バックアップ、置換、失敗後の既存内容維持を検証します。
