# Windows インストーラー試作

自己完結型の Windows アプリイメージと MSI を生成する試作である。利用者の
Java、PowerShell 7、Apache Ant、7-Zip には依存しない。

## 生成物

- `NicoCache_nl.exe`: 通常はGUIで起動する単一の製品ランチャー
- `NicoCacheCA.exe`: OSへ登録せず証明書ファイルを生成する内部ランチャー
- アプリ専用 Java ランタイム
- NicoCache_nl 本体、証明書生成ツール、Bouncy Castle、既定設定、ローカル配信用
  ファイル
- `NicoCache_nl-<version>.msi`: 対話・無人インストール兼用パッケージ

MSI の無人操作には Windows Installer の標準オプションを使う。

```powershell
msiexec.exe /i .\NicoCache_nl-0.1.0.msi /qn /norestart
msiexec.exe /x .\NicoCache_nl-0.1.0.msi /qn /norestart
```

## ローカルでの安全な試作

JDK 17 でアプリイメージだけを生成する。成果物と依存ファイルは Git 管理外の
`.test-work/windows-package/` に限定される。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
```

スモークテストは次の制約で実行する。

- 空いているループバックポートを一時利用する
- 生成したアプリイメージ内だけにテスト設定とキャッシュを作る
- TLS MitMを無効にし、証明書ストアを変更しない
- テスト用CAをイメージ内だけに生成し、生成物を検証後に削除する
- 証明書ストアとWindowsプロキシー設定がテスト前後で一致することを確認する
- Windowsのプロキシー設定、環境変数、タスクスケジューラーを変更しない
- 製品と同じ `NicoCache_nl.exe` を内部用の `--headless` 指定で起動する
- 起動した単一ランチャーの実行ファイルとPIDを照合して、そのPIDだけを終了する
- 外部サイトへ接続せず、`http://127.0.0.1:<一時ポート>/` の応答だけを確認する

成功時のログは削除する。失敗調査でログを保持する場合は `-KeepLogs` を指定する。

ローカル環境には WiX Toolset を追加せず、MSI の生成、無人インストール、起動、
無人アンインストールは一時的な GitHub Actions の `windows-2022` ランナーで
検証する。

## 依存関係

Bouncy Castle 1.84 の取得URLとSHA-256を `dependency-lock.psd1` に固定している。
ダウンロード後のハッシュが一致しない場合は生成を中止する。依存バージョンを
更新する場合は、公式配布元、ライセンス、ハッシュ、証明書生成テストを確認して
ロックファイルと `THIRD-PARTY-NOTICES.txt` を更新する。

## 現在の試作範囲

この段階ではパッケージ生成と、単一製品ランチャーを使った隔離起動を対象とする。
証明書の生成・登録、Windowsプロキシー設定、自動起動、既存設定とキャッシュの
移行、変更前設定の保存とアンインストール時の復元はまだインストーラーから
実行しない。これらは初回起動ウィザードで明示的な同意とロールバック情報を
管理してから追加する。
