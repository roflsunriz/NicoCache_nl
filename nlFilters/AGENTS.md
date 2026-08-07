# AGENTS.md

共通ルールは `COMMON-AGENTS.md` を必ず確認し、上位方針として扱う。
このファイルでは、リポジトリ直下の `nlFilters/` で管理する nlFilter 固有の補足だけを記載する。
配置先は固定パスと仮定せず、以下の相対パスはすべて NicoCache_nl リポジトリ直下を基準にする。

## このディレクトリの役割

- NicoCache_nl本体リポジトリが、実際に読み込む`nlFilters`ディレクトリを
  直接Git管理している。独立した`.git`は置かない。ここで追跡中の`.txt`を
  変更すると、作業用コピーではなく稼働環境のフィルターが変わる。
- nlFilter は、対象 URL と Content-Type に応じてレスポンス本文、HTML、JavaScript、CSS、リクエストヘッダーを変更する NicoCache_nl 専用 DSL である。
- 現在 Git 管理している主なフィルターは次のとおり。
  - `01_globalFilter.txt`: 共通の head 挿入位置と `NicoCache_nl` グローバルを用意する。
  - `05_topBarFilter.txt`: 空き容量警告とキャッシュ削除ボタンを追加する。
  - `08_MutationObserverHooks.txt`: 動的に追加される動画要素を検出する共通フックを提供する。
  - `09_thumbInfoFilterBase.txt`: サムネイルポップアップの共通スクリプトを読み込む。
  - `10_thumbInfoFilterLegacyLinks.txt`: 従来HTML/XML向けのリンク置換を提供する。
  - `11_thumbInfoFilterVideoLinks.txt`: 現行SPAを含む動的動画リンクを補完する。
  - `15_thumbInfoFilterCache.txt`: キャッシュ状態に応じたリンク色と、CMAF/Domand品質バッジを追加する。
  - `20_watchFilter.txt`: 視聴ページ向けの置換、スタイル、スクリプトを提供する。
- `.vscode/`、`evac/` は Git 管理外のローカル領域である。ユーザーが明示した場合を除き、調査対象や変更対象に含めない。

## 現行 NicoCache_nl のキャッシュ契約

- 現行の動画配信・保存経路は Domand の CMAF セグメントである。保存先がディレクトリで拡張子相当が
  `.hls` のキャッシュを、`Cache`、`VideoDescriptor`、`/cache/info/v2` を通して扱う。
- `VideoDescriptor.isDmc()` と `/cache/info/v2` の `dmc` は公開ABI・保存済みキャッシュとの
  互換名であり、「現在もDMC配信を使っている」という意味ではない。Domand/CMAFもこの値が
  `true` になるため、UI文言、アイコン、色の世代判定へそのまま使わない。
- `/cache/info/v2` の動画ごとの値には `preferred`、`preferredHTML5`、`preferredDmcHls`、
  `cacheIds`、`cachings`、`completes`、`caches` がある。現行CMAF/Domandの表示対象はまず
  `preferredDmcHls` を使い、該当する `caches[cacheId]` の `complete` を確認する。
- 各 `caches[cacheId]` の `movieType` が `hls` で、`dmcMovieType` に `videoMode`、
  `videoBitrate`、`audioBitrate` が入る。現行Domand/CMAFでは `videoMode` が `1080p`、
  `720p-mid`、`360p-lowest` などの映像品質、`audioBitrate` が音声kbpsを表し、
  `videoBitrate` は0である。`videoBitrate`の非0値は旧DMCキャッシュの補足値としてだけ扱う。
  フィールド欠落や0から架空の品質を推測しない。
- `economy` / `VideoDescriptor.isLow()` はDMC以降では「取得時に選択可能だった最高品質より低い」
  ことを示す互換フラグであり、旧SmileVideoのエコノミーモードと同一ではない。
  `videoMode`内の `_low` / `-lowest` とも別概念なので、品質表示は個別フィールドを優先する。
- `idGroup` の `<通常$$エコノミー$$dmc通常$$dmcエコノミー>` は本体DSLが維持する旧互換分岐で、
  CMAF/Domandの解像度・音声品質を表現できない。静的置換は汎用フォールバックに留め、現行DOMの
  品質表示は `/cache/info/v2` と `local/ncnl_cache_display.js` を使う。
- キャッシュ表示の共通契約は `local/ncnl_cache_display.js`、外観は `local/nl_cacheIcon.css` に置く。
  一覧用 `local/15_cached_link_color.js` と視聴ページ用 `local/20_watchpage.js` へ品質選択や
  アイコンDOM生成を重複実装しない。
- `/cache/*` の本体API契約は `documents/api.md`、実装は
  `src/dareka/processor/impl/CacheDirProcessor.java`、キャッシュの識別・優先順位は
  `VideoDescriptor.java` と `Cache.java` / `CacheManager.java` を正とする。

## 作業前に確認するもの

- 最初にリポジトリ直下で`git status --short --branch`を実行し、
  本体を含むユーザーの未コミット変更を把握する。
- 対象ファイル内の説明、変更履歴、依存するフィルターを先に読む。`09` の共通資産、`10` の旧HTML互換、`11` の現行動的リンク、`15` のキャッシュ表示は役割を分担しているため、片方だけを見て重複実装しない。
- 公式同梱フィルターの背景と運用は `documents/Readme_nl+mod.txt`、現在の実装は `src/dareka/processor/impl/EasyRewriter.java` を参照する。本体ソースも同じリポジトリにあるが、必要性を確認せずフィルター変更へ混在させない。
- JavaScript や CSS が参照する `/local/*` の実体、`window.NicoCache_nl`、`/cache/*` API を変更・利用するときは、`local/`、`documents/api.md`、本体実装、呼び出し元を検索して契約を確認する。
- 追跡中の nlFilter を編集する前に `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 source-check` と `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check` を本体リポジトリ直下から実行し、本体パーサーソースとの基準一致と既存構文の正常性を確認する。

## nlFilter Lab の使用

- 追跡中の `.txt` を変更した作業では、最低限 `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 check <対象ファイル>` と `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 test` を実行する。コーディングエージェントから結果を扱う場合は `check --json` を使ってよい。
- HTML、JavaScript、CSS、SPA挙動へ影響する変更では `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 headless` を使う。対象に応じて `watch`、`search`、`anime` のfixture、5種類の互換キャッシュ状態、`--spa-add` を選び、`result.json`、`final.html`、`console.json`、`screenshot.png` を確認する。Labの`DMC` / `DMC_ECONOMY`モックは現行CMAF/Domandの品質メタデータを返す。生成物は `nlFilters/.cache/nlfilter-lab/` 配下に置き、Gitへ追加しない。
- 対話的な調査が必要なら `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 serve` を使う。Labは疑似実装なので、最終的な実環境確認が必要な変更では NicoCache_nl 経由の検証も省略しない。
- NicoCache_nl の nlFilter パーサーは滅多に変わらないが、変更される可能性はある。`source-check` または `check` がソース差異を報告した場合や、新しい構文・マクロを扱う場合は、`src/dareka/processor/impl/EasyRewriter.java`、`src/dareka/common/regex/JavaPattern.java`、`JavaMatcher.java`、`NestPattern.java`、`NestMatcher.java` を確認し、必要に応じて構文チェッカー、ローカルテスター、互換テストを修正する。
- `nlFilters\tools\nlfilter-lab\parser-baseline.properties` のハッシュだけを更新して差異を解消してはいけない。本体の変更内容を監査し、Lab側の修正とテストが完了した後に基準値を更新する。
- Lab自体または本体パーサーとの互換処理を変更した場合は `.\nlFilters\tools\nlfilter-lab\nlfilter-lab.ps1 compatibility --json` も実行し、`source.status` と `productionOracle.status` がともに `matched` であること、残る差が `externalBoundaries` に限定されることを確認する。

## 詳細リファレンス

- [nlFiltersとは？](https://roflsunriz.github.io/setup-nicocache-nl/nl-filters/): nlFilter の目的、配置、更新、自動再読み込みを確認する入口として使う。
- [nlFiltersの文法](https://roflsunriz.github.io/setup-nicocache-nl/nl-filters-syntax/): セクション、基本フィールド、動作オプション、`idGroup`、専用コマンド、変数、サンプルを調べる標準リファレンスとして使う。記法を記憶で補わず、実際の該当節を確認する。
- [正規表現](https://roflsunriz.github.io/setup-nicocache-nl/regex/): 文字クラス、境界、先読み・後読み、グループ、数量詞と nlFilter 向け例を確認する。
- フルアクセスがある場合は同様のドキュメントが`C:\Users\UserName\Documents\setup-nicocache-nl\docs`で確認できる。（setup-nicocache-nlのリポジトリ）
- 上記ページは実用上の第一参照先、リポジトリ内の既存フィルターは互換性のある用例、`EasyRewriter.java` など現在の本体ソースは最終的な動作仕様として扱う。説明と実装が食い違う場合は、稼働中バージョンの実装を優先し、差異を報告する。
- 一般的な JavaScript 用正規表現解説や古い nlFilter 配布物だけを根拠にしない。nlFilter の本文マッチは Java の正規表現エンジンと NicoCache_nl 独自の前処理・置換を通るためである。

## Git 管理境界とシンボリックリンク

- 変更前に `git ls-files` で Git 管理対象か確認する。現在の `100_features.txt`、`101_disable_official_function.txt`、`105_premium_hide.txt` は `.gitignore` 対象であり、`C:\filter-matome\nlFilters` を参照するシンボリックリンクである。
- リンクは見かけ上このディレクトリにあっても、編集は参照先を直接変更する。`100` 番台を依頼された場合は `Get-Item -Force <path> | Format-List FullName,LinkType,Target` で毎回リンク先を確認し、`C:\filter-matome` 側の `AGENTS.md` と `nlFilters\nlFilters_編集ガイド.md` に従う。
- リンクを通常ファイルへ置換したり、参照先の内容をNicoCache_nlリポジトリへコピーしたりしない。作成、削除、張り替えが必要なら、ユーザーの依頼範囲と参照先を確認してから行う。
- `01`、`05`、`08`、`09`、`10`、`11`、`15`、`20` は NicoCache_nl の標準フィルター群である。上流版との差分を意図せず消さない。大きな独自機能を加える場合は、既存ファイルへ詰め込む前に、適用順が分かる別番号ファイルに分離できるか検討する。

## 読み込み順とファイル名

- NicoCache_nl は `nlFilters` 直下の `.txt` をファイル名の辞書順で読み込み、各ファイル内は上から順に解析する。番号は分類だけでなく適用順を表すため、リネームや新規追加では前後のフィルターとの依存関係を確認する。
- 新規ファイルは既存の番号体系に合わせ、役割が分かる名前にする。同じ番号を安易に再利用せず、辞書順を実際に列挙して意図した位置になることを確認する。
- `nlFilterIgnore` と、ファイル名に `$` を含むバックアップなどは読み込み対象外になり得る。無効化確認のためにファイル名だけを変える場合も、この挙動を踏まえる。
- 一時コピーや退避ファイルを `nlFilters` 直下へ置かない。拡張子が `.txt` のファイルは意図せず読み込まれる可能性がある。

## nlFilter 構文を編集するときの規律

- 各フィルター先頭の `# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)` を維持する。
- 基本セクションは `[Replace]`、`[Script]`、`[Style]`、`[RequestHeader]`、`[Config]` である。通常の本文置換、JavaScript挿入、CSS挿入、リクエストURL/ヘッダー書き換え、拡張から参照する設定という役割を混同しない。
- 一時的にフィルターを無効化する場合は、文法ページに従いセクション行を `#[Replace]` のようにする。ファイル名変更や本文全体のコメントアウトで読み込み順・履歴を不明瞭にしない。
- `URL` は `https://` などのプロトコルより後ろを前方一致で評価し、`FullURL` はプロトコルを含めて評価する。POSTデータ側を対象にする `POST/` 接頭辞も含め、用途に合う形式を使う。正規表現の `.` は `\.` とし、対象ホストとパス境界を必要以上に広げない。
- `ContentType` は部分一致の正規表現であり、指定時は Content-Type のないレスポンスにマッチしない。先頭の `!` による否定も可能だが、広い否定条件より必要な型の明示を優先する。
- `Require` はレスポンス本文、`RequireHeader` はリクエストヘッダーに対する追加条件である。CookieやUser-Agentを扱う場合は、値をログ、コメント、テストデータへ残さない。
- `Match<`、`Replace<`、`Append<` の本文は、単独行の `>` で閉じる。DSL の区切りと、埋め込んだ HTML/JavaScript/CSS の括弧・タグを別々に確認する。
- `EachLine = FALSE` または省略時は `Match` 内の物理改行が無視され、`Replace` 内の改行は出力へ反映される。改行自体をマッチさせる場合は `\s*` や `\r\n` を明示する。
- `Multi` は省略時、最初の1件だけを置換する。全件置換が本当に必要な場合だけ `Multi = TRUE` にする。`EachLine = TRUE` では `Match` と `Replace` の各行が同じ順番で1組になるため、行数と対応を確認する。
- `MatchLocal = TRUE` は通常のURL条件を `/local/` 配下にも適用する。`URL` 自体に `/local/` を明示した場合の挙動とは分けて考え、ローカル配信物へ意図せず全フィルターを適用しない。
- `AddList` は置換結果をLSTへ追加し、`AddVariable` はURL固有変数へ保存する。単なる本文置換ではないため、保存先、重複、更新後の利用元、秘密情報の混入を確認する。
- `$0`、`$1`、`$TS(...)`、`<nlVar:...>`、`<CRLF>`、`<$>`、`<通常$$エコノミー$$dmc通常$$dmcエコノミー>` などは NicoCache_nl が解釈する記法である。最後の4分岐名は現行配信方式の名称ではなく保存互換の内部状態である。通常の正規表現置換やテンプレート文字列だと思って書き換えない。
- `idGroup` はキャッシュ存在時だけ置換するためのキャプチャ番号である。グループを追加・削除すると番号がずれるため、`$1` などの置換参照と一緒に見直す。`<$>` または文字列内のキャッシュ種別分岐は、同じ `Replace` 内で複数使用できないという制約も確認する。
- `$NEST` はネストしたタグ向けの単独コマンドである。開始・終了タグにキャプチャグループを入れず、通常の正規表現と同じ行へ混在させない。
- `$LST("file")` はファイルを行単位の選択肢として読み込み、キャプチャグループを1つ増やす。`!` 付きは内容をエスケープせず正規表現として扱う。リストが空の場合に置換がスキップされること、動的更新されること、参照番号がずれることを考慮する。
- `$INC`、`$SET`、`AddVariable`、`<nlVar:...>` は状態を介して別の置換へ影響する。変数名の利用元を検索し、初期値、数値/文字列、URL単位か設定単位かを確認する。`$SET` の値を動的な式として扱わない。
- `$TS(path)` は `[Replace]` でローカルファイルの更新時刻をクエリへ付ける用途であり、ファイルが存在しない場合は引数がそのまま残る。`$URL0`、`$URL1` などは `URL` 条件側のキャプチャを参照し、`$0`、`$1` などの本文マッチ参照とは区別する。
- `[Debug]` は調査対象URL、マッチ、置換結果を大量にログへ出し得る。一時調査だけに使用し、必要な結果を得たら削除する。ログへCookie、本文、個人情報が出ていないか確認する。
- `#` が行頭にある行だけがコメントになる。ブロック本文の途中や行末コメントを一般的な設定ファイルと同じ感覚で追加しない。
- ファイルごとの既存の改行コードを維持し、無関係な行末変換や全体整形を混ぜない。文字コードは既存どおり UTF-8 を基本とし、BOMを不用意に追加しない。

## 正規表現を変更するときの規律

- DSLの正規表現は Java `Pattern` として解釈される前提で書く。ブラウザーのJavaScriptコンソールやRubularだけで合格しても完了とせず、JavaとNicoCache_nl独自コマンドの差を確認する。
- `.`、`?`、`+`、`*`、`(`、`)`、`[`、`]`、`{`、`}`、`^`、`$`、`|`、`\` を文字そのものとして扱う場合はエスケープする。特にドメインの `.` とクエリ区切りの `?` を見落とさない。
- キャプチャが不要なまとまりには `(?:...)` を使う。不要なキャプチャ追加は `$1`、`idGroup`、`$URL1`、`$LST` が増やす番号を連鎖的にずらす。
- Match内の後方参照は `\1`、Replace内の参照は `$1` である。先読み・後読みは境界確認に限定し、本文全体を何度も走査する複雑な入れ子にしない。
- `^`、`$`、`\b` は行・単語境界、`[\s\S]` は改行を含む任意文字という意味を意識する。部分一致でよいのか、文字列全体またはURL境界を固定すべきかをテストケースごとに決める。
- `\d`、`\w` などの短縮文字クラスを使う前に、対象が半角ASCIIだけか、日本語や全角文字を含むかを確認する。動画IDなど形式が明確な値は `[a-z]{2}\d{1,12}` のように範囲を明示する。
- 貪欲な `.*` や `[\s\S]*` を大きなHTMLへ適用すると、誤マッチとバックトラッキング負荷が増える。安定した前後境界、否定文字クラス、適切な非貪欲量指定を使い、マッチする例・しない例・長い入力を確認する。
- 正規表現だけで不安定な現行SPAのDOMを完全解析しようとしない。レスポンス置換が適切か、狭いJavaScriptフックへ責務を移すべきかを判断する。

## 埋め込み JavaScript / CSS

- グローバル汚染と他フィルターとの変数衝突を避けるため、独立した処理は IIFE などでスコープを閉じる。共有が必要なものだけを、既存契約に従って `window.NicoCache_nl` 配下へ置く。
- SPA と遅延描画を扱う場合は、初期DOMと後続Mutationの両方を処理し、二重登録・二重挿入を属性、クラス、`WeakSet` などで防ぐ。MutationObserver の監視範囲とコールバック内の探索量を最小限にする。
- DOM セレクターは、表示文言、生成ハッシュ、壊れやすい階層だけに依存させない。対象ページの現行DOMを確認し、URL、安定属性、意味のある要素を優先する。
- 複数動画の `/cache/info/v2` 取得は可能な限りまとめ、同じ動画や要素への重複リクエストを避ける。HTTPエラー、JSON不正、要素消失を扱い、失敗時に無限再試行しない。
- ページ側へ差し込むコードは、対象ブラウザーと NicoCache_nl がそのまま配信できる JavaScript/CSS にする。このリポジトリにはトランスパイルやバンドル工程がないため、ビルドで補正されると仮定しない。
- `console` 出力は障害解析に必要な警告・エラーへ限定し、動画ID、Cookie、レスポンス本文などを大量に出力しない。
- CSS は既存のキャッシュアイコンやポップアップの詳細度を確認し、`!important`、固定座標、サイト全体に波及するセレクターを最小限にする。

## 変更方法

- 依頼された不具合に対して、まず対象 URL、期待する変換、実際のレスポンスまたは DOM、既存フィルターがマッチしない理由を特定する。
- 既存機能の修正は、可能な限り対象セクションだけを小さく変更する。古いページ向け処理を、現行ページで再現できないという理由だけで削除しない。
- サイト仕様変更への対応では、確認日、旧構造、新構造、対応方針を対象ファイルのコメントへ簡潔に残す。長い調査ログやエージェント向け検証手順はソースへ埋め込まない。
- nlFilter自体には外部依存やビルド工程を追加しない。小規模なフィルター変更のたびに形式だけの `package.json` や独立文書を新設せず、利用者影響は本体ルートの`CHANGELOG.md`、対象ファイルの変更履歴コメント、日本語Conventional Commitへ記録する。
- `src/`、`config.properties`、`local/`、`nlFilter_sys.txt` も同じGit管理下だが責務は異なる。フィルター修正のついでに変更せず、本体変更が必要なら根拠と検証範囲を明確にして別コミットにする。ただしフィルターが明示的に読み込む`local/`資産は同じ表示機能として一緒に検証する。

## 検証

- 最低限、変更後に次を確認する。
  1. `git diff --check` で空白エラーを確認する。
  2. `git diff -- <対象ファイル>` で意図しない行末変換、文字化け、別セクションの変更がないことを確認する。
  3. セクション、ブロック終端、正規表現、埋め込みJavaScript/CSSの括弧と構文を確認する。
  4. 変更対象URLと、隣接する非対象URLの両方で適用範囲を確認する。
- NicoCache_nl は `nlFilterCheckInterval` に従ってファイル更新を検出し、次の対象リクエスト時にユーザーフィルターを再読み込みする。単なる再読込のために、まず本体を再起動しない。
- 実環境確認では、NicoCache_nl 経由で対象ページをハード再読み込みし、必要に応じて次を確認する。
  - NicoCache_nl ログの `Loading User Filters` と対象ファイル名、および `parse error`、`pattern error`、例外の有無。
  - 変換後DOM、対象要素、重複挿入、コンソールエラー、ネットワークリクエスト数。
  - 初期表示、SPA内遷移、戻る/進む、再描画後の挙動。
  - キャッシュあり/なし、完成/取得中、旧形式/CMAF(Domand)、複数の`videoMode`・音声kbps、旧DMCの映像bitrate、品質フィールド欠落など変更に関係する状態。
- ページ内容は外部サイトの現在状態に依存する。再現できない場合は、確認できた静的事項、未実行の実環境確認、その理由と残るリスクを最終報告へ分けて記載する。
- 実環境検証が必要でも、キャッシュ削除、ログイン状態変更、設定変更など副作用のある操作を勝手に行わない。

## NicoCache_nl の起動・終了が必要な場合

- フィルターの通常再読込で足りないことを確認してから再起動する。
- 終了前に、リポジトリ直下の `NicoCacheLauncher.jar --headless --stop` が対象にできる本体とPIDを確認する。Javaプロセスを名前だけで一括終了しない。
- 通常終了は `NicoCacheLauncher.jar --headless --stop`、起動は配布物に同梱されたランチャーを使う。JARと同梱JREの存在を先に確認し、開発用ビルドスクリプトを起動手段として流用しない。
- 強制終了はユーザーが現在の依頼で明示的に許可した場合だけ行う。終了確認が取れない状態で新しいプロセスを重ねて起動しない。
- 起動後は新しいPID、フィルター読込ログ、対象ページでの動作まで確認し、起動コマンドが成功しただけで完了としない。
