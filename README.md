# NicoCache_nl

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーです。

## 利用を始める

詳細な利用開始手順は [NicoCache_nl USAGE GUIDE](https://roflsunriz.github.io/setup-nicocache-nl/) にあります。

## GUIログを検索する

main、debug、Extensionの各ログタブには独立した検索欄があります。入力中に一致する
行だけが表示され、`正規表現`と`大/小文字を区別`を必要に応じて切り替えられます。
`Ctrl+F`で検索欄へ移動し、`Esc`または`解除`で全ログ表示へ戻ります。

検索欄でEnterを押すか別の場所へ移動すると、検索語、検索モード、入力日時が
タブごとに`NicoCacheGUI.property`へ保存されます。検索欄へカーソルを入れて
日時付き履歴を選ぶと、その検索語とモードで直ちに再検索します。

## 開発者向け

ビルド、機能テスト、Windows パッケージの検証は[更新・検証手順](how-to-update.md) にまとめています。

Windows インストーラーの詳細は [Windows パッケージの説明](packaging/windows/README.md)、

テストの範囲は[テスト概要](tests/README.md) を参照してください。

変更履歴は [CHANGELOG.md](CHANGELOG.md) に集約しています。

`documents/archive/`には、互換性確認や来歴のために旧版の README、変更履歴、開発メモを保存しています。
これらは現行の利用手順ではありません。また、配布パッケージには含まれません。
