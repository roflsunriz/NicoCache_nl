package dareka.processor.impl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Logger;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.LocalCacheFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.SwfConvertResource;
import dareka.processor.URLResource;
import dareka.processor.impl.NicoIdInfoCache.Entry;

public class NicoCachingProcessor implements Processor {
    // 任意のMethodにマッチ
    private static final String[] SUPPORTED_METHODS = new String[] { null };
    /**
     * SMILEVIDEOの動画URLの正規表現
     */
    // http://s-clb5.smilevideo.jp/smile?v=102982.92382
    // 2009/3頃から http://smile-clb51.nicovideo.jp/smile?s=7074214.90075as3
    private static final Pattern SM_FLV_PATTERN =
//            Pattern.compile("^http://[^/]+(?:smilevideo|nicovideo)\\.jp/smile\\?(\\w)=([^.]+)\\.\\d+(as3)?(low)?$");
            Pattern.compile("^https?://(?!tn(?:-skr|\\.))[^/]+(?:smilevideo|nicovideo)\\.jp/smile\\?(\\w)=([^.]+)\\.\\d+(as3)?(low)?$");

    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-(\\d*)");

    private final Executor executor;

    // 二重ダウンロード禁止処理の削除に伴うレースコンディションを
    // とりあえずこれで潰す
    public final static ReentrantLock giantLock = new ReentrantLock();

    public NicoCachingProcessor(Executor executor) {
        this.executor = executor;
    }

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return SM_FLV_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        // GET以外も捕まえてサーバに存在しないURLへのリクエストが飛ぶのを防ぐ
        if (!HttpHeader.GET.equals(requestHeader.getMethod())) {
            return StringResource.getMethodNotAllowed();
        }

        giantLock.lock();
        try {
            return onRequestCore(requestHeader, browser);
        } finally {
            giantLock.unlock();
        }
    }
    protected Resource onRequestCore(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        Matcher m = SM_FLV_PATTERN.matcher(requestHeader.getURI());
        if (!m.find()) {
            // it must not happen...
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        MovieData data = new MovieData(m);
        String postfix = data.getCache().getPostfix();

        // [nl] 知らない形式はキャッシュしない
        if (postfix.equals(".unknown")) {
            Logger.warning("unknown data type: " + requestHeader.getURI());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // [nl] Extensionにキャッシュ要求イベントを通知する
        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader, data.getCache());
            int result = NLShared.INSTANCE.notifySystemEvent(
                    SystemEventListener.CACHE_REQUEST, eventSource, true);
            if (result != SystemEventListener.RESULT_OK) {
                Logger.debug(requestHeader.getURI() + " pass-through by extension");
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }
        }

// WatchRewriter(本体はWatchVarsとgetflvRewriter)で、
// 通常キャッシュがあるときは"low"を削除してる
// ただし、eco=1の時は削除しないので、
// 通常キャッシュがあってlowのアクセスが来た時はeco=1と判断
        VideoDescriptor cachedVideo = CacheManager.getPreferredCachedVideo(
                data.getType() + data.getId(), false, data.getPostfix());
        Logger.debug("Preferred cache: " + cachedVideo);
        if (cachedVideo != null && !data.getVideoDescriptor().isPreferredThan(cachedVideo, false, null)) {
            Cache cache = new Cache(cachedVideo);
            if (data.isLowAccess() && !cachedVideo.isLow()) {
                // キャッシュがあるのにここに来るのはeco=1の時
                // すでに通常キャッシュがあるのでキャッシュしない
                // 一時キャッシュも無いはずだから、レジュームはブラウザに任せてそのまま返す
                Logger.info("force economy mode: " + cache.getCacheFileName());
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }

            // cvcacheにmp4ファイルを手動で配置した場合などに対応
            // flv2mp4の変換処理は別途
            if (postfix.equals(Cache.MP4)) {
                File convertedFile = CacheManager.video2ConvertedMp4_get(cachedVideo);
                if (convertedFile != null) {
                    Logger.info("using cvcache: " +  convertedFile.getName());
                    if (Boolean.getBoolean("touchCache")) {
                        cache.touch();
                    }
                    Resource r = new LocalCacheFileResource(convertedFile);
                    r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
                    return r;
                }
            }

            String cachedPostfix = cache.getPostfix();
            boolean formatMismatch = !data.getPostfix().equals(cachedPostfix);

            // リクエストされた形式とキャッシュの形式が一致しない時は素通し
            if (formatMismatch && !Boolean.getBoolean("convertFlv2Mp4")
                    && cachedPostfix.equals(Cache.FLV)) {
                Logger.info("format mismatch: " + cache.getCacheFileName());
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }

            Logger.info("using cache: " +  cache.getCacheFileName());
            if (Boolean.getBoolean("touchCache")) {
                cache.touch();
            }
            Resource r;
            if (Boolean.getBoolean("swfConvert") && postfix.equals(Cache.SWF)) {
                // [nl] キャッシュから再生時にSWF変換を行う
                SwfConvertResource swf = new SwfConvertResource(cache.getURLString());
                swf.setAS3(data.isAS3());
                r = swf;
                r.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/x-shockwave-flash");
            } else if (formatMismatch && Boolean.getBoolean("convertFlv2Mp4")
                    && cachedPostfix.equals(Cache.FLV)) {
                File convertedFile;
                // 変換処理は時間がかかるので一旦アンロックする
                giantLock.unlock();
                try {
                    convertedFile = cache.getConvertedMp4FromFlv();
                } finally {
                    giantLock.lock();
                }
                if (convertedFile == null) {
                    Logger.info("Error in flv2mp4: " + cache.getCacheFileName());
                    return Resource.get(Resource.Type.URL, requestHeader.getURI());
                }
                r = new LocalCacheFileResource(convertedFile);
                r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
            } else {
                r = new LocalCacheFileResource(cache.getCacheFile());
                // swf以外はブラウザにキャッシュさせる
                r.addCacheControlResponseHeaders(12960000);
            }
            LimitFlvSpeedListener.addTo(r);

            return r;
        }

        // dmcキャッシュを持っている場合
        VideoDescriptor preferredDmc = CacheManager.getPreferredCachedVideo(
                data.getType() + data.getId(), true, null);
        if (preferredDmc != null) {
            Cache cache = new Cache(preferredDmc);
            // lowのアクセスでdmcキャッシュを持っているときもキャッシュしない
            if (data.isLowAccess()) {
                Logger.info("force economy mode: " + cache.getCacheFileName());
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }
            // 非dmc動画が再エンコードされていることがわかっていて かつ
            // dmcキャッシュを持っていて かつ 非dmc動画のビットレートより
            // dmcキャッシュのビットレートが高い時 キャッシュしない
            // Note:
            //   初めてアクセスする場合に再エンコードされていかは知らないので
            //   素通りするからNicoCachingListenerで通信終了時にも対処している
            if (Boolean.getBoolean("removeReEncodedCache")) {
                if (!preferredDmc.isLow()) {
                    ReEncodingInfo.Entry info = ReEncodingInfo.getEntry(data.getType() + data.getId());
                    if (info != null && info.reencoded &&
                            (!Boolean.getBoolean("useHighBitrateReEncodedCache")
                            || !preferredDmc.hasBitrate()
                            || info.bitrate <= preferredDmc.getVideoBitrate() + preferredDmc.getAudioBitrate())) {
                        Logger.info("dmc cache exists: " + cache.getCacheFileName());
                        return Resource.get(Resource.Type.URL, requestHeader.getURI());
                    }
                }
            }
        }

// 生放送から呼び出された場合にキャッシュしない
        // 呼び出しで古いリストが消えるので、必ず呼び出す
        boolean isLive = NLShared.INSTANCE.isLiveMovie(data.getType() + data.getId());
        if (Boolean.getBoolean("noLiveCache")) {
            // Refererで弾ければ、そっちの方が正確
            String referer = requestHeader.getMessageHeader("Referer");
            if (referer != null) {
                isLive = referer.matches("https?://live\\.nicovideo\\.jp.*") ||
                         referer.contains("/liveplayer.swf");
            }
            if (isLive) {
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }
        }

        // [nl] Extensionがキャッシュを禁止する場合はキャッシュしない
        if (eventSource != null) {
            int result = NLShared.INSTANCE.notifySystemEvent(
                    SystemEventListener.CACHE_STARTING, eventSource, true);
            if (result != SystemEventListener.RESULT_OK) {
                Logger.info("disable cache: " + data.getCache().getCacheFileName());
                return Resource.get(Resource.Type.URL, requestHeader.getURI());
            }
        }

        FutureTask<String> retrieveTitlteTask = null;
        if (Boolean.getBoolean("title")
                && (data.getIdInfo() == null || !data.getIdInfo().isTitleValid())) {
            retrieveTitlteTask =
                    new FutureTask<>(new NicoCachingTitleRetriever(
                            data.getType(), data.getId()));
            executor.execute(retrieveTitlteTask);
        }

        // [nl] ブラウザのレジューム対応と、NCでのレジューム対応
        requestHeader.removeMessageHeader("If-Range");
        long requestedPositionStart = -1;
        long requestedPositionEnd = -1;
        String range = requestHeader.getMessageHeader("Range");
        Logger.debug("Requested range: " + range);
        if (range != null) {
            Matcher e = RANGE_PATTERN.matcher(range);
            if (e.matches()) {
                requestedPositionStart = Long.parseLong(e.group(1));
                if (!e.group(2).equals("")) {
                    requestedPositionEnd = Long.parseLong(e.group(2));
                }
            }
        }

        MappedRanges mr = data.getCache().getCacheTmpMappedRanges();

        // ファイルサイズがmapされている領域の最後より小さければ
        // 壊れているのでclearしてやり直し
        if (!Cache.getDLFlag(data.getVideoDescriptor()) && mr != null) {
            File tmpFile = data.getCache().getCacheTmpFile();
            if (tmpFile == null || tmpFile.length() < mr.getLastTail()) {
                Logger.warning("Resetting broken tmp cache: " +
                        Cache.videoDescriptorToAltId(data.getCache().getVideoDescriptor()));
                mr.clear();
            }
        }

        // キャッシュが完全でなくとも要求された部分は全部持っているならば
        // LocalCacheFileResourceで応答する(サーバにリクエストを発行しない)
        // DL中のセッションがいないのに全部取得済みでここに来た時は
        // 最後の1バイトだけサーバへリクエストさせてcompleterを走らせる．
        if (!Cache.getDLFlag(data.getVideoDescriptor()) && mr != null && mr.isMapped(0, mr.getSize())) {
            long size = mr.getSize();
            mr.clear();
            mr.setSize(size);
            mr.map(0, size - 1);
        } else if (mr != null && mr.isMapped(requestedPositionStart, requestedPositionEnd)) {
            Resource r = new LocalCacheFileResource(data.getCache().getCacheTmpFile());
            Logger.info("using partial cache ["
                    + (requestedPositionStart == -1 ? "" : requestedPositionStart)
                    + "-"
                    + (requestedPositionEnd == -1 ? "" : requestedPositionEnd)
                    + "]: " + data.getCache().getCacheFileName());
            r.addCacheControlResponseHeaders(12960000);
            LimitFlvSpeedListener.addTo(r);
            return r;
        }

        Logger.info("no cache found: " + data.getCache().getCacheFileName());

        InputStream cacheInput = null;
        // [nl] レジュームするよー
        long initialPos = -1;
        if (mr != null) initialPos = mr.findTail(requestedPositionStart != -1 ? requestedPositionStart : 0);
        if (initialPos == -1) initialPos = requestedPositionStart != -1 ? requestedPositionStart : 0;
        if (initialPos != 0 && requestedPositionStart < initialPos) {
            String newRange;
            if (requestedPositionEnd != -1) {
                newRange = "bytes=" + initialPos + "-" + requestedPositionEnd;
            } else {
                newRange = "bytes=" + initialPos + "-";
            }
            Logger.debug("Rewriting range: " + newRange);
            requestHeader.setMessageHeader("Range", newRange);
            cacheInput =
                    new BufferedInputStream(data.getCache().getTmpInputStream());
            if (requestedPositionStart != -1) {
                cacheInput.skip(requestedPositionStart);
            }
        }

        URLResource r;
        try { // ensure cacheInput.close() in error cases.
            // [nl] DL中リストに入れる

            Cache.incrementDL(data.getCache().getVideoDescriptor());

            r = new URLResource(requestHeader.getURI());

            // *.nicovideo.jpではないホストへリダイレクトされることがある．
            // 転送先は http://ISP内部のIPアドレス:80/data/16桁英数字/元のURLのホスト名以降 らしい．
            // CDNのようなものだろうか．
            r.setFollowRedirects(true);

            r.addTransferListener(new NicoCachingListener(data.getCache(),
                    retrieveTitlteTask, cacheInput, eventSource,
                    requestedPositionStart));
        } catch (RuntimeException e) {
            Logger.error(e);
            CloseUtil.close(cacheInput);
            Cache.decrementDL(data.getCache().getVideoDescriptor());
            throw e;
        }

        return r;
    }

    /**
     * Class for manage various data for a movie.
     *
     */
    static final class MovieData {
        private String format;
        private String id;
        private String as3;
        private String suffix;
        private Entry idInfo;
        private String postfix;
        private String type;
        private Cache cache;
        private VideoDescriptor video;

        MovieData(Matcher m) {
            initializeFormatIdSuffix(m);
            initializeIdInfo(getId());
            initializePostfix(format);
            initializeTypeCache(getIdInfo(), format);
        }

        private void initializeFormatIdSuffix(Matcher m) {
            format = m.group(1);
            id = NLShared.INSTANCE.vid2cid(m.group(2)); // [nl]
            as3 = m.group(3);
            suffix = m.group(4);
        }

        private void initializeIdInfo(String id) {
            if (Boolean.getBoolean("title")) {
                idInfo = NicoIdInfoCache.getInstance().get(id);
            } else {
                idInfo = null;
            }
        }

        private void initializePostfix(String format) {
            switch (format) {
            case "v": postfix = ".flv"; break;
            case "m": postfix = ".mp4"; break;
            case "s": postfix = ".swf"; break;
            default:  postfix = ".unknown"; break;
            }
        }

        private void initializeTypeCache(Entry idInfo, String format) {
            if (idInfo == null) {
                // this may not be correct, but we can not know without idInfo...
                if (format.endsWith("s")) {
                    type = "nm";
                } else {
                    type = "sm";
                }
                video = VideoDescriptor.newClassic(type + id, postfix, isLowAccess());
                cache = new Cache(video);
            } else {
                type = idInfo.getType();
                video = VideoDescriptor.newClassic(type + id, postfix, isLowAccess());
                cache = new Cache(video, idInfo.getTitle());
            }
        }

        public Cache getCache() {
            return cache;
        }

        public VideoDescriptor getVideoDescriptor() {
            return video;
        }

        public Entry getIdInfo() {
            return idInfo;
        }

        public String getType() {
            return type;
        }

        public String getPostfix() {
            return postfix;
        }

        public String getId() {
            return id;
        }

        public boolean isAS3() {
            return as3 != null;
        }

        public String getCacheId() {
            return (suffix == null) ? type + id : type + id + suffix;
        }

        public boolean isLowAccess() {
            return suffix != null;
        }
    }

}
