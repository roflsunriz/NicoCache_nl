NicoCache_nl (modified NicoCache)
                      http://nicolist.net/nicocache_nl/

* NicoCache_nlって何？

nicolist.netによる、NicoCacheの拡張版です。
本来の機能については Readme.txt の方を参照してください。

* 増えた機能

出力中に、JavaScriptなどの任意のタグを埋め込みます。
これを利用して、余計なオブジェクトの削除とか、配置の移動とかができます。
オミトロン使うほどでもないし、折角NicoCacheでプロキシかませてるしー、
って人にお勧めかもしれません。
他にも。。。
　・キャッシュした動画の管理機能
　・flvplayer_wrapper.swfを利用できるようにします
　・flvplayer_wrapperのローカルFLV再生で、削除された動画もキャッシュから再生できます
　・レジュームダウンロードなどの帯域節約機能
　・拡張によるユーザビリティ向上などの様々な機能
　・他にも色々！

* 設定

config.properties.default にデフォルトの設定と説明が書かれています。
config.propertiesが無い状態で起動した場合は、config.properties.defaultが
自動的にリネームされて使用されます。
どちらもない場合は、標準の設定を使います。

* 設定の説明

allowFrom
	NicoCacheへのアクセス制限。
		local	自PC
		all		全IP
		lanA	プライベートIP (10.xxx.xxx.xxx）
		lanB	プライベートIP（172.16.xxx.xxx 〜 172.31.xxx.xxx）
		lanC	プライベートIP（192.168.xxx.xxx） ←普通これ
		lan		lanCと同じ

scriptOn
	埋め込みを行うかどうか。
	値：1 = 行う, 0 = 行わない

scriptTarget
	埋め込み対象のURI。
	一応正規表現対応だけど、コンパイルエラーとかの処理してないので注意。
	また、HTML出力以外に誤爆すると確実に変なことになるのも注意。
	例：/watch/[^ ]+
		/(watch|mylist|tag)/[^ ]+

scriptText
	埋め込むHTMLテキスト。=記号を \ でエスケープする必要あり。
	外部ファイルを使わない場合はそのまま書くだけ。
	外部ファイルを使う場合は、localフォルダを作成してその中にスクリプトを放り込んだあと、
	/local/script.jsのように、/local/を読み込むHTMLを書く。

flvWrapper
	true にすると、使用するプレイヤーが flvplayer_wrapper になるようにします。
	localフォルダに flvplayer_wrapper.swf を入れてください。
	注：旧flvplayer.swfもlocalフォルダに入れてください。
		無いと再生できません。入手先は各自で。
	値：true = flvplayer_wrapper(RC1)を使う, rc2 = rc2対応版を使う, false = ラッパを使わない,
		rc2_XXX = XXXに数値を指定すると、高さを変えれます。

localFlv
	trueにすると、ローカルFLV再生機能が利用できます。
		flvplayer_wrapperの「ローカルFLV再生サーバを使う」をOn
		その下のテキストボックスの上のみ（上下でもおｋ）に
			http://www.nicovideo.jp/cache/
		と指定してください。
	実際に上記URIへアクセスされることはなく、NicoCache_nl内で全て処理されます。
	また、動画ページのユーザバーにもリンクが設置され保存等が簡単に行えます。
	
smartCookie
	！これじゃ対策にならないみたいです。気休め？！
	trueにすると、クッキー地獄の対策を行います。
	具体的には、「最近見た動画」の数を強制的に25個に減らすことで、
	クッキーのサイズを減らしてるだけです。
	私は1回しかなったことがないので、これでOKかは不明です。
	副作用として、「最近見た動画」で見れる履歴が25個になっちゃいます。
	
touchCache
	trueにすると、キャッシュの動画を読み込んだ際に、更新時間を最新にします。
	エクスプローラで見た順に並び替えるなどの用途にどうぞ。
	値：true = 有効, false = 無効

continueDownload
	trueにすると、動画をニコニコから転送中にブラウザを閉じた場合も、
	裏でダウンロードを続ける。
	一度ページを開いて閉じて、あとでDL終わってから見るなどできます。
	値：true = 有効, false = 無効
	
resumeDownload
	trueにすると、中途半端なキャッシュでも保持しておき、
	次に必要になったときに、残りの部分からダウンロードします。
	主に、continueDownload=falseの時用だけど、併用も一応できます。
	値：true = 有効, false = 無効

NGWORDtxt
	オミトロンのNGWORD.txtへのパスを指定する。
	その際、 \ は \\ と2つに増やしてください（エスケープしないと変になる）
	詳しい利用法は Add NGWORD.txt の方を参照してください。

* /local なURIについて

nicocache_nl.jarと同階層にある local フォルダの中身を呼び出すことができる。
例：
	/local/script.js   =>  local\script.js を参照する
	/local/img/1.gif   =>  local\img\1.gif を参照する

* /cache なURIについて

	1.http://www.nicovideo.jp/cache/
		のみで呼び出すと、キャッシュしてる動画の一覧を返します。
		キャッシュ管理機能つき。
	2.http://www.nicovideo.jp/cache/smXXXX.flv
		と呼び出すと、動画を送信します。
	3.http://www.nicovideo.jp/cache/rm?smXXXX
		と呼び出すと、指定したIDのキャッシュを削除します。
	他にもあります。cache_ajax.txtを参照してください。
	（どれも、ニコニコ動画(RC)のサーバには接続せずローカルで完結します。）

* 履歴

Webページ参照

* 謝辞

NicoCacheを公開してらっしゃる えいさあ様 へ感謝を申し上げます。
http://homepage1.nifty.com/asr/

* お約束

NicoCache_nlで追加された機能について、本来の作者様に問い合わせないでください。

----
ルナン
http://nicolist.net/