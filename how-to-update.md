# 更新・検証手順

## 前提

- JDK 17 など、`javac --release 11` と `jar` を利用できる JDK
- Apache Ant 1.9.8 以降（Ant ビルドを確認する場合）
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

または Apache Ant を利用する。

```powershell
.\build-ant.ps1
```

初回起動ウィザードを変更した場合は、OS設定を変更しない専用テストも実行する。

```powershell
.\test-first-run-setup.ps1
```

画面を確認する場合は `-KeepWorkDir` を指定し、
`.test-work/first-run-setup/preview/` の3画面を確認する。証明書ストア、
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
別リポジトリの `nlFilters` にある `01`〜`20` 番台の `.txt` も含まれるが、
シンボリックリンクは含まれない。ビルドした `NicoCache_nl.jar` と
`NicoCache_nl.jar.sha256`、タグのソースからJDK 17で生成・検証した
`NicoCache_nl-<版番号>.msi` とそのSHA-256も個別アセットとして添付される。
同じタグのソースから、`updater/VERSION` で生成した
`NicoCache_nl-Updater-<アップデーター版>.msi` とそのSHA-256も添付される。
既存タグから再実行する場合は、Release workflow の手動実行でタグ名を指定する。

## Windows インストーラー

JDK 17 の `jpackage` を使い、Javaランタイムと単一の製品ランチャーを含む
アプリイメージを生成する。隔離テストでは同じ製品ランチャーへ内部用の
`--headless` を指定する。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
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

MSIの `packaging/windows/resources/main.wxs` はJDK 17の `jpackage` が内蔵する
WiXテンプレートへ、アンインストール前のWindows設定復元だけを追加したもので
ある。JDKを更新する場合は、そのJDKの `jdk.jpackage.jmod` に含まれる
`jdk/jpackage/internal/resources/main.wxs` と比較し、上流テンプレートの変更を
取り込んでからMSI構造試験を実行する。

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
