# Responsibility boundaries

- NicoCache_nl本体は更新GUIを内包しません。
- 独立アップデーターが本体更新と外部依存更新のUI、取得、検証、適用を担当します。
- NicoCache_nl側には、実行中ランタイムを安全に置換するための終了時フックだけを残します。
- 公開中Temurin LTSはAdoptium APIから動的に取得し、対応可否はNicoCache_nl側の互換性宣言と分離します。
