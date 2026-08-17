# GitHubリポジトリ設定チェックリスト

この文書は、Git管理できないGitHub画面上の設定を、初めての管理者でも確認できるようにした
ものです。設定変更後は、IssueやPull Requestを1件ずつ試し、意図した動作を確認してください。

## 1. General

`Settings` → `General` で次を確認します。

- Features
  - Issues: 有効
  - Discussions: 有効
- Pull Requests
  - Allow squash merging: 有効（通常の推奨）
  - Allow merge commits: 無効
  - Allow rebase merging: 無効
  - Always suggest updating pull request branches: 有効
  - Automatically delete head branches: 有効

Squash merge時のコミットタイトルはPull Requestタイトルを使います。このリポジトリでは
`type(scope): 要約` 形式をActionsで検証します。

## 2. Rulesetでmainを保護する

`Settings` → `Rules` → `Rulesets` で、`main`を対象にActiveなBranch rulesetを作成します。

最初に有効にする推奨項目:

- Block force pushes
- Restrict deletions
- Require a pull request before merging
- Require status checks to pass before merging
- Require branches to be up to date before merging
- Require conversation resolution before merging

必須status checkは、各ワークフローを一度実行してGitHubの候補に表示された後に選択します。
少なくとも次を候補にします。

- `build-and-test`（CIの主要ジョブ）
- `runtime-compatibility-gate`（全Javaベンダー・17/21/25 matrixの集約結果）
- `validate-title`（Pull request governanceのタイトル検証）

matrixジョブの `${{ matrix.distribution }}` などを含む定義上の名前は、Rulesetの
status check名としてワイルドカード展開されません。個別の実行名をすべて登録する代わりに、
全matrixへ依存する固定名の`runtime-compatibility-gate`を必須にします。
`validate-title`は最初のPull Requestで一度実行されるまで候補に表示されません。

単独保守で自分のPull Requestを自分でマージする場合、Required approvalsを1にすると自分を
ロックアウトします。別の継続的なレビュアーが参加するまではRequired approvalsを0とし、
Code Owner reviewを必須にしないでください。`CODEOWNERS`は、それでも担当表示とレビュー依頼に
利用できます。

## 3. Actions

`Settings` → `Actions` → `General` で次を確認します。

- Actions permissions: GitHubが作成したActionだけを許可すれば、現行workflowを実行可能
- Require actions to be pinned to a full-length commit SHA: 有効
- Workflow permissions: Read repository contents and packages permissions
- Allow GitHub Actions to create and approve pull requests: 有効
- Fork pull request workflows: 初回実行を管理者承認にする

各workflowは必要な権限を明示しています。Issueラベルには`issues: write`、Pull Requestラベルには
`pull-requests: write`だけを使用し、外部forkのコードを権限付きで実行しません。
ActionsによるPull Request作成は、毎週の依存関係更新workflowが検証済みの更新候補を
レビュー用Pull Requestとして作成するために必要です。自動承認や自動マージは行いません。

## 4. セキュリティ

`Settings` → `Security` または`Security and analysis`で次を有効にします。

- Private vulnerability reporting
- Dependabot alerts
- Dependabot security updates
- Secret scanning
- Push protection

Private vulnerability reportingを有効にすると、`SECURITY.md`とIssue作成画面の非公開報告リンクが
実際に利用できます。公開Issueへ脆弱性の詳細を書かせないため、最優先で設定してください。

## 5. Discussions

次の標準カテゴリを削除または改名しないでください。フォームのファイル名がカテゴリのslugと
一致する必要があります。

- General: `general`
- Ideas: `ideas`
- Q&A: `q-a`

Announcementsは管理者からのお知らせ、Pollsは投票、Show and tellは利用例の共有に使えます。
Discussion category formsはPollsには適用できません。

## 6. 自動ラベル

最初のIssueまたはPull Requestが作成されると、Actionsが`area:`、`platform:`、
`status: needs triage`ラベルを必要に応じて作成します。Issueフォームの`bug`と`documentation`、
Discussionフォームの`enhancement`と`question`はGitHubの既存ラベルを使用します。

ラベル名を画面上で変更するとworkflowとの対応が切れるため、変更する場合は次も同時に更新します。

- `.github/labeler.yml`
- `.github/workflows/issue-labeler.yml`
- `.github/workflows/pull-request-governance.yml`
- `.github/ISSUE_TEMPLATE/`

## 7. 初回の動作確認

1. Q&A、Ideas、Generalの各Discussion作成画面に専用フォームが出ることを確認します。
2. テスト用の不具合Issueを作り、`bug`、`status: needs triage`、`area:`、`platform:`が付くことを
   確認してから閉じます。
3. テスト用Pull Requestを作り、変更パスのラベルとタイトル検証が動くことを確認します。
4. `main`のRulesetで必須にしたcheckが成功するまでマージできないことを確認します。
5. 非公開脆弱性報告の作成画面を、ログイン状態で開けることを確認します。

設定を変えた日と理由は、管理者向けメモまたは関連Issueへ記録してください。
