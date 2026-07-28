package dareka.processor.impl;

import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Map;
import java.util.TreeMap;
// import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// - 個別キャッシュディレクトリ下のファイルを配信するためのProcessor.
// - 2024-03-31よりも前では /eachcache/ を使っていた.
//   以降は /cache/file/ 下を使う.
// - master.m3u8さえあれば自由な形式のキャッシュを使えるようにするための仕様.
// - 実装時点ではDomandのCmafCachingProcessor.javaがこれを使う.
public class CmafUseCacheProcessor implements Processor {

    // - このURL仕様を使う時は必ず"CmafUseCacheProcessor"の名前をコメントに残す
    //   こと.
    // - seach部だけではなくpath部にもパラメーター部を設けたのは、相対的に参照さ
    //   れるサブリソースにもpath部パラメーターが簡単に伝わるから.
    //   例: x.m3u8にyy.tsと書いてある. /cache/file/a=b//x.m3u8をブラウザが読む.
    //       ブラウザは相対値として/cache/file/a=b//yy.tsを生成してリクエストす
    //       る. yy.tsにもa=bが伝わる.
    // group(1): "&"区切りのパラメーター. 例: "a=b&del_flag&ans=5=6"
    // group(2): subpath. 単一キャッシュフォルダからの相対パスを表す.
    // group(3): "?"付きのsearch部に示されるパラメーター..
    // private static final Pattern URL_PATTERN = Pattern.compile("^https?://([^/]*[.])?" + Pattern.quote("nicovideo.jp/eachcache/") + "([^?]*)//([^?]*)([?].*)?$");
    // "/eachcache/" 以降のみを対象にする.
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^([^?]*)//([^?]*)([?].*)?$");

    private static final Pattern REMOVE_PREFIX_PATTERN = Pattern.compile(
        "^https?://([^/]*[.])?" + Pattern.quote("nicovideo.jp/cache/file/"));

    private static final Pattern MUST_CATCH_URL_PATTERN = Pattern.compile(
        "^https?://([^/]*[.])?" + Pattern.quote("nicovideo.jp/cache/file/") + ".*$");

    public final static ReentrantLock giantLock = new ReentrantLock();

    private static final String[] PROCESSOR_SUPPORTED_METHODS = new String[] { "GET" };
    private static final Pattern PROCESSOR_SUPPORTED_PATTERN =
        MUST_CATCH_URL_PATTERN;

    // private final Executor executor;
    // public CmafUseCacheProcessor(Executor executor) {this.executor = executor;}
    public CmafUseCacheProcessor() {}

    @Override
    public String[] getSupportedMethods() {
        return PROCESSOR_SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return PROCESSOR_SUPPORTED_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    private static String removeUrlPrefix(String url) {
        Matcher m = REMOVE_PREFIX_PATTERN.matcher(url);
        if (m.find()) {
            return url.substring(m.end());
        };
        return null; // 異常. コーディングミス.
    };

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) {

        try {
            String url = removeUrlPrefix(requestHeader.getURI());
            if (url == null) {
                return StringResource.getNotFound();
            };

            return onRequestSubPath(url, requestHeader, browser);
        } catch (Exception e) {
            Logger.error(e);
            // do nothing.
        };
        // 実サーバーへリクエストが行くことは避ける.
        return StringResource.getNotFound();
    };

    // - path: プレフィックス部分が除去されたpath部分.
    //   - リクエストが"http://localhost/cache/file/params=xx//master.m3u8"
    //     ならばpath="params=xx//master.m3u8"
    public Resource onRequestSubPath(
        String path, HttpRequestHeader requestHeader, Socket browser) {

        // - この例外処理は正常処理上の何かを想定しているわけではない.
        // - コーディングミスでニコ動サーバーにリクエストが行くことを防ぐために
        //   設置しておく.
        try {
            Matcher m = URL_PATTERN.matcher(path);
            if (!m.matches()) {
                // どこかにコーディングミスがある.
                Logger.info("(cmafU)不正なURL形式です("
                            + requestHeader.getURI() + ")");
                return StringResource.getNotFound();
            };
            String parameters = m.group(1);
            // - subsubpathと呼ぶのは冗長だからsubはひとつ.
            // - 単一キャッシュフォルダからの参照したいファイルの相対パス.
            String subpath = m.group(2);
            String search = m.group(3);
            Resource result = onRequestCore(
                requestHeader, browser, parameters, subpath, search);
            if (result == null) {
                Logger.info("--- result == null");
                return StringResource.getNotFound();
            };
            return result;
        } catch (Exception e) {
            Logger.error(e);
            // do nothing.
        };
        return StringResource.getNotFound();
    };

    static class Parameters {
        // a=b=c のような形式は a と b=c . そのために左辺は最短一致.
        private static final Pattern LEFT_EQUAL_RIGHT = Pattern.compile("^(.*?)=(.*)$");

        public static Map<String, String> parse(String paramsStr) {
            Map<String, String> params = new TreeMap<>();

            for (String s : paramsStr.split("&")) {
                try {
                    s = URLDecoder.decode(s, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // do nothing
                };
                Matcher m = LEFT_EQUAL_RIGHT.matcher(s);
                if (!m.matches()) {
                    params.put(s, "");
                    continue;
                };
                String lhs = m.group(1);
                String rhs = m.group(2);
                params.put(lhs, rhs);
            };

            return params;
        };

        Map<String, String> parameters;
        HttpRequestHeader requestHeader;
        public Parameters(String parametersStr, HttpRequestHeader requestHeader) {
            this.parameters = parse(parametersStr);
            this.requestHeader = requestHeader;
        };

        public String getParameter(String key) {
            // path部パラメーターを優先.
            String v = parameters.get(key);
            if (v != null) {
                return v;
            };
            return requestHeader.getParameter(key);
        };
    };

    // - onRequestからのみ呼ばれる.
    // - nullを返したら呼び出し元は404 not found応答にする.
    // - それ以外の場合は戻り値Resourceをブラウザに応答する.
    private Resource onRequestCore
    (HttpRequestHeader requestHeader, Socket browser, String parametersStr,
     String subpath, String searchOfUrl) {

        Parameters p = new Parameters(parametersStr, requestHeader);

        Cache cache;
        cache = getCacheByDomandCVIKey(p);

        if (cache != null && cache.exists()) {
            // - 例: https://www.nicovideo.jp/cache/file/nicocachenl_domandcvikey=<動的ID>//master.m3u8
            return responseFile(cache, subpath, requestHeader, browser);
        };
        // - nicocachenl_domandcvikeyによる処理ここまで.

        // - nicocachenl_refcacheによる処理ここから.
        // - 例: https://www.nicovideo.jp/cache/file/nicocachenl_refcache=sm9//master.m3u8
        for (boolean once=true; once; once=false) {

            // "sm9[360p,128]"のようなクオリティ付きの表現.
            String altid = p.getParameter("nicocachenl_refcache");

            if (altid == null || "".equals(altid)) {
                Logger.info("(cucp)altid==null: " + requestHeader.getURI());
                break;
            };
            VideoDescriptor video = findMatchedPreferredCachedVideo(altid);
            if (video == null) {
                Logger.info("(cucp)video==null: " + requestHeader.getURI());
                break;
            };
            cache = new Cache(video);
            if (cache != null && cache.exists()) {
                return responseFile(cache, subpath, requestHeader, browser);
            };
            Logger.info("(cucp)cache==null" + requestHeader.getURI());
        };

        Logger.info("(CmafUseCacheProcessor)cache is null: " + requestHeader.getURI());
        return null;
    };


    private static final Pattern SMID_PATTERN = Pattern.compile("^(\\w{2}\\d+)");
    private static VideoDescriptor findMatchedPreferredCachedVideo(
        String altid) {

        // - altid: "sm9[360p,128]"のようなクオリティ付きの表現.
        // - コンプリートしたキャッシュのみを対象とする(部分キャッシュを
        //   無視する).
        VideoDescriptor requested = Cache.altIdToVideoDescriptor(altid);
        if (requested == null && altid.indexOf('[') >= 0) {
            // nicocachenl_refcacheの従来表現は拡張子を省略できる。
            requested = Cache.altIdToVideoDescriptor(altid + Cache.HLS);
        }
        if (requested != null && requested.isDmc()
                && Cache.HLS.equals(requested.getPostfix())) {
            VideoDescriptor registered =
                    Cache.getRegisteredVideoDescriptor(requested);
            if (registered != null && new Cache(registered).exists()) {
                return registered;
            }
            // 品質指定があるのに一致するキャッシュがない場合、別品質へ
            // 暗黙にフォールバックすると要求と異なる映像を返すため失敗させる。
            return null;
        }

        // 品質指定のない従来URLは、これまでどおり最適なHLSを選ぶ。
        Matcher m = SMID_PATTERN.matcher(altid);
        if (!m.find()) {
            return null;
        };

        String smid = m.group(1);

        VideoDescriptor preferredDmcHls =
            Cache.getPreferredCachedVideo(smid, true, Cache.HLS);

        // - この関数は単一ファイル系preferred cacheは確認しない.
        //   - VideoDescriptor preferredSmile =
        //       Cache.getPreferredCachedVideo(videoId, false, null);
        //   - VideoDescriptor preferredDmc =
        //       Cache.getPreferredCachedVideo(videoId, true, Cache.MP4);
        //   - VideoDescriptor preferredDmcFlv =
        //       Cache.getPreferredCachedVideo(videoId, true, Cache.FLV);

        return preferredDmcHls;
    };


    private static Resource responseFile
    (Cache cache, String subpath, HttpRequestHeader requestHeader,
     Socket browser) {

        // - "cachedir/smXXX[1080p,192]_動画タイトル.hls/" を基準に、そこから
        //   相対的にsubpathへアクセスする.
        // - "master.m3u8"にアクセスする場合のsubpath例: "master.m3u8"

        // - 空subpathはmaster.m3u8へ.
        // - もしトッププレイリストがmaster.m3u8ではない場合はそこを指す.
        //   - 実装時点ではdmc/hlsもdomand(cmaf)もmaster.m3u8であるため
        if ("".equals(subpath)) {
            subpath = "master.m3u8";
        };

        File cacheroot = cache.getCacheFile();
        File file = new File(cacheroot, subpath);
        String filestr = file.toString();

        // 拡張子でcontent-typeを分けるための局所関数のためのクラス.
        class xclass {
            public Boolean ew(String str) {
                return filestr.endsWith(str);
            };
            public Boolean ew(String... strList) {
                for (String s : strList) {
                    if (filestr.endsWith(s)) {
                        return true;
                    };
                };
                return false;
            };
            public Resource fr(String mime) {
                return fileResource(file, mime, requestHeader);
            };
        };
        xclass x = new xclass();

        if (x.ew(".m3u8")) {
            return x.fr("application/vnd.apple.mpegurl");
        };

        if (x.ew(".mp4", ".ts", ".cmfv", ".cmfa")) {
            return x.fr("video/mp4");
        };

        return x.fr("application/octet-stream");
    };

    private static Cache getCacheByDomandCVIKey(Parameters p) {

        String key = p.getParameter("nicocachenl_domandcvikey");
        if (key == null) {
            return null;
        };

        DomandCVIEntry movieInfo =
            NLShared.INSTANCE.getDomandCVIManager().get(key);

        if (movieInfo == null) {
            Logger.info("(CmafUseCacheProcessor)movieInfo is null: key=" + key);
            return null;
        };

        {
            Cache cache = movieInfo.getCacheOfCacheForUsing();

            if (cache != null && cache.exists()) {
                return cache;
            };
        };
        {
            Cache cache = movieInfo.getCache();

            if (cache != null && cache.exists()) {
                return cache;
            };
        };

        // 関連するものから同じsrc idで利用可能なキャッシュを探す.
        String videoSrcId = movieInfo.getVideoSrcId();
        String audioSrcId = movieInfo.getAudioSrcId();

        for (DomandCVIEntry x : movieInfo.assocList) {
            // - 基準videoSrcIdがnullであるならば求めるものは音声のみ.
            if (videoSrcId != null) {
                if (videoSrcId != x.getVideoSrcId()) {
                    continue;
                };
            };
            if (audioSrcId != x.getAudioSrcId()) {
                continue;
            };
            Cache cache = x.getCache();
            if (cache == null || !cache.exists()) {
                continue;
            };
            return cache;
        };

        String videoMode =
            DomandCVIEntry.videoSrcIdToCacheQualityExpression(videoSrcId);
        int audioKbps =
            DomandCVIEntry.audioSrcIdToCacheQualityExpression(audioSrcId);
        if (videoMode == null && audioKbps == -1) {
            return null;
        };

        for (VideoDescriptor v : CacheManager.getVideos(movieInfo.getSmid())) {
            if (videoMode != null) {
                if (!v.getVideoMode().equals(videoMode)) {
                    continue;
                };
            };
            if (audioKbps != -1) {
                if (v.getAudioBitrate() != audioKbps) {
                    continue;
                };
            };
            Cache cache = new Cache(v);
            if (cache.exists()) {
                movieInfo.setCacheOfCacheForUsing(cache);
                return cache;
            };
        };

        return null;
    };

    private static Resource fileResource
    (File file, String contentType, HttpRequestHeader requestHeader) {
        if (!file.exists()) {
            // NicoCacheが把握していな理由によってキャッシュが欠落した.
            Logger.info("--- (CUCP#FR)not found: " + file);
            return null;
        };

        Resource r;
        try {
            r = new LocalFileResource(file);
        } catch (IOException e) {
            Logger.info("--- CUCP#FR/LocalFileResource,IOException: " + file);
            return null;
        };
        LimitFlvSpeedListener.addTo(r);
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, contentType);
        setCors(r, requestHeader);
        return r;
    };

    // - あっちこっちに同じ関数がある.
    private static void setCors(Resource r, HttpRequestHeader requestHeader) {
        if (requestHeader.getMessageHeader("Origin") != null) {
            r.setResponseHeader("Access-Control-Allow-Credentials", "true");
            r.setResponseHeader("Access-Control-Allow-Origin", requestHeader.getMessageHeader("Origin"));
        }
    }


};
