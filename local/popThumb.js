// uses with NicoCache_nl
//	動画IDにカーソルを合わせたときにサムネ情報を表示
// <history>
//	2008/02/18 点滅しにくいように修正（thx >>258@part2）
//	thumbのアドレス変更に対応
//	2009/11/04 公式動画のリダイレクトに対応
//	2009/12/09 公式動画が複数ある時に問題があったので修正
//	2009/12/27 ポップアップが上バーと重なるのを修正(thx >>544@part8)
//	2010/07/21 pupThumb2で書き換えたol_delayを復帰するようにした
//	2010/10/10 生放送(lv～)に対応
//	2010/11/02 チャンネル(ch～)に対応
//	2010/11/13 Prototype.jsを使わないように＆公式動画のエラー処理を入れた
//	2011/01/07 ユーザー生放送(watch/co～)に対応
//	2011/02/19 公式動画対応は連続アクセス規制になることがあるので廃止
//	2011/07/02 静画(sg|im|mg)に対応
//	2018/02/08 HTTPSページでのポップアップに対応(埋め込みAPIが提供されているもののみ)
//	2018/03/13 コミュニティのHTTPS埋め込みに対応
//	2018/04/06 チャンネルの埋め込みURL変更に対応
//	2018/04/20 すべてhttpsで接続できるようになったので制限を解除
//	2023/07/17 公式動画(so～)のポップアップ禁止を解除
var popThumb_sy = 50;

function popThumb(path, sticky) {
	// Fxのときwatchでツールチップがflvplayerとかぶると後ろに行っちゃうので対策
	var isWatch = location.href.match(/watch/);
	var domain = path.match(/^embed\/lv\d+$/) ? 'live' :
				 path.match(/^thumb_community/) ? 'com' :
				 path.match(/^thumb_channel/) ? 'ch' :
				 path.match(/^thumb\/(sg|im|mg)\d+$/) ? 'ext.seiga' : 'ext';

	path = path.replace(/^(thumb_channel)\/([^/]*)$/, "$2/$1");
/*	// 公式動画の場合はリダイレクト先からスレッドIDを取得する
	if (path.match(/thumb\/(so\d+)/)) {
		if (location.host != "www.nicovideo.jp") {
			return overlib("wwwドメイン以外から公式動画(so～)のポップアップはできません");
		}
		var smid = RegExp.$1;
		// watchページに何度もアクセスするとアクセス規制になるので
		// 初回のみリダイレクト先から取得して保存しておく
		if (!window.popThumbPath) {
			window.popThumbPath = new Object();
		}
		if (!popThumbPath[smid]) {
			var nicohistory = "";
			if (document.cookie.match(/nicohistory=([^;]+)/))
				nicohistory = RegExp.$1;
			var xhr = null;
			if (window.XMLHttpRequest) {
				xhr = new XMLHttpRequest();
			} else if (window.ActiveXObject) {
				try {
					xhr = new ActiveXObject("Msxml2.XMLHTTP");
				} catch (e) {
					try {
						xhr = new ActiveXObject("Microsoft.XMLHTTP");
					} catch (e) {}
				}
			}
			if (xhr != null) {
				xhr.open('GET', "/watch/" + smid, false);
				xhr.onreadystatechange = function() {
					if (xhr.readyState != 4) return;
					if (xhr.responseText.match(/so\.addVariable\("v",\s+"(\d+)"/)) {
						popThumbPath[smid] = "thumb/" + RegExp.$1;
					} else {
						popThumbPath[smid] = 'failed';
					}
					// 履歴に残らないようにnicohistoryを書き戻す
					var d = new Date();
					if (nicohistory.length > 0) {
						d.setTime(d.getTime() + 1000 * 60 * 60 * 24 * 365);
					} else {
						d.setTime(0); // nicohistoryが無ければ削除
					}
					document.cookie = "nicohistory=" + nicohistory +
						"; expires=" + d.toGMTString() + "; domain=.nicovideo.jp; path=/;";
				}
				try {
					xhr.send(null);
				} catch (e) {}
			}
		}
		if (!popThumbPath[smid] || popThumbPath[smid] == 'failed') {
			return overlib("watchページからスレッドIDが取得できませんでした");
		}
		path = popThumbPath[smid] || path;
	}*/

    // 変数に値を設定
	ol_hauto = 1;
	ol_vauto = !isWatch;
//	if (isWatch) {
//		ol_vpos = ABOVE;
//	}
	ol_offsetx = 20;
	ol_width = 312;
	ol_height = 176 + (domain=='ext' ? 20 : 0);
    ol_fgcolor = "white";
    ol_bgcolor = "#cccccc";
	ol_border = 5;

	// watchで上バーに重ならないように
	if (isWatch) {
		var popThumb_scy = (olIe4) ? eval('o3_frame.'+docRoot+'.scrollTop') : o3_frame.pageYOffset;
		olMouseCapture();
		ol_offsety = - (ol_height + 26);
		if (ol_offsety + o3_y < popThumb_scy + popThumb_sy) { ol_offsety += popThumb_scy + popThumb_sy - (ol_offsety + o3_y) ; }
	}

	ol_close = "閉じる";
	ol_closeclick = 0;

	ol_sticky = sticky;
	ol_cap = sticky ? path : '';

    ol_text = '<iframe width="' + ol_width + '" height="' + ol_height + '" src="' + location.protocol + '//' + domain + '.nicovideo.jp/' + path + '" scrolling="no" style="border:solid 1px #CCC; z-index:500;" frameborder="0">'
	        + '</iframe>';

    // ツールチップを表示
    return overlib();
}

function popThumb2(path, sticky, wait) {
	var delay_orig = ol_delay;
	if (wait != undefined) {
		ol_delay = wait;
	}
	if (path.match(/^(mylist|user|community|channel)\//)) {
		path = "thumb_" + path;
	} else if (path.indexOf("co", 0) == 0) {
		path = "thumb_community/" + path;
	} else if (path.indexOf("ch", 0) == 0) {
		path = "thumb_channel/" + path;
	} else if (path.indexOf("lv", 0) == 0) {
		path = "embed/" + path;
	} else if (path.indexOf("watch/co", 0) == 0) {
		path =  "thumb_community/" + path.substring(6);
	} else if (path.indexOf("watch/ch", 0) == 0) {
		path =  "thumb_channel/" + path.substring(6);
	} else if (path.indexOf("watch/lv", 0) == 0) {
		path = "embed/" + path.substring(6);
	} else if (path.indexOf("watch/", 0) == 0) {
		path = "thumb/" + path.substring(6);
	} else if (path.indexOf("seiga/", 0) == 0) {
		path = "thumb/" + path.substring(6);
	} else {
		path = "thumb/" + path;
	}
	var result = popThumb(path, sticky);
	ol_delay = delay_orig;
	return result;
}


// - mouseoverしていた要素が消失するとmouseoutを捕まえられないため
//   動画切り替えのイベントを捕まえて消す
// - Name = ページ遷移時ポップアップ消去(watchページ HTML5)
// - ndはoverlib_mini.jsで定義される.
//   popThumb_FAが導入されている場合はそれが上書き定義する.
if (window.NicoCache_nl !== undefined
    && window.NicoCache_nl.watch !== undefined
    && window.nd !== undefined) {
  NicoCache_nl.watch.addEventListener('videoChanged', function() { setTimeout(nd, 0); });
};
