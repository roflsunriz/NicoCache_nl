# HTTPS MitM の設定

現行ニコニコ動画ではHTTPS通信が必須のため、NicoCache_nlのHTTPS MitMを有効にし、
次の手順で証明書を生成します。

1. `certificate-targets.txt` の対象ドメインを確認します。
2. アプリケーションルートで、ユーザーデータルートを明示して
   `NicoCacheCA.jar`を実行します。

   ```powershell
   java -Dnicocache.applicationRoot="C:\NicoCache_nl" `
     -Dnicocache.userDataRoot="C:\Users\利用者名\Documents\NicoCache_nl" `
     -jar .\NicoCacheCA.jar --headless `
     --targets-file="C:\NicoCache_nl\certificate-targets.txt"
   ```

3. 生成されたユーザーデータ側の`certs/ca.cer`を、利用するブラウザーまたはOSの
   信頼済み証明書ストアへ、認証局証明書として登録します。
4. `config.properties` の HTTPS MitM 関連設定とプロキシー設定を確認し、`proxy.pac`を
   用意して NicoCache_nl を起動します。
   ブラウザーのプロキシーは`listenPort`（既定値`8080`）を使い、状態ファイルの
   `port`（起動管理API用）とは分けてください。`userDataRoot/certs/site.jks`が
   ない状態では本体は`degraded`となり、プロキシー待ち受けを開始しません。

`NicoCacheCA.jar` は対象サイト用の証明書を更新します。認証局を作り直す必要がある場合だけ、
`certs/` 内の `ca.` と `site.` で始まる生成ファイルをバックアップしてから削除し、
`NicoCacheCA.jar`を再実行してください。この場合は `ca.cer` を証明書ストアへ登録し直します。

## 既存環境を更新した場合

ランチャーの「データルート診断」で`site.targets`の対象不足が表示されなければ、証明書の
作り直しは不要です。`nicocachenl.test`など新しい対象が不足している場合だけ、上記コマンドで
現在の`certificate-targets.txt`からSITE証明書を再生成してください。この操作は既存の
`certs/ca.jks`を再利用するため、`ca.cer`の再登録は不要です。

`ca.jks`を削除した場合、またはCAの期限・秘密鍵の問題でCA自体を作り直した場合は、新しい
`ca.cer`をOSと、独自ストアを使うFirefox等へ登録し直してください。

証明書、秘密鍵、対象ドメイン一覧は公開しないでください。Firefox などが独自の
証明書ストアを使う場合は、ブラウザー側にも `ca.cer` を登録する必要があります。
