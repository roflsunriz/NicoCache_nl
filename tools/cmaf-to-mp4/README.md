# NicoCache CMAF/Domand MP4変換

保存済みのNicoCache_nl CMAF/Domandキャッシュを、同梱されている相対パスの
`master.m3u8`からFFmpegで単一のMP4へ変換する独立Javaアプリです。

通常起動はGUIです。入力欄には、完成済みキャッシュフォルダをドラッグ・アンド・
ドロップできます。ドラッグ・アンド・ドロップを使わない場合は「参照」でフォルダを
選択します。出力MP4の保存先、FFmpeg実行ファイル、titleメタデータ、既存ファイルの
置き換えを画面から指定できます。変換後に出力先フォルダを開く設定と、すぐに開く
ボタンもあります。

## 前提

- Java 11以上（ビルドはJava 11向けにコンパイルします）。
- FFmpegが`PATH`上の`ffmpeg`として実行できること、または画面／`--ffmpeg`で実行ファイルを指定すること。
- 入力はNicoCache_nlが保存した完成済みキャッシュフォルダです。キャッシュ内の
  `master.m3u8`、サブプレイリスト、`.cmfv`、`.cmfa`などの相対ファイルを使います。

FFmpegは本アプリに同梱せず、各OSで導入した実行ファイルを使用します。本アプリは
ネットワークプロトコルを許可せず、保存済みローカルファイルだけを読み込みます。
部分キャッシュや欠落セグメントはFFmpegのエラーになります。変換中は一時ファイルへ
書き込み、成功時だけ出力先へ移動するため、入力キャッシュは変更しません。

## ビルド

Windows PowerShell:

```powershell
.\build.ps1
```

Linux/macOS:

```sh
./build.sh
```

どちらも`dist/nico-cmaf-to-mp4.jar`を作成します。JavaのJARと標準APIだけで構成
しているため、同じJARをWindows・Linux・macOSで利用できます。

## GUI

ビルド後、引数なしで起動します。

```text
java -jar dist/nico-cmaf-to-mp4.jar
```

Linux/macOSで画面のない環境ではGUIを起動できないため、`--headless`を指定してCLIを
使用してください。

## CLI／ヘッドレス

```text
java -jar dist/nico-cmaf-to-mp4.jar --headless \
  --input "/path/to/cache/sm12345[720p,128]_Title.hls" \
  --output "/path/to/output/video.mp4"
```

Windows PowerShellでは次のように指定します。

```powershell
java -jar .\dist\nico-cmaf-to-mp4.jar --headless `
  --input "D:\NicoCache\cache\sm12345[720p,128]_Title.hls" `
  --output "D:\NicoCache\output\video.mp4"
```

`--output`を省略すると、入力キャッシュフォルダの隣にフォルダ名から作ったMP4を
出力します。主なオプションは次のとおりです。

```text
--ffmpeg <path>       FFmpegの実行ファイル（既定: ffmpeg、またはFFMPEG_BINARY）
--force               既存の出力を置き換える
--title <text>        MP4のtitleメタデータ
--open-output         変換後にOS標準のファイルマネージャーで出力先を開く
--lang ja|en          CLIの表示言語
--verbose             実行したFFmpegコマンドを表示する
--help                ヘルプ
```

入力にキャッシュの親フォルダを指定した場合、配下の`master.m3u8`が1つなら自動で
選択します。複数ある場合は、変換対象のキャッシュフォルダを直接指定してください。
CLIの終了コードは、成功が`0`、引数・入力エラーが`2`、FFmpeg起動失敗が`3`、
変換失敗が`4`です。

## テスト

```powershell
.\test.ps1
```

```sh
./test.sh
```

テストはFFmpeg本体を必要としない入力探索・引数・コマンド構築・起動失敗・一時
ファイル後始末を確認します。実際のMP4変換は、利用するOSのFFmpegと完成済み
キャッシュを使ってGUIまたはCLIから確認してください。
