# Windows インストーラー試作

自己完結型の Windows アプリイメージと MSI を生成する試作である。利用者の
Java、PowerShell 7、Apache Ant、7-Zip には依存しない。

## 生成物

- `NicoCache_nl.exe`: GUI、ヘッドレス、初回セットアップを担う唯一のアプリ
  ランチャー
- アプリ専用 Java ランタイム
- NicoCache_nl 本体、内部ライブラリとしての証明書生成ツール、Bouncy Castle、
  既定設定、ローカル配信用ファイル
- `NicoCache_nl-<version>.msi`: 対話・無人インストール兼用パッケージ

MSI の無人操作には Windows Installer の標準オプションを使う。

```powershell
msiexec.exe /i .\NicoCache_nl-0.1.0.msi /qn /norestart
msiexec.exe /x .\NicoCache_nl-0.1.0.msi /qn /norestart
```

固定したUpgrade UUIDにより、新版MSIは同じ製品の更新として扱う。対話導入では
インストール先とショートカットを選択できる。スタートメニューに加えて
デスクトップへ `NicoCache_nl` ショートカットを作成し、アンインストール時に
両方を削除する。インストーラー基盤の責務と採点は `requirements.md` を参照する。

## 初回起動ウィザード試作

MSIからの最初の通常GUI起動で `config.properties` がまだない場合、3画面の
初回セットアップを表示する。

1. 適用するまでOS設定を変更しないことを確認する
2. HTTPS証明書、Windows自動プロキシー、ログオン時自動起動を個別に選択する
3. 変更内容を確認して適用する

既存の `config.properties` がある更新利用者と、通常の `--headless` 起動では
表示しない。キャンセル時は設定ファイルを作らず終了する。
適用時は既定設定から `config.properties` を作り、選択に応じて `proxy.pac`、
CA証明書、現在ユーザーの証明書ストア、自動プロキシー、ログオン時起動を設定する。
ログオン時起動などで作業ディレクトリが製品ルートと異なる場合、同じ
`NicoCache_nl.exe` が製品ルートを作業ディレクトリとして一度だけ自己再起動する。
サイト証明書の対象は従来の `genCerts.bat` と共通の
`certificate-targets.txt` から読み込む。
OS設定の変更前値は `data/setup-system-state.json` に保存する。途中で失敗した
場合は今回作成したファイル、証明書、プロキシー、自動起動を復元し、次回起動で
再試行できる。Windows設定処理が失敗した場合は、失敗した処理段階と内容を
ダイアログへ表示し、同じ情報を `data/setup-windows-error.txt` に保存する。

画面を利用できない環境でも別の実行ファイルは使わず、同じ
`NicoCache_nl.exe` に全選択を明示して初回セットアップを実行する。

```powershell
.\NicoCache_nl.exe --setup --headless `
  --https=true `
  --trust-certificate=true `
  --proxy=true `
  --autostart=true
```

4項目はすべて `true` または `false` の明示が必要である。HTTPSを無効にする
場合、証明書登録とWindows自動プロキシーも無効にする。処理が成功または失敗
するとサーバーを起動せず終了し、終了コードを返す。通常のヘッドレスサーバー
起動は従来どおり `NicoCache_nl.exe --headless` を使う。

Firefoxが独自の証明書ストアを使う構成では、生成した `certs/ca.cer` の
インポートが別途必要になる。確認画面でその場所を案内する。

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
- 同じEXEのヘッドレス初回セットアップでテスト用CAをイメージ内だけに生成し、
  生成物を検証後に削除する
- 証明書ストアとWindowsプロキシー設定がテスト前後で一致することを確認する
- Windowsのプロキシー設定、環境変数、タスクスケジューラーを変更しない
- 初回セットアップとサーバーを同じ `NicoCache_nl.exe` で起動する
- ログオン時起動相当の別作業ディレクトリから自己再起動し、応答を継続する
- 起動した単一ランチャーの実行ファイルとPIDを照合して、そのPIDだけを終了する
- 外部サイトへ接続せず、`http://127.0.0.1:<一時ポート>/` の応答だけを確認する

成功時のログは削除する。失敗調査でログを保持する場合は `-KeepLogs` を指定する。

ローカル環境には WiX Toolset を追加せず、MSI の生成、旧版導入、配布ファイルの
修復、新版への更新、設定保全、起動、無人アンインストールは一時的な
GitHub Actions の `windows-2022` ランナーで検証する。MSI試験スクリプトは
GitHub Actions以外での実行を拒否する。

初回ウィザードの画面遷移、選択連動、キャンセル、設定作成、既存ファイル保全、
失敗ロールバックはフェイクOS環境でローカル検証する。証明書登録、自動
プロキシー、ログオン時起動の実適用と完全復元は一時GitHub Actionsランナー
だけで検証する。

## 依存関係

Bouncy Castle 1.84 の取得URLとSHA-256を `dependency-lock.psd1` に固定している。
ダウンロード後のハッシュが一致しない場合は生成を中止する。依存バージョンを
更新する場合は、公式配布元、ライセンス、ハッシュ、証明書生成テストを確認して
ロックファイルと `THIRD-PARTY-NOTICES.txt` を更新する。

## 現在の試作範囲

インストーラー基盤は自己完結配布、対話・無人導入、単一製品ランチャー、
旧版からの更新、修復、起動導線、削除までを対象とする。証明書の生成・登録、
Windowsプロキシー設定、自動起動、変更前設定の保存と復元は、初回起動
ウィザードで明示的な同意とロールバック情報を管理する。
