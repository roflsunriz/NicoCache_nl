# NicoCache_nl

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーです。

## 利用を始める

### 必要なもの

- Java 11 以降（Java 17 を推奨）
- Windows では、配布パッケージに含まれる自己完結ランチャーも利用できます

### 起動

リポジトリから実行する場合は、次のいずれかを使用します。

```powershell
.\RunNicoCache.ps1
```

または、Windows のランチャーを直接起動します。

```powershell
.\NicoCache_nl.bat
```

通常の開発・手動利用では、`config.properties.default` を参考に
`config.properties` を作成して設定します。キャッシュ、証明書、実行時データは
リポジトリ内の各データディレクトリ、または Windows パッケージが選択した利用者
データディレクトリに保存されます。

## HTTPS と証明書

現行ニコニコ動画ではHTTPS接続が必要です。[TLS 設定手順](documents/tls.md) を参照してください。
証明書と秘密鍵を含む `certs/` は公開・共有しないでください。

## 開発者向け

ビルド、機能テスト、Windows パッケージの検証は
[更新・検証手順](how-to-update.md) にまとめています。Windows インストーラーの
詳細は [Windows パッケージの説明](packaging/windows/README.md)、テストの範囲は
[テスト概要](tests/README.md) を参照してください。

変更履歴は [CHANGELOG.md](CHANGELOG.md) に集約しています。`documents/archive/`
には、互換性確認や来歴のために旧版の README、変更履歴、開発メモを保存しています。
これらは現行の利用手順ではありません。
