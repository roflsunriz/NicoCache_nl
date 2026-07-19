package dareka.processor.impl;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.impl.CmafUseCacheProcessor;
import dareka.processor.util.AudioExtractor;
import dareka.processor.util.CommentDownloader;
import dareka.processor.util.Hls2SingleConverter;

/*
 * [nl] cacheフォルダ関係の処理.
 *
 * - 2024-03-29: hls→mp4仕様を追加. あまり検証していないが従来の仕様とは衝突し
 *   ないはず. これを実装前はhlsキャッシュに対する要求には404を返していた.
 * - /cache/<smid>/auto/movie アクセス時にhlsキャッシュ動画であった場合は、下記
 *   のようなmp4 URLへリダイレクト.
 * - https://www.nicovideo.jp/cache/sm9.mp4
 *   https://www.nicovideo.jp/cache/sm9.hls.mp4
 *   https://www.nicovideo.jp/cache/sm9[360p,128].mp4
 *   などにアクセスした場合に、キャッシュがhlsであっても、mp4に変換して返す.
 * - webm,mkv,flvへの変換にも対応しているがまだ使い途はない. 動画コンテナが
 *   コーデックを扱えない場合は失敗する.
 */
public class CacheDirProcessor implements Processor {

    CmafUseCacheProcessor cacheFileDirProcessor =
        new CmafUseCacheProcessor();

    // 任意のMethodにマッチ
    private static final String[] SUPPORTED_METHODS = new String[] { null };

    private static final Pattern CACHE_DIR_PATTERN = Pattern
        .compile("^https?://[^/]+\\.nicovideo\\.jp/cache/?(|.+)$");

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return CACHE_DIR_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    // api urlに続くAlt IDを検出するために^$は省略.
    private static final Pattern ALT_ID_PATTERN = Pattern.compile(
        // group(1), マッチ全体.
        "(" +
        // group(2). smXXX.
        "([a-z]{2}[0-9]+)" +
        // group(3).
        "(low)?" +
        // group(4), dmc部分, 省略可.
        "(" +
        // video mode, video kbps, audio kbps
        "\\[[\\w-]+(?:,\\d+)?,\\d+\\]" +
        // srcId(wクラスではなく[0-9a-f]ではないか？)
        "\\w*" +
        // 拡張子
        "\\.(?:swf|flv|mp4|hls|webm|mkv)" +
        ")?" +
        ")"
        );

    private static final Pattern LOCAL_FLV_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*)?(?:\\.(?:flv|swf|mp4|webm|mkv)){1,2}$"); // double suffix for cachemanager workaround
    private static final Pattern LOCAL_HLS_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*)?\\.hls$");
    private static final Pattern AUDIO_EXTRACT_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls|webm|mkv))?\\.(?:mp3|m4a)$");
    private static final Pattern HLS_CONVERT_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\])?\\w*\\.hls)?\\.(?:mp4|mkv|webm|flv)$");
    private static final Pattern COMMENT_API_PATTERN = Pattern.compile(
            "^(\\w{2}\\d+)\\.xml(?:|\\?(.*))$");
    private static final Pattern LST_API_PATTERN = Pattern.compile(
            "^(add|trim)(r)?(list/[^\\?]+)(?:|\\?(.+))$");
    private static final Pattern SEARCH_API_PATTERN = Pattern.compile(
            "^r?search/([^\\?]+)(\\?.+)?$");

    // 削除予定. replaced by #mightProcessInfoAPI()
    private static final Pattern INFO_API_PATTERN = Pattern.compile(
            "^oldinfo(/v2)?\\?(.+)$");

    private static final Pattern MOVID_AUDIO_REDIRECTER_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4))?(/auto)?/(movie|audio)$");

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {

        // - 2026-06-28: 以前はAPI pathが一致していてもパラメータパターン不一致ならば
        //   ログ"no method:xxx"とhttp-404(not found)を返していた.
        //   (例えば従来仕様のクエリ部のない GET /cache/info )
        // - この仕様を変更し, パラメータ不正はhttp-400(bad request)を返すとする.
        // - 順次その仕様へ変更する.
        // - (このファイルではパラメータを含まない"info/v2"のような部分をAPI pathと呼ぶ).

        // - onRequestが長く複雑であるため整理する.
        // - 子メソッドを繰り返し呼び出して, 非nullを返したらonRequestはその値を返す.
        // - 子メソッド仕様:
        //   - 名称テンプレートは mightProcess{Name}API.
        //   - API pathが一致していなければ何もせずにnullを返す.
        //   - API pathさえ一致していれば以降のエラー処理なども行う.
        //   - HTTP Method違いの場合はhttp-405(method not allowed)を返す.
        //   - パラメータエラーにはhttp-400(bad request)を返す.

        // - onRequestの最後にあるべきだが長く複雑であるため, まだここに置く.
        // - GET, POST以外も捕まえてサーバに存在しないURLへのリクエストが飛ぶのを防ぐ
        if (!requestHeader.isGetMethod() && !requestHeader.isPostMethod()) {
            return StringResource.getMethodNotAllowed();
        };

        // この変数は何度か変更されるので注意.
        String path = requestHeader.getPath();

        if (!path.startsWith("/cache/")) {
            Logger.warning("CacheDirProcessor.java: programming error(1)");
            return StringResource.getNotFound();
        };

        // "/cache/"よりも後の部分.
        path = path.substring(7);

        // - "/cache/"よりも後の部分, なおかつ'?'以降を含まない.
        // - 例: "info", "info/v2"
        final String apiPath = skipSlash(requestHeader.getPathWithoutQuery(), 2);

        // - might be null.
        // - 例: "sm1234"
        // - queryという名前にすべきだがメソッド内で名前が衝突するため別名を使う.
        final String apiArg = requestHeader.getQuery();

        if (apiPath == null) {
            Logger.warning("CacheDirProcessor.java: programming error(2)");
            return StringResource.getNotFound();
        };

        // 共通チェック終了.

        Resource result = null;

        result = mightProcessInfoAPI(requestHeader, browser, apiPath, apiArg);
        if (result != null) return result;

        result = mightProcessRemoveAPI(requestHeader, browser, apiPath, apiArg);
        if (result != null) return result;

        result = mightProcessEchoAPI(requestHeader, browser, apiPath, apiArg);
        if (result != null) return result;

        // このパートは削除予定. replaced by #mightProcessInfoAPI()
        if (HttpHeader.POST.equals(requestHeader.getMethod())) {
            String postString = Main.getRewriterProcessor().getPostString(
                    requestHeader, browser, null);
            if (postString.length() > 0) {
                if (path.equals("oldinfo")) {
                    return processCacheInfoAPI(postString, 1);
                } else if (path.equals("oldinfo/v2")) {
                    return processCacheInfoAPI(postString, 2);
                }
            }
            return StringResource.getNotFound();
        }

        // 単一キャッシュを対象としないAPIを先に処理する
        Matcher m;
        if ((m = SEARCH_API_PATTERN.matcher(path)).matches()) {
            String query = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            boolean desc = m.group(2) != null && m.group(2).contains("order=d");
            boolean regex = path.startsWith("r");
            StringBuilder sb = createJsonStringBuilder();
            for (VideoDescriptor v : SearchRewriter.searchVideo(query, regex, desc).keySet()) {
                if (Cache.writeJSON(v, sb)) sb.append(",\n");
            }
            return createJsonStringResource(sb);
        }

        // このパートは削除予定. replaced by #mightProcessInfoAPI()
        if ((m = INFO_API_PATTERN.matcher(path)).matches()) {
            return processCacheInfoAPI(m.group(2), "/v2".equals(m.group(1)) ? 2 : 1);
        }

        // "/cache/file/param1=xxx&param2=yyy//master.m3u8".
        if (path.startsWith("file/")) {
            return cacheFileDirProcessor.onRequestSubPath(
                path.substring("file/".length()), requestHeader, browser);
        };

        // 以降は単一キャッシュを対象とするこれまでの処理

        String altid = "";
        String smid = "";
        VideoDescriptor video = null;
        Cache cache = null;

        {
            Specifier spec = new Specifier(path);
            altid = spec.altid;
            smid = spec.smid;
            video = spec.video;
            cache = spec.cache;

            // - 振舞いを旧仕様に合わせる.
            // - このパートは廃止予定.
            if (video != null && cache == null) {
                cache = new Cache(video);
            };

            // 2024-03-29に以下のコード追記. 要求されたものとは違う品質を返す
            // ことは不適切かも知れない.
            if (cache != null && !cache.exists()) {
                video = Cache.getPreferredCachedVideo(smid);
                cache = new Cache(video);
            };
        };

        // [nl] ローカルFLV（単一ファイルでキャッシュを送る）
        if (Boolean.getBoolean("localFlv") &&
            (m = LOCAL_FLV_PATTERN.matcher(path)).matches()) {

            if (cache == null || !cache.exists()) {
                return StringResource.getNotFound();
            }
            Logger.info("Local Flv: " + cache.getId() + " " + cache.getTitle());
            if (Boolean.getBoolean("touchCache")) {
                cache.touch();
            }

            if (cache.getPostfix().equals(Cache.HLS)) {
                Hls2SingleConverter o = new Hls2SingleConverter(
                    cache, path, requestHeader.getMessageHeader("User-Agent"));
                return o.convert();
            };

            Resource r = new LocalFileResource(cache.getCacheFile());
            LimitFlvSpeedListener.addTo(r);
            r.addCacheControlResponseHeaders(12960000);
            return r;
        };

        // [nl] ローカルHLS（複数ファイルでキャッシュを送る）
        if (Boolean.getBoolean("localFlv") &&
            (m = LOCAL_HLS_PATTERN.matcher(path)).matches()) {

            return StringResource.getRedirect(
                requestHeader.getScheme()
                + "://www.nicovideo.jp/cache/file/nicocachenl_refcache="
                + m.group(0)
                + "//");
        };


        // 音声ファイル抽出
        if ((m = AUDIO_EXTRACT_PATTERN.matcher(path)).matches()) {
            AudioExtractor ae = new AudioExtractor(
                    cache, path, requestHeader.getMessageHeader("User-Agent"));
            return ae.extract();
        }

        // http://localhost/cache/sm9.hls.mp4
        if ((m = HLS_CONVERT_PATTERN.matcher(path)).matches()) {
            Hls2SingleConverter o = new Hls2SingleConverter(
                cache, path, requestHeader.getMessageHeader("User-Agent"));
            return o.convert();
        };

        // コメント取得
        if ((m = COMMENT_API_PATTERN.matcher(path)).matches()) {
            String id = m.group(1);
            String params = m.group(2);
            Resource r;
            if (params == null) { // パラメータ無し(HTML5形式)
                WatchVars wv = WatchVars.get(id);
                if (wv != null) {
                    r = CommentDownloader.getURLResource(id, wv, requestHeader);
                } else {
                    return StringResource.getInternalError("watchページをリロードしてください");
                }
            } else { // パラメータ有りならGINZA形式
                r = CommentDownloader.getURLResource(id,
                        params.contains("has_owner_thread=1"), requestHeader);
            }
            if (r == null){
                return StringResource.getInternalError("処理中にエラーが発生しました");
            }
            return r;
        }

        // 適切なダウンロードパスへリダイレクトさせる(夏.01)
        Matcher mMovieAudio = MOVID_AUDIO_REDIRECTER_PATTERN.matcher(path);
        if (mMovieAudio.matches()) {
            if (mMovieAudio.group(1) != null) {
                // auto select
                video = Cache.getPreferredCachedVideo(smid);
                if (video != null) {
                    cache = new Cache(video);
                }
            }
            if (video == null || cache == null || !cache.exists()) {
                return StringResource.getNotFound();
            }
            String ext = cache.getPostfix();
            String base = requestHeader.getScheme() + "://www.nicovideo.jp/cache/" +
                    Cache.videoDescriptorToAltId(video);
            if (mMovieAudio.group(2).equals("movie")) {
                if (video.isDmc()) {
                    // dmcのaltidには拡張子が入っている
                    if (ext.equals(Cache.HLS)) {
                        // hlsを変換URLへ.
                        return StringResource.getRedirect(base + ".mp4");
                    }
                    else {
                        return StringResource.getRedirect(base);
                    }
                } else {
                    return StringResource.getRedirect(base + ext);
                }
            } else {
                if (ext.equals(Cache.FLV) || ext.equals(Cache.SWF)) {
                    return StringResource.getRedirect(base + ".mp3");
                } else if (ext.equals(Cache.MP4) || ext.equals(Cache.HLS)) {
                    return StringResource.getRedirect(base + ".m4a");
                }
            }
            return StringResource.getNotFound();
        }
        // [nl] crossdomain.xmlにNotFound（同一ドメインなので本来必要ない）
        else if (path.equals("crossdomain.xml")) {
            return StringResource.getNotFound();
        }
        // [nl] キャッシュ管理ページ
        else if (path.equals("") || path.endsWith(".html")) {
            // [nl] swfからのリクエストなら簡易版にする
            if (requestHeader.getMessageHeader("x-flash-version") != null) {
                String flvList = "# my cache\r\n" + Cache.getFlvListForLocalFlv() + "\n";
                return createStringResource(flvList);
            }
            long start = System.currentTimeMillis();
            String str;
            if (path.equals("")) {
                str = Cache.getFlvList();
            } else {
                str = Cache.getFlvList("/local/" + path);
            }
            long elapsed = System.currentTimeMillis() - start;
            Logger.info("Local Flv List (" + elapsed + "msec)");
            return getStringResource(str, "text/html");
        }
        // [nl] リロードなしのflvのid一覧 (FxでのLocal Flv用)
        else if (path.equals("flvlist") || path.equals("flvlist/")) {
            String flvList = "# my cache\r\n" + Cache.getFlvListForLocalFlv() + "\n";
            return createStringResource(flvList);
        }
        else if (path.equals("flvlist/crossdomain.xml")) {
            return StringResource.getNotFound();    // ↑で"flvlist/"を追加したので
        }
        // [nl] キャッシュ管理ページ(ajax版)
        else if (path.equals("ajax")) {
            long start = System.currentTimeMillis();
            String str = Cache.getFlvListAjax();
            long elapsed = System.currentTimeMillis() - start;
            Logger.info("Local Flv List ajax (" + elapsed + "msec)");
            return getStringResource(str, "application/x-javascript");
        }
        //[nl] + JSON fix
        else if (path.endsWith(".json")) {
            String name = null;
            String str = null;
            long start = System.currentTimeMillis();
            switch (path) {
            case "cachelist.json":
                name = "Local Cache List as JSON";
                str = Cache.getCacheListAsJson();
                break;
            case "templist.json":
                name = "Local Temp Cache List as JSON";
                str = Cache.getTempListAsJson();
                break;
            case "dirlist.json":
                name = "Local Cache Dir List as JSON";
                str = Cache.getDirListAsJson();
                break;
            case "flvlist.json":
                name = "Local Flv List as JSON";
                str = Cache.getFlvListAsJson();
                break;
            }
            long elapsed = System.currentTimeMillis() - start;

            if (str != null) {
                Logger.info("%s (%d msec)", name, elapsed);
                return getStringResource(str, "application/json");
            }
            return StringResource.getNotFound();
        }
        //[xmlfix] XML取得
        else if (path.startsWith("getxml")) {
            if (path.length() < 7) {
                return StringResource.getBadRequest();
            }
            HashMap<String, String> query = new HashMap<>();

            String key, value;
            for (String params : path.substring(7).split("&")) {
                String[] param = params.split("=",2);
                if (params.length() > 1) {
                    key = param[0]; value = param[1];
                    value = decodeURLForUTF8(value);
                    query.put(key, value);
                }
            }

            String name = "";
            String xml = null;
            String type = query.get("type");

            long start = System.currentTimeMillis();
            if (null != type) switch (type) {
            case Cache.TYPE_DIRLIST: // ディレクトリ一覧
                name = "Local Cache Dir List as XML";
                xml = Cache.getDirListAsXml(query);
                break;
            case Cache.TYPE_TEMPLIST: // 一時ファイル一覧
                name = "Local Temp Cache List as XML";
                xml = Cache.getTempListAsXml(query);
                break;
            case Cache.TYPE_CACHELIST: // キャッシュ一覧
                name = "Local Cache List as XML [" + query.get("path") + "]";
                xml = Cache.getCacheListAsXml(query);
                break;
            case Cache.TYPE_CACHELIST_ALL: // キャッシュ一覧（すべて）
                name = "Local Cache List as XML";
                xml = Cache.getCacheListAsXml(query);
                break;
            }
            long elapsed = System.currentTimeMillis() - start;

            if (xml != null) {
                //Logger.info("%s (%d msec)", name, elapsed);
                Logger.debug(name + " (" +  elapsed + " msec)");
                StringResource r = new StringResource(xml);
                //r.addNoCacheResponseHeaders();
                r.setResponseHeader("Expires", "Thu, 01 Dec 1994 16:00:00 GMT");
                r.setResponseHeader("Cache-Control", "no-store");
                r.setResponseHeader("Pragma", "no-cache");
                r.setResponseHeader("Content-Type", "application/xml; charset=UTF-8");
                return r;
            }
            return StringResource.getBadRequest();
        }
        // ログ(夏.??)
        else if (path.equals("log")) {
            if (ViewableLoggerHandler.isLogging()) {
                return new StringResource(ViewableLoggerHandler.getLogWithTableTag());
            } else {
                return new StringResource("NicoCache_nlのログをページから見るには、<br>enableLogHandler=true<br>としてください。");
            }
        }

        // デコード後、ajaxかどうかの判定
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        boolean ajax = false;
        if (path.startsWith("ajax_")) {
            ajax = true;
            path = path.substring(5);
        }

        String msg = "NG";

        // このパートは削除予定. replaced by #mightProcessRemoveAPI()
        // [nl] リストページ
        // [nl] ローカルFLVリスト→削除
        if (path.matches("oldrm(?:tmp)?\\?[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?(?:low)?")) { // tail low for cachemanager workaround
            if (path.startsWith("rmtmp")) {
                Logger.info("Remove Temporary: " + altid);
                if (Cache.removeTmp(video)) {
                    msg = "OK";
                }
            } else {
                Logger.info("Remove Cache: " + smid);
                if (Cache.remove(video)) {
                    msg = "OK";
                }
            }
            if (msg.equals("NG")) {
                Logger.info("...failed.");
            }
        }
        else if (path.matches("oldrmall\\?[a-z]{2}[0-9]+")) {
            Logger.info("Remove All Cache: " + smid);
            if (Cache.removeAll(smid)) {
                msg = "OK";
            }
            if (msg.equals("NG")) {
                Logger.info("...failed.");
            }
        }
        // [nl] ローカルFLVリスト→タイトルを設定
        else if (path.matches("title\\?[a-z]{2}[0-9]+(low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?(-.*)?")) {
            if (cache != null && cache.exists()) {
                String title = null;
                String[] t = path.split("-", 2);
                if (t.length == 1) {
                    try {   // ニコニコから取得
                        title = new NicoCachingTitleRetriever(smid, requestHeader).call();
                    } catch (Exception e) {}
                } else {    // 引数で設定
                    title = t[1];
                }
                if (title != null) {
                    title = Cache.getSanitizedDescription(title);
                    if (cache.setTitle(title)) {
                        msg = "OK " + title;
                    }
                }
            }
        }
        // [nl] ローカルFLVリスト→フォルダ移動
        else if (path.matches("move\\?([a-z]{2}[0-9]+(low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?)-.*")) {
            if (cache != null && cache.exists()) {
                String[] t = path.split("-", 2);
                if (cache.moveTo(t[1]))
                    msg = "OK";
            }
        }
        // [nl] ローカルFLVリスト→topの時のみフォルダ移動
        else if (path.matches("topmove\\?([a-z]{2}[0-9]+(low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?)-.*")) {
            if (cache != null && cache.exists()) {
                String[] t = path.split("-", 2);
                if ("".equals(Cache.getPathFromVideoDescriptor(video)) && cache.moveTo(t[1]))
                    msg = "OK";
            }
        }
        // 以下のパートは削除予定. replaced by #mightProcessInfoAPI()
        // idからキャッシュ情報を取得(拡張性が足りなくてdmc対応できないので非推奨)
        else if (path.matches("oldinfo\\?[a-z]{2}[0-9]+(low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?")) {
            VideoDescriptor preferredSmileVideo = Cache.getPreferredCachedVideo(smid, false, null);
            if (preferredSmileVideo != null) {
                Cache preferredSmileCache = new Cache(preferredSmileVideo);
                msg = "OK,"+(preferredSmileVideo.isLow()?"low":"")+","
                        +preferredSmileCache.getPostfix()+","
                        +preferredSmileCache.length()+",0,"
                        +preferredSmileCache.getTitle();
            } else if (video != null) {
                if (Cache.getDLFinalSize(video) != 0) {
                    msg = "OK,,,"+Cache.getDLSize(video)+","+Cache.getDLFinalSize(video)+",";
                } else if (Cache.getDLFinalSize(video.replaceLow(true)) != 0) {
                    msg = "OK,low,,"+Cache.getDLSize(video.replaceLow(true))+","+Cache.getDLFinalSize(video.replaceLow(true))+",";
                }
            }
        }
        // LST処理API
        else if ((m = LST_API_PATTERN.matcher(path)).matches()) {
            ajax = true;
            String cmd = m.group(1), file = m.group(3), value = m.group(4);
            boolean regex = m.group(2) != null;
            if (value == null) value = "";
            if ("add".equals(cmd) && value.length() > 0) {
                if (EasyRewriter.LST.append(file, value, regex)) {
                    msg = "OK";
                }
            } else if ("trim".equals(cmd)) {
                if (EasyRewriter.LST.trim(file, value.contains("type=smid"))) {
                    msg = "OK";
                }
            }
        }
        else {
            Logger.info("no method:" + path);
            return StringResource.getNotFound();
        }

        // 何かしらハンドラが有った場合は最後に応答を返す
        if (ajax) {
            return createStringResource(msg);
        } else {
            return createRedirectResponse(requestHeader);
        }
    }

    private static Resource createRedirectResponse(HttpRequestHeader requestHeader) {
        String redir;
        String nlFrom = requestHeader.getParameter("nl-region");

        if (nlFrom != null
            && (nlFrom.equals("cache") || nlFrom.equals("local"))) {

            redir = requestHeader.getParameter("nl-from");
        } else {
            redir = "http://www.nicovideo.jp/";
        };

        StringResource r = StringResource.getRedirect(redir);
        r.addNoCacheResponseHeaders();
        return r;
    };

    /**
     * /cache/info (GET,POST)
     * /cache/info/v2 (GET,POST)
     * /cache/ajax_info (GET)
     *
     * 2026-06-28: 以前は空のqueryには"no method:xxx"とhttp-404を返していた.
     * 仕様を変更して空のqueryには空のjsonを返すとする.
     * また以前はajax_info以外, GET時にqueryをURL decodeせずに処理していた.
     * 仕様を変更してdecodeしてから処理する.
     *
     * @throws IOException browserからのpost contentを読み込み中にエラーが起きた場合.
     */
    private static Resource mightProcessInfoAPI
    (HttpRequestHeader requestHeader, Socket browser, String path, String query)
        throws IOException {

        boolean isget = requestHeader.isGetMethod();
        boolean ispost = requestHeader.isPostMethod();

        if (query == null) {
            query = "";
        } else {
            query = URLDecoder.decode(query, StandardCharsets.UTF_8);
        };

        if (path.equals("info")) {
            if (isget) {
                return processCacheInfoAPI(query, 1);
            };
            if (ispost) {
                return processCacheInfoAPI(loadPostContent(requestHeader, browser), 1);
            };
            return StringResource.getMethodNotAllowed();
        };

        if (path.equals("info/v2")) {
            if (isget) {
                return processCacheInfoAPI(query, 2);
            };
            if (ispost) {
                return processCacheInfoAPI(loadPostContent(requestHeader, browser), 2);
            };
            return StringResource.getMethodNotAllowed();
        };

        if (path.equals("ajax_info")) {
            if (!isget) {
                return StringResource.getMethodNotAllowed();
            };
            return processAjaxCacheInfoAPI(query);
        };

        return null;
    };

    private static Resource processAjaxCacheInfoAPI(String query) {
        // コメント転記:
        // idからキャッシュ情報を取得(拡張性が足りなくてdmc対応できないので非推奨)

        // - sm123_title.mp4 のような[]の無い形式にしか対応していない.
        // - 利用例もなさそうだがそのままにしておく.

        // - 元々は次のような正規表現でパラメータチェックをしていた
        //   (pathはURL decode後)
        //   path.matches("info\\?[a-z]{2}[0-9]+(low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*\\.(?:flv|mp4|hls))?")
        // - 一致しない場合はhttp-404(not found)を返していた.
        // - 仕様を変更して"NG"を返す.

        Specifier spec = new Specifier(query);
        String msg = "NG";

        VideoDescriptor preferredSmileVideo
            = Cache.getPreferredCachedVideo(spec.smid, false, null);

        if (preferredSmileVideo != null) {
            Cache preferredSmileCache = new Cache(preferredSmileVideo);
            msg = "OK," + (preferredSmileVideo.isLow()?"low":"") + ","
                + preferredSmileCache.getPostfix() + ","
                + preferredSmileCache.length() + ",0,"
                + preferredSmileCache.getTitle();

        } else if (spec.video != null) {
            if (Cache.getDLFinalSize(spec.video) != 0) {
                msg = "OK,,," + Cache.getDLSize(spec.video) + ","
                    + Cache.getDLFinalSize(spec.video)+",";

            } else if (Cache.getDLFinalSize(spec.video.replaceLow(true)) != 0) {
                msg = "OK,low,," + Cache.getDLSize(spec.video.replaceLow(true)) + ","
                    + Cache.getDLFinalSize(spec.video.replaceLow(true))+",";
            };
        };

        return createStringResource(msg);
    };

    // キャッシュ情報一括取得API
    private static Resource processCacheInfoAPI(String query, int version) {
        StringBuilder sb = createJsonStringBuilder();
        for (String id : query.split("[,\\s]+")) {
            String smid = id;
            if (id.matches("\\d+")) {
                if (id.length() >= 10)
                    smid = NLShared.INSTANCE.thread2smid(id);
                else
                    smid = Cache.id2Smid(id);
            } else if (!id.matches("[a-z]{2}\\d+")) {
                return StringResource.getBadRequest();
            }
            sb.append('"').append(id).append('"').append(':');
            boolean success = false;
            if (version == 1) {
                success = setCacheInfo(sb, smid);
            } else if (version == 2) {
                if (smid != null) {
                    smid = smid.substring(0, 2) + NLShared.INSTANCE.vid2cid(smid.substring(2));
                    success = setCacheInfo2(sb, smid);
                } else {
                    success = setCacheInfo2(sb, id);
                }
            } else {
                Logger.info("processCacheInfoAPI: programming error: version=" + version);
            }
            if (!success) {
                sb.append("null");
            }
            sb.append(",\n");
        }
        return createJsonStringResource(sb);
    }

    private static boolean setCacheInfo(StringBuilder sb, String videoId) {
        VideoDescriptor video = Cache.getPreferredCachedVideo(videoId);
        VideoDescriptor cachingSmile = null;
        VideoDescriptor cachingDmc = null;
        VideoDescriptor cachingSuperior = null;
        SortedSet<VideoDescriptor> videos = CacheManager.id2Videos.get(videoId);
        if (videos == null) {
            return false;
        }
        // キャッシュ進行中のVideoDescriptorを探す
        for (VideoDescriptor v : videos) {
            if (CacheManager.getDLFlag(v)) {
                if (v.isDmc()) {
                    if (cachingDmc == null) {
                        cachingDmc = v;
                    }
                } else {
                    if (cachingSmile == null) {
                        cachingSmile = v;
                    }
                }
                if (cachingSuperior == null) {
                    cachingSuperior = v;
                }
            }
        }
        if (video == null) { // キャッシュ無し
            if (cachingSuperior != null) {
                video = cachingSuperior;
            } else {
                return false;
            }
        }
        Cache cache = new Cache(video);

        sb.append('{');
        putKeyValueStingily(sb, "videoId", videoId);
        putKeyValueStingily(sb, "cacheId", Cache.videoDescriptorToAltId(video));
        putKeyValueStingily(sb, "economy", video.isLow());
        putKeyValueStingily(sb, "dmc", video.isDmc());
        if (video.isDmc()) {
            putVideoMode(sb, "dmcMovieType", video);
        }
        if (Cache.getDLFlag(video)) {
            putKeyValueStingily(sb, "size", Cache.getDLFinalSize(video));
            putKeyValueStingily(sb, "caching", true);
            putKeyValueStingily(sb, "cachingSize", Cache.getDLSize(video));
        } else {
            putKeyValueStingily(sb, "size", cache.length());
        }
        if (cache.exists()) {
            if (cachingSmile != null && !cachingSmile.isLow()) {
                putKeyValueStingily(sb, "normalCacheId", Cache.videoDescriptorToAltId(cachingSmile));
                putKeyValueStingily(sb, "normalSize", Cache.getDLFinalSize(cachingSmile));
                putKeyValueStingily(sb, "normalCachingSize", Cache.getDLSize(cachingSmile));
            }
            if (cachingDmc != null) {
                if (!cachingDmc.isLow()) {
                    putKeyValueStingily(sb, "dmcNormalCacheId", Cache.videoDescriptorToAltId(cachingDmc));
                    putKeyValueStingily(sb, "dmcNormalSize", Cache.getDLFinalSize(cachingDmc));
                    putKeyValueStingily(sb, "dmcNormalCachingSize", Cache.getDLSize(cachingDmc));
                    putVideoMode(sb, "dmcNormalMovieType", cachingDmc);
                } else {
                    putKeyValueStingily(sb, "dmcLowCacheId", Cache.videoDescriptorToAltId(cachingDmc));
                    putKeyValueStingily(sb, "dmcLowSize", Cache.getDLFinalSize(cachingDmc));
                    putKeyValueStingily(sb, "dmcLowCachingSize", Cache.getDLSize(cachingDmc));
                    putVideoMode(sb, "dmcLowMovieType", cachingDmc);
                }
            }
            if (cachingSuperior != null) {
                putKeyValueStingily(sb, "superiorCacheId", Cache.videoDescriptorToAltId(cachingSuperior));
                putKeyValueStingily(sb, "superiorSize", Cache.getDLFinalSize(cachingSuperior));
                putKeyValueStingily(sb, "superiorCachingSize", Cache.getDLSize(cachingSuperior));
                putKeyValueStingily(sb, "superiorIsDmc", cachingSuperior.isDmc());
                putKeyValueStingily(sb, "superiorIsEconomy", cachingSuperior.isLow());
                if (cachingSuperior.isDmc()) {
                    putVideoMode(sb, "superiorDmcMovieType", cachingSuperior);
                }
            }
            putKeyValueStingily(sb, "movieType", cache.getPostfix().substring(1));
            putKeyValueStingily(sb, "title", cache.getTitle());
            putKeyValueStingily(sb, "subFolder", Cache.getPathFromVideoDescriptor(video));
            putKeyValueStingily(sb, "ts", cache.getCacheFile().lastModified() / 1000L);
        }
        sb.replace(sb.length() - 1, sb.length(), "}");

        return true;
    }

    private static boolean setCacheInfo2(StringBuilder sb, String videoId) {
        boolean filled;
        SortedSet<VideoDescriptor> videos = CacheManager.id2Videos.get(videoId);
        if (videos != null && videos.isEmpty()) {
            videos = null;
        }

        // キャッシュァイルが削除されていた場合にcacheIdsに含めないため
        if (videos != null) {
            for (VideoDescriptor video : videos) {
                Cache.video2File_get(video);
            }
        }

        sb.append('{');
        if (videos != null) {
            VideoDescriptor preferredSmile =
                    Cache.getPreferredCachedVideo(videoId, false, null);
            VideoDescriptor preferredDmc =
                    Cache.getPreferredCachedVideo(videoId, true, Cache.MP4);
            VideoDescriptor preferredDmcFlv =
                    Cache.getPreferredCachedVideo(videoId, true, Cache.FLV);
            VideoDescriptor preferredDmcHls =
                Cache.getPreferredCachedVideo(videoId, true, Cache.HLS);
            putKeyValue(sb, "preferred", videoDescriptorToAltId(
                    Cache.getPreferredCachedVideo(videoId)));
            if (Boolean.getBoolean("useNotReEncodedCache") &&
                    preferredSmile != null && !preferredSmile.isLow() &&
                    Cache.MP4.equals(preferredSmile.getPostfix()) &&
                    !(new Cache(preferredSmile)).isReEncoded()) {
                putKeyValue(sb, "preferredHTML5", videoDescriptorToAltId(preferredSmile));
                putKeyValue(sb, "preferredFlash", videoDescriptorToAltId(preferredSmile));
            } else {
                putKeyValue(sb, "preferredHTML5", videoDescriptorToAltId(
                        preferredDmc != null ? preferredDmc : preferredSmile));
                putKeyValue(sb, "preferredFlash", videoDescriptorToAltId(
                        preferredDmcFlv != null ? preferredDmcFlv : preferredSmile));
            }
            putKeyValue(sb, "preferredSmile", videoDescriptorToAltId(preferredSmile));
            putKeyValue(sb, "preferredDmc", videoDescriptorToAltId(preferredDmc));
            putKeyValue(sb, "preferredDmcFlv", videoDescriptorToAltId(preferredDmcFlv));
            putKeyValue(sb, "preferredDmcHls", videoDescriptorToAltId(preferredDmcHls));

            // cacheIds
            putKey(sb, "cacheIds").append('[');
            for (VideoDescriptor video : videos) {
                putValue(sb, videoDescriptorToAltId(video)).append(',');
            }
            sb.replace(sb.length() - 1, sb.length(), "],");

            // cachings
            putKey(sb, "cachings").append('[');
            filled = false;
            for (VideoDescriptor video : videos) {
                if (Cache.getDLFlag(video)) {
                    putValue(sb, videoDescriptorToAltId(video)).append(',');
                    filled = true;
                }
            }
            if (filled) {
                sb.setLength(sb.length() - 1);
            }
            sb.append("],");

            // completes
            putKey(sb, "completes").append('[');
            filled = false;
            for (VideoDescriptor video : videos) {
                if (new Cache(video).exists()) {
                    putValue(sb, videoDescriptorToAltId(video)).append(',');
                    filled = true;
                }
            }
            if (filled) {
                sb.setLength(sb.length() - 1);
            }
            sb.append("],");

            // caches
            putKey(sb, "caches").append('{');
            for (VideoDescriptor video : videos) {
                Cache cache = new Cache(video);
                String altid = cache.getId();
                boolean complete = cache.exists();
                boolean caching = Cache.getDLFlag(video);

                putKey(sb, altid).append('{');
                putKeyValue(sb, "videoId", videoId);
                putKeyValue(sb, "cacheId", altid);
                putKeyValue(sb, "complete", complete);
                putKeyValue(sb, "economy", video.isLow());
                putKeyValue(sb, "dmc", video.isDmc());
                if (video.isDmc()) {
                    putVideoMode(sb, "dmcMovieType", video);
                }
                putKeyValue(sb, "caching", caching);
                putKeyValue(sb, "movieType", cache.getPostfix().substring(1));
                if (complete) {
                    putKeyValue(sb, "size", cache.length());
                    putKeyValue(sb, "title", cache.getTitle());
                    putKeyValue(sb, "subFolder", Cache.getPathFromVideoDescriptor(video));
                    putKeyValue(sb, "filename", cache.getCacheFileName());
                    putKeyValue(sb, "ts", cache.getCacheFile().lastModified() / 1000L);
                } else {
                    File tmpFile = Cache.video2Tmp.get(video);
                    putKeyValue(sb, "size", cache.tmpFinalSize());
                    putKeyValue(sb, "cachingSize", cache.tmpCachedSize());
                    if (tmpFile != null) {
                        putKeyValue(sb, "title", Cache.getTitleFromFilename(tmpFile.getName().substring(6)));
                        putKeyValue(sb, "subFolder", "");
                        putKeyValue(sb, "filename", tmpFile.getName());
                        putKeyValue(sb, "ts", tmpFile.lastModified() / 1000L);
                    } else {
                        putKeyValue(sb, "title", null);
                        putKeyValue(sb, "subFolder", null);
                        putKeyValue(sb, "filename", null);
                        putKeyValue(sb, "ts", null);
                    }
                }
                if (!video.isDmc() && !video.isLow()) {
                    Boolean reencoded;
                    if (Cache.MP4.equals(video.getPostfix()) &&
                            (reencoded = cache.isReEncodedStrictly()) != null) {
                        putKeyValue(sb, "reEncoded", reencoded);
                    } else {
                        putKeyValue(sb, "reEncoded", null);
                    }
                }
                sb.replace(sb.length() - 1, sb.length(), "},");
            }
            sb.replace(sb.length() - 1, sb.length(), "},");
        } else {
            putKeyValue(sb, "preferred", null);
            putKeyValue(sb, "preferredHTML5", null);
            putKeyValue(sb, "preferredFlash", null);
            putKeyValue(sb, "preferredSmile", null);
            putKeyValue(sb, "preferredDmc", null);
            putKeyValue(sb, "preferredDmcFlv", null);
            putKey(sb, "cacheIds").append("[],");
            putKey(sb, "cachings").append("[],");
            putKey(sb, "completes").append("[],");
            putKey(sb, "caches").append("{},");
        }

        // キャッシュがあれば上ですでに判定されている

        ReEncodingInfo.Entry reencodedInfo;
        if (videoId.matches("\\d+")) {
            reencodedInfo = ReEncodingInfo.getEntryFromNumber(videoId);
        } else {
            reencodedInfo = ReEncodingInfo.getEntry(videoId);
        }
        if (reencodedInfo != null) {
            putKeyValue(sb, "reEncoded", reencodedInfo.reencoded);
            putKeyValue(sb, "reEncodedBitrate", reencodedInfo.bitrate);
        } else {
            putKeyValue(sb, "reEncoded", null);
            putKeyValue(sb, "reEncodedBitrate", 0);
        }

        sb.replace(sb.length() - 1, sb.length(), "}");
        return true;
    }

    /**
     * /cache/rm (GET)
     * /cache/rmtmp (GET)
     * /cache/rmall (GET)
     * /cache/ajax_rm (GET)
     * /cache/ajax_rmtmp (GET)
     * /cache/ajax_rmall (GET)
     *
     * rmall, ajax_rmall ではsmid以外の修飾(品質や拡張子)が付いている場合は
     * http-400(bad request)を返す.
     */
    private static Resource mightProcessRemoveAPI
    (HttpRequestHeader requestHeader, Socket browser, String path, String query) {

        // コメント転記:
        // [nl] リストページ
        // [nl] ローカルFLVリスト→削除

        boolean isget = requestHeader.isGetMethod();

        if (query == null) {
            query = "";
        } else {
            query = URLDecoder.decode(query, StandardCharsets.UTF_8);
        };

        if (path.equals("rm") || path.equals("ajax_rm")) {
            if (!isget) {
                return StringResource.getMethodNotAllowed();
            };

            String msg = "NG";
            Specifier spec = new Specifier(query);

            Logger.info("Remove Cache: " + spec.altid);
            // Logger.info("  resolved(" + spec.video + ")");
            if (spec.video != null) {
                if (Cache.remove(spec.video)) {
                    NLShared.INSTANCE.getDomandCVIManager().removeBySmid(spec.smid);
                    msg = "OK";
                };
            };

            Logger.info("..." + msg);
            if (path.startsWith("ajax_")) {
                return createStringResource(msg);
            };
            return createRedirectResponse(requestHeader);
        };

        if (path.equals("rmtmp") || path.equals("ajax_rmtmp")) {
            if (!isget) {
                return StringResource.getMethodNotAllowed();
            };

            String msg = "NG";
            Specifier spec = new Specifier(query);

            Logger.info("Remove Temporary: " + spec.altid);
            // Logger.info("  resolved(" + spec.video + ")");
            if (spec.video != null) {
                if (Cache.removeTmp(spec.video)) {
                    msg = "OK";
                };
            };

            Logger.info("..." + msg);
            if (path.startsWith("ajax_")) {
                return createStringResource(msg);
            };
            return createRedirectResponse(requestHeader);
        };

        if (path.equals("rmall") || path.equals("ajax_rmall")) {
            if (!isget) {
                return StringResource.getMethodNotAllowed();
            };

            if (!query.matches("[a-z]{2}[0-9]+")) {
                return StringResource.getBadRequest();
            };

            String msg = "NG";
            Specifier spec = new Specifier(query);

            Logger.info("Remove All Cache: " + spec.smid);
            if (!"".equals(spec.smid)) {
                if (Cache.removeAll(spec.smid)) {
                    msg = "OK";
                };
            };

            Logger.info("..." + msg);
            if (path.startsWith("ajax_")) {
                return createStringResource(msg);
            };
            return createRedirectResponse(requestHeader);
        };


        return null;
    };

    /**
     * /cache/echo (GET)
     * 仕様未決定のため人間が読むことしか想定していない.
     * 単一キャッシュパラメーターの解析結果を表示する.
     */
    private static Resource mightProcessEchoAPI
    (HttpRequestHeader requestHeader, Socket browser, String path, String query) {
        boolean isget = requestHeader.isGetMethod();

        if (!path.equals("echo")) {
            return null;
        };

        if (!isget) {
            return StringResource.getMethodNotAllowed();
        };

        String decoded = null;
        if (query != null) {
            decoded = URLDecoder.decode(query, StandardCharsets.UTF_8);
        };

        Specifier spec = new Specifier(decoded);

        StringBuilder sb = createJsonStringBuilder();
        sb.append(jsondq("rawQuery")).append(':').append(jsondq(query));
        sb.append(",\n");
        sb.append(jsondq("decodedQuery")).append(':').append(jsondq(decoded));
        sb.append(",\n");
        sb.append(jsondq("altId")).append(':').append(jsondq(spec.altid));
        sb.append(",\n");
        sb.append(jsondq("smid")).append(':').append(jsondq(spec.smid));
        sb.append(",\n");

        sb.append(jsondq("videoDescriptor")).append(':').append(jsondq(String.valueOf(spec.video)));
        sb.append(",\n");
        String vdpostfix = null;
        String revAltId = null;
        Boolean isdmc = null;
        if (spec.video != null) {
            isdmc = spec.video.isDmc();
            revAltId = Cache.videoDescriptorToAltId(spec.video);
            vdpostfix = spec.video.getPostfix();
        };
        sb.append(jsondq("videoDescriptor.isDmc")).append(':').append(jsondq(String.valueOf(isdmc)));
        sb.append(",\n");
        sb.append(jsondq("videoDescriptor.postfix")).append(':').append(jsondq(String.valueOf(vdpostfix)));
        sb.append(",\n");
        sb.append(jsondq("videoDescriptorToAltId")).append(':').append(jsondq(String.valueOf(revAltId)));
        sb.append(",\n");
        String cachetmpfile = null;
        String cachefile = null;
        if (spec.video != null && spec.cache != null) {
            cachefile = String.valueOf(spec.cache.getCacheFile());
            cachetmpfile = String.valueOf(spec.cache.getCacheTmpFile());
        };
        sb.append(jsondq("cache.tmpFile")).append(':').append(jsondq(String.valueOf(cachetmpfile)));
        sb.append(",\n");
        sb.append(jsondq("cache.file")).append(':').append(jsondq(String.valueOf(cachefile)));
        // 2文字削除されるために必要.
        sb.append(",\n");

        return createJsonStringResource(sb);
    };

    // - json向けにdouble quoteで囲う.
    // - 重要なことには使わないこと.
    private static String jsondq(String s) {
        if (s == null) {
            return "null";
        };
        s = s.replaceAll("\\\\", "\\\\");
        s = s.replaceAll("\"", "\\\"");
        s = s.replaceAll("\b", "\\b");
        s = s.replaceAll("\f", "\\f");
        s = s.replaceAll("\n", "\\n");
        s = s.replaceAll("\r", "\\r");
        s = s.replaceAll("\t", "\\t");
        return "\"" + s + "\"";
    };

    private static String videoDescriptorToAltId(VideoDescriptor video) {
        if (video == null) {
            return null;
        } else {
            return Cache.videoDescriptorToAltId(video);
        }
    }

    private static StringBuilder putKey(StringBuilder sb, String key) {
        return sb.append('"').append(key).append('"').append(':');
    }

    private static StringBuilder putValue(StringBuilder sb, boolean value) {
        return sb.append(Boolean.toString(value));
    }

    private static StringBuilder putValue(StringBuilder sb, long value) {
        return sb.append(Long.toString(value));
    }

    private static StringBuilder putValue(StringBuilder sb, String value) {
        if (value == null) {
            return sb.append("null");
        } else {
            return sb.append('"')
                    .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
    }

    private static void putKeyValueStingily(StringBuilder sb, String key, boolean value) {
        if (value) {
            putValue(putKey(sb, key), value).append(',');
        }
    }

    private static void putKeyValueStingily(StringBuilder sb, String key, long value) {
        if (value > 0) {
            putValue(putKey(sb, key), value).append(',');
        }
    }

    private static void putKeyValueStingily(StringBuilder sb, String key, String value) {
        if (value != null && value.length() > 0) {
            putValue(putKey(sb, key), value).append(',');
        }
    }

    private static void putKeyValue(StringBuilder sb, String key, boolean value) {
        putValue(putKey(sb, key), value).append(',');
    }

    private static void putKeyValue(StringBuilder sb, String key, long value) {
        putValue(putKey(sb, key), value).append(',');
    }

    private static void putKeyValue(StringBuilder sb, String key, String value) {
        putValue(putKey(sb, key), value).append(',');
    }

    private static void putVideoMode(StringBuilder sb, String key, VideoDescriptor video){
        sb.append('"').append(key).append('"').append(":{");
        putKeyValue(sb, "videoMode", video.getVideoMode());
        putKeyValue(sb, "videoBitrate", video.getVideoBitrate());
        putKeyValue(sb, "audioBitrate", video.getAudioBitrate());
        sb.replace(sb.length() - 1, sb.length(), "},");
    }

    //[xmlfix]
    private static String decodeURLForUTF8(String encodeStr) {
        String decodeStr = (encodeStr == null) ? "" : encodeStr;

        try {
            decodeStr = URLDecoder.decode(decodeStr, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Logger.warning(e.toString());
        }

        return decodeStr;
    }

    private static StringResource createStringResource(String str) {
        return getStringResource(str, null);
    }

    private static StringResource getStringResource(String str, String contentType) {
        if (contentType == null) {
            contentType = "text/plain; charset=UTF-8";
        } else if (!contentType.contains("charset=")) {
            contentType += "; charset=UTF-8";
        }
        StringResource r = new StringResource(str);
        r.addCacheControlResponseHeaders(0);
        r.addResponseHeader(HttpHeader.CONTENT_TYPE, contentType);
        return r;
    }

    private static StringBuilder createJsonStringBuilder() {
        StringBuilder sb = new StringBuilder(4096);
        return sb.append("{\n");
    }

    private static StringResource createJsonStringResource(StringBuilder sb) {
        if (sb.length() > 2) {
            sb.replace(sb.length() - 2, sb.length(), "\n}");
        } else {
            sb.replace(0, sb.length(), "{}");
        }
        return getStringResource(sb.toString(), "application/json");
    }

    /**
     * 文字列冒頭から'/'をskip回探してそれ以降の部分を返す.
     *
     * '/'がskip回含まれていない場合はnullを返す.
     * 例: ("/cache/info/v2", 2) => "info/v2"
     */
    private static String skipSlash(String path, int skip) {
        if (skip <= 0) {
            return path;
        };

        int slash = 0;
        for (; skip > 0; --skip) {
            slash = path.indexOf('/', slash);
            if (slash < 0) {
                return null;
            };
            ++slash;
        };
        return path.substring(slash);
    };

    private static String loadPostContent
    (HttpRequestHeader requestHeader, Socket browser) throws IOException {
        return Main.getRewriterProcessor().getPostString(
            requestHeader, browser, null);
    };

    /**
     * 単一のキャッシュ指定表現のパース結果を保持する.
     */
    static class Specifier {
        public String altid = ""; // not null
        public String smid = ""; // not null
        public VideoDescriptor video = null;
        public Cache cache = null;

        /**
         * @param query URLデコードされた後のquery. 冒頭の'?'を含まない.
         */
        public Specifier(String query) {
            // - ALT_ID_PATTERNを部分一致で探す.
            // - 厳密一致にするべきだがこのファイルの整理が済むまではこのまま.

            Matcher m = ALT_ID_PATTERN.matcher(query);
            if (!m.find()) {
                return;
            };
            altid = m.group(1);
            smid = m.group(2);

            video = Cache.altIdToVideoDescriptor(altid);

            if (video != null) {
                cache = new Cache(video);
            };
        };
    };
}
