# 変更履歴

このプロジェクトの重要な変更を [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/) に基づいて記録します。

## [Unreleased]

### Added

- 同一動画の再ホバー、別動画、短時間で離脱するホバーをChromeで自動操作し、
  iframe生成数、DOM再利用、キャンセルを検証する`--popthumb-probe`を追加しました。
- 稼働中フィルターを変更する前に構文と適用結果を確認できるよう、Java正規表現対応の構文チェッカーと、watch・検索・Nアニメ風fixtureを備えた `nlFilter Lab` を追加しました。
- キャッシュ5状態、`/cache/info/v2`、SPAによる後続DOM追加、`/local` 資産配信をローカルで検証できる疑似環境を追加しました。
- コーディングエージェントが最終DOM、スクリーンショット、コンソールログ、JSON結果を受け取れるヘッドレス実行を追加しました。
- 本体パーサー変更を見落とさないよう、関連ソースの基準ハッシュ確認と機械可読な `check --json` / `source-check --json` を追加しました。
- 構文受理を本体そのものと照合できるよう、稼働中JARの `EasyRewriter.parseFilterFile` を構文オラクルとして呼び出す互換検査を追加しました。
- DSL全体の疑似実行範囲を広げるため、`$LST`、`$INC`、`$SET`、`$NEST`、`nlcase`、RequestHeader、状態変数、再エンコード明示モックを追加しました。
- 疑似実行の意味差を検出できるよう、副作用のない互換コーパスを本体 `applyUserFilter` とLabの両方で実行して最終結果を照合するテストを追加しました。

### Changed

- 標準フィルターとLabの現行CMAF/Domandキャッシュ取得を`/cache/info/v3`へ移行し、
  既存`low`名は品質値と分離した互換情報として検証するようにしました。
- サムネイルポップアップを動画ID単位で再利用し、短い通過ホバーを外部アクセス前に
  取り消す軽量実装へ変更しました。動的動画リンクはイベント委譲だけで処理し、
  DOM全走査とMutationObserverを不要にしました。
- 実視聴ページと同じ`data-anchor-page`、`data-anchor-href`、
  `data-decoration-video-id`構造で初期表示とSPA追加を検証できるよう、watch fixtureを
  旧`WatchRecommendation`構造から更新しました。
- 本体の版・配布物と同じコミットで標準フィルターを保守できるよう、
  独立リポジトリの全履歴をNicoCache_nlの`nlFilters/`へ統合しました。
- 誤った疑似適用を防ぐため、本体が破棄する構文エラールールを除外し、未対応の状態機能・動的マクロは警告付きでスキップするよう互換性を強化しました。
- 現行ニコニコ動画をChrome CDPで検証し、React検索・タグページでは10番の旧inlineポップアップ処理を除外して11番のSPA対応処理へ一本化しました。
- 現行watchページの`data-decoration-video-id`による関連動画描画に対応し、初期化時に生成されて即時削除される`server-response`を捕捉できない場合もURLから動画IDを取得してキャッシュアイコンと削除ボタンを動作させるようにしました。
- 現行ユーザーページの`TimelineItem_video`をキャッシュアイコン・リンク色変更の対象に追加しました。
- head内から読み込まれるキャッシュ削除ボタンが`CommonHeader`の生成前に終了しないよう待機処理を追加し、ユーザーページのポップアップID正規表現も修正しました。

### Fixed

- Windowsのシステムプロキシ設定が有効でもheadless検証が自身のloopback APIで停止しないよう、
  JavaクライアントとChromeを直接接続に固定し、HTTP/1.1で通信するよう修正しました。
- 同一ソースを改行形式だけでパーサー変更と誤判定しないよう、Javaソースの基準ハッシュを
  LF正規化後に計算し、CRLFチェックアウトとの一致を自動テストするよう修正しました。
- watch fixtureが現行メニューの視聴ページ判定とREST API契約を正しく検証できるよう、
  履歴URL、キャッシュAPIモック、保存・削除・SPA切替プローブを更新しました。
