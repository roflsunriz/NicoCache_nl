# 更新・検証手順

## 前提

具体的な手順・構成・知見は [findings.md](<findings.md#update-1>) に移動しました。着手前に該当条件を確認してください。

## 検証

具体的な手順・構成・知見は [findings.md](<findings.md#update-2>) に移動しました。着手前に該当条件を確認してください。

Watch応答の仕様変更へ追従するときは、認証情報や署名付きURLを保存せず、初期HTML、
SPA切替応答、`nvapi /v4/watch`更新応答を別々に確認する。`WatchVars`、
`local/nllib_watch.js`、公開`CommentDownloader`、nlFilter LabのWatch fixtureを同時に監査し、
旧direct、旧`response`、V4 direct、V4 `response`をテストする。最低限、次を実行する。

```powershell
node --test .\tests\local\nllib-watch.test.js
.\test-functional.ps1 -LibraryDirectory .\.test-work\build-dependencies
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check 20_watchFilter.txt
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test
```

## GitHub Actions とリリース

具体的な手順・構成・知見は [findings.md](<findings.md#update-3>) に移動しました。着手前に該当条件を確認してください。

### リリース版への更新手順

本文は [findings.md](<findings.md#update-3>) を参照してください。


## リポジトリ依存関係

具体的な手順・構成・知見は [findings.md](<findings.md#update-4>) に移動しました。着手前に該当条件を確認してください。

## Linux/macOS パッケージ

具体的な手順・構成・知見は [findings.md](<findings.md#update-5>) に移動しました。着手前に該当条件を確認してください。

## Windows インストーラー

具体的な手順・構成・知見は [findings.md](<findings.md#update-6>) に移動しました。着手前に該当条件を確認してください。

## Extension API を変更する場合

具体的な手順・構成・知見は [findings.md](<findings.md#update-7>) に移動しました。着手前に該当条件を確認してください。

## 旧取得設定からの移行

具体的な手順・構成・知見は [findings.md](<findings.md#update-8>) に移動しました。着手前に該当条件を確認してください。

## 復旧

具体的な手順・構成・知見は [findings.md](<findings.md#update-9>) に移動しました。着手前に該当条件を確認してください。

## Dependabot PR の更新



Dependabot version update は毎月28日00:00、検証済み PR の自動マージは同日18:00（いずれも `Asia/Tokyo`）にまとめます。28日より前に自動マージせず、現在の head SHA と全 PR チェックを月末処理で再確認します。
具体的な手順・構成・知見は [findings.md](<findings.md#update-10>) に移動しました。着手前に該当条件を確認してください。
