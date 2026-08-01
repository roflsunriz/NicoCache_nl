# NicoCache_nl

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーです。

## 利用を始める

詳細な利用開始手順は [NicoCache_nl USAGE GUIDE](https://roflsunriz.github.io/setup-nicocache-nl/) にあります。

Windows・Linux・macOSパッケージ版の初回セットアップでは、キャッシュ、個人設定、利用者が追加する
`local`・`nlFilters`・Extensionを保存するユーザーデータフォルダーを指定します。
選択した絶対パスはアプリケーションフォルダーの`config.properties`にある
`userDataRoot`へ保存されます。標準資材はアプリケーション側から先に読み込み、
同名の利用者資材を後から読み込んで上書きします。書き込みはユーザーデータ側だけに
行います。

パッケージ版の既定ユーザーデータ先は、Windowsではドキュメント内の
`NicoCache_nl`、macOSでは`~/Library/Application Support/NicoCache_nl`、Linuxでは
`$XDG_DATA_HOME/NicoCache_nl`（未設定時は`~/.local/share/NicoCache_nl`）です。
初回ウィザードから別の絶対パスへ変更できます。

## GUIログを検索する

main、debug、Extensionの各ログタブには独立した検索欄があります。入力中に一致する
行だけが表示され、`正規表現`と`大/小文字を区別`を必要に応じて切り替えられます。
`Ctrl+F`で検索欄へ移動し、`Esc`または`解除`で全ログ表示へ戻ります。

検索欄でEnterを押すか別の場所へ移動すると、検索語、検索モード、入力日時が
利用者データフォルダーの`NicoCacheGUI.search-history.properties`へタブ別に
保存されます。検索欄へカーソルを入れて日時付き履歴を選ぶと、その検索語と
モードで直ちに再検索します。

ログ画面上部の`デバッグログを debug.log に記録`をオンにすると、デバッグログを
アプリケーションJARと同じフォルダーの`debug.log`へ記録します。この状態は
`NicoCacheGUI.property`の`DebugMode`へ保存され、次回起動時にも反映されます。
ログは新しい履歴を残しながら自動的に切り詰められ、1 MiBを超えません。

## 開発者向け

ビルド、機能テスト、Windows・Linux・macOSパッケージの検証は
[更新・検証手順](how-to-update.md) にまとめています。

標準nlFilterと検証用nlFilter Labは`nlFilters/`で本体と同じGit履歴に管理しています。
フィルター変更時は[nlFilters固有の更新手順](nlFilters/how-to-update.md)も確認してください。

Windows インストーラーの詳細は [Windows パッケージの説明](packaging/windows/README.md)、
Linux/macOSパッケージの詳細は[Unixパッケージの説明](packaging/unix/README.md)、

テストの範囲は[テスト概要](tests/README.md) を参照してください。

## 保存済みCMAF/Domandを単一MP4へ変換する

独立ツール `tools/cmaf-to-mp4/` は、保存済みCMAF/DomandキャッシュをFFmpegで
単一のMP4へ変換します。引数なしならGUIが起動し、キャッシュフォルダのドラッグ・
アンド・ドロップ、出力先指定、出力先フォルダを開く操作、変換後に自動で開く設定を
利用できます。画面のない環境や自動処理では同じJARを`--headless`で実行できます。

詳しい前提、ビルド、CLIオプションは[CMAF/Domand MP4変換ツールの説明](tools/cmaf-to-mp4/README.md)
を参照してください。

変更履歴は [CHANGELOG.md](CHANGELOG.md) に集約しています。

`documents/archive/`には、互換性確認や来歴のために旧版の README、変更履歴、開発メモを保存しています。
これらは現行の利用手順ではありません。また、配布パッケージには含まれません。
