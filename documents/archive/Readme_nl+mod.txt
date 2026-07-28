NicoCache_nl+mod (NicoCache_nl 勝手に改造版) by swfConvertの人

■これは何？

NicoCache_nl 本家である nicolist.net が無くなってしまったのと、改造版ベースの
NicoCache_nl (9).12 が古くなってきたので、NicoCache_nl+mod として単体動作する
パッケージをリリースしてみるテスト。バージョン表記はこれまで通り YYMMDD です。

同梱物は NicoCache_nl (9).12 パッケージと比較して大幅に減らしていますので、
以下のものが必要な人はオリジナルのパッケージから各自で導入してください。
　cacheManager、新プレイヤーコントローラ、サンプルフィルタ
※NicoCacheGUI は質問が多いので相当機能を実装しました(下記説明を参照のこと)

動作確認は Linux x86_64 上でしか行っていないので、その他の環境で問題が発生する
可能性があります。また、自分が常用していない設定で問題が発生する事もあります。
あと、Java 1.5 は使っていないので動かなくなっている事があるかも知れません。


■お約束

本改造版を利用した事によるいかなる損害に対して、作者は一切の責任を持ちません。
セキュリティ的にザルなので、WAN 側に公開するような無謀な使い方は止めて下さい。

あくまで NicoCache_nl 本体の改造が目的でなので、統合パッケージを求めている人は
無理に使う必要はありません。あと、ChangeLog.txt を読まない人も同様です。
また、最低限設定ファイルの編集ができないと NicoCache_nl を使うのは無理でしょう。

NicoCache スレで質問する前に、各種ドキュメント類には目を通しておいてください。
また、質問する場合はスレのテンプレに書いてある内容は最低限守ってください。

  http://crus.biz/nicocache_nl/index.php?NicoCache%E3%82%B9%E3%83%AC

nl本体側に問題があると思った場合、デバッグモードで起動してから問題が発生する
操作を行い debug.log を取得してください。取得した debug.log を斧(Axfc)等の
短期うｐろだにアップロードしてから、そのURLを添えて報告して貰えると助かります。
※「NicoCache関連ファイル置き場」にはアップロードしないでください
※ログに個人を特定可能な情報(ユーザーID等)が含まれる事があるので注意して下さい
※また、debug.log の切り詰めはしないので常時デバッグモードで使用しないこと

デバッグモードの起動方法:

GUI起動する場合は
NicoCacheGUI.property に DebugMode=true を記述してから NicoCache_nl.jar を起動

同梱のバッチファイルから起動する場合は
> NicoCache_nl.bat debug

javaコマンドから直接起動する場合は
$ java -Ddareka.debug=true -Ddareka.logfile=debug.log -ea -jar NicoCache_nl.jar


■ドキュメント

documents フォルダにオリジナルの Readme を同梱してます。

  Readme.txt        : NicoCache本家 (by えいさあ氏)
  Readme_dev.txt    : NicoCache本家の開発者向けメモ (by えいさあ氏)
  Readme.nl.txt     : NicoCache_nl本家 (by ルナン氏)
  Readme_nl_(9).txt : NicoCache_nl改造版 (by いつもの人)
  readme_swfConvert.txt : ββベースの改造版 (by swfConvertの人)
  ReadmeGUI.txt     : NicoCacheGUI本家 (by HB.pencil氏)

NicoCache_nl+mod の更新履歴に関して同梱の ChangeLog.txt を参照してください。
本体の解説などは NicoCache_nl wiki (http://crus.biz/nicocache_nl/) を
参照してください。(中の人ありがとうございます)

なお、NicoCache_nl (9).12 付属の Readme.html および documents/html/ 以下は
メンテできそうにないので同梱していません。(ごめんなさい)
今後は NicoCache_nl wiki の方を更に充実させるということで勘弁してください。


■各種設定

defaults フォルダに以下の設定ファイルを同梱しています。
設定の説明とデフォルト値を記述しているので一通り目を通しておいてください。

  00_NicoCache.properties        : NicoCache 本家の設定
  10_NicoCache_nl.properties     : NicoCache_nl 独自の設定
  25_NicoCache_nl_NEW.properties : 改造版で追加・変更した設定

設定ファイルは辞書昇順で順番に読み込まれるので、最後に読み込まれた設定値が
有効になります。また、上記の設定ファイルはリードオンリーなので編集はできません。
設定値を変更する場合は config.properties ファイルに転記・修正してください。
※config.properties が一番最後に読み込まれるので、こちらの値が有効になります


■標準フィルタ

nlFilters フォルダに以下の標準フィルタを同梱しています。

  01_globalFilter.txt
  05_topBarFilter.txt
  10_thumbInfoFilter(ポップアップリンク用).txt
  15_thumbInfoFilter(基本).txt
  20_watchFilter.txt
  99_3列Filter+mod.txt

標準フィルタは基本的に最低限の対応しか行いません。ただし、99_3列Filter+mod は
追加機能の使い方サンプルを兼ねているので、多少複雑な処理を行っています。

標準フィルタの内容が気に入らない人は、各自で勝手に改造してください。
ただし、その場合はトラブルを避けるために*ファイル名を変更*してください。
標準フィルタと内容がバッティングする場合は、config.properties に
nlFilterIgnore= を記述して標準フィルタを読み込まないようにしてください。


■起動スクリプト

以下の jar 起動スクリプトを同梱しています。

  NicoCache_nl.bat  : Windows用のバッチファイル
  NicoCache_nl.sh   : Unix系OS用のbshスクリプト

起動スクリプトは以下の環境変数を参照します。

  NICOCACHE_JAVA  : jar 起動に使用する java コマンド(設定しない場合は java)
  NICOCACHE_OPTS  : java コマンドに渡すオプション(設定しない場合はデフォルト値)

スクリプトの引数でも java コマンドに渡すオプションを指定できます。
また、最初の引数に debug を指定した場合はデバッグモードで起動します。

スクリプトが exitStatusIfJarModified で指定した終了コードを検出した場合、
(jar を上書き更新した場合等) NicoCache_nl を再起動します。

※ Windows で jar をダブルクリックして起動する場合は機能しないので注意


■GUI起動対応

NicoCache_nl+100118mod から NicoCacheGUI 相当の機能を内蔵しています。
また、Windows 環境ではシャットダウン等の対策として JNI 用 DLL を同梱しています。
GUIを使う人はシステムの詳細設定等から以下のディレクトリを PATH に追加して下さい。

  C:\Program Files\Java\jre6\bin  ※Java7の人は適時読み替えて下さい

Java6以降でSystemTrayをサポートする環境の場合はNicoCacheGUIとほぼ同じ動作です。
Java1.5やSystemTrayをサポートしない環境の場合はログウィンドウのみで動作します。

GUI起動するかどうかはコンソールの有無(System#console()==null)で判断します。
コンソールが存在する場合の動作は今までと同じです。

GUIモードの起動方法:

Windows の場合は NicoCache_nl.jar をダブルクリックで起動
※起動オプションを指定する場合は javaw.exe のショートカットを作って記述する
>"C:\Program Files\Java\jre6\bin\javaw.exe" -Xmx128m -jar NicoCache_nl.jar
# 作業フォルダを NicoCache フォルダに変更するのを忘れずに

Unix 系の場合は出力をリダイレクトしてコンソールが無い状態で起動
$ java -jar NicoCache_nl.jar >/dev/null 2>&1 &

Java1.5の場合はコンソールの有無を判別できないのでオプション指定で起動
> javaw -Ddareka.gui=true -jar NicoCache_nl.jar

コンソールは無いけどGUI起動したくない場合も同様にオプション指定で起動
> java -Ddareka.gui=false -jar NicoCache_nl.jar

NicoCacheGUI.property の設定値 (nlを終了してから編集すること)

NicoCacheGUI に元からある設定値
　LogWindowX/Y/W/H     ログウィンドウの位置と大きさ
　LogWindowAlwaysOnTop ログウィンドウを右端で折り返す
　LogWindowLineWrap    ログウィンドウを常に最前面に表示
　OutputFileLog        使っていません
　SystemExitWaitMS     使っていません

NicoCache_nl+mod で追加した設定値
　DebugMode        デバッグモードで起動(true/false)
　DebugLog         デバッグログのファイル名(空なら出力しない)
　ExitOnClose      ログウィンドウの「閉じる」ボタンで終了(true/false)
　FlipColor        ログウィンドウの前景色と背景色を反転(true/false)
　FontName         ログウィンドウのフォント名(Javaに渡す名前なので注意)
　FontSize         ログウィンドウのフォントサイズ
　HideWindow       起動時にログウィンドウを表示しない(true/false)
　MaxLines         ログウィンドウの最大行数
　DisableSuspend   サスペンド移行禁止(true/false) ※Windows DLL利用時のみ


■開発者向け

添付の development.zip に Ant 用の build.xml、Eclipse 用のプロジェクトや設定、
Extension のサンプル等をまとめてあります。NicoCache フォルダに展開してから、
> ant extract
で jar から NicoCache_nl 本体のソースのみ抽出できます。

コマンドラインから jar を作るのと、Eclipse 上から jar を作るのではコンパイラ等の
違いから jar サイズが若干異なります(配布用は Eclipse 内蔵コンパイラでビルド)
# 自動ビルドを止めれば一致するけど、デメリットの方が大きいので…

Eclipseでビルドする方法:

1. 以下から適当なバージョンの zip を取得＆展開して Eclipse を起動
　Pleiades All in One 日本語ディストリビューション
　　http://mergedoc.sourceforge.jp/

2. [ファイル]→[インポート]を開き、ワークスペースにプロジェクトを追加
　一般＞既存プロジェクトをワークスペースへ＞ルート・ディレクトリーの選択
　　[参照...] から development.zip を展開したフォルダを選択

3. [プロジェクト]→[すべてビルド] を実行すれば NicoCache_nl.jar が出来ているはず
　※エラーや警告がある場合は下の「問題」タブに随時表示される

4. [ウィンドウ]→[設定] を開き、以下の項目を設定しておくと良い
　一般＞エディター＞テキスト・エディター
　　「印刷マージンの表示」をチェック(横80桁の目安に)


■謝辞

以下の各バージョンをベースにしています。各作者様ありがとうございます。

  NicoCache v0.45
  NicoCache_nl 秋.01 (based on NicoCache v0.37)
  NicoCache_nl 秋.01 (based on NicoCache v0.43) +(9).12
  NicoCacheGUI ver0.09 fix20090925

NicoCache スレの情報を色々と参考にしています。ありがとうございます。
また、本改造版で追加・修正した部分のライセンスは本家 NicoCache に準じます。

本改造版を更に改変して公開する場合、ベースとなるバージョンを明記してください。
 ex1) 細かな修正 → NicoCache_nl+YYMMDDmod +fix
 ex2) 大幅な変更 → NicoCache_xx ver.XX (based on NicoCache_nl+YYMMDDmod)


■今後の予定

日常的に使っていて見つけた不具合や不便な部分は随時修正しています。
NicoCache スレで強く要望が出た場合は TODO に追加しますが実装するかは未定です。

そのうちやるかも
・extensionsフォルダの更新監視と再起動
・ログウィンドウ検索(デバッグ時にたまに必要なだけで優先度は低い)

絶対やらない
・自動更新機能(仕組み的にもリソース的にも無理)
・全部入りパッケージ(必要な人がライセンスを守って自分でやれ)
