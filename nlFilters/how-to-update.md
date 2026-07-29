# 更新手順

## 前提

- JDK 17 以上
- PowerShell 7 以上
- Git 管理中のフィルターを検査する場合は Git

外部パッケージのインストールは不要です。コンパイル結果とテスト用一時ファイルは `.cache/nlfilter-lab/` に生成され、Git 管理されません。

## 更新と確認

1. `tools/nlfilter-lab/` のJava、Web UI、fixtureを更新します。
2. リポジトリ直下で次を実行します。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 source-check
.\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json
.\tools\nlfilter-lab\nlfilter-lab.ps1 test
.\tools\nlfilter-lab\nlfilter-lab.ps1 check
git diff --check
```

3. UIやfixtureを変更した場合はローカルテスターを起動し、代表ページと狭幅・広幅の両方をブラウザーで確認します。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 serve
.\tools\nlfilter-lab\nlfilter-lab.ps1 headless --fixture search --cache-state DMC --spa-add 1
```

4. 追跡フィルターを変更した場合は、Labだけで完了扱いにせず、NicoCache_nl経由の対象ページでも確認します。

## 本体パーサーが変わった場合

`source-check` が差異を報告したら、`EasyRewriter.java`、`JavaPattern.java`、`JavaMatcher.java`、`NestPattern.java`、`NestMatcher.java` とJAR内の対応classの変更内容を確認します。構文受理、正規表現、置換、キャッシュ分岐への影響を互換コーパスへ反映し、必要ならLab実装を修正します。`compatibility --json` と全テスト完了後にだけ `tools/nlfilter-lab/parser-baseline.properties` のSHA-256を現在値へ更新します。基準値だけを先に更新してはいけません。

## 復旧

Labは `tools/nlfilter-lab/` とドキュメントだけで完結しています。問題がある場合は、変更前のGitリビジョンからこれらのファイルを復元してください。`.cache/nlfilter-lab/` は再生成可能な一時成果物です。
