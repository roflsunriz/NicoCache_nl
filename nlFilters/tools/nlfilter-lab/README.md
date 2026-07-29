# nlFilter Lab

稼働中の `nlFilters` を変更する前に、構文と代表ページへの適用結果をローカルで確認するための開発ツールです。JDK 17 と PowerShell だけで動作し、外部パッケージは使用しません。

## 構文チェック

リポジトリ直下で実行します。引数を省略すると、Git が追跡している `.txt` だけを辞書順で検査します。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 check
.\tools\nlfilter-lab\nlfilter-lab.ps1 check .\20_watchFilter.txt
.\tools\nlfilter-lab\nlfilter-lab.ps1 check --json
```

終了コードは、エラーなしなら `0`、構文エラーまたは本体パーサーソースとの差異があれば `1` です。警告だけでは失敗しません。`--json` はコーディングエージェントやCI向けに、診断と本体ソース互換状態を標準出力へJSONだけで返します。

NicoCache_nl 本体の関連ソースだけを確認する場合は次を使います。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 source-check
.\tools\nlfilter-lab\nlfilter-lab.ps1 source-check --json
.\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json
```

`check` はLab独自解析に加え、同じファイルを稼働中の `NicoCache_nl.jar` に含まれる実際の `EasyRewriter.parseFilterFile` でも解析し、ルール数・セクション・正規表現・置換・全オプションの内部表現を照合します。JARが利用できる環境では、本体の構文受理結果そのものをオラクルにします。

自動テストでは、副作用のない互換コーパスを本体の `applyUserFilter` でも実行し、SET/INC、nlVar、nlcase、NESTを組み合わせた最終文字列がLabと一致することまで確認します。

## ローカルテスター

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 serve
```

表示された `http://127.0.0.1:8765/` をブラウザーで開きます。ポートを変える場合は `serve --port 9000` のように指定します。

画面では次を切り替えられます。

- watch、検索、Nアニメ風の fixture
- 対象URLと Content-Type
- キャッシュなし、通常、エコノミー、DMC通常、DMCエコノミー
- 適用するフィルターファイル
- SPA風の後続DOM追加

適用したルール、変換前後のHTML、ページ内で発生した JavaScript の警告・エラーも確認できます。`/local/*` は実環境の `C:\NicoCache_nl\local` から読み取り専用で配信します。

プレビューiframeは sandbox と Content Security Policy で外部資産の取得、フォーム送信、ポップアップを遮断し、fixture内リンクの既定遷移も無効化します。既存の `overlib_mini.js` が文字列から関数を生成するため、プレビュー内に限って `unsafe-eval` を許可しています。ローカルAPIは同一オリジンとsandboxの `null` Originだけを受け付けます。

## ヘッドレステスト

Chrome または Edge の headless モードでプレビューを実行し、結果をコーディングエージェントから直接読める形で保存します。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 headless `
  --fixture search `
  --cache-state DMC `
  --spa-add 1 `
  --output-dir .\.cache\nlfilter-lab\headless\search-dmc
```

標準出力は `result.json` と同じ1行JSONです。出力先には `final.html`、`screenshot.png`、`console.json`、変換トレースを含む `render.json` も保存します。終了コードは成功 `0`、構文・コンソール・ブラウザー結果の失敗 `1`、引数エラー `2`、ブラウザー未検出など実行基盤の失敗 `3` です。

主なオプションは `--fixture watch|search|anime`、`--cache-state NONE|NORMAL|ECONOMY|DMC|DMC_ECONOMY`、`--reencoded true|false|null`、`--reencoded-bitrate N`、`--spa-add N`、`--viewport 1280x900`、`--file <filter>`、`--no-filters`、`--browser <path>`、`--timeout <seconds>` です。`--output-dir` を省略した場合は `.cache/nlfilter-lab/headless/<fixture>-<cache-state>/` を使います。

## 自動テスト

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 test
```

## 再現範囲

このツールは、現在の追跡フィルターで利用している主要機能を対象にした疑似実装です。NicoCache_nl 本体を起動せず、キャッシュやHTTP応答を変更しません。構文チェッカーは Java `Pattern` と本体の解析順序へ可能な限り合わせていますが、本体より安全側に厳しい診断もあります。

決定的に疑似実行する対象:

- `[Replace]`、`[Script]`、`[Style]`
- `URL`、`FullURL`、`ContentType`、`Require`、`StatusCode`
- `Multi`、`EachLine`、`ReplaceDelay`、`ReplaceOnly`
- `idGroup` と5種類の疑似キャッシュ状態
- `$URLn`、`$RequireHeaderN`、`$TS(...)`、`<CRLF>`、`<TAB>`、キャッシュ種別分岐
- `$LST`、`$INC`、`$SET`、`AddList`、`AddVariable` のメモリ内状態
- `$NEST`、`<nlcase>/<when>`、`<nlVar:...>`、`<id>`、`<smid>`、`<memoryId>`
- `[RequestHeader]` のリクエストURL書換とURLだけを列挙する特殊Append

明示モックで再現する対象:

- 実キャッシュ検索と `idGroup` は画面またはCLIで選択する5状態
- `$REENCODED` と `$REENCODED_BITRATE` は再エンコード状態・bitrate入力
- `freeSpace`、VERSION、RequireHeader、`/cache` APIは決定的なLabモック

安全に再現できない外部境界:

- `NLFilterListener` 拡張固有の非数字 `idGroup` と拡張変数
- `[Config]` を参照するNicoCache_nl本体・拡張機能の挙動
- 実キャッシュ索引、threadからsmidへの解決、実Cookie・認証ヘッダー
- `AddList` の実ファイル書込み、NicoCache_nlを介するネットワーク副作用

これらは誤って実環境へ副作用を与えないため、Labでは意図的にモックまたはメモリ内処理に留めます。`compatibility` コマンドは機械可読な対応範囲と残る外部境界を返します。

最終確認では、通常どおり NicoCache_nl 経由の対象ページでも動作を確認してください。

`parser-baseline.properties` は `EasyRewriter.java` と関連する Java/Nest 正規表現実装、およびJAR内の対応classのSHA-256を保持します。本体ソースは変更頻度が低くても変更され得るため、差異を検出したら先に実装内容とLabの互換性を再監査し、必要なコードとテストを直した後にだけ基準値を更新してください。ハッシュだけを合わせて警告を消してはいけません。
