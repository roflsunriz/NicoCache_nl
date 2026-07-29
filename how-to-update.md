# 更新・検証手順

## 前提

- JDK 17、21、25 のいずれか（`build-javac.ps1` が検出して選択）
- PowerShell

作業前に `git status --short --branch` を確認する。`cache/`、`certs/`、
`data/`、ローカル `extensions/`、`NicoCacheGUI.property` はバックアップや
検証対象として明示された場合を除き変更しない。

## 検証

最初に機能テストと Extension ABI 互換テストを実行する。

```powershell
.\test-functional.ps1
```

次に本体をビルドする。

```powershell
.\build-javac.ps1
```

GUIログのタブ、検索、メニュー、履歴保存など画面操作を変更した場合は、実Swing
ウィンドウを使うE2Eも実行する。`-KeepWorkDir`を付けると、最小サイズと標準サイズの
確認画像を`.test-work/e2e/gui/preview/`へ残せる。

```powershell
.\test-e2e.ps1 -KeepWorkDir
```

複数の対応 JDK がある場合は、既定で検出された最新版を使用する。特定の JDK を
使う場合は、次のように指定する。

```powershell
.\build-javac.ps1 -JavaVersion 17
```

起動時も同じ選択ができる。無指定時は検出された最新版を使用する。

```powershell
.\RunNicoCache.ps1 -JavaVersion 21
```

初回起動ウィザードを変更した場合は、OS設定を変更しない専用テストも実行する。

```powershell
.\test-first-run-setup.ps1
```

画面を確認する場合は `-KeepWorkDir` を指定し、
`.test-work/first-run-setup/preview/` の4画面を確認する。結果画面は成功時と
失敗時の両方を確認する。証明書ストア、
Windowsプロキシー、ログオン時起動の実適用試験はローカルで実行せず、
一時GitHub Actionsランナーへ限定する。

証明書の対象ドメインを更新する場合は `certificate-targets.txt` だけを変更する。
従来の `genCerts.bat` と初回起動ウィザードは同じ一覧を参照するため、別々の
ドメイン一覧を追加しない。

ビルドスクリプトは `src/` の `.class` と `NicoCache_nl.jar` を更新するため、
必要な検証が終わったら `git status --short --branch` で生成物や無関係な差分が
混入していないことを確認する。

## GitHub Actions とリリース

`main` への push、`main` 向け Pull Request、手動実行では、GitHub Actions が
JDK 11 で本体をビルドし、機能テストと Extension ABI 互換テストを実行する。
加えてTemurin 25でWindows、Linux、macOSのビルド、機能、TLS、
Extension ABI、初回セットアップを検証し、WindowsインストーラーはJDK 25で生成、
隔離起動、修復、更新、アンインストールを確認する。
さらにリリースワークフローの契約テストで、従来のZIP、JAR、本体MSIと各ハッシュ
が公開対象に残り、独立アップデーターMSIとハッシュが追加されていることを確認する。

リリースは `v<major>.<minor>.<build>` 形式のタグをpushすると開始する。MSIの
版番号制約に合わせ、majorとminorは0〜255、buildは0〜65535にする。例えば次の
ようにタグを作成・pushする。

```powershell
git tag v1.0.1
git push origin v1.0.1
```

独立アップデーターを変更したリリースでは、タグを作成する前に
`updater/VERSION` を `<major>.<minor>.<build>` 形式で更新する。この版番号も
majorとminorは0〜255、buildは0〜65535にする。本体とアップデーターは別々に
版管理するため、本体だけを変更したときにアップデーター版を合わせて上げない。

テストに合格すると、GitHub Release に `.gitignore` で除外されていないファイルを
まとめた `NicoCache_nl-<タグ名>.zip` が添付される。例外指定で残る配布ファイルと、
同じタグの `nlFilters` にある `01`〜`20` 番台の `.txt` も含まれるが、
シンボリックリンクは含まれない。ビルドした `NicoCache_nl.jar` と
`NicoCache_nl.jar.sha256`、タグのソースからJDK 25で生成・検証した
`NicoCache_nl-<版番号>.msi` とそのSHA-256も個別アセットとして添付される。
同じタグのソースから、`updater/VERSION` で生成した
`NicoCache_nl-Updater-<アップデーター版>.msi` とそのSHA-256も添付される。
既存タグから再実行する場合は、Release workflow の手動実行でタグ名を指定する。

## リポジトリ依存関係

GitHub Actionsは完全なコミットSHAへ固定し、Dependabotが毎週月曜日に同じ
メジャー系列の更新を確認する。メジャー更新は自動追従せず、変更内容と移行条件を
別途レビューする。DependabotとBouncy Castleの更新PRは自動マージしない。

Bouncy Castleは毎週の `Update repository dependencies` workflow がMaven Central
の公式メタデータから安定版を確認する。更新がある場合だけ
`automation/update-bouncy-castle` ブランチのPRを作成または更新し、3成果物の
版、URL、SHA-256、サイズとPOMのライセンス情報を本文へ記録する。

手元で更新有無とレポートを確認する場合は次を実行する。

```powershell
.\packaging\windows\update-dependency-lock.ps1 `
  -Mode Check `
  -ReportFile .\.test-work\dependency-update-report.md
.\packaging\windows\test-dependency-lock.ps1
.\packaging\windows\test-dependency-update.ps1
```

ロックを更新する場合は `-Mode Update` を指定する。更新後は上記2テストに加えて
本体ビルド、機能テスト、Windows AppImage・MSI生成と構造試験を実行し、
`packaging/windows/THIRD-PARTY-NOTICES.txt` のライセンス本文や著作権表示に
変更がないことも確認する。ライセンス名または公式URLが想定と異なる場合は
自動更新せず、上流の公式POMとライセンスを確認する。

更新PRの検証に失敗した場合はマージしない。自動処理が変更する管理対象は
`packaging/windows/dependency-lock.psd1` だけなので、誤更新を取り込んだ場合は
該当コミットを `git revert` し、直前のロックへ戻したうえで再検証する。

## Windows インストーラー

リリースとWindowsインストーラーCIではJDK 25の `jpackage` を使い、Java 25の
ランタイムと単一の製品ランチャーを含むアプリイメージを生成する。日本語設定の
読込みに必要な `jdk.charsets` を含む最小ランタイムで起動を検証する。
隔離テストでは同じ製品ランチャーへ内部用の `--headless` を指定する。

Windowsパッケージ版の利用者データは、既定ではWindowsの「ドキュメント」内の
`NicoCache_nl` に保存する。更新前にアプリ本体と同じ場所へあった
`config.properties`、`config.ini`、`NicoCacheGUI.property`、`proxy.pac`、
`local/`、`nlFilters/`、`extensions/` は、新配置に同名の項目がない場合だけ
初回起動時にコピーする。移行元と、設定で絶対パスを指定したキャッシュは削除・
移動しない。

一時的な検証や管理された配備では、環境変数 `NICOCACHE_DATA_ROOT` または
Javaシステムプロパティ `nicocache.dataRoot` で保存先を上書きできる。
実行ファイル横に `portable.flag` を置いた場合は、明示ルートがない限り従来どおり
アプリ本体を利用者データルートにする。復旧時は利用者データを削除せず、
`.data-layout-version` と移行元・移行先を確認する。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
```

ZIPとMSIへ同じ内容を入れる変更を確認する場合は、共通アプリイメージとZIPを生成し、
内容一致テストを実行する。

```powershell
.\packaging\windows\build-windows-package.ps1 `
  -PackageType Zip `
  -AppVersion 0.1.0
.\packaging\windows\test-package-parity.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl `
  -ZipPath .\.test-work\windows-package\output\NicoCache_nl-0.1.0.zip
```

MSIを生成した場合は、インストールせずに内部テーブルを読み取り、デスクトップと
スタートメニューのショートカットが各1件定義されていること、および明示的な
アンインストール時にWindows設定復元が製品ファイル削除より前に実行されることを
確認する。

```powershell
.\packaging\windows\test-windows-msi-structure.ps1 `
  -MsiPath .\.test-work\windows-package\output\NicoCache_nl-<version>.msi
```

この隔離テストはログオン時起動を再現するため、製品ルートとは異なる作業
ディレクトリから単一ランチャーを起動し、製品ルートからの自己再起動後も
HTTP応答を継続することを確認する。

MSIの `packaging/windows/resources/main.wxs` と `main-jdk25.wxs` は、それぞれ
JDK 17とJDK 25の `jpackage` が内蔵するWiXテンプレートへ、アンインストール前の
Windows設定復元を追加したものである。パッケージ生成時は実行中のJDKに合う
テンプレートを自動選択する。JDKを更新する場合は、そのJDKの
`jdk.jpackage.jmod` に含まれる `jdk/jpackage/internal/resources/main.wxs` と
対応テンプレートを比較し、上流テンプレートの変更を取り込んでからMSI構造試験を
実行する。

ローカルではOSへインストールせず、`.test-work/windows-package/` 内の
アプリイメージだけを検証する。MSIの生成と `msiexec /qn` による無人
インストール・修復・更新・アンインストールは、一時的なGitHub Actions
ランナーで実行する。MSIの版を上げても
`packaging/windows/package-identity.psd1` の `UpgradeUuid` は変更しない。
変更すると既存版を更新できなくなる。具体的なテスト境界、依存関係更新、
完成条件は `packaging/windows/README.md` と
`packaging/windows/requirements.md` を参照する。

## Extension API を変更する場合

公開・protected API は未知の外部 Extension も利用しているものとして扱う。
削除が避けられない場合は `tests/README.md` の基準に従い、削除予定の完全修飾
シグネチャと根拠を `tests/compat/allowed-api-removals.txt` に記録してから ABI
基準を更新する。

## 旧取得設定からの移行

Smile、DMC単一ファイル、DMC-HLSの新規取得経路は廃止した。利用者の
`config.properties` に次の設定が残っていても参照されないため、更新時に削除して
よい。

- `disableStreamingWarning`
- `useSmileCacheInsteadOfDmcEconomy`
- `useSmileCacheInsteadOfDmc`
- `ignoreEmbeddedPlayer`
- `noLiveCache`
- `deletedMoviePlayMode`
- `mapFileUpdatingInterval`
- `swfConvert`
- `swfCacheV3`
- `swfConvertAll`

既存のFLV、SWF、MP4、旧HLSキャッシュは削除しない。更新後も `/cache/` APIと
ローカルキャッシュ配信から利用できる。ここで廃止する `swfConvert*` は上流から
取得中にSWFを書き換える設定であり、保存済みSWFの読取りには影響しない。

## 復旧

機能テストが失敗した場合は `.\test-functional.ps1 -KeepWorkDir` を実行し、
`.test-work/functional/sandbox/nicocache-functional.log` を確認する。テスト領域は
実データから独立しているため、不要になった `.test-work/functional/` は削除して
よい。

本体ビルド前の JAR が必要な場合は `NicoCache_nl.jar` を別名で退避してから
ビルドする。ユーザーのキャッシュや設定をロールバック手段として削除しない。

Windowsパッケージの生成に失敗した場合は、既存のNicoCache_nlやOS設定を
変更せず、Git管理外の `.test-work/windows-package/` だけを削除して再生成する。
証明書ストア、Windowsプロキシー設定、タスクスケジューラーを復旧操作として
変更する必要はない。

初回セットアップの適用に失敗した場合は、同じ試行で作成した設定とOS変更が
自動復元される。復元にも失敗した場合は
`data/setup-system-state.json` を削除せず保持し、記録された変更前状態を確認して
から `packaging/windows/runtime/first-run-setup.ps1 -Action Rollback` 相当の
復元処理を行う。ユーザーの既存設定を推測で削除しない。
