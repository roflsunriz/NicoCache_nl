# HTTPS MitM の設定

NicoCache_nl の HTTPS MitM 機能を使う場合は、次の手順で証明書を生成します。

1. `certificate-targets.txt` の対象ドメインを確認します。
2. Windows では `genCerts.bat`、Unix 系では `genCerts.sh` を実行します。
3. 生成された `certs/ca.cer` を、利用するブラウザーまたは OS の信頼済み証明書ストアへ、認証局証明書として登録します。
4. `config.properties` の HTTPS MitM 関連設定とプロキシー設定を確認して NicoCache_nl を起動します。

`genCerts` は対象サイト用の証明書を更新します。認証局を作り直す必要がある場合だけ、
`certs/` 内の `ca.` と `site.` で始まる生成ファイルをバックアップしてから削除し、
スクリプトを再実行してください。この場合は `ca.cer` を証明書ストアへ登録し直します。

証明書、秘密鍵、対象ドメイン一覧は公開しないでください。Firefox などが独自の
証明書ストアを使う場合は、ブラウザー側にも `ca.cer` を登録する必要があります。
