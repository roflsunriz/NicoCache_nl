# Windows インストーラー

自己完結型の Windows アプリイメージ、ZIP、MSI を生成する。利用者の
Java、PowerShell 7、Apache Ant、7-Zip には依存しない。

## 生成物

- `NicoCache_nl.exe`: GUI、ヘッドレス、初回セットアップを担う唯一のアプリ
  ランチャー
- アプリ専用 Java ランタイム
- NicoCache_nl 本体、内部ライブラリとしての証明書生成ツール、Bouncy Castle、
  既定設定、ローカル配信用ファイル
- `NicoCache_nl-<version>.msi`: 対話・無人インストール兼用パッケージ
- `NicoCache_nl-<version>.zip`: MSIと同じアプリイメージを展開した開発・検証用ZIP

本体インストーラーと本体ランチャーには、アプリ本体を受け皿へ収める意匠の
`assets/nicocache-installer.ico` を使う。独立アップデーターには、更新対象を
2本の軌道が循環する別意匠の `assets/nicocache-updater.ico` を使う。
どちらも16～256ピクセルの表示サイズを収録し、従来の `niconico-0.ico` とは
役割とシルエットを分けている。

ZIPとMSIは同じアプリイメージを共通の入力として生成する。ZIPにはMSIと同じ実行環境、
既定設定、ローカル配信用ファイルに加え、`development/` 以下へソース、テスト、
ビルド・検証スクリプト、開発資料を収録する。`.git`、CI管理ファイル、キャッシュ、
証明書、利用者データは収録しない。
標準フィルターは本体と同じコミットの`nlFilters/`から取り込み、外部管理の
シンボリックリンクは配布物へ含めない。

MSI の無人操作には Windows Installer の標準オプションを使う。

```powershell
msiexec.exe /i .\NicoCache_nl-0.1.0.msi /qn /norestart
msiexec.exe /x .\NicoCache_nl-0.1.0.msi /qn /norestart
```

固定したUpgrade UUIDにより、新版MSIは同じ製品の更新として扱う。対話導入では
インストール先とショートカットを選択できる。スタートメニューに加えて
デスクトップへ `NicoCache_nl` ショートカットを作成し、アンインストール時に
両方を削除する。インストーラー基盤の責務と採点は `requirements.md` を参照する。

## 初回起動ウィザード

MSIからの最初の通常GUI起動で、アプリケーションフォルダーに
`config.properties` がまだない場合、5画面の初回セットアップを表示する。

1. 適用するまでOS設定を変更しないことを確認する
2. キャッシュ、フィルター、拡張、個人設定を保存するユーザーデータフォルダーを指定する
3. HTTPS証明書、Windows自動プロキシー、ログオン時自動起動を個別に選択する
4. 変更内容を確認して適用する
5. 3項目それぞれの成功、失敗、未選択と、失敗時の詳細を確認する

既存の `config.properties` がある更新利用者と、通常の `--headless` 起動では
表示しない。キャンセル時は設定ファイルを作らず終了する。
適用に失敗した場合は結果画面から確認画面へ戻り、再試行できる。
適用時はアプリ本体の既定設定からアプリケーションフォルダーへ
`config.properties` を作り、選択した `userDataRoot` を保存する。選択に応じて
利用者データ側へ `proxy.pac`、CA証明書、
自動プロキシー、ログオン時起動を設定する。CAを信頼する選択をした
場合はWindows標準の証明書登録を自動で開始し、OSの許可画面で利用者が承認した
場合だけ現在ユーザーの信頼済みルートへ登録する。
サイト証明書の対象は従来の `genCerts.bat` と共通の
`certificate-targets.txt` から読み込む。
OS設定の変更前値は利用者データフォルダーの
`data/setup-system-state.json` に保存する。途中で失敗した
場合は今回作成したファイル、証明書、プロキシー、自動起動を復元し、次回起動で
再試行できる。Windows設定処理が失敗した場合は、失敗した処理段階と内容を
ダイアログへ表示し、同じ情報を `data/setup-windows-error.txt` に保存する。
明示的なMSIアンインストールでは、製品ファイルを削除する前にこの保存状態を
使い、初回セットアップが変更したCA証明書、Windowsプロキシー、自動起動を
変更前へ戻す。利用者データ自体は削除しない。新版への更新中は復元しない。
復元に失敗した場合はアンインストール
を中止し、`data/uninstall-windows-error.txt` に診断を残すため、復元情報を
失ったまま製品だけが削除されることはない。

製品GUIを使わないセットアップでも別の実行ファイルは使わず、同じ
`NicoCache_nl.exe` に全選択を明示して初回セットアップを実行する。

```powershell
.\NicoCache_nl.exe --setup --headless `
  --user-data-root="C:\Users\利用者名\Documents\NicoCache_nl" `
  --https=true `
  --trust-certificate=true `
  --proxy=true `
  --autostart=true
```

保存先の絶対パスと4項目すべての `true` または `false` の明示が必要である。HTTPSを無効にする
場合、証明書登録とWindows自動プロキシーも無効にする。処理が成功または失敗
するとサーバーを起動せず終了し、終了コードを返す。通常のヘッドレスサーバー
起動は従来どおり `NicoCache_nl.exe --headless` を使う。
`--trust-certificate=true` はヘッドレス指定でもWindowsの許可画面を表示するため、
利用者が操作できるデスクトップが必要になる。完全に画面のない自動実行では
`--trust-certificate=false` とし、必要なら生成後の `certs/ca.cer` を別途
承認して登録する。

Firefoxが独自の証明書ストアを使う構成では、生成した `certs/ca.cer` の
インポートが別途必要になる。確認画面でその場所を案内する。

## 利用者データ

Windowsパッケージ版は、アプリケーションフォルダーの `config.properties` に
保存した `userDataRoot` を利用者データルートにする。初回候補はWindowsの
「ドキュメント」内の `NicoCache_nl` である。キャッシュ、証明書、個人設定、
利用者が追加した `local/`、`nlFilters/`、Extension、初回セットアップ状態を
ここへ保存する。

標準フィルター、標準ローカル配信物、Extensionサンプルはアプリケーション側から
先に読み、利用者側のファイルを後から適用する。同名のローカル配信物は利用者側を
優先する。システム資材の一覧は `packaging/system-files.txt` で一元管理する。
環境変数やJavaシステムプロパティによる利用者データルートの上書きは行わない。

## ローカルでの安全な検証

JDK 25 でアプリイメージとZIPを生成する。MSIも同じJDK 25の専用ランタイムを
同梱する。成果物と依存ファイルは Git 管理外の
`.test-work/windows-package/` に限定される。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
```

スモークテストは次の制約で実行する。

- 空いているループバックポートを一時利用する
- `.test-work` 内の専用利用者データ領域だけにテスト設定とキャッシュを作る
- TLS MitMを無効にし、証明書ストアを変更しない
- 同じEXEのヘッドレス初回セットアップでテスト用CAをイメージ内だけに生成し、
  生成物を検証後に削除する
- 証明書ストアとWindowsプロキシー設定がテスト前後で一致することを確認する
- Windowsのプロキシー設定、環境変数、タスクスケジューラーを変更しない
- 初回セットアップとサーバーを同じ `NicoCache_nl.exe` で起動する
- ログオン時起動相当の別作業ディレクトリから起動し、`userDataRoot` の設定で応答を継続する
- 起動した単一ランチャーの実行ファイルとPIDを照合して、そのPIDだけを終了する
- 外部サイトへ接続せず、`http://127.0.0.1:<一時ポート>/` の応答だけを確認する

成功時のログは削除する。失敗調査でログを保持する場合は `-KeepLogs` を指定する。

ローカル環境には WiX Toolset を追加せず、MSI の生成、旧版導入、配布ファイルの
修復、新版への更新、設定保全、起動、無人アンインストールは一時的な
GitHub Actions の `windows-2022` ランナーで検証する。MSI試験スクリプトは
GitHub Actions以外での実行を拒否する。MSI試験では許可画面を自動承認せず、
CA生成と証明書ストアが変わらないことを検証する。自動プロキシーと自動起動は
実際に適用し、アンインストールだけで試験前のOS状態へ完全に戻ることを確認する。
更新試験の`1.0.0`と`1.0.1`はMSIの
版比較を発生させる固定テスト値であり、パッケージ内容は実行時にチェックアウト
した現在のソースから両方とも再コンパイルする。

初回ウィザードの画面遷移、選択連動、キャンセル、設定作成、既存ファイル保全、
失敗ロールバックはフェイクOS環境でローカル検証する。自動プロキシーと
ログオン時起動の実適用と完全復元は一時GitHub Actionsランナーで検証する。
証明書の許可画面だけは自動承認せず、VirtualBoxなど利用者が操作できるWindows
環境で次を手動確認する。

1. `--setup --headless` と4項目の指定で `--trust-certificate=true` を実行する
2. Windowsの証明書登録の許可画面が表示されることを確認して承認する
3. `certs/ca.cer` が現在ユーザーの信頼済みルートへ登録されたことを確認する
4. MSIをアンインストールし、登録したCA、プロキシー、自動起動が残らないことを確認する

## 依存関係

Bouncy Castle 1.85 の取得URLとSHA-256を `dependency-lock.psd1` に固定している。
ダウンロード後のハッシュが一致しない場合は生成を中止する。毎週のGitHub Actions
はMaven Centralの公式メタデータから安定版を確認し、3成果物の版、公式URL、
SHA-256とPOMのライセンスを検証して、更新がある場合だけレビュー用PRを作成する。
自動マージは行わない。

手動確認では `update-dependency-lock.ps1 -Mode Check`、
`test-dependency-lock.ps1`、`test-dependency-update.ps1` を実行する。版を更新する
場合は `-Mode Update` を使い、証明書生成とパッケージ試験に加えて
`THIRD-PARTY-NOTICES.txt` のライセンス本文と著作権表示も確認する。詳しい手順と
復旧方法はリポジトリ直下の `how-to-update.md` を参照する。

## インストーラーの範囲

インストーラー基盤は自己完結配布、対話・無人導入、単一製品ランチャー、
旧版からの更新、修復、起動導線、削除までを対象とする。証明書の生成・登録、
Windowsプロキシー設定、自動起動、変更前設定の保存と復元は、初回起動
ウィザードで明示的な同意とロールバック情報を管理する。

`v<major>.<minor>.<build>` 形式のタグをpushすると、リリース対象タグのソースから
MSIを再生成し、構造、隔離起動、Windows連携の適用と復元を検証する。合格した
`NicoCache_nl-<version>.msi` とSHA-256ファイルは、ZIPおよびJARと同じ
GitHub Releaseへ自動添付される。同じリリース処理は `updater/VERSION` を
独立アップデーターの版として検証し、専用Javaランタイムを含む
`NicoCache_nl-Updater-<version>.msi` とSHA-256も追加する。
