# nlFilters 更新手順

標準フィルターは本体と同じリポジトリの `nlFilters/*.txt` で管理します。外部の
利用者設定やリンクされたフィルターは、標準フィルターの更新対象に含めません。

## 変更前の確認

リポジトリ直下から、まず構文と本体パーサーとの互換性を確認します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 source-check
```

特定のファイルだけを確認する場合は、次のようにパスを渡します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check .\nlFilters\20_watchFilter.txt
```

## 変更後の確認

変更したフィルターを保存したら、次を実行します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test
```

画面を使わず代表ページの適用結果まで確認する場合は、Labのヘッドレステストを
実行します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless `
  --fixture search `
  --cache-state DMC `
  --output-dir .\.cache\nlfilter-lab\headless\filter-update
```

終了コードが `0` であること、生成された `result.json` と `final.html` に意図しない
変更がないことを確認してください。必要に応じて `--fixture watch` または
`--fixture anime` も実行します。

詳細な対応範囲、既知の外部境界、ローカルテスターの使い方は
[nlFilter Labの説明](tools/nlfilter-lab/README.md)を参照してください。

## 本体へ反映する場合

フィルター変更後は、リポジトリ直下の機能テストとE2Eテストも実行します。

```powershell
.\test-functional.ps1
.\test-e2e.ps1
```

フィルターは構文チェックだけでなく、本体を経由した実ページ相当の結果を確認してから
コミットしてください。外部管理リンクを解除・置換する場合は、対象ファイルの履歴と
ライセンス、配布対象であるかを先に確認します。
