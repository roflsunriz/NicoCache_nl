# NicoCache_nl のスレッドダンプ取得手順

NicoCache_nl が応答しない、または処理が長時間止まっているときは、動作中の全スレッドの
状態を `debug-dump-stack.txt` へ保存できます。配布版だけで取得でき、`jps`、`jstack`、
`jmap` などのJDKコマンドは不要です。

現行の本体はJavaの `ThreadMXBean.dumpAllThreads(true, true)` を使用します。出力には、
各スレッドの名前、ID、状態、スタックトレース、待機中のロック、所有中のモニターと
同期オブジェクトが含まれます。

## ブラウザーで取得する

1. NicoCache_nlを起動します。
2. 次のURLを開きます。`8080` は `listenPort` の設定値に置き換えてください。

   ```text
   http://localhost:8080/debug/dump-stack
   ```

3. 本体から `OK` と表示されたら取得成功です。

`localhost` の `/debug/` パスは、NicoCache_nlがプロキシー内部で処理します。
ブラウザーのプロキシー設定に依存せず、NicoCache_nlの待ち受けポートへ
直接接続します。URLは必ず `http://` で開いてください。
現行の `proxy.pac` を利用している場合は、
`http://DEBUG:8080/debug/dump-stack` でも同じ処理を呼び出せます。

## PowerShellで取得する

ブラウザーを使わない場合は、WindowsのPowerShellで次を実行します。

```powershell
curl.exe `
  --noproxy "*" `
  http://127.0.0.1:8080/debug/dump-stack
```

`--noproxy "*"` は、OSや環境変数のプロキシーを使わず、ローカルで動作する
NicoCache_nlへ直接送信するために指定します。

## 出力先

取得結果は、利用者データルート直下の `debug-dump-stack.txt` にUTF-8で保存されます。
通常、利用者データルートはアプリケーションフォルダーの `config.properties` にある
`userDataRoot` で指定されます。未指定時の選択規則は
[利用者データの保存先](documents/user-data-root.md)を参照してください。

同名ファイルは取得のたびに上書きされます。複数回取得する場合は、各取得後に時刻を含む
別名へコピーしてください。障害調査では、症状が起きている間に数秒間隔で複数回取得すると、
同じ処理で停止し続けているかを判断しやすくなります。

## 取得できないとき

- 接続できない場合は、NicoCache_nlが起動しているか、`listenPort` が一致しているか、
  URLが `https://` へ自動変更されていないかを確認します。ブラウザーで失敗する場合は、
  PowerShellの手順を使用してください。
- `404 Not Found` の場合は、パスが `/debug/dump-stack` であることを確認します。
- `OK` と表示されてもファイルが見つからない場合は、実行ファイルの場所ではなく、現在の
  `userDataRoot` を確認します。

## 取り扱い上の注意

このデバッグ用URLには認証がありません。NicoCache_nlのプロキシーポートを外部ネットワークへ
公開しないでください。スレッド名やファイルパスには利用者名などが含まれる可能性があるため、
共有前に内容を確認し、障害調査に必要な範囲だけを渡してください。
