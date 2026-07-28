# テスト

## 実利用経路のE2Eテスト

次のコマンドは、テスト用に生成した製品JARを利用者と同じエントリーポイントから
別プロセスで起動し、実ソケットと実Swing Event Dispatch Threadを操作する。

```powershell
.\test-e2e.ps1
```

HTTP側は隔離した利用者データ領域とローカル上流サーバーだけを使い、通常の
GET・HEAD・Rangeに加えて、パストラバーサル、巨大ヘッダー、矛盾する
`Content-Length`、`Transfer-Encoding`との併用、不完全送信、途中切断、
24利用者相当の並行アクセス、上流停止時の応答と復旧を期限付きで検証する。
GUI側は本体ログウィンドウを実際に作成し、安定したコンポーネント識別子、
最小・標準画面での描画、タブ、全右クリック項目、折り返し・最前面設定、
ログ上限、重複排除、キャッシュ進捗更新、バックログ復帰、ウィンドウ位置の
永続化、終了後のウィンドウとトレイ解放を検証する。

失敗時のログ、スクリーンショット、隔離データを確認する場合は
`-KeepWorkDir` を指定する。E2Eは外部サイト、実キャッシュ、実設定、
証明書ストア、OSプロキシー設定には接続しない。

## 機能テスト

リポジトリ直下で次を実行する。

```powershell
.\test-functional.ps1
```

テストは `.test-work/functional/` に本体、設定、キャッシュ、ローカル
Extension を隔離して作成する。実際の `cache/`、`certs/`、`data/`、
`extensions/` は使用しない。外部サイトにも接続せず、テスト内のローカル上流
HTTP サーバーを利用する。

確認範囲は次のとおりである。単なる起動確認ではなく、実ソケットの応答内容と
隔離ファイルの生成・削除結果を検証する。

- HTTP プロキシーの GET・HEAD・POST、ヘッダー・本文・ステータス、Range、
  ETag による条件付き取得、上流接続不能時の502応答、CONNECTからTLS
  ループバックしたHTTPSローカル配信
- `/local/` の本文・MIME・Range・許可メソッドと、Extension Rewriter による
  本文・レスポンスヘッダー書換え
- サムネイルの上流取得・ファイル保存・再利用
- nvcomment 応答の動画別 JSON 保存（外部 TLS 接続を避けるため、テスト
  Extension が同じ `CommentSavingProcessor` をローカル HTTP fixture に登録する）
- DOMAND/CMAF の access-rights、master/sub playlist、AES key、初期化 chunk、
  暗号化 media segment、復号、完成処理、上流停止後のキャッシュ再生と、
  アニメ公式動画で使われる `hlsext` 経路
- `/cache/*` の情報取得、配信、Range、削除、不正入力と既存エラー形式
- 単一 MP4・FLV・SWF、旧 DMC MP4・HLS の検索、配信、削除
- Extension と Extension2 のロード、および Processor、stopper Processor、
  Rewriter、RequestFilter、CompleteCache、イベント、終了通知

失敗調査で作業ファイルとログを残す場合は `-KeepWorkDir` を指定する。

## Extension ABI 互換テスト

`tests/compat/extension-api.txt` と `extension-api.sha256` は、配布済み
`NicoCache_nl.jar` に含まれる全 `dareka.*` public/protected 型を
`javap -protected -s` で正規化した基準である。同梱サンプルが使用していない型も
対象に含む。型の継承宣言と、所属型・可視性・JVM descriptor を含む各メンバーを
比較し、追加・変更と未許可の削除を失敗させる。

次の理由だけでは基準値を更新しない。

- 本体やサンプルから参照されていない
- `@Deprecated` が付いている
- 古い配信方式を示す名前である
- 既定設定で無効、または実装が no-op である

公開 API を意図的に削除する場合は、先に
`tests/compat/allowed-api-removals.txt` へ `extension-api.txt` の完全一致行と、
現行のニコニコ動画で不要と断定できる根拠、互換 shim では不十分な理由を
記録する。許可行が実差分に存在しない場合もテストは失敗する。基準は互換対象の
配布済み JAR からのみ再生成する。

```powershell
.\tests\compat\get-extension-api-hash.ps1 -JarPath .\NicoCache_nl.jar -Detailed
.\tests\compat\get-extension-api.ps1 -JarPath .\NicoCache_nl.jar
```

## Windows パッケージの隔離スモークテスト

`packaging/windows/test-windows-app-image.ps1` は自己完結アプリイメージの
唯一のアプリランチャー `NicoCache_nl.exe` でヘッドレス初回セットアップを
実行した後、同じEXEを `--headless` 指定で専用の空きループバックポートへ
起動し、ルートのHTTP応答とバージョン文字列を確認する。アプリイメージ直下の
EXEがこの1本だけであることも検証する。テスト設定、
ログ、キャッシュは
`.test-work/windows-package/` 内に限定し、TLS MitM、証明書登録、Windows
プロキシー設定、タスク登録は行わない。終了時は起動した実行ファイルのパスと
PIDを照合し、そのPIDだけを終了する。ヘッドレス初回セットアップはテスト領域
だけにCAとサイト証明書を生成し、OSへ登録しない指定が守られることを確認して
新規生成ファイルを削除する。
証明書ストアとWindowsプロキシー設定はテスト前後のスナップショットが一致する
ことも検証する。

`packaging/windows/test-package-parity.ps1` は、共通アプリイメージから作成したZIPを
展開し、アプリイメージ内の全ファイルとSHA-256が一致することを検証する。MSIは
この同じアプリイメージを入力として生成するため、ZIP、MSIの製品ペイロード、
アプリイメージの内容を同一経路で確認できる。

MSIはGitHub Actionsの一時Windowsランナーへ旧版を `msiexec /qn` で
インストールする。配布ファイルの修復、ユーザー状態を保った新版への更新、
同じスモークテスト、`msiexec /x /qn` によるアンインストールまで実行する。
導入時に本体が意図せず起動しないこと、スタートメニューの登録と削除、
試験前後で証明書・Windowsプロキシー・製品登録が一致することも確認する。
ローカル環境の製品登録を汚染しないため、MSI試験スクリプトは一時GitHub
Actionsランナー以外での実行を拒否する。

## 初回起動ウィザード

次のコマンドはOS設定を変更せず、初回セットアップの判定、設定作成、
既存ファイル保全、失敗ロールバック、日本語と英語の辞書キー、4画面の全ボタンと
チェック項目、選択連動、戻る・次へ・キャンセル・適用、処理中の操作無効化、
単一EXE用ヘッドレス引数の必須項目・値・矛盾検査を検証する。あわせて、
アプリ本体と利用者データのパス解決、絶対・相対キャッシュパス、ポータブル
モード、旧配置の上書きしない移行、移行の再実行、分離した初回セットアップを
検証する。

```powershell
.\test-first-run-setup.ps1
```

`-KeepWorkDir` を指定すると、標準幅と最小幅の画面プレビューを
`.test-work/first-run-setup/preview/` に保持する。OS連携の実処理は
`packaging/windows/test-windows-first-run.ps1` が一時GitHub Actionsランナーで
同じEXEの `--setup --headless` 経由でCA登録、Windows自動プロキシー、
ログオン時起動を適用し、保存した変更前状態へ完全に復元できることを確認する。
現行環境の汚染を防ぐため、この実連携試験はGitHub Actions以外での実行を
拒否する。

## リリースワークフロー契約テスト

次のテストは、リリース時の機能テストと本体MSI検証が残っていること、および
アップロードする資産とGitHub Releaseへ公開する資産が一致することを検証する。
従来のZIP、JAR、本体MSIと各ハッシュを欠落させた場合や、独立アップデーター
MSIまたはハッシュを追加し忘れた場合は失敗する。

```powershell
.\tests\release\test-release-workflow.ps1
```
