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

## Extension API を変更する場合

公開・protected API は未知の外部 Extension も利用しているものとして扱う。
削除が避けられない場合は `tests/README.md` の基準に従い、削除予定の完全修飾
シグネチャと根拠を `tests/compat/allowed-api-removals.txt` に記録してから ABI
基準を更新する。

## 復旧

機能テストが失敗した場合は `.\test-functional.ps1 -KeepWorkDir` を実行し、
`.test-work/functional/sandbox/nicocache-functional.log` を確認する。テスト領域は
実データから独立しているため、不要になった `.test-work/functional/` は削除して
よい。

本体ビルド前の JAR が必要な場合は `NicoCache_nl.jar` を別名で退避してから
ビルドする。ユーザーのキャッシュや設定をロールバック手段として削除しない。
