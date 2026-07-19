package dareka.processor.util;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.URLResource;
import dareka.processor.URLResourceCache;
import dareka.processor.impl.Cache;
import dareka.processor.impl.NLShared;
import dareka.processor.impl.NicoApiUtil;
import dareka.processor.impl.NicoCachingTitleRetriever;

public class GetThumbInfoUtil {

    private static URLResourceCache getthumbinfoCache =
        new URLResourceCache("cacheGetThumbInfoMax", "cacheGetThumbInfoExpire");

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "<video_id>([a-z]{2}(\\d+))</video_id>");
    private static final Pattern THUMBNAIL_URL_PATTERN = Pattern.compile(
            "<thumbnail_url>.+/smile\\?i=(\\d+)</thumbnail_url>");
    private static final NLShared NLSHARED = NLShared.getInstance();

    /**
     * api/getthumbinfoを呼び出した結果のXML文書からsmidを取得する。
     * @param content XML文書文字列
     * @return 取得したsmid文字列、存在しなければnull
     */
    public static String getSmid(String content) {
        if (content != null) {
            Matcher m = VIDEO_ID_PATTERN.matcher(content);
            if (m.find()) {
                String smid = m.group(1), id = m.group(2);
                // キャッシュが存在してかつサムネIDと異なるなら記録しておく
                if (Cache.getPreferredCachedVideo(smid) != null) {
                    m = THUMBNAIL_URL_PATTERN.matcher(content);
                    if (m.find() && !m.group(1).equals(id)) {
                        NLSHARED.put_vid2cid(m.group(1), id);
                    }
                }
                return smid;
            }
        }
        return null;
    }

    /**
     * api/getthumbinfoを呼び出した結果のXML文書からタイトルを取得する。
     * @param content XML文書文字列
     * @return 取得したタイトル文字列、存在しなければnull
     */
    public static String getTitle(String content) {
        if (content != null) {
            return NicoCachingTitleRetriever.getTitleFromXml(content);
        }
        return null;
    }

    /**
     * api/getthumbinfoを呼び出して結果のXML文書を取得する。
     * 既にキャッシュにあればそちらの結果を返す。
     * また、smidが取得出来た場合はタイトルキャッシュを更新する。
     *
     * @param id 取得するID文字列(smid or threadId)
     * @return 取得したXML文書文字列(Rewriter処理済み)、失敗したらnull
     * @throws IOException
     */
    public static String get(String id) throws IOException {
        URLResource r = getURLResource(id);
        if (r == null) {
            return null;
        }
        byte[] responseBody = r.getResponseBody();
        if (responseBody == null) {
            return null;
        }
        String content = new String(responseBody, "UTF-8");
//      String content = Main.getRewriterProcessor().stringRewriter(
//              NicoApiUtil.getThumbURL("", id),
//              new String(responseBody, "UTF-8"),
//              null, r.getResponseHeader(null, null));
// TODO Rewriter内から呼ばれると無限ループに陥る可能性あり、要検討
        String smid = getSmid(content);
        if (smid != null) {
            if (id.matches("\\d+")) {
                NLSHARED.putThread2Smid(id, smid);
            }
            NicoCachingTitleRetriever.putTitleCache(smid, getTitle(content));
        }
        return content;
    }

    /**
     * リトライつき{@link #get(String) get}
     *
     * @param id 取得するID文字列(smid or threadId)
     * @param num リトライ回数
     * @param ms リトライ間隔(ミリ秒)
     * @return 取得したXML文書文字列、失敗したらnull
     * @throws IOException
     */
    public static String getWithRetry(String id, int num, long ms)
            throws IOException {
        if (!isValidSmid(id) && !isValidThreadId(id)) return null;
        String content = null;
        for (int i = 0; i < num; i++) {
            content = get(id);
            if (content != null) break;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) { }
        }
        return content;
    }

    /**
     * api/getthumbinfoを呼び出して受信済の
     * {@link dareka.processor.URLResource URLResource} を取得する。
     * 既にキャッシュにあればそちらの結果を返す。なお、こちらは
     * {@link #get(String)} とは異なりタイトルキャッシュは更新しない。
     *
     * @param id 取得するID文字列(smid or threadId)
     * @return 取得したURLResource、受信できなければnull
     */
    public static URLResource getURLResource(String id) {
        if (!isValidSmid(id) && !isValidThreadId(id)) return null;
        String url = NicoApiUtil.getThumbURL("", id);
        return getthumbinfoCache.cacheAndGet(id, url);
    }

    /**
     * XML文書がキャッシュされているか？
     *
     * @param id 対象のID文字列(smid or threadId)
     * @return キャッシュされていればtrue
     * @since NicoCache_nl+110706mod
     */
    public static boolean isCached(String id) {
        return getthumbinfoCache.isCached(id);
    }

    /**
     * 個々のapi/getthumbinfoキャッシュ内容について古ければ破棄する。
     */
    public static void expires() {
        getthumbinfoCache.expires();
    }

    /**
     * 特定のapi/getthumbinfoキャッシュを削除する。
     *
     * @param id 削除するapi/getthumbinfoキャッシュのID文字列
     */
    public static void remove(String id) {
        getthumbinfoCache.remove(id);
    }

    private static final Pattern SMID_PATTERN = Pattern.compile("^\\w{2}\\d+$");
    private static final Pattern THREADID_PATTERN = Pattern.compile("^\\d+$");

    private static boolean isValidSmid(String id) {
        if (id == null) return false;
        Matcher m = SMID_PATTERN.matcher(id);
        return m.matches();
    }

    private static boolean isValidThreadId(String id) {
        if (id == null) return false;
        Matcher m = THREADID_PATTERN.matcher(id);
        return m.matches();
    }
}
