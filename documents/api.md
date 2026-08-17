# NicoCache_nl REST API

NicoCache_nlは、通常のニコニコ動画通信と分離した専用仮想ホストで管理サイトとREST APIを提供する。

```text
https://nicocachenl.test/
```

`proxy.pac`がこのホストをローカルプロキシーへ送り、NicoCache_nlのCAで生成した専用証明書を使う。管理サイトとAPIはループバック接続だけを受け付ける。ニコニコ動画ページからの組み込み機能に必要なCORSだけを返し、WAN公開は想定しない。

## 管理サイト

| パス | 内容 |
| --- | --- |
| `/` | 稼働状態とランタイム概要 |
| `/cache` | 完成・一時キャッシュの一覧と削除 |
| `/health` | livenessとreadiness |
| `/diagnostics` | JVM、メモリー、スレッド、デッドロック概要 |
| `/diagnostics/threads` | 利用者操作による完全スレッドダンプ採取・表示 |
| `/videos/<動画ID>` | 動画別キャッシュ情報とエクスポート |

## 共通契約

- API接頭辞は `/api/v1`。
- JSON応答は `application/json; charset=UTF-8`、キャッシュ禁止。
- 不正入力は400、対象なしは404、メソッド違いは405と`Allow`、競合は409、上流失敗は502。
- エラー本文は `{"error":{"code":"...","message":"..."}}`。
- 動画IDは `[a-z]{2}[0-9]+`。キャッシュIDをパスへ含める場合は1セグメントとしてUTF-8パーセントエンコードする。
- GETは削除、設定変更、診断採取を行わない。

## キャッシュ情報

| メソッドとパス | 内容 |
| --- | --- |
| `GET /api/v1/videos/<動画ID>/cache-entries` | 動画単位のCMAF/Domandキャッシュ情報 |
| `POST /api/v1/cache-entry-queries` | 最大256動画の一括照会。本文は `{"videoIds":["sm9"]}` |
| `GET /api/v1/cache-entries` | 完成・一時キャッシュ一覧 |
| `GET /api/v1/cache-entries?state=complete` | 完成キャッシュ一覧 |
| `GET /api/v1/cache-entries?state=temporary` | 一時キャッシュ一覧 |
| `GET /api/v1/cache-entries?query=<文字列>&order=desc` | キャッシュ検索 |
| `GET /api/v1/cache-entries?query=<正規表現>&mode=regex&order=desc` | 正規表現検索 |
| `GET /api/v1/cache-directories` | キャッシュ保存先一覧 |

単一動画の応答は`videoId`、`preferred`、`cacheIds`、`cachings`、`completes`、`caches`を持つ。各キャッシュは`complete`、`caching`、`videoMode`、`audioBitrate`、サイズ、タイトル、保存先情報を返す。

## 再生とエクスポート

| メソッドとパス | 内容 |
| --- | --- |
| `GET/HEAD /api/v1/videos/<動画ID>/media` | 完成キャッシュを再生用に返す。HEADは存在確認だけを行う |
| `GET /api/v1/videos/<動画ID>/exports/video` | 動画を添付ファイルとして返す |
| `GET /api/v1/videos/<動画ID>/exports/audio` | 音声をM4AまたはMP3として抽出する |
| `GET /api/v1/videos/<動画ID>/exports/comments` | 現行NVComment JSONを返す |

動画・音声には完成済みキャッシュが必要である。コメントには、同じNicoCache_nlを通して視聴ページを先に開き、現行コメント取得情報を記録している必要がある。

## 削除

| メソッドとパス | 内容 |
| --- | --- |
| `DELETE /api/v1/cache-entries/<キャッシュID>` | 特定の完成キャッシュを削除 |
| `DELETE /api/v1/temporary-cache-entries/<キャッシュID>` | 特定の停止中一時キャッシュを削除 |
| `DELETE /api/v1/videos/<動画ID>/temporary-cache-entries` | 動画に属する全一時キャッシュを削除。取得中は削除予約 |
| `DELETE /api/v1/videos/<動画ID>/cache-entries` | 動画に属する完成・一時キャッシュを削除・削除予約 |

即時削除は200、取得完了待ちの予約は202、対象なしの動画単位削除は200と`not_found`を返す。

## ヘルスチェックと診断

| メソッドとパス | 内容 |
| --- | --- |
| `GET /api/v1/health/live` | HTTP処理のliveness |
| `GET /api/v1/health/ready` | 利用可能状態と本体バージョン |
| `GET /api/v1/diagnostics/runtime` | JVM、OS、ヒープ、スレッド数、デッドロック数 |
| `POST /api/v1/diagnostic-snapshots` | 完全診断スナップショットを明示的に作成 |
| `GET /api/v1/diagnostic-snapshots/<ID>` | 採取済み診断JSON |
| `GET /api/v1/diagnostic-snapshots/<ID>/thread-dump` | 採取済み全スレッドダンプ |

完全診断はページ表示だけでは採取せず、ボタン操作またはPOST時にだけ作成する。直近8件をメモリーに保持する。

## メディア内部経路

次はREST APIではなく、再生データの内部配信経路として維持する。

- `/cache/file/<内部パラメーター>//<相対パス>`
- `/cache/<キャッシュID>.<拡張子>`

情報取得、検索、一覧、削除、移動、LST更新、管理画面、動画・音声・コメント保存に使われていた旧 `/cache/*` APIは廃止した。

## ランチャー用ControlServer

ランチャー用のControlServerは専用サイトとは分離し、`127.0.0.1`のランダムポートと起動ごとのBearerトークンを維持する。

- `GET /api/control/status`
- `GET /api/control/ping`
- `GET /api/control/diagnostics/snapshot`
- `POST /api/control/graceful-shutdown`
- `POST /api/control/force-shutdown`

ポートとトークンはユーザーデータの`data/nicocache-control.properties`に保存される。値をログや文書へ出力しない。

## 検証

```powershell
.\test-api.ps1
```

隔離ユーザーデータと実ソケットを使用し、実キャッシュ、外部サイト、OSプロキシー設定は変更しない。
