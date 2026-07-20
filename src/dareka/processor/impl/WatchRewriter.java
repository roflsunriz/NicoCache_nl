package dareka.processor.impl;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

// [nl] watchページ書き換え
public class WatchRewriter implements Rewriter {

    // Rewriter interface
    // https://www.nicovideo.jp/watch/sm9
    // https://www.nicovideo.jp/api/watch/v3/sm9?
    //   動画切り替え
    // https://www.nicovideo.jp/api/watch/v3_guest/sm9?
    //   非ログイン時の動画切り替え・埋め込みプレイヤー
    private static final Pattern WATCH_PAGE_PATTERN = Pattern.compile(
            "^https?://www\\.nicovideo\\.jp/(?:watch|api/watch/v3(?:_guest)?)/([a-z]{2})?(\\d+)" +
            "(|\\?.*)$");

    // 処理するURLの正規表現
    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return WATCH_PAGE_PATTERN;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
        String type = match.group(1);
        String id   = match.group(2);

        //
        // WatchVars を生成してキャッシュする
        // これ以降の Rewriter はキャッシュした WatchVars を利用できる
        //
        WatchVars vars = WatchVars.get(content);

        // スレッドIDの場合は動画IDとの対応も記録する
        if (type == null && vars.getVideoId() != null) {
            NLShared.INSTANCE.putThread2Smid(id, vars.getVideoId());
        }

        return content;
    }

}
