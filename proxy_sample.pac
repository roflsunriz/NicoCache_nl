// - nicovideo,smile,nimg,dmcへのアクセスをNicoCache_nl経由にする proxy.pac の例.
// - カスタムしたい場合はコピーを取って下さい.

function FindProxyForURL(url, host) {
  // NicoCache_nl内部のデバッグ用仮想ホスト.
  if (host.toLowerCase() === 'debug') {
    return 'PROXY 127.0.0.1:8080';
  };

  // NicoCache_nl内部のデバッグ用仮想ホスト.
  if (host.toLowerCase() === 'debug') {
    return 'PROXY 127.0.0.1:8080';
  };

  // - アニメ生放送や過去の生放送(見逃し配信中)などで"試聴する"ボタンを押した時
  //   にエラーすることへの回避策の一つ.
  // - NicoCache_nlのログに
  //   "failed to set workaround for allowing patch method"と出ない環境では，
  //   これを"DIRECT"にする必要はありません.
  //   "if"から"}"までを削除して下さい.
  if ('live2.nicovideo.jp' === host) {
    return 'DIRECT';
  };

  if ((shExpMatch(host, '*.nicovideo.jp')
       || shExpMatch(host, '*.nvcomment.nicovideo.jp')
       || shExpMatch(host, '*.smilevideo.jp')
       || shExpMatch(host, '*.nimg.jp')
       || shExpMatch(host, '*.video.nimg.jp')
       || shExpMatch(host, '*.dmc.nico')
      ) && (url.indexOf('http:') == 0 || url.indexOf('https:') == 0)) {
    return 'PROXY 127.0.0.1:8080';
  };
  return 'DIRECT';
}
