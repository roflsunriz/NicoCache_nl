package dareka.processor.impl;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;
import dareka.processor.util.GetThumbInfoUtil;

// 外部プレイヤー用APIの書き換え
public class ThumbWatchRewriter implements Rewriter {

    private static final Pattern THUMB_WATCH_PATTERN = Pattern.compile(
        "^https?://(POST/)?ext\\.nicovideo\\.jp/thumb_watch(?:$|/([a-z0-9]++))");
    private static final Pattern PARAM_ID_PATTERN = Pattern.compile(
            "(?<=^|&)v=(\\w{2}\\d+)(?=&|$)");
    private static final Pattern PARAM_URL_LOW_PATTERN = Pattern.compile(
            "(?<=^|&)(url=[^&]+nicovideo\\.jp%2Fsmile%3F[vms]%3D(\\d+)\\.\\d+)low");

    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return THUMB_WATCH_PATTERN;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader,
            String content) throws IOException {
        if (match.group(1) != null) { // POST
            processPostContent(content);
        } else {
            if (match.group(2) != null) {
                setupIdInfoCache(match.group(2));
            }
            // キャッシュが存在する場合はlowを削除(?eco=1対応の対策)
            Matcher m = PARAM_URL_LOW_PATTERN.matcher(content);
            if (m.find()) {
                String smid = Cache.id2Smid(m.group(2));
                if (smid != null){
                    VideoDescriptor video = Cache.getPreferredCachedVideo(smid, false, null);
                    if (video != null && !video.isLow()) {
                        content = m.replaceFirst("$1");
                    }
                }
            }
        }
        return content;
    }

    private void processPostContent(String content) {
        Matcher m = PARAM_ID_PATTERN.matcher(content);
        if (m.find()) {
            String v = m.group(1);
            setupIdInfoCache(v);
        }
    }

    // 動画IDとタイトルを取得してIdInfoCache(=タイトルキャッシュ)に設定する
    // 動画のキャッシュ前に取得しないと公式動画を正しく処理できない
    private void setupIdInfoCache(String v) {
        String smid = v.matches("[a-z]{2}\\d+") ? v : null;
        String title = null; // キャッシュ済みならタイトルは不要
        if (smid == null || Cache.getPreferredCachedVideo(smid, false, null) != null) {
            try {
                String thumbinfo = GetThumbInfoUtil.getWithRetry(v, 3, 3000);
                if (thumbinfo != null) {
                    if ((smid = GetThumbInfoUtil.getSmid(thumbinfo)) != null) {
                        NLShared.INSTANCE.putThread2Smid(v, smid);
                    }
                    title = GetThumbInfoUtil.getTitle(thumbinfo);
                }
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        if (smid != null) {
            NicoCachingTitleRetriever.putTitleCache(smid, title);
        }
    }

}
