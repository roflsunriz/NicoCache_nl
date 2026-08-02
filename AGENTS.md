# AGENTS.md

## 作業開始前の必須手順（最優先・例外なし）

1. エージェントは、調査、計画、コマンド実行、スキル利用、ファイル編集、コミット、プッシュを始める前に、必ずリポジトリ直下の `.\COMMON-AGENTS.md` を開き、先頭から末尾まで全文を読む。
2. `COMMON-AGENTS.md` はGit管理外のシンボリックリンクである。`git`や既定のignore設定が有効な`rg --files`の検索結果だけで、ファイルが存在しないと判断してはならない。PowerShellでは最初に次を実行する。

```powershell
Get-Content -Raw -LiteralPath .\COMMON-AGENTS.md
```

3. 読み取りに失敗した場合、出力が省略された場合、または末尾まで読めたことを確認できない場合は、一切の作業を開始せず、パスとシンボリックリンク先を確認して全文を再取得する。必要なら分割して末尾まで読む。
4. 全文を読了するまで、ローカル `AGENTS.md` だけを根拠に作業を続けてはならない。読了後は `COMMON-AGENTS.md` を最優先の指針とし、読了直後の最初の進捗報告で全文を読了したことを明示する。
このファイルでは `NicoCache_nl` 固有の補足だけを記載する。

## このリポジトリについて

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーである。Java の本体ソース、実行 JAR、設定例、GUI、ローカル配信用ファイル、拡張機構をこのリポジトリに含む。

`nlFilters/` の標準フィルターと検証ツールはこのリポジトリで管理する。
`nlFilters/AGENTS.md` の追加規律にも従う。100番台と`COMMON-AGENTS.md`は
他リポジトリ・共通指示を参照するシンボリックリンクなので管理対象外とし、
リンクの作成、置換、削除前には `LinkType` と `Target` を確認する。

## 主要な構成

- `src/`: NicoCache_nl 本体の Java ソース。ビルド時にクラスファイルが生成されることがある。
- `extensions/`: Java 拡張のサンプルとローカル拡張。生成された `.class` は管理しない。
- `local/`: ブラウザーへ配信する JavaScript、CSS、画像など。NicoCache_nl 起動中は `/local/` 以下として配信される。
- `nlFilters/`: NicoCache_nlが直接読み込む標準フィルターと、構文・互換性を確認する
  nlFilter Lab。外部管理リンクとローカル生成領域は追跡しない。
- `defaults/`: 本体が参照する既定設定群。
- `README.md`, `CHANGELOG.md`, `documents/`, `how-to-update.md`: 利用方法と変更履歴。旧版資料は `documents/archive/` に保存する。
- `cache/`, `cvcache/`, `thcache/`: 動画、変換済み動画、サムネイルのキャッシュ。生成物なので管理しない。
- `certs/`: 生成された認証局・サイト証明書などの秘密情報。内容を表示・コミットしない。
- `data/`, `list/`: 実行時データとユーザー設定。配布用サンプル以外は管理しない。
- `lib/`: 本体と証明書生成に使う依存ライブラリ。
- `build-javac.ps1`, `build-javac.sh`, `manifest-nl.mf`: 独立Javaアプリを生成する
  正規ビルドのブートストラップと定義。

## 変更時の注意

- ユーザー操作、設定、ビルド手順が変わる場合は、付属 README、`documents/`、変更履歴の更新要否も確認する。
- `/cache/*` API を変更または利用するときは、実装と既存のエラー形式を確認する。代表的な実装は `src/dareka/processor/impl/CacheDirProcessor.java` にある。
- `window.NicoCache_nl.watch` は互換ヘルパーであり、ニコニコ動画側の構造変更に影響される。動画 ID は URL や呼び出し元、再生状態は `HTMLMediaElement` などページ上の実体を優先し、このヘルパーは型と失敗時処理を確認したフォールバックとして使う。
- 用途が不明なファイルを推測で変更しない。付属文書、設定、ソース、リンク先を確認する。

## ビルド

- `build-javac.ps1` と `build-javac.sh` は、クリーンなチェックアウトから
  `NicoCacheBuild.jar`を生成して起動する唯一の正規ビルドブートストラップである。
- `build-javac.ps1` は `src/` と `NicoCache_nl.jar` を更新し得るため、依頼されたビルドまたは検証の範囲でのみ実行する。
- ビルド前に対象ソース、生成先、復旧方法を確認し、既存の `.class` や JAR を無関係に上書きしない。

## 起動・終了・デバッグ

- GUI起動には `NicoCacheLauncher.jar` または配布物のネイティブランチャーを使い、
  実行前に対象JARの存在を確認する。
- 終了時に `Stop-Process -Name java`、`taskkill /IM java.exe` など、名前だけで全 Java プロセスを終了しない。`NicoCacheLauncher.jar --headless --stop` または `--force-stop` を利用して対象本体だけを終了する。
- 再起動は旧プロセスの終了を確認してから行い、起動後は新しい PID と対象 JAR を確認する。
- デバッグでは `NicoCacheGUI.property` の変更前の値を記録する。検証後は、継続指定がない限り元へ戻す。ログには秘密情報や個人情報が含まれ得るため、無制限に表示・コミットしない。
