# Responsibility boundaries

- NicoCache_nl本体は更新GUIや依存関係更新エンジンを内包しません。
- 独立アップデーターが本体更新と外部依存更新のUI、取得、ハッシュ検証、展開、バックアップ、適用、ロールバックを担当します。
- 外部依存関係の処理はアップデーター内の純Javaエンジンで行い、PowerShellや対象側のスクリプトには依存しません。
- アップデーター自身のインストール先と更新対象のNicoCache_nlルートを分離し、対象ルート外への書き込みとリンク経由の逸脱を拒否します。
- 公開中Temurin LTSはAdoptium APIから動的に取得し、NicoCache_nlで検証済みの17/21/25を選択可能にします。Java 25を推奨・既定とします。
- 配布物はSHA-256またはSHA-512を取得できる場合だけ受理し、7-ZipのGitHub Release assetsもAPIのSHA-256 digestを必須とします。
- AppImage/MSI成果物は専用Javaランタイムだけを内包し、PowerShellスクリプトやPowerShellデータファイルを同梱しません。
