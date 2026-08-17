[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

$requiredFiles = @(
    'LICENSE'
    'CODE_OF_CONDUCT.md'
    'CONTRIBUTING.md'
    'SECURITY.md'
    'SUPPORT.md'
    '.github/CODEOWNERS'
    '.github/REPOSITORY_SETTINGS.md'
    '.github/DISCUSSION_TEMPLATE/general.yml'
    '.github/DISCUSSION_TEMPLATE/ideas.yml'
    '.github/DISCUSSION_TEMPLATE/q-a.yml'
    '.github/ISSUE_TEMPLATE/bug-report.yml'
    '.github/ISSUE_TEMPLATE/documentation.yml'
    '.github/ISSUE_TEMPLATE/config.yml'
    '.github/pull_request_template.md'
    '.github/labeler.yml'
    '.github/workflows/issue-labeler.yml'
    '.github/workflows/pull-request-governance.yml'
)

foreach ($relativePath in $requiredFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "GitHub community file is missing: $relativePath"
    }
    if ((Get-Item -LiteralPath $path).Length -eq 0) {
        throw "GitHub community file is empty: $relativePath"
    }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Context
    )

    if (-not $Content.Contains($Expected)) {
        throw "$Context is missing: $Expected"
    }
}

$issueWorkflow = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github/workflows/issue-labeler.yml'
)
foreach ($required in @(
        'issues: write'
        'status: needs triage'
        'answerFor(''対象領域'')'
        'answerFor(''OS'')'
        'actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3 # v9.0.0'
    )) {
    Assert-Contains $issueWorkflow $required 'Issue labeling workflow'
}
if ($issueWorkflow -match '(?m)^\s*contents:\s*write\s*$') {
    throw 'Issue labeling workflow must not request contents: write'
}

$pullWorkflow = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github/workflows/pull-request-governance.yml'
)
foreach ($required in @(
        'pull_request_target:'
        'permissions: {}'
        'contents: read'
        'pull-requests: write'
        'actions/labeler@634933edcd8ababfe52f92936142cc22ac488b1b # v6.0.1'
        'sync-labels: true'
        'PRタイトルは type(scope): 要約 形式にしてください。'
    )) {
    Assert-Contains $pullWorkflow $required 'Pull request governance workflow'
}
if ($pullWorkflow -match 'actions/checkout@' -or
        $pullWorkflow -match '(?m)^\s+run:\s*') {
    throw 'pull_request_target workflow must not check out or execute pull request code'
}

$bugForm = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github/ISSUE_TEMPLATE/bug-report.yml'
)
$areaOptions = @(
    'キャッシュ・プロキシー本体 / API'
    'ランチャー・初回セットアップ'
    '診断アプリ'
    '独立アップデーター'
    'Windows / Linux / macOS 配布'
    '標準 nlFilter / nlFilter Lab'
    'ブラウザー表示・ローカル資産'
    'ドキュメント'
)
foreach ($option in $areaOptions) {
    Assert-Contains $bugForm $option 'Bug issue form'
    Assert-Contains $issueWorkflow $option 'Issue area mapping'
}
foreach ($platform in @('Windows', 'Linux', 'macOS')) {
    Assert-Contains $bugForm $platform 'Bug issue form OS options'
    Assert-Contains $issueWorkflow "platform: $($platform.ToLowerInvariant())" `
        'Issue platform mapping'
}

$discussionDirectory = Join-Path $root '.github/DISCUSSION_TEMPLATE'
$discussionNames = @(Get-ChildItem -LiteralPath $discussionDirectory -File |
        Sort-Object Name | ForEach-Object Name)
$expectedDiscussionNames = @('general.yml', 'ideas.yml', 'q-a.yml')
$discussionDifference = @(Compare-Object `
        -ReferenceObject $expectedDiscussionNames `
        -DifferenceObject $discussionNames `
        -CaseSensitive)
if ($discussionDifference.Count -ne 0) {
    throw "Discussion forms must match category slugs: $($discussionNames -join ', ')"
}

$labeler = Get-Content -Raw -LiteralPath (Join-Path $root '.github/labeler.yml')
foreach ($label in @(
        'area: core'
        'area: launcher'
        'area: diagnostics'
        'area: updater'
        'area: packaging'
        'area: nlfilter'
        'area: browser'
        'area: documentation'
        'area: ci'
        'platform: windows'
        'platform: linux'
        'platform: macos'
    )) {
    Assert-Contains $labeler "`"$label`":" 'Pull request label configuration'
    Assert-Contains $pullWorkflow "['$label'," 'Managed pull request labels'
}

$settingsChecklist = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github/REPOSITORY_SETTINGS.md'
)
foreach ($required in @(
        'Require actions to be pinned to a full-length commit SHA: 有効'
        'Workflow permissions: Read repository contents and packages permissions'
        'Allow GitHub Actions to create and approve pull requests: 有効'
        '`runtime-compatibility-gate`'
    )) {
    Assert-Contains $settingsChecklist $required 'Repository settings checklist'
}

$license = Get-Content -Raw -LiteralPath (Join-Path $root 'LICENSE')
foreach ($required in @(
        'NicoCache License'
        'Copyright (c) 2007, ASR'
        'Redistribution and use in source and binary forms'
        'THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS'
    )) {
    Assert-Contains $license $required 'NicoCache License'
}

$legacyReadme = Get-Content -Raw -LiteralPath (
    Join-Path $root 'documents/archive/legacy/Readme.txt'
)
$trackedLicense = [regex]::Match(
    $legacyReadme,
    '(?s)Copyright \(c\) 2007, ASR.*?' +
    'SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE\.'
).Value
if (-not $trackedLicense) {
    throw 'Tracked NicoCache License text was not found in the legacy README'
}
$rootLicense = $license -replace '^NicoCache License\r?\n\r?\n', ''
$trackedWords = (($trackedLicense -split '\s+') -join ' ').Trim()
$rootWords = (($rootLicense -split '\s+') -join ' ').Trim()
if ($trackedWords -cne $rootWords) {
    throw 'Root LICENSE must reproduce the tracked NicoCache License text'
}

Write-Output 'GitHub community file contract tests passed'
