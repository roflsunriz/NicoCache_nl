package dareka.processor.impl;

import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;
import dareka.processor.util.GetThumbInfoUtil;

public class NicoCachingTitleRetriever implements Callable<String> {
    /**
     * 動画タイトルの正規表現
     */
    private static final Pattern HTML_H1_PATTERN = Pattern.compile(
            "<h1[^>]*>(.+?)</h1>");
    private static final Pattern HTML_TITLE_CLASS_PATTERN = Pattern.compile(
            "<p (?:class|id)=\"video_title\"[^>]*>(.+?)(?:<a|</p)");
    private static final Pattern HTML_TITLE_VIDEO_PATTERN = Pattern.compile(
            "var Video = \\{[^\\}]+title:\\s*'(.+?)',\\s");
    private static final Pattern STRIP_TAGS_PATTERN = Pattern.compile(
            "<.+?>");
    private static final Pattern API_TITLE_PATTERN = Pattern.compile(
            "<title>(.+?)</title>");

    private String type;
    private String id;

    public NicoCachingTitleRetriever(String type, String id) {
        this.type = type;
        this.id = id;
    }

    // [nl] リクエストヘッダ付き
    private HttpRequestHeader requestHeader = null;

    public NicoCachingTitleRetriever(String smid, HttpRequestHeader reqHdr) {
        this.type = smid.substring(0,2);
        this.id = smid.substring(2);
        this.requestHeader = reqHdr;
    }

    /**
     * [nl] タイトルキャッシュにsmidとタイトルを記録する。
     * 内部的には {@link dareka.processor.impl.NicoIdInfoCache NicoIdInfoCache}
     * に値を保存する。また、既に値が存在する時は変更のある場合のみ保存する。
     *
     * @param smid smid文字列
     * @param title タイトル文字列
     */
    public static void putTitleCache(String smid, String title) {
        if (TextUtil.isVideoId(smid)) {
            String type = smid.substring(0, 2);
            String id   = smid.substring(2);
            NicoIdInfoCache infoCache = NicoIdInfoCache.getInstance();
            NicoIdInfoCache.Entry entry = infoCache.get(id);
            if (title == null && entry != null && entry.isTitleValid()) {
                title = entry.getTitle();
            }
            if (entry == null || !type.equals(entry.getType()) ||
                    (title != null && !title.equals(entry.getTitle()))) {
                if (title != null) {
                    infoCache.put(type, id, title);
                    Logger.debugWithThread(
                            "title recorded: " + type + id + " => " + title);
                } else {
                    infoCache.putOnlyTypeAndId(type, id);
                    Logger.debugWithThread(
                            "type recorded: " + id + " => " + type);
                }
            }
        }
    }

    @Deprecated
    public static boolean containsId(String id) {
        return NicoIdInfoCache.getInstance().get(id) != null;
    }

    @Deprecated
    public static String getSMID(String id) {
        NicoIdInfoCache.Entry entry = NicoIdInfoCache.getInstance().get(id);
        if (entry != null) {
            return entry.getType() + entry.getId();
        }
        return null;
    }

    @Deprecated
    public static String getTitleBySmid(String smid) {
        return getTitleById(smid.substring(2));
    }

    @Deprecated
    public static String getTitleById(String id) {
        NicoIdInfoCache.Entry entry = NicoIdInfoCache.getInstance().get(id);
        if (entry != null) {
            return entry.getTitle();
        }
        return null;
    }

    public String call() throws Exception {
        Logger.debugWithThread("title retrieving start");

/* [nl] GetThumbInfoUtilを使うように変更(本家とのdiffを取るために残す)
        String url = NicoApiUtil.getThumbURL(type, id);
        URLResource r = new URLResource(url);
        // In the general case, proxy must let browser know a redirection,
        // but in this case, behave just as a client.
        r.setFollowRedirects(true);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        r.transferTo(null, bout, null, null);

        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        new HttpResponseHeader(bin); // skip header

        String title = NicoApiUtil.getThumbTitle(bin);
*/
        String title = getTitleFromXml(GetThumbInfoUtil.get(type + id));

        // [nl] 削除されてAPIで取得できない場合はwatchから取得(rc2.04)
        if (title == null) {
            String url = "https://www.nicovideo.jp/watch/" + type + id;
            URLResource r = new URLResource(url);
            r.setFollowRedirects(true);
            HttpResponseHeader res = r.getResponseHeader(null, requestHeader);
            if (res.getStatusCode() == 200) {
                byte[] body = r.getResponseBody();
                if (body != null) {
                    String responseString = new String(body, "UTF-8");
                    title = getTitleFromResponse(responseString);
                }
            }
        }
        if (title == null) {
            Logger.debugWithThread("title retrieving failed");
            return null;
        }
        Logger.debugWithThread("title retrieving end (" + title + ")");

        NicoIdInfoCache.getInstance().put(type, id, title);

        return title;
    }

    /**
     * HTML文書からタイトルを取得する。
     *
     * @param htmlSource HTML文書
     * @return タイトル。見つからない場合はnull。
     */
    public static String getTitleFromResponse(String htmlSource) {
        if (htmlSource != null) {
            WatchVars vars = WatchVars.get(htmlSource);
            if (vars.getVideoTitle() != null) {
                return vars.getVideoTitle();
            }
// // TODO これ以降に辿り着くことは無いはずなのでそのうち削除
//             Logger.warning("WatchVars failed, use old code...");
//
// // 2010/10/14の仕様変更でH1でヘッディングされなくなった
// // 念のため <p class="video_title"→Video.title→H1 の順に取得を試みる
//             Matcher mTitle = HTML_TITLE_CLASS_PATTERN.matcher(htmlSource);
//             if (mTitle.find()) {
//                 return unescape(TextUtil.stripTags(mTitle.group(1)));
//             } else {
//                 Logger.debug("no title matched, use alternative matching...");
//             }
//             mTitle = HTML_TITLE_VIDEO_PATTERN.matcher(htmlSource);
//             if (mTitle.find()) {
//                 return TextUtil.ascii2native(mTitle.group(1));
//             }
//             mTitle = HTML_H1_PATTERN.matcher(htmlSource);
//             if (mTitle.find()) {
//                 return unescape(TextUtil.stripTags(mTitle.group(1)));
//             }
//             Logger.warning("title matching failed");
        }
        return null;
    }

    /**
     * APIのxml文書からタイトルを取得する。
     *
     * @param xmlSource XML文書
     * @return タイトル。見つからない場合はnull。
     */
    public static String getTitleFromXml(String xmlSource) {
        if (xmlSource != null) {
            Matcher mTitle = API_TITLE_PATTERN.matcher(xmlSource);
            if (mTitle.find()) {
                return TextUtil.unescapeHTML(mTitle.group(1));
            }
        }
        return null;
    }

    /**
     * {@link dareka.common.TextUtil#unescapeHTML(String)} を使ってください
     */
    // タイトルに含まれそうなものだけ限定のunescapeHTML()もどき
    @Deprecated
    public static String unescape(String title) {
        return title.replace("&amp;","&").replace("&apos;", "'").replace("&quot;", "\"");
    }

    /**
     * {@link dareka.common.TextUtil#stripTags(String)} を使ってください
     */
    // Prototype.jsにある同名関数のパク(ry
    @Deprecated
    public static String stripTags(String title) {
        Matcher m = STRIP_TAGS_PATTERN.matcher(title);
        if (m.find()) {
            return m.replaceAll("");
        } else {
            return title;
        }
    }

    /**
     * {@link dareka.common.TextUtil#ascii2native(String)} を使ってください
     */
    // \\uXXXXにエンコードされた文字列をUTF-16に戻す(native2asciiの逆)
    @Deprecated
    public static String ascii2native(String ascii) {
        return TextUtil.ascii2native(ascii);
    }

}
