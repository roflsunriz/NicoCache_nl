# 更新手順

## 前提

- JDK 17 以上
- PowerShell 7 以上
- `test`でJavaScriptの動作確認も行う場合はNode.js
- Git 管理中のフィルターを検査する場合は Git

外部パッケージのインストールは不要です。コンパイル結果とテスト用一時ファイルは `.cache/nlfilter-lab/` に生成され、Git 管理されません。

## 更新と確認

1. `nlFilters/tools/nlfilter-lab/` のJava、Web UI、fixtureを更新します。
2. NicoCache_nlリポジトリ直下で次を実行します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 source-check
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check
git diff --check -- nlFilters
```

3. UIやfixtureを変更した場合はローカルテスターを起動し、代表ページと狭幅・広幅の両方をブラウザーで確認します。

```powershell
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 serve
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless --fixture search --cache-state DMC --spa-add 1
```

4. 追跡フィルターを変更した場合は、Labだけで完了扱いにせず、NicoCache_nl経由の対象ページでも確認します。

## 本体パーサーが変わった場合

`source-check` が差異を報告したら、`EasyRewriter.java`、`NlFilterDiagnostics.java`、
`JavaPattern.java`、`JavaMatcher.java`、`NestPattern.java`、`NestMatcher.java`とJAR内の対応classを
確認します。構文受理、正規表現、置換、キャッシュ分岐、診断への影響を互換コーパスや本体の
機能テストへ反映し、必要ならLab実装を修正します。`compatibility --json`と全テストを確認してから
`nlFilters/tools/nlfilter-lab/parser-baseline.properties`のSHA-256を更新します。基準値だけを先に
更新して差異を解消してはいけません。基準更新を含む検証では、隔離した候補基準でテストしてから
正式な基準へ反映できます。

稼働中の本体を使っている場合は、正規ビルドの出力先を分け、検証対象JARを明示します。

```powershell
.\build-javac.ps1 -LibraryDirectory .\lib -OutputDirectory .\.test-work\nlfilter-diagnostics\build
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar compatibility --json
.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 -ProductionJar .\.test-work\nlfilter-diagnostics\build\NicoCache_nl.jar test
```

`-ProductionJar`は`check`、`source-check`にも使用できます。ソースは作業ツリー、classの照合と
本体パーサー・置換の実行は指定JARを使います。指定を省略すると従来どおり本体ルートの
`NicoCache_nl.jar`を使用します。存在しないJARへ指定した場合に、稼働中JARへ自動で切り替えることはありません。
検証したJARの実環境への反映には、対象本体を通常終了してからの差し替えと再起動が必要です。

## 復旧

Labは `nlFilters/tools/nlfilter-lab/` とドキュメントだけで完結しています。問題がある場合は、変更前のNicoCache_nl Gitリビジョンからこれらのファイルを復元してください。`nlFilters/.cache/nlfilter-lab/` は再生成可能な一時成果物です。
