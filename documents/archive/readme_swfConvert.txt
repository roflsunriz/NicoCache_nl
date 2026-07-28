■これは何？

ニコ動の新プレイヤー導入に伴い、SWFファイルが新旧プレイヤー用で異なるようになったので、

・旧SWFキャッシュを新プレイヤーで再生→再生できない
・新SWFキャッシュを旧プレイヤーで再生→動画の制御が効かない

といった不具合を改善するために、SWFキャッシュ変換を実装したNicoCache_nlの改変版。

元々はwrapperで時報ニコ割を正しく再生する為のExtensionを作れないかと試行錯誤していた
ものなんだけれど、ちょっと挫折気味なのでSWFキャッシュ変換に的を絞ってみた。ところが、
Extensionだとキャッシュ再生に割り込めない事が分かったのでnl本体に実装したもの。

基本動作は、SWFキャッシュから再生する時に新旧どちらのURLへのアクセスかを判別して、
キャッシュ内のSWFファイルを正しく再生できる形式に変換してプレイヤーに渡す。この動作は
リクエスト毎に行うもので、SWFキャッシュ自体の置き換えは行わない。また、URLからの直接
変換はレジュームとか絡んで複雑なのでやらない(つーか出来ない…)。

その他にも細々とした改変を加えています(既にSWF変換以外の部分が多いかも)。


■改造元とか

	NicoCache_nlββ.06c +nl167
	NicoCache 0.43
	flvplayer_wrapper_mod 2009-07-11

nl最新版にnl167.zipのMp4パッチを参考に音声抽出周りを改変、SWFキャッシュ変換を実装、
本家0.43をマージ、その他にも細々とした改変を加えています。

あと、Marquee(ニコニコニュース等)を常に読み込むwrapperも同梱してみた。wrapper_modは
2009-01-23より後の修正で特定条件でしかMarqueePlayerを読み込まないようになったみたい
なので、個人的に2009-01-23版の処理に戻して使っていたもの。これも最新版に適用。

ニコニコニュースだけ必要でニコ割は不要な人は、以下のフィルタを追加すると良いかも。

[Replace]
Name = ニコ割とか削除
URL = (www|ext).nicovideo.jp/api/getmarquee
Multi = TRUE
Match<
(?s)<s_marquee_\d+>.+?</s_marquee_\d+>
>
Replace<
>

更におまけで、普段は新プレイヤーだけどwrapperも使いたい人向けに、oldplayer=1で
アクセスした時にwrapper再生に置換するフィルタを同梱。


■設定とか

defaults/01_swfConvert.properties から必要なものを config.properties に追加。
デフォルト無効なので、最低限 swfConvert=true だけは有効にしないと動作しません。

また、旧プレイヤーのサポート終了に伴い、新SWFしか取得できない動画が出てきたので、
キャッシュ内のSWFはデフォルトで新SWFとみなすようにしています(swfCacheV3=true)。
新プレイヤーをメインで使う人は、newPlayerSet v0.2 同梱の SwfConverter で旧SWFを
新SWFに一括変換しておいたほうが良いでしょう。


■実装詳細

SwfConvertResource という URLResource を拡張したクラスを追加しています。
キャッシュ再生時、SWFの場合はこのリソースを返すように変更。

変換内容自体はnewPlayerSet同梱のSwfConverterと同じで、
	AVM1->AVM2の場合はFILEATTRIBUTESを付加してDOACTIONを除去
	AVM2->AVM1の場合はFILEATTRIBUTESを除去して最初と最後(*)にDOACTION追加
詳しくはコード参照のこと。
*) 正確には最終フレームの1つ前のSHOWFRAMEの後

なお、SWF変換はストリーム処理が前提なので変換前にサイズを確定する必要があります。
そのため、予め増える可能性のあるサイズ分だけ下駄を履かせています。SWF的にはファイル
末尾の0はENDタグなので読まれませんが、実際のサイズより若干大きくなります。

また、LocalFLVアクセス時はSWF変換を行いません。LocalFLVは主に動画ファイルを
保存する目的でアクセスするので、SWF変換を通してファイル末尾に余計な0が付くのを
防ぐためです。ちなみに、wrapperからアクセスした場合はpathが異なる為なのか
動画の制御が効くっぽいです(AVM2で読み込まれてる？)。

Extensionで更にサブクラス化する事で、時報ニコ割も変換できるようにしたつもり。
とりあえず時報のループを抑止する(つーか、PLACEOBJECT2を削除しただけの)ものを
同梱してますので、誰かDOABC->DOACTION変換を実装してください…。

JavaもFlashも素人同然なので、使いづらい実装になっている可能性あり。特にFlashは
今回初めて弄ったから実際どーなんだろ？


■NicoCache本家のマージについて

これまで、NicoCache_nlはv0.37ベースといいつつソースレベルではv0.29あたりだったのですが、
本家・nl共に落ち着いて来ているので、v0.43ベースにして可能な限りマージしました。

一番大きな変更は、nlのタイトルキャッシュ(NicoCachingTitleRetrieverに実装)を止めて、
本家のNicoIdInfoCacheを使うようにした事です。nlのタイトルキャッシュは上限無しにメモリに
キャッシュして行くので、長期間稼働させていると徐々にメモリが減っていきます。
一方、本家の方のNicoIdInfoCacheは上限が設けてあるので、古いものから消えていきます。

なお、Extension互換性維持のためNicoCachingTitleRetriever.putTitleCache等は残しています。
これらはNicoIdInfoCacheへのラッパーとして機能します。また、NicoRecordingXxxに関しては
Rewriterと競合するので取り込んでいません。これらの機能はRewriter側で対応しています。

本家0.43で追加されたResource#stopTransferには、一時ファイルを作るSwfConvertResourceと
TemporaryFileResourceに対応を入れました。主に強制中断した時の後始末処理です。

本家とのdiffを取りやすくするために、nl側で追加したコードの位置を調整しています。
こうする事によって、WinMerge等でdiffを表示した時に見やすくなっているかと思います。
ソースのインデントは本家が空白4文字、nl側は主にタブ文字です。

ソースを整理してEclipseで警告が出ている部分を解消しています。更に、長期間コメントアウト
されて使われていないコードを削除しています(特にββ以降でnlFilterに追い出した部分)。

自分はJavaやWeb系に詳しくないので、本質的なレベルでミスをしている可能性があります。
あと、最近は実行環境をLinuxに移行したので、Windowsで問題が出る可能性があります。


■dareka.common.Configの整理について

過去にConfigのインスタンスに設定値を直接格納していた名残からなのか、現在のNicoCacheでは
設定値の取得方法が複数あります。例えば、proxyPortの値を参照するには以下の方法があります。

	1.Config.proxyPort
	2.Config.getInteger("proxyPort", 8081)
	3.Integer.getInteger("proxyPort", 8081).intValue()
	4.Integer.getInteger("proxyPort").intValue()

1.は@Deprecatedを付けて非推奨としているので、他の値も同様に廃止の方向で良いと思います。
2.と3.は同意です。3.は一度Integerへの変換が伴いますが、Config程度なら大差は無いでしょう。
4.はデフォルト値を取らないものですが、仮にproxyPortが存在しないとぬるぽになってしまいます。

また、デフォルト値の持ち方についても、本家はdefaults以下にプロパティファイルを格納して
保持していますが、defaultsが無い場合に備えてコード中にも同じ内容を保持しています。
更に、同じ内容をconfig.propertiesにも記述できるので、nlユーザーの中にはdefaultsの存在を
知らない人も居るんじゃないかと思います(自分もその一人だったり…)。
かつ、Config.getXxxを使ってデフォルト値指定付きで取得しているので、コード中のあちこちに
デフォルト値が重複して分散しているという状態です。

そこで、Config#setDefaults(Mainから移動)でデフォルト値をまとめて設定するように変更して、
更にConfig#setPropertyを用意して値の検証を行うようにしました。この変更により、Configを
動的に更新できるようになるので、config.prorertiesの更新チェックをするようにしてみました。

今後、新規に設定値を追加する場合は以下の手順を推奨します。

	・defaultsフォルダにNN_FeatureName.propertiesを作ってデフォルト値を記述
	・デフォルト値が必須なものはConfig#setDefaultsにもデフォルト値を設定
	・設定値のチェックが必要な場合はConfig#setProperty内でチェック
	・値の取得はなるべくjava.langにあるものを使う(Config.getXxxは使わない)
		真偽値 Boolean.getBoolean("propertyName")
		文字列 System.getProperty("propertyName")
		整数値 Integer.getInteger("propertyName")


■新サムネキャッシュ方式について

最近、サムネ表示で404が多発しているので、サムネ取得でエラーになった場合は別の鯖から
サムネを取得するようにしてみました。(tn-skrNでエラーになったらN+1,N+2,...,N-1まで)

そのため、従来はサムネキャッシュが無い場合は動画と同様にブラウザへの転送に対して
TransferListenerでキャッシュしていましたが、新方式では一旦NicoCache_nl側でサムネを
取得してキャッシュし、ブラウザへは常にキャッシュを返すようにしました。

最初はthcache.datを効率的に扱うよう実装していたんですが、MappedByteBufferを使うと
2GB制限があるのと、従来からthcache.datだとサムネキャッシュの管理が出来ないことが
不満だったので、ファイル単位で保存するようにしてみました(その方が実装簡単だし(^^;)。

なお、単一フォルダだとファイル数が膨大になるので、10万ID単位でサブフォルダを作って
保存しています。ただ、サムネは4KB強のものが多く1クラスタに収まらないものが大半なので、
ディスク使用率はthcache.datの時よりも1.5倍増しになる可能性があります。

また、一時的に多数のファイルをオープンするので、Unix系では"Too many open files"が出る
事があるようです。どうやら、swfConvert以前からfdを掴んで離さないケースがあるみたい。
ググってみると(古い情報ですが)setSoTimeoutが怪しいようで、タイムアウトを実現するために
FIFO(r/w)+eventpoll(fd3つ分)を使っている部分がリークしているっぽいですね。
readTimeout=0にするとタイムアウトを設定しないようなので、Unix系で問題が出るようなら
試してみると良いかも。ブラウザへのタイムアウト設定なので無限に待つことは無いと思うし。

従来方式ではサムネをキャッシュから返す場合にLast-Modifiedヘッダを付けませんでしたが、
新方式では各キャッシュファイルの更新時刻をサムネ鯖が返すLast-Modifiedに設定する事で、
キャッシュからサムネを返す場合にもLast-Modifiedを付けるようにしました。こうする事で
ブラウザ側がIf-Modified-Sinceヘッダを付ける事ができ、またnl側も304を返す事出来るので、
無駄なキャッシュファイル本体へのアクセス、および実体転送が減るかと思います。

完全に別実装なので、thcacheMode=folderを指定しなければ従来方式がそのまま動きます。
新方式はテーブルを作成せずにファイルの有り無しでキャッシュの有無を判断しているので、
少なくともthIndex.datのサイズ分はメモリの消費量が減っていると思います。コード量的にも、
thcache.datの変換も含めて従来の半分程度なので結構シンプルです。あと、従来コードには
殆ど手を入れていないので、nl起動中にthcacheModeを弄るのは止めた方がいいです。

代替サムネは主に(古くてサムネが残っていない)削除された動画用に実装しましたが、未だ動画の
存在しないIDを指定した場合も、当然ながらサムネ鯖が404を返すので機能してしまいます。
普通にニコ動を見ているだけならまず有り得ませんが、フィルタやツール類の不具合でそのような
ケースが発生する可能性もあります。そういう場合は該当サムネを削除して再取得してください。


■URLResource#setProxyMyselfについて

Extensionでnl本体を通してURLを処理したい場合(ex. nl本体にキャッシュ処理させたい、
他のExtensionのAPI処理結果が欲しい、等)、URLResource#setProxyで自分自身を設定すれば
一見正しく動きます。しかし、nl内部で以下の不具合が発生するようです。

	1. setProxyしたURLと同一ドメインに対する次回のリクエストでプロキシ設定が残る
	2. 自分自身からのリクエストを処理した後もworkerスレッドがKeep-Alive処理の状態で残る

上記のうち、1.はかなり深刻でRewriter対象のURLだとnlFilter二度掛け問題が発生します。
2.の方は入力待ちがタイムアウトすれば、リクエストヘッダ処理のエラーでそのうち消えます。

調査したところ、1.はtransferToの最初でURL#openConnection(Proxy)しているにもかかわらず、
同一ドメインに対する最初のURLConnection#connectで何故か以前のプロキシ設定が残っている
ようです(URLConnectionがプールされている為？)。2.はプロキシに繋ぎに行く時にHTTP/1.1で
アクセスするのですが、HopByHopヘッダを除去するので常にKeep-Alive状態になるのが原因です。

対策方法ですが、1.はプロキシを変更した場合は、転送終了後に元のプロキシ設定に戻して
一度connectしてやれば(この時点では前のプロキシ設定が残っている)、その次のconnect時には
正しく反映されるようです。2.は自分自身をプロキシ設定する場合はリクエストヘッダに
"X-NicoCache_nl: proxy-myself"を付加してリクエストし、プロキシ側のConnectionManagerが
リクエストを処理する前にこのヘッダを取得＆除去して、リクエスト処理後にproxy-myselfが
含まれる場合はKeep-Alive状態を無視にしてやります。

通常処理に対する影響は、1.で条件判定2回、2.でヘッダ取得1回＋判定2回、なので軽微だと
思います。あと、nl本体の修正ついでに専用メソッドsetProxyMyselfを追加しました。
下位互換性を保つためには以下のようにしてください(ただしsetProxyの場合は2.が発生します)。

	try {
		r.setProxyMyself(); // swfConvert07b以降
	} catch (NoSuchMethodError e) {
		r.setProxy("localhost", Integer.getInteger("listenPort"));
	}

互換性が不要ならsetProxyMyselfだけで良いです。


■改変履歴

とりあえず本流にマージされるまで履歴を付けてみる

swfConvert07b
・URLResource#setProxyでプロキシを変更した時に発生する不具合を対策したつもり
　→詳細はreadmeにて
・URLResource#setProxyMyselfを追加した
　→主にExtensionでnl本体を通してURLを処理させたい場合に使用します
・新サムネキャッシュで不具合が出る可能性がある部分を修正
・SwfConvertResourceでメンバ変数名をnoCache→noBrowserCacheに変更
・swfDebug=true時にnlを終了すると"remaining live workers"のURLを表示(デバッグ用)

swfConvert07a
・案の定Firefoxで不具合が出たのでHTTPヘッダ処理を元に戻した＆若干無駄な処理を省いた
　→SP1.02〜.05辺りで二転三転した修正だったようで…orz
　　SP1.02>リクエスト／レスポンスヘッダの処理をUTF-8対応にした。
　　SP1.04>リクエストをUTF-8で解釈するのを廃止 (thx スレの方々)
　　SP1.05>リクエストの1行目以外はUTF-8で解釈することにした (FxでRefererがinvalidになる対策）

swfConvert07
・本家0.43をマージしてNicoCache v0.43ベースにした
　→本家の変更量が結構多いので、もしかしたらマージミスがあるかも知れません
　　HTTPヘッダ処理を(効率良さげな)本家版に変更したので文字エンコード周りで影響あるかも
　　Ctrl-Cで終了時に"internal error: remaining〜"が出るけど実害は無いと思う…
・nlFilterでURL正規表現のグループ参照もReplace内で使えるようにした
　→Replace内で"$URLg"(gは数字)と記述すればURL正規表現のグループ参照gに置換します
　　wrapperReplaceFilterで使っているので参考にしてみてください
　※"$URL0"は非対象、グループ参照不要なURL正規表現は"(?:〜)"を使った方が速くなります
・nlFilter処理中に例外(エラー)が発生した場合もその置換を無視して続行するようにした
　→主にReplace内で'$'をエスケープしなかった場合が該当します
　　今まではエラーになると短時間に再取得が発生するのか連続アクセス規制になっていました

swfConvert06a
・Firefoxの場合に問題が出るのでStringResourceの変更を元に戻した
　→404の場合も"Content-Length: 0"を返してやらないと接続しっぱなしになるようです

swfConvert06
・SearchExtensionでタイトル無し(sm5437157.flvとか)にヒットするとエラーになる不具合を修正
　→swfConvert以前から存在する問題で、".flv"とかで検索すると発生していました
・投稿者コメントの無い(=oldplayer=1指定の効かない)動画でwrapper置換が効いていない不具合を修正
　→旧プレイヤーのサポート中止以降で発生する問題、修正したnlFilter_sys.txtを同梱しました
・http://www.nicovideo.jp/local/ にアクセスするとぬるぽになる不具合を修正
・watchページにアクセスした時にタイトルが取得できない場合もIDのみ記録するようにした
・新サムネキャッシュで全てのサムネ鯖が404を返す時に代替サムネを指定できるようにした
　→サムネが取得できない動画で毎回サムネ鯖にアクセスすることが無くなります
　　デフォルト(thcacheReplace404=local/thumb404.jpg)が気に入らない人は適当に変更してください
　　また、代替サムネは対応する位置にコピーするので切り替えが出来るわけではありません
・新サムネキャッシュでthcacheFixLastModified→thcacheFixEpochに名前変更
・nlFilterの更新チェック間隔を毎回から1秒毎に間引くようにした
・local/以下にもLast-Modifiedを付加出来るようにした(localDirAddLastModified)
　→ファイルを過去のバージョンに戻す事もあるので、時刻が一致した場合のみ304を返します
・Extension2の場合もシステムプロパティに extension.<className>=true を登録するようにした
・StringResourceでメッセージボディが無い場合(304とか)は"Content-Length: 0"を返さないようにした
・ファイル関連の処理をユーティリティクラスに分離した(dareka.common.FileUtil)
　→既存処理を置き換え、対象となるのはswfConvertで追加した部分のみです
・readTimeoutが0以下(負の値も含む)の場合はsetSoTimeoutを行わないようにした
　→今まで負の値でもセットしようとしてExceptionが発生していた＆Unix系のfdリーク回避策
　　Unix系で"Too many open files"が出る場合はreadTimeout=0にしてみると良いかも
・その他、こまごまとした修正

swfConvert05
・defaultsフォルダが無いとconfig.propertiesが読み込まれない不具合を修正
・(主にUnix系で)ファイルシステムを跨いだstoreFilterが失敗する不具合を修正
　→swfConvert以前から存在する問題で、移動に失敗してキャッシュファイルも消えていました
・Content-Encodingに(本来使われないはずの)identityがあると警告メッセージが出るのを抑制
・SWF変換で展開後のサイズ+118がswfConvertMemoryLimit*1024を跨ぐとぬるぽになる不具合を修正
・swfConvert=trueの時はSWFをブラウザにキャッシュさせないようにした
　→新旧プレイヤー切り換え対策(nlMarqueeConverterから移動しました)
・(リネームする人が居たので)設定サンプルをdefaults/01_swfConvert.propertiesに移動
　→値を変更する場合はconfig.propertiesに追加してください
・新サムネキャッシュのログ出力を失敗時のみに抑制
・新サムネキャッシュで数字無し鯖(tn-skr.smilevideo.jp)に対応
・新サムネキャッシュでサムネ毎にLast-Modifiedを返すようにした
　→各キャッシュファイルの更新時刻を毎回更新するのを止めて鯖が返すLast-Modifiedにしました
　　thcache.datから変換したものはepoch(1970/01/01 00:00:00 GMT)に設定します
　　swfConvert04で作成済みのものに関してはUnix系なら以下のような感じでepochに設定出来ます
　　　　$ find thcache -type f -print0 | TZ=GMT xargs -0 touch -t 197001010000
　　※別に無理に更新日時をepochに設定し直す必要はありません
　　epochのままだと気持ち悪い人はthcacheFixLastModified=trueを指定すると次回取得し直します
※LocalFLV時のSWF変換はファイル保存時に問題があるのでやっぱり見送りました

swfConvert04 テスト版

本家0.42マージによるソース全体の修正規模が大きいのでテスト版としています。
新サムネキャッシュは勢いで実装したのでテスト不十分です。使う人は注意してください。

・flvWrapperの値にかかわらずキャッシュ内のSWFを新プレイヤー用(V3)とみなすようにした
　→念のためconfig.propertiesで変更可能です(swfCacheV3)
・flvWrapperの値にかかわらずflvplayer_wrapper.swfをlocalから返すようにした
・flvWrapperが有効な時にトップページのおすすめからでも旧プレイヤー再生が効くようにした
　→nlFilterは"URL = www\.nicovideo\.jp/watch/\w{2}\d+\?(?:.+&)?oldplayer=1"としてください
・音声抽出周りを整理してWindows依存のexe存在チェックを止めた
　→コマンドを実行して駄目ならエラーメッセージを出すようにしました
・音声抽出時の一時ファイル作成場所をキャッシュフォルダ直下からシステムテンポラリに変更
・NicoCache 0.42ベースにして可能な限りマージした(nl独自部分と競合するものは除く)
・speedLimitを指定しない時はリスナを登録しないようにした(多少軽くなるはず)
・Configを整理してconfig.propertiesを動的に読み込むようにしてみた
　→nlが動作中でもconfig.propertiesが更新されていればリロードします
　　ただし、設定が動的に反映されるかはその機能の性格に依存します(listenPortとかは駄目)
・ファイルシステムを跨いでキャッシュファイルを移動できるようにした
　→主にUnix系の追加フォルダやシンボリックリンクで有用、Windows系は元から出来ていたかも
・新しいサムネキャッシュ方式を実装してみた(thcacheMode=folder)
　→指定フォルダにファイル単位でキャッシュします(従来よりもディスク使用効率は低下します)。
　　初回起動時にthcache.datがあれば変換しますが、サイズに応じてかなり時間がかかります。
　　自分の環境では 532MB(thcache.dat)->770MB(115,232ファイル) でした。
・niconicoModeのURL判定にnimg.jpを追加
・ごくまれにnlFilters更新チェックでぬるぽになって以降の通信が出来なくなる事があったので対策
nlMarqueeConverter:
・swfCaptureMarquee=trueの時は保存したニコ割をキャッシュ的に利用するようにした
・更に↑の時はブラウザにキャッシュさせないようにしてみた(新旧プレイヤー切り換え対策)
・新旧プレイヤー判断のURL取得をProcessorからRewriterに変更
　→Processorを使ってResourceを返すと後段のRewriter(nlFilter)が効かないので

swfConvert03
・フィルタの文字コード自動判別でBOM付きUTF-8に対応してみた
・連続アクセス規制時にキャッシュタイトルがおかしくなるのを修正
　→watchページを通した時のタイトルキャッシュをステータス・コード200限定にした
・dareka.debug=true時に膨大なキャッシュ読み込みログが出る部分を抑制

swfConvert02
・wrapper同梱忘れた…orz

swfConvert01 初版 [based on NicoCache_nlββ.06c +nl167(Mp4パッチ)]
・新旧プレイヤーを切り換えた時にSWFキャッシュから再生できるようにした
・配信終了削除(getflvでdeleted=を返さない)の再生に対応
　→動画IDとダウンロードURLのIDが一致しない場合は削除とみなすようにした
　　ただし、チャンネル動画等で配信終了メッセージしか表示されないタイプは無理
