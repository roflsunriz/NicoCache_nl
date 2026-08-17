# NicoCache_nl への貢献

不具合報告、ドキュメント改善、コード変更を歓迎します。最初に、内容に合う窓口を選んで
ください。

- 使い方や設定の質問: [Q&A Discussions](https://github.com/roflsunriz/NicoCache_nl/discussions/categories/q-a)
- 新機能や改善の提案: [Ideas Discussions](https://github.com/roflsunriz/NicoCache_nl/discussions/categories/ideas)
- 再現できる不具合: Issue の「不具合報告」
- ドキュメントの誤り: Issue の「ドキュメント報告」
- 脆弱性: [SECURITY.md](SECURITY.md) に従い、公開 Issue へ詳細を書かない

## 変更を始める前に

1. 同じ Issue、Discussion、Pull Request がないか検索します。
2. 大きな仕様変更は、先に Ideas Discussion で目的と互換性を相談します。
3. JDK 25を通常の開発環境とし、Java 17互換を維持します。
4. 利用者の `config.properties`、キャッシュ、証明書、ログをリポジトリへ追加しません。

## 実装上の原則

- 既存のディレクトリ構成と責務分離を維持します。
- 生成済みJAR、DLL、パッケージを直接編集せず、生成元を変更します。
- ユーザー操作、設定、配布方法を変える場合は、READMEや関連文書も更新します。
- 利用者に影響する変更は `CHANGELOG.md` の `Unreleased` に、目的が分かる形で記録します。
- 証明書、Cookie、トークン、動画の個人情報を、コード、テストデータ、ログへ含めません。
- `nlFilters/` を変更する場合は、同ディレクトリの説明と Lab の検証手順に従います。

## 主な検証

変更範囲に応じて、少なくとも該当する検証を実行してください。詳しい説明は
[`tests/README.md`](tests/README.md) にあります。

```powershell
./build-javac.ps1 -Clean
./test-functional.ps1
./test-api.ps1
./test-first-run-setup.ps1
./test-launcher.ps1
node --test ./tests/local/*.test.js
./tests/release/test-release-workflow.ps1
```

Windows InstallerやOS設定を実際に変更する一部の試験は、隔離されたGitHub Actions
ランナー専用です。通常のPCで制限を迂回して実行しないでください。

## コミットとPull Request

- コミットとPull Requestのタイトルは、`type(scope): 要約` 形式を使います。
- 例: `fix(cache): 一時キャッシュ削除の競合を修正`
- 主なtypeは `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci`、`chore`です。
- Pull Request本文には、目的、利用者への影響、関連Issue、実行した検証を記載します。
- 見た目を変えた場合は、変更前後を比較できるスクリーンショットを添付します。
- CIがすべて成功し、レビュー指摘を反映してからマージ対象になります。

分からない点がある場合、変更を始める前にQ&A Discussionで質問してください。

リポジトリ管理者がGitHub画面で設定する項目は
[`REPOSITORY_SETTINGS.md`](.github/REPOSITORY_SETTINGS.md) にあります。
