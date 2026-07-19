package dareka.processor.impl;

import java.io.IOException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.NLConfig;
import dareka.common.Logger;
import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

// getFlvを捏造して、通常プレイヤーでも削除動画を再生
public class GetFlvRewriter implements Rewriter {
// 処理するURLの正規表現
    private static final Pattern GETFLV_PATTERN = Pattern.compile(
            "^https?://(POST/)?[^/]+\\.nicovideo\\.jp/api/getflv(.*)$");
// URLのパラメータ部分からsmidを取得用
    private static final Pattern GETFLV_URL_PATTERN = Pattern.compile(
            "(&url=[^&]+nicovideo\\.jp%2Fsmile%3F)([vms]%3D)(\\d+)(\\.\\d+)((?:low)?(?:as3)?&)");
    private static final Pattern GETFLV_URL_PARAM_PATTERN = Pattern.compile(
            "(?:.*?[\\?&][vms]=|/)(\\w{2})([0-9]+)(?:[^0-9]+.*)?");
// errorで捏造時にuser_idが無いとエラーになるので取得
// 起動後すぐはダミー入れてあるけど、コメ投稿したりすると問題出るかも
    private static String userId = "0";
// user_id取得用パターン
    private static final Pattern GETFLV_USERID_PATTERN = Pattern.compile(
            "&user_id=(\\d+)");
    private static final Pattern GETFLV_THREADID_PATTERN = Pattern.compile(
            "thread_id=(\\d+)");

    // Rewriter interface
    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return GETFLV_PATTERN;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
// POST内容の書き換えは何もしない
        if (match.group(1) != null) {
            return content;
        }
        return replace(match.group(2), content);
    }

    // WatchVarsからの呼び出し用に分離
    static String replace(String url_parm, String content) {
// RTMPを返す場合は警告を出すだけ
        if (content.contains("&url=rtmp")) {
            if (!Boolean.getBoolean("disableStreamingWarning")) {
                Matcher m = GETFLV_THREADID_PATTERN.matcher(content);
                if (m.find()) {
                    Logger.info("watch/" + m.group(1) + ": ストリーミング動画のためキャッシュしません");
                }
            }
            return content;
        }
        if (url_parm == null)
            return content;
        String as3 = "";
        if (url_parm.contains("as3=1"))
            as3 = "as3";
        String smid, numid;
        Matcher m = GETFLV_USERID_PATTERN.matcher(content);
        if (m.find()) {
            userId = m.group(1);
        }
        m = GETFLV_URL_PARAM_PATTERN.matcher(url_parm);
        if (m.matches()) {
            smid = m.group(1) + m.group(2);
            numid = m.group(2);
        } else {
//          Logger.info("Unknown getflv pattern. Skip. ("+match.group(0)+")");
            return content;
        }

// URLから生放送かどうか判断する
        if (url_parm.contains("&ckey=")) {
            NLShared.INSTANCE.setLiveMovie(smid);
        }

        if (smid.matches("\\d+")) {
            String threadId = smid;
            if ((smid = NLShared.INSTANCE.thread2smid(threadId)) == null) {
                Logger.debugWithThread(
                        "getting smid failed: threadId=" + threadId);
                return content;
            }
            numid = smid.substring(2);
        }

        // smidと動画IDが異なる場合にsmidでキャッシュするために記憶しておく
        Matcher m_url = GETFLV_URL_PATTERN.matcher(content);
        String videoId = m_url.find() ? m_url.group(3) : numid;
        if (!videoId.equals(numid) && !content.contains("deleted=")) {
            if (NLConfig.matches("deletedVideoId", videoId)) {
                content += "&deleted=1"; // 以降は削除された動画として処理
            } else {
                NLShared.INSTANCE.put_vid2cid(videoId, numid);
            }
        }

        VideoDescriptor video = Cache.getPreferredCachedVideo(smid, false, null);
        if (video != null) {
            Cache cache = new Cache(video);
            String type = video.isLow() ? "low" : "";
            String cacheExt;
            switch (cache.getPostfix()) {
            case Cache.SWF:
                cacheExt = "s"; break;
            case Cache.MP4:
                cacheExt = "m"; break;
            default:
                cacheExt = "v"; break;
            }
            // lowのキャッシュがブラウザに残ってた時の対策
            // eco=1で通常キャッシュがあるときはそのままにして、後で利用する
            if (!video.isLow() && content.contains("low&") &&
                    !url_parm.contains("eco=1") && !url_parm.contains("lo=1")) {
                content = content.replaceFirst("\\w(%3[dD]\\d+\\.\\d+)low&", cacheExt + "$1&");
            }
            // エラーだったら捏造するかも
            if ((content.contains("error=") || content.contains("deleted=")) &&
                    Boolean.getBoolean("deletedMoviePlayMode")) {
                Logger.info("deleted movie. forcePlayMode.");
                Logger.debug(String.format("fake videoId: %s => %s", videoId, numid));
                // 削除動画でキャッシュがあるなら捏造
                if (content.contains("deleted=")) {
                    content = m_url.replaceFirst(
                            "$1" +cacheExt +"%3D" + numid +"$4" + type + as3 + "&");
                    // deleted=\dが存在するとnm動画で再生が失敗する
                    content = content.replaceFirst("&deleted=\\d+", "");
                } else {
                    // エラーでキャッシュありも捏造する
                    content =
                        "l=36000&url=http%3A%2F%2Fwww.nicovideo.jp%2fsmile%3F" +
                        cacheExt + "%3D" + numid + ".12345" + type + as3 +
                        "&ms=http%3A%2F%2Fmsg.nicovideo.jp%2F1%2Fapi%2F&time=" +
                        (new Date().getTime() / 1000) +
                        "&user_id=" + userId +
                        "&done=true";
                }
            }
        }
        NicoCachingTitleRetriever.putTitleCache(smid, null);

        return content;
    }

}
