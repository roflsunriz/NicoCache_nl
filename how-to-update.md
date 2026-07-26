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

ビルドスクリプトは `src/` の `.class` と `NicoCache_nl.jar` を更新するため、
必要な検証が終わったら `git status --short --branch` で生成物や無関係な差分が
混入していないことを確認する。

## GitHub Actions とリリース

`main` への push、`main` 向け Pull Request、手動実行では、GitHub Actions が
JDK 11 で本体をビルドし、機能テストと Extension ABI 互換テストを実行する。

リリースは `v` で始まるタグを push すると開始する。例えば次のようにタグを
作成・pushする。

```powershell
git tag v2026.07.20
git push origin v2026.07.20
```

テストに合格すると、GitHub Release に `.gitignore` で除外されていないファイルを
まとめた `NicoCache_nl-<タグ名>.zip` が添付される。例外指定で残る配布ファイルと、
別リポジトリの `nlFilters` にある `01`〜`20` 番台の `.txt` も含まれるが、
シンボリックリンクは含まれない。ビルドした `NicoCache_nl.jar` と
`NicoCache_nl.jar.sha256` もアーカイブおよび個別アセットとして添付される。
既存タグから再実行する場合は、Release workflow の手動実行でタグ名を指定する。

## Windows インストーラー試作

JDK 17 の `jpackage` を使い、Javaランタイムと単一の製品ランチャーを含む
アプリイメージを生成する。隔離テストでは同じ製品ランチャーへ内部用の
`--headless` を指定する。

```powershell
.\packaging\windows\build-windows-package.ps1 -PackageType AppImage
.\packaging\windows\test-windows-app-image.ps1 `
  -AppImagePath .\.test-work\windows-package\output\NicoCache_nl
```

ローカルではOSへインストールせず、`.test-work/windows-package/` 内の
アプリイメージだけを検証する。MSIの生成と `msiexec /qn` による無人
インストール・アンインストールは、一時的なGitHub Actionsランナーで実行する。
具体的なテスト境界、依存関係更新、現在の試作範囲は
`packaging/windows/README.md` を参照する。

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

Windowsパッケージ試作の生成に失敗した場合は、既存のNicoCache_nlやOS設定を
変更せず、Git管理外の `.test-work/windows-package/` だけを削除して再生成する。
証明書ストア、Windowsプロキシー設定、タスクスケジューラーを復旧操作として
変更する必要はない。
