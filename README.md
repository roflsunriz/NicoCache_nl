# NicoCache_nl

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーです。

## 利用を始める

詳細な利用開始手順は [NicoCache_nl USAGE GUIDE](https://roflsunriz.github.io/setup-nicocache-nl/) にあります。

Windows・Linux・macOSパッケージ版の初回セットアップでは、キャッシュ、個人設定、利用者が追加する
`local`・`nlFilters`・Extensionを保存するユーザーデータフォルダーを指定します。
初回ウィザードでは、HTTPS MitM、ローカルCAの信頼登録、`proxy.pac`、ログオン時自動起動の
4項目を推奨設定として最初から選択します。HTTPSの2項目はHTTPS通信に必須、`proxy.pac`は
通常通信の性能維持、自動起動は手動起動の手間をなくすための設定です。
選択した絶対パスはアプリケーションフォルダーの`config.properties`にある
`userDataRoot`へ保存されます。標準資材はアプリケーション側から先に読み込み、
同名の利用者資材を後から読み込んで上書きします。書き込みはユーザーデータ側だけに
行います。

アプリケーションの`defaults/`は、`application.properties`、`network.properties`、
`video-cache.properties`、`legacy-cache-compatibility.properties`、
`rewriting.properties`、`thumbnail-cache.properties`、`https-mitm.properties`に
目的別で分かれています。これらは初期値と説明の参照用で、直接編集せず、変更するキーだけを
`config.properties`へ記述してください。廃止設定からの移行は
[更新・検証手順](how-to-update.md#旧取得設定からの移行)を参照してください。

パッケージ版の既定ユーザーデータ先は、Windowsではドキュメント内の
`NicoCache_nl`（$env:USERPROFILE\NicoCache_nl）、macOSでは`~/Library/Application Support/NicoCache_nl`、Linuxでは
`$XDG_DATA_HOME/NicoCache_nl`（未設定時は`~/.local/share/NicoCache_nl`）です。
初回ウィザードから別の絶対パスへ変更できます。

## 独立アプリと起動管理

ビルド、証明書生成、本体起動管理はそれぞれ独立したJavaアプリです。正規ビルドは
`.java-version`で指定するEclipse Temurin JDK 25を既定とします。Java 17/21は
互換性検証で明示指定する場合だけ使用し、通常のビルドや同梱ランタイムには使用しません。
配布JARの互換ターゲットは引き続きJava 11です。
外部JDKで実行する場合の対応範囲はJava 17、21、25だけです。GitHub Actionsでは、
`actions/setup-java`が提供する現行13配布元（Temurin、Zulu、Liberica、Microsoft、
Semeru/OpenJ9、Corretto、Oracle、Dragonwell、SapMachine、GraalVM、GraalVM Community、
JetBrains Runtime、Tencent Kona）の各3世代、計39通りで同一のビルド成果物を実行します。
廃止済みのAdopt系識別子と、17/21/25以外の外部JDKは対応対象に含めません。
次のコマンドで実行し、`NicoCache_nl.jar`、`NicoCacheCA.jar`、
`NicoCacheLauncher.jar`、`NicoCacheDiagnostics.jar`、`NicoCacheBuild.jar`を生成します。

```powershell
.\build-javac.ps1
```

Linux/macOSでは同じJavaビルドアプリをPOSIXラッパーから実行できます。

```sh
./build-javac.sh
```

Windowsのアプリケーションルート・MSI・ZIP、Linuxのアプリケーションルート・ZIP・DEB/RPM、
macOSのアプリケーションルート・ZIP・PKG/DMGは、`git clone`直後と同じ追跡ファイル配置を
保ち、その直下へ上記5本のプリコンパイル済みJARと専用`jre/`を重ねた共通構成です。
キャッシュや個人設定は、この読み取り専用にできるアプリケーションルートとは別の
ユーザーデータルートへ保存します。

`NicoCacheLauncher.jar`は引数なしなら管理GUIだけを起動し、NicoCache_nl本体は自動で
起動しません。タスクトレイ常駐、ログオン時に一回だけ実行する自動起動タスクの
登録・更新・削除、本体の起動状態表示を管理します。
起動管理画面またはタスクトレイから未起動の本体を起動すると、本体のGUIログも表示されます。
ログオン時自動起動タスクはランチャーをタスクトレイへ格納し、本体を明示起動するため、
ログオン後もタスクトレイから本体のGUIログを操作できます。
起動時にはユーザーデータルートも自動診断し、移行先の不足項目、既定資材による代替、
HTTPS証明書や権限の問題を画面で確認できます。詳細は[ユーザーデータルートの診断と移行](documents/user-data-root.md)
を参照してください。
画面のない環境では同じJARを次のように使えます。

```powershell
java -jar .\NicoCacheLauncher.jar --headless --start
java -jar .\NicoCacheLauncher.jar --headless --status
java -jar .\NicoCacheLauncher.jar --headless --stop
java -jar .\NicoCacheLauncher.jar --headless --check-data-root
```

GUIを最初からタスクトレイへ格納する場合は`--tray`、最小化する場合は`--minimized`を
指定します。どちらも単独では本体を起動せず、同時に起動する場合だけ`--start`を
追加します。Windows、Linux、macOSで同じオプションを使用できます。

```powershell
java -jar .\NicoCacheLauncher.jar --tray --start
java -jar .\NicoCacheLauncher.jar --minimized
```

ランチャー、本体、診断アプリは別プロセスで、終了操作の対象も分かれています。
常駐ランチャーがある状態で別のコマンドプロンプトから`--headless --stop`を実行しても、
終了するのはNicoCache_nl本体だけです。ランチャーと診断アプリは常駐を続け、診断アプリは
本体の次回起動を待ちます。停止後に本体を自動再起動することはありません。

| 操作 | NicoCache_nl本体 | ランチャー | 診断アプリ |
| --- | --- | --- | --- |
| ウィンドウの× | 変更なし | タスクトレイへ格納 | 変更なし |
| 画面・トレイの「本体を停止」 | 停止 | 常駐を継続 | 常駐して次回起動を待機 |
| `--headless --stop` | 停止 | 常駐中なら継続 | 常駐して次回起動を待機 |
| 「ランチャーのみ終了」 | 変更なし | 終了 | 変更なし |
| 診断アプリの「診断アプリを終了」 | 変更なし | 変更なし | 終了 |

「ランチャーのみ終了」は起動管理画面とタスクトレイの両方にあります。実行前の確認画面にも、
本体と診断アプリが終了しないことを表示します。

初回は`--setup --headless`に`--user-data-root`、`--https=true`、
`--trust-certificate`、`--proxy=true`、`--autostart`を明示してセットアップします。

Linux/macOSでも同じCLIを利用できます。旧来のGUI起動・証明書生成スクリプトは削除し、
配布パッケージの入口は全OSで同じ起動管理アプリに統一しています。
`--setup --headless`は初回セットアップへ転送されます。

起動管理GUI、タスクトレイ、ヘッドレスCLIのどの経路から本体を起動しても、独立した
`NicoCacheDiagnostics.jar`も自動起動して常駐します。画面のない環境ではバックグラウンドで
監視を継続します。管理APIと実際のプロキシー経路を2秒ごとに監視し、3回連続で応答しない場合や
本体プロセスが予期せず終了した場合に、匿名化済みの自己完結HTMLレポートを利用者データへ
自動保存します。診断アプリはNicoCache_nlを自動再起動しません。動画固有の不具合を
識別できるよう、動画IDと動画タイトルはレポートへ保持します。詳細は
[常駐診断アプリと自動障害レポート](documents/diagnostics-watchdog.md)を参照してください。

現行ニコニコ動画向けの通常構成ではHTTPS MitMが必須で、ユーザーデータルートの
`certs/site.jks`と`proxy.pac`が必要です。
証明書が未生成の移行先では、本体を起動する前に次を実行してください。

```powershell
java -Dnicocache.applicationRoot="$env:LOCALAPPDATA\NicoCache_nl" `
  -Dnicocache.userDataRoot="$env:USERPROFILE\Documents\NicoCache_nl" `
  -jar .\NicoCacheCA.jar --headless `
  --targets-file="$env:LOCALAPPDATA\certificate-targets.txt"
```

`NicoCacheCA.jar`は`config.properties`の`userDataRoot`も読み取ります。生成後は必要であれば
Firefoxへユーザーデータ側の`certs/ca.cer`をインポートし、本体を再起動してください。（通常はWindowsに設定した証明書ストアをFirefoxが読み取って利用するため追加の操作は不要）
JAR自体にはOSのファイルアイコンを持たせられないため、GUIのウィンドウ・タスクトレイと
Windowsインストーラーのショートカットには専用アイコンを割り当てます。

本体はランダムトークンで保護したループバック限定の管理APIを提供し、起動管理アプリが
グレイスフル停止・強制停止・状態確認を呼び出します。ポートは設定の
`controlPort=0`（空きポート自動選択）が既定です。証明書生成対象は
`certificate-targets.txt`から読み込み、ドメインをJavaソースへ埋め込みません。

Firefoxなどのブラウザーで指定するプロキシーは、設定の`listenPort`（既定値
`8080`）です。`--status`に表示される`port`は起動管理API用なので、ブラウザーの
プロキシーには指定しないでください。HTTPS MitMを有効にしたまま
`userDataRoot/certs/site.jks`がない場合、本体は`degraded`状態になり、8080番では
待ち受けません。移行後は証明書と`proxy.pac`を用意してから再起動してください。

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

## 視聴ページから保存・削除する

NicoCache_nlを経由して現行のニコニコ動画の視聴ページを開くと、画面右上（狭い画面では
下部）に`動画保存`、`コメント保存`、`音声のみ保存`、`キャッシュ削除`が表示されます。
動画と音声の保存には完成済みキャッシュを使います。コメント保存は、視聴ページから取得した
現行NVComment情報を使って`<動画ID>.comments.json`をダウンロードします。

各リンクが利用する`/cache/*`の仕様と、外部ツールから呼ぶ場合の前提は
[本体APIリファレンス](documents/api.md)を参照してください。

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
