# AGENTS.md

- [共通指針](../COMMON-AGENTS.md)と[ルートのAGENTS.md](../AGENTS.md)を全文確認し、着手前に[findings.md](../findings.md)を全文読む。
- 標準フィルターの互換性と読み込み順を維持し、変更対象と非対象の両方を検証する。
- 既存の未コミット変更を戻さず、依頼範囲外の本体やユーザー設定を変更しない。
- 外部管理リンクを通常ファイルへ置換・複製しない。作成・削除・張り替え前に対象とリンク先を確認する。
- Git管理外のローカル領域は、明示指定がなければ調査・変更対象へ含めない。
- フィルター定義の文字コード判定行、既存の文字コードと改行を維持し、秘密情報をログ・コメント・テストへ残さない。
- 構文、正規表現、埋め込みコード、変換後DOMを確認し、再現不能・未検証の範囲を成功扱いしない。
- 再読み込みで足りる場合は本体を再起動しない。Javaプロセスを名前だけで一括終了しない。強制終了には現在の依頼での明示許可を要する。
- 実現手段・API契約・ツールの具体的手順は[移動した本文](../findings.md#nlfilter-guide)に従い、変更履歴と実測結果を正式文書に残す。

## 固有情報の参照

## このディレクトリの役割

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 現行 NicoCache_nl のキャッシュ契約

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 作業前に確認するもの

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## nlFilter Lab の使用

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 詳細リファレンス

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## Git 管理境界とシンボリックリンク

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 読み込み順とファイル名

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## nlFilter 構文を編集するときの規律

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 正規表現を変更するときの規律

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 埋め込み JavaScript / CSS

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 変更方法

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## 検証

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。

## NicoCache_nl の起動・終了が必要な場合

具体的な知見・手順は[findings.md](../findings.md#nlfilter-guide)に従う。
