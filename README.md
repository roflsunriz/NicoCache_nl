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

## 独立アプリと起動管理

ビルド、証明書生成、本体起動管理はそれぞれ独立したJavaアプリです。正規ビルドは
次のコマンドで実行し、`NicoCache_nl.jar`、`NicoCacheCA.jar`、
`NicoCacheLauncher.jar`、`NicoCacheBuild.jar`を生成します。

```powershell
.\build-javac.ps1
```

Linux/macOSでは同じJavaビルドアプリをPOSIXラッパーから実行できます。

```sh
./build-javac.sh
```

`NicoCacheLauncher.jar`は引数なしならGUIで起動し、タスクトレイ常駐、ログオン時に
一回だけ実行する自動起動タスクの登録・更新・削除、本体の起動状態表示を管理します。
画面のない環境では同じJARを次のように使えます。

```powershell
java -jar .\NicoCacheLauncher.jar --headless --start
java -jar .\NicoCacheLauncher.jar --headless --status
java -jar .\NicoCacheLauncher.jar --headless --stop
```

初回は`--setup --headless`に`--user-data-root`、`--https`、
`--trust-certificate`、`--proxy`、`--autostart`を明示してセットアップします。

Linux/macOSでも同じCLIを利用できます。旧来のGUI起動・証明書生成スクリプトは削除し、
配布パッケージの入口は全OSで同じ起動管理アプリに統一しています。
`--setup --headless`は初回セットアップへ転送されます。

HTTPS MitMを有効にした場合は、ユーザーデータルートの`certs/site.jks`が必要です。
証明書が未生成の移行先では、本体を起動する前に次を実行してください。

```powershell
java -Dnicocache.applicationRoot="C:\NicoCache_nl" `
  -Dnicocache.userDataRoot="C:\Users\利用者名\Documents\NicoCache_nl" `
  -jar .\NicoCacheCA.jar --headless `
  --targets-file="C:\NicoCache_nl\certificate-targets.txt"
```

`NicoCacheCA.jar`は`config.properties`の`userDataRoot`も読み取ります。生成後は
Firefoxへユーザーデータ側の`certs/ca.cer`をインポートし、本体を再起動してください。
JAR自体にはOSのファイルアイコンを持たせられないため、GUIのウィンドウ・タスクトレイと
`jpackage`のネイティブランチャーには専用アイコンを割り当てます。

本体はランダムトークンで保護したループバック限定の管理APIを提供し、起動管理アプリが
グレイスフル停止・強制停止・状態確認を呼び出します。ポートは設定の
`controlPort=0`（空きポート自動選択）が既定です。証明書生成対象は
`certificate-targets.txt`から読み込み、ドメインをJavaソースへ埋め込みません。

Firefoxなどのブラウザーで指定するプロキシーは、設定の`listenPort`（既定値
`8080`）です。`--status`に表示される`port`は起動管理API用なので、ブラウザーの
プロキシーには指定しないでください。HTTPS MitMを有効にしたまま
`userDataRoot/certs/site.jks`がない場合、本体は`degraded`状態になり、8080番では
待ち受けません。移行後は証明書を生成してから再起動してください。

## GUIログを検索する

mainとExtensionの各ログタブには独立した検索欄があります。入力中に一致する
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
本体のキャッシュAPIと起動管理APIの一覧・利用例は[本体APIリファレンス](documents/api.md)を
参照してください。

## 保存済みCMAF/Domandを単一MP4へ変換する

独立ツール `tools/cmaf-to-mp4/` は、保存済みCMAF/DomandキャッシュをFFmpegで
単一のMP4へ変換します。引数なしならGUIが起動し、キャッシュフォルダのドラッグ・
アンド・ドロップ、出力先指定、出力先フォルダを開く操作、変換後に自動で開く設定を
利用できます。画面のない環境や自動処理では同じJARを`--headless`で実行できます。
`.cmfv`/`.cmfa`を正しく読むためのFFmpeg HLS設定と、旧FFmpegへの互換再試行もツール側で
処理します。

詳しい前提、ビルド、CLIオプションは[CMAF/Domand MP4変換ツールの説明](tools/cmaf-to-mp4/README.md)
を参照してください。Windows・Linux・macOSの本体パッケージとアプリイメージZIPにも、
実行用JARが`tools/cmaf-to-mp4/`として含まれます。

変更履歴は [CHANGELOG.md](CHANGELOG.md) に集約しています。

`documents/archive/`には、互換性確認や来歴のために旧版の README、変更履歴、開発メモを保存しています。
これらは現行の利用手順ではありません。また、配布パッケージには含まれません。
