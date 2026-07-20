# テスト

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
  ETag による条件付き取得、上流接続不能時の切断、CONNECTからTLS
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
