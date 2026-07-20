# 変更履歴

このファイルでは現在の開発系列を記録する。過去の履歴は
`ChangeLog.txt` を参照する。

## [Unreleased]

### Added

- 継続的インテグレーションで JDK 11 のビルド、機能テスト、Extension ABI 互換
  テストを自動実行し、`v*` タグからシンボリックリンクを除く配布ファイル一式の
  ZIP、JAR、SHA-256 チェックサムを公開する GitHub Actions を追加した。
- 不要コード整理による退行を検出するため、実プロキシー、条件付き取得、上流
  障害、本文書換え、コメント、サムネイル、DOMAND/CMAF の完成・オフライン
  再生、`hlsext` の暗号化セグメント復号、HTTPS TLSループバック、
  `/cache/*`、MP4・FLV・SWF・旧HLSキャッシュ、Extensionライフサイクルを
  隔離環境で確認する機能テストを追加した。
- 未知の外部 Extension のバイナリ互換性を維持するため、全 `dareka.*` の
  public/protected ABI と同梱 Extension のコンパイルを検証するテストを追加した。

### Removed

- Java 11 以降では必ずスキップされていた Java 8 以前向け PATCH 許可リスト
  書換え処理と、効果のない `useWorkaroundForAllowedMethods` 設定を削除した。
- 保守時に実装と誤認しないよう、置換済みのコメントアウトコードを削除した。
- 現行ニコニコ動画で使用されないSmile、DMC単一ファイル、DMC-HLSの新規取得
  Processor登録と取得実装、getflvレスポンス書換えを削除した。配布済み
  Extension用のpublic/protected ABIは互換shimで維持する。
- 旧取得経路だけに作用していた `disableStreamingWarning`、
  `useSmileCacheInsteadOfDmcEconomy`、`useSmileCacheInsteadOfDmc`、
  `ignoreEmbeddedPlayer`、`noLiveCache`、`deletedMoviePlayMode`、
  `mapFileUpdatingInterval` と、上流SWF変換用の `swfConvert*` 既定設定を削除した。

### Fixed

- checkout を行わない release の publish job でも GitHub CLI が対象リポジトリを
  解決できるよう、release 作成時にリポジトリ名を明示するよう修正した。
- Windows で直前の配信が保持するファイルハンドルが解放される前にキャッシュ削除
  API が呼ばれても、一時的な削除失敗で `NG` にならないよう短時間の削除再試行を
  追加した。
- Extension の定期イベント通知スレッドが JVM の終了を妨げないよう、サーバー
  停止時に対象スレッドを割り込ませて終了するよう修正した。

### Changed

- 旧取得経路の実装を削減しながら既存Extensionをロードできるよう、公開ABIを
  変更せず、旧ProcessorとSWF上流変換クラスを非登録の互換shimへ変更した。
- 既存のFLV・SWF・MP4・旧HLS・CMAFキャッシュを引き続き利用できるよう、
  キャッシュ走査、`/cache/*`、HLS変換、現行CMAF内の旧HLS応答処理を維持した。
