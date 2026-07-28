NicoCacheCA

NicoCache_nl+mod+mod用の認証局と証明書を作るよ

外部ライブラリに依存しているのでNicoCache_nl本体へ
組み込むのを避けて別アプリケーションにしてあります．

[Required libraries]
 - Bouncy Castle
  - bcprov.jar (bcprov-jdk15on-xxx.jar)
  - bcpkix.jar (bcpkix-jdk15on-xxx.jar)
  - bcutil.jar (bcutil-jdk15on-xxx.jar)

[使い方]
上記ライブラリを
  https://www.bouncycastle.org/latest_releases.html
から入手して，libフォルダに配置してください．
 - lib/bcpkix.jar
 - lib/bcprov.jar
 - lib/bcutil.jar

genCerts.bat (Windows), genCerts.sh (Linux / MacOSX)
を実行すると自動でCAと証明書が生成されます．

certs/ca.cerをお使いのブラウザの証明書ストアに
ウェブサイトの識別をする認証局証明書としてインポートしてください．
認証局としてNicoCache_nl CAが追加されます．


NicoCacheCA.jarの動作は次のとおりです．
1. 認証局のキーストア(certs/ca.jks)が存在しない場合はCAの鍵ペアと証明書を生成する
2. 存在しない場合は認証局のキーストア(certs/ca.jks)からca.cerとca.pemをそれぞれ生成する
3. コマンドライン引数に与えられたドメインをSubject Alternative Nameとして持つ証明書を
   1で作成した認証局で署名して certs/site.{cer,jks} に生成する．
   最初に指定したドメイン名がCNのフィールドに設定されます．
4. コマンドライン引数に与えられたドメインを certs/site.targets ファイルに出力する．

[更新履歴]
210723+240314
・ca.pemも生成するようにした
・jksからpemとcerを復元するようにした

210723
・最新のBouncy Castleに対応 (thx.NicoCache23>>914)
  ・bcutil.jarが新たに必要になった

200228
・CAのextended key usageを追加 (thx.NicoCache23>>622)
・siteのextended key usageを修正

180404
・生成時に対象ドメインをsite.targetsに出力
・エラーメッセージを追加

170421
初版

