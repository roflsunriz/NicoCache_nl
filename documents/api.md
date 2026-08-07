# NicoCache_nl 本体APIリファレンス

この文書は、本体がプロキシー経由で提供する`/cache/*` APIと、起動管理アプリが
利用するループバック管理APIをまとめたものである。動画サイトの通常のページや
上流APIを本体APIと混同しないよう、ここではNicoCache_nl自身が処理を分岐する
エンドポイントだけを扱う。

## 共通事項

### 接続先

`/cache/*`はブラウザーと同じプロキシーポート（設定の`listenPort`、既定値
`8080`）へ、ニコニコ動画向けのHTTPリクエストとして送る。例では
`http://www.nicovideo.jp`を使っている。クエリ、パス中の動画ID、タイトルはUTF-8の
パーセントエンコードを使用する。

`/cache/*`で本体が受け付けるHTTPメソッドはGETとPOSTである。APIが指定するメソッドと
異なる場合は`405 Method Not Allowed`、新しいAPIの引数が不正な場合は
`400 Bad Request`、存在しないAPIは`404 Not Found`になる。削除・移動・タイトル変更の
AJAX版は`200`とプレーンテキストの`OK`または`NG`を返す。AJAXでない変更APIは
`302 Found`で`nl-region`に応じた画面へ戻る。

動画IDは`sm900001`のような`[a-z]{2}[0-9]+`形式で指定する。情報APIでは従来互換の
数字だけのIDやthread IDも受け付ける。品質・拡張子を含むDMCキャッシュの代替IDは、
例えば`sm900002[720p,128].mp4`のように指定する。

## キャッシュ情報API

| エンドポイント | メソッド | 引数・本文 | 応答 |
| --- | --- | --- | --- |
| `/cache/info` | GET/POST | GETは`?smid[,smid...]`、POSTは本文に同じID列 | IDをキーとするJSON。未登録IDは`null`。空入力は`{}`。 |
| `/cache/info/v2` | GET/POST | `/cache/info`と同じ | 品質別の`preferred`、`cacheIds`、`cachings`、`completes`、`caches`などを含むJSON。 |
| `/cache/ajax_info` | GET | 単一の従来型IDをクエリに指定 | `OK,<low>,<拡張子>,<サイズ>,<最終サイズ>,<タイトル>`または`NG`。新規利用では`info/v2`を使う。 |
| `/cache/echo` | GET | 解析対象の代替IDをクエリに指定 | `rawQuery`、`decodedQuery`、`altId`、`smid`、`videoDescriptor`、対応キャッシュパスを含む診断JSON。引数なしは400。 |

例:

```text
GET http://www.nicovideo.jp/cache/info/v2?sm900001,sm900002 HTTP/1.1
Host: www.nicovideo.jp
Connection: close
```

POSTではフォーム形式ではなく、ID列そのものを本文に送る。

```text
POST http://www.nicovideo.jp/cache/info HTTP/1.1
Host: www.nicovideo.jp
Content-Type: text/plain; charset=UTF-8
Content-Length: 17

sm900001,sm900002
```

`/cache/info/v2`では、現行Domand/CMAFキャッシュを`preferredDmcHls`が指す。
対応する`caches[cacheId]`は`complete: true`、`movieType: "hls"`、互換名の
`dmc: true`を持ち、`dmcMovieType`に`videoMode`、`videoBitrate`、`audioBitrate`を
含む。現行Domand/CMAFでは映像品質を`videoMode`（例:`1080p`、`360p-lowest`）、
音質を`audioBitrate`（kbps）で表し、`videoBitrate`は0である。`dmc`はDMCと
Domand/CMAFの両方で真になる保存形式互換フィールドなので、配信世代の判定には使わない。
完成済みかどうかは`completes`または各エントリーの`complete`で確認する。

## キャッシュ削除API

| エンドポイント | メソッド | 対象 | AJAX応答 |
| --- | --- | --- | --- |
| `/cache/rm` / `/cache/ajax_rm` | GET | 1つの代替ID。品質・拡張子の指定可 | 成功`OK`、対象なし・削除失敗`NG` |
| `/cache/rmtmp` / `/cache/ajax_rmtmp` | GET | 1つの代替IDのダウンロード中一時ファイル | 成功`OK`、対象なし・削除失敗`NG` |
| `/cache/rmall` / `/cache/ajax_rmall` | GET | 修飾のない動画IDだけ。通常、低品質、DMCをまとめて対象にする | 成功`OK`、対象なし・削除失敗`NG` |

`rmall`に`[720p,128]`や`.mp4`などを付けることはできない。不正な空引数、
部分一致で余分な文字を含むID、`rmall`への修飾付きIDは400になる。AJAXでない
エンドポイントは削除結果を本文に返さず、通常`http://www.nicovideo.jp/`へ302で戻る。

例:

```text
GET http://www.nicovideo.jp/cache/ajax_rmall?sm900001 HTTP/1.1
Host: www.nicovideo.jp
Connection: close
```

## 検索・一覧・XML API

| エンドポイント | メソッド | 用途 |
| --- | --- | --- |
| `/cache/search/<文字列>` | GET | ファイル名を単語OR検索し、キャッシュ一覧JSONを返す。`+`は除外語の指定に使える。 |
| `/cache/rsearch/<正規表現>` | GET | パス名を正規表現検索する。`?order=d`で降順にする。 |
| `/cache/cachelist.json` | GET | 完成キャッシュ一覧JSON。 |
| `/cache/templist.json` | GET | 一時キャッシュ一覧JSON。 |
| `/cache/dirlist.json` | GET | キャッシュフォルダー一覧JSON。 |
| `/cache/flvlist.json` | GET | フォルダー・一時・完成キャッシュをまとめたJSON。 |
| `/cache/ajax` | GET | キャッシュ管理画面が読むJavaScript形式の一覧。 |
| `/cache/getxml?type=dirlist` | GET | XMLのフォルダー一覧。`type`は`dirlist`、`templist`、`cachelist`、`cachelistall`。 |
| `/cache/`、`/cache/<画面名>.html` | GET | キャッシュ管理画面。 |
| `/cache/flvlist`、`/cache/flvlist/` | GET | 旧ローカルFLVプレーヤー向けのID一覧。 |
| `/cache/log` | GET | `enableLogHandler=true`時のログ表示。設定が無効でも案内を返す。 |

`getxml`の`cachelist`では、必要に応じて`path`、`name`、`filetype`、`reload`を
追加できる。`dirlist`の`ignore`も利用できる。値はすべてURLエンコードする。

## 旧来の管理API

互換性のため、次のUI向けAPIは残っている。新しい外部ツールは上記のAPIを優先する。

- `oldinfo`、`oldinfo/v2`：`GET /cache/oldinfo?smid`または
  `GET /cache/oldinfo/v2?smid`で旧形式のキャッシュ情報を返す。本文を使うPOSTにも
  対応する（POST先は`/cache/oldinfo`または`/cache/oldinfo/v2`）。
- `oldrm`、`oldrmtmp`、`oldrmall`：旧削除API。各APIに`ajax_`接頭辞を付けた
  `/cache/ajax_oldrm`、`/cache/ajax_oldrmtmp`、`/cache/ajax_oldrmall`もある。
- `title`：`/cache/title?<代替ID>-<タイトル>`または
  `/cache/ajax_title?<代替ID>-<タイトル>`でタイトルを変更する。
- `move`、`topmove`：`/cache/move?<代替ID>-<フォルダー>`または
  `/cache/ajax_move?<代替ID>-<フォルダー>`、および`topmove`版で移動する。
  フォルダーは`dirlist`に現れる値（例:`/api-target`）を使い、`topmove`は
  キャッシュ直下にある場合だけ動作する。
- `addlist`、`trimlist`：`/cache/addlist/<ファイル>?<文字列>`または
  `/cache/ajax_addlist/<ファイル>?<文字列>`、`trimlist`版で`list/<ファイル>`を更新する。
  正規表現追加の`addrlist`（AJAX版は`ajax_addrlist`）と、互換的に受け付ける
  `rtrimlist`もある。外部API経由のLSTファイルは`list/`配下に限定され、`..`や
  Windows区切りによる脱出は拒否される。

`/cache/file/<パラメーター>//<相対パス>`はCMAF/Domandの再生用内部パスであり、
管理操作APIではない。`/cache/<動画ID>.<拡張子>`、`/cache/<動画ID>/auto/movie`、
コメントXML取得も同じくキャッシュ再生・既存サイト互換のためのリソース経路である。

## 起動管理アプリ向け管理API

本体はプロキシーポートと別に、`127.0.0.1`だけでランダムポートを待ち受ける。
ポートとBearerトークンはユーザーデータの`data/nicocache-control.properties`に保存する。
トークンはログやドキュメントへ出力しない。

| エンドポイント | メソッド | 応答 |
| --- | --- | --- |
| `/api/control/status` | GET | `status`、`pid`、管理APIの`port`、`proxyPort`、`problem`、`version`を含むJSON。 |
| `/api/control/ping` | GET | `{"status":"ok"}`。 |
| `/api/control/graceful-shutdown` | POST | `202`、`{"status":"stopping"}`。 |
| `/api/control/force-shutdown` | POST | `202`、`{"status":"forcing"}`。 |

`/api/control/shutdown`はgraceful、`/api/control/force`はforceの互換別名である。
すべてのリクエストに次のヘッダーを付ける。

```text
Authorization: Bearer <data/nicocache-control.properties の token>
```

認証なし・トークン不一致は401、未知のパスは404である。停止APIは応答を受けてから
状態ファイルの`state`が消えるまで待つ。管理アプリや監視ツールが必要とする状態確認、
疎通確認、グレイスフル停止、強制停止は既存APIで完結するため、同じ意味の新しいAPIは
追加していない。

## 実動作検証

この文書の主要なAPI契約は、隔離したユーザーデータと実ソケットを使う次のCIテストで
検証する。実ユーザーのキャッシュ、証明書、プロキシー設定、外部サイトには接続しない。

```powershell
.\test-api.ps1
```

通常の機能テストにも同じ削除・情報取得経路の回帰確認が含まれる。
