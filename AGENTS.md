# AGENTS.md

共通ルールは `COMMON-AGENTS.md` を必ず確認し、上位方針として扱う。
このファイルでは `NicoCache_nl` 固有の補足だけを記載する。

## このリポジトリについて

NicoCache_nl は、ニコニコ動画向けのローカル HTTP/HTTPS プロキシー兼キャッシュサーバーである。Java の本体ソース、実行 JAR、設定例、GUI、ローカル配信用ファイル、拡張機構をこのリポジトリに含む。

`nlFilters/` は別リポジトリで管理しているため、このリポジトリでは変更・コミットしない。シンボリックリンクやハードリンクも管理対象外とし、リンクの作成、置換、削除前には `LinkType` と `Target` を確認する。

## 主要な構成

- `src/`: NicoCache_nl 本体の Java ソース。ビルド時にクラスファイルが生成されることがある。
- `extensions/`: Java 拡張のサンプルとローカル拡張。生成された `.class` は管理しない。
- `local/`: ブラウザーへ配信する JavaScript、CSS、画像など。NicoCache_nl 起動中は `/local/` 以下として配信される。
- `defaults/`: 本体が参照する既定設定群。
- `documents/`, `Readme.txt`, `Readme_dms.txt`, `変更点.txt`, `ChangeLog.txt`: 利用方法と変更履歴。
- `cache/`, `cvcache/`, `thcache/`: 動画、変換済み動画、サムネイルのキャッシュ。生成物なので管理しない。
- `certs/`: 生成された認証局・サイト証明書などの秘密情報。内容を表示・コミットしない。
- `data/`, `list/`: 実行時データとユーザー設定。配布用サンプル以外は管理しない。
- `lib/`: 本体と証明書生成に使う依存ライブラリ。
- `build.xml`, `build-ant.ps1`, `build-javac.ps1`, `manifest-nl.mf`: 本体のビルド定義とスクリプト。

## 変更時の注意

- ユーザー操作、設定、ビルド手順が変わる場合は、付属 README、`documents/`、変更履歴の更新要否も確認する。
- `/cache/*` API を変更または利用するときは、実装と既存のエラー形式を確認する。代表的な実装は `src/dareka/processor/impl/CacheDirProcessor.java` にある。
- `window.NicoCache_nl.watch` は互換ヘルパーであり、ニコニコ動画側の構造変更に影響される。動画 ID は URL や呼び出し元、再生状態は `HTMLMediaElement` などページ上の実体を優先し、このヘルパーは型と失敗時処理を確認したフォールバックとして使う。
- 用途が不明なファイルを推測で変更しない。付属文書、設定、ソース、リンク先を確認する。

## ビルド

- `build-ant.ps1` は Apache Ant を使用する。
- `build-javac.ps1` は JDK の `javac`、`jar`、`manifest-nl.mf` を使用する。
- どちらも `src/` と `NicoCache_nl.jar` を更新し得るため、依頼されたビルドまたは検証の範囲でのみ実行する。
- ビルド前に対象ソース、生成先、復旧方法を確認し、既存の `.class` や JAR を無関係に上書きしない。

## 起動・終了・デバッグ

- 起動には `RunNicoCache.ps1` または `NicoCache_nl Starter.bat` を使い、実行前に対象ファイルの存在を確認する。
- 終了時に `Stop-Process -Name java`、`taskkill /IM java.exe` など、名前だけで全 Java プロセスを終了しない。コマンドラインの `-jar ...\NicoCache_nl.jar` を確認して対象 PID を限定し、通常終了を優先する。
- 再起動は旧プロセスの終了を確認してから行い、起動後は新しい PID と対象 JAR を確認する。
- デバッグでは `NicoCacheGUI.property` の変更前の値を記録する。検証後は、継続指定がない限り元へ戻す。ログには秘密情報や個人情報が含まれ得るため、無制限に表示・コミットしない。
