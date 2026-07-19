// uses with nlThumbInfoRewriter
//    完全な作者コメをダウンロードしてページを書き換える
//    吹き出しは消す
//        2010/11/03 : /api/getthumbinfo/<id>?nlFilterに対応
//        2010/10/17 : watchページの仕様変更に対応(削除された動画の全文取得)
//        2010/07/16 ::検索ページの仕様変更に対応(last_res取得修正、全文取得時にtitle属性削除)
//        2009/12/20 : 削除された動画のwatchページからの取得がおかしかったので修正
//        2009/12/05 : コミュニティでの取得用にもう一つ引数追加。無くてもおk
//                     数字のみIDを入れて、2つ目の引数が""なら"sm""nm"でトライ
//                     nl123.zip以降に修正していた分をマージ＆getThumbFrmのmarking.js対応
//        2009/12/04 : コミュニティでの取得用に引数追加。getFullDesc(elem_caller,nicom_smid)の二つ目。なくてもOK
//        2009/04/15 : getthumbinfoが駄目ならwatchページから取得＆メモを保持する
//        2009/04/10 : 大幅に書き換えた(検索ページの仕様変更対応とか)
//        2008/12/27 : IE以外でgetFullDescが動かない事があるので修正(nextTag)とか
//        2008/12/08 : 仕様変更とかバグ修正とか by kapaer
//        2008/07/12 : 仕様変更に対応
//        2008/04/19 : コメント欄が消えなくなっていたのを修正
//        2008/02/09 : IE6/Fx1.5/Opera9の動画一覧・ランキングで動作確認

var disableHideComments  = false;
var disableFromWatchPage = false;
var disablePreserveMemo  = false;
function getFullDesc(elem_caller, nicom_smid, nicom_numid) {
	var getThumbFrm = function(elem) {
		while (elem) {
			if (elem.tagName == 'BODY')
				return null;
			if (elem.className && elem.className.indexOf("thumb_frm") > -1)
				break;
			elem = elem.parentNode;
		}
		return elem;
	}
	var getFromWatchPage = function(smid) {
		var desc = null;
		if (!disableFromWatchPage && location.host == "www.nicovideo.jp") {
			var nicohistory = Cookie.get('nicohistory');
			new Ajax.Request("/watch/" + smid, {
				method: "get",
				asynchronous: false,
				onSuccess: function(httpObj) {
					var res = httpObj.responseText;
//					var m = res.match(/class="video_des_tit"><\/td>[^<]*<td[^>]*><p[^>]*>(.+?)<\/p>/);
					var m = res.match(/<div id="itab_description"[^>]*>\s*<p[^>]*>([\s\S]+?)<\/p>/);
					if (m) {
						desc = m[1];
					} else {
						// 管理者削除等で動画説明が存在しない場合
						m = res.match(/<span id="deleted_message_default"[^>]*>.+?<\/span>/);
						if(m) desc = m[0];
					}
					if (nicohistory)
						Cookie.set('nicohistory', nicohistory,
							1000 * 60 * 60 * 24 * 365, '.nicovideo.jp', '/');
				}
			});
		}
		return desc;
	}
	var getThumbDesc = function(smid) {
		var desc = "";
		new Ajax.Request("http://" + location.host + "/api/getthumbinfo/" + smid, {
			method: "get",
			parameters: "nlFilter",
			asynchronous: false,
			onSuccess: function(httpObj) {
				var m = httpObj.responseText.match(/<description>(.+)<\/description>/);
				if (m) {
					desc = m[1].replace(/&apos;/g,"'").replace(/&amp;/g,"&").replace(/&nbsp;/g,"");
				}
				if (desc == "" || desc == "deleted" || desc == "community") {
					var desc_watch = getFromWatchPage(smid);
					if (desc_watch) {
						// プレミアムアカウントのタグ残骸を消す(リンク誤爆も修正)
						desc = desc_watch.replace(/&lt;.+?&gt;/g, "").replace(
							/(<a href="[^"]+)&lt("[^>]*>[^<]+)&lt<\/a>;br \/&gt;/g,
							function(str,p1,p2){ return p1+p2+"</a>" });
					}
				}
			}
		});
		return desc;
	}

	var elem = null, elem_res = null, inner_html = "";
	var thumb_frm = getThumbFrm(elem_caller);
	if (thumb_frm) {
		elem = document.getElementsByClassName("vinfo_description", thumb_frm)[0];
		elem_res = document.getElementsByClassName("vinfo_last_res", thumb_frm)[0];
		inner_html = thumb_frm.innerHTML;
		thumb_frm.style.wordBreak = 'break-all';
	}
	if (!elem) {
		elem = elem_caller;
		while (elem.tagName != 'P' && elem.tagName != 'SPAN') {
			elem = elem.parentNode;
		}
		elem_res = document.getElementsByClassName("vinfo_last_res2", elem.parentNode)[0];
		inner_html = elem.parentNode.innerHTML;
	}
	var smid;
	var m = inner_html.match(/watch\/(\w{2}[0-9]+)/);
	if (m) {
		smid = m[1];
	} else {
		window.status = "getFullDesc() failed.";
		return; // 動画IDが取れない場合は無駄なリクエストをしない
	}

	if (nicom_smid != undefined){
		smid = nicom_smid;
	}
	var desc;
// コミュからのアクセスはsm,nmでテストする
	if (nicom_numid != undefined && smid == "") {
		desc = getThumbDesc("sm"+nicom_numid);
		if (desc == "not found or invalid") {
			desc = getThumbDesc("nm"+nicom_numid);
		}
	} else {
		desc = getThumbDesc(smid);
	}
	var parent = $(elem_caller.parentNode);
	var memo = parent.hasClassName("memo");
	if (desc == "" || desc == "deleted" || desc == "community") {
		// 取得出来ない場合は元テキストに追加(メモがある場合は除外)
		if (desc != "" && !memo)
			desc = (elem.textContent||elem.innerText) + " [" + desc + "]";
	}
	// 最新コメ部分を消してずれを少なくする
	if (!disableHideComments && elem_res)
		elem_res.hide();
	// メモが有る場合はセパレータを入れて先頭に追記(Thx#974)
	if (!disablePreserveMemo && memo) {
		$(elem_caller).replace(desc +
			'<img class="dot_1" src="http://res.nicovideo.jp/img/_.gif"' +
			' style="width:100%; height:1px; margin:4px 0;">');
	} else {
		elem.innerHTML = desc;
	}
	// 全文取得に成功したら親ノードのtitle属性(全文ポップアップ)を削除
	if (parent.tagName == 'P') parent.removeAttribute('title');
}
