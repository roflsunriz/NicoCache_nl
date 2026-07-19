package dareka.processor.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
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
import dareka.processor.URLResource;

public class DmcCachingProcessor implements Processor {

    private static final String[] PROCESSOR_SUPPORTED_METHODS = new String[]{"GET"};
    // サーバ名はpa03とかpb04とか
    private static final Pattern PROCESSOR_SUPPORTED_PATTERN
            = Pattern.compile("^https?://[^/]+?\\.dmc\\.nico(?::\\d+)?/vod/ht2_nicovideo/nicovideo-(\\w+?)(\\d+)_(\\w+)"
                    + "\\?.*ht2_nicovideo=([^&]+)");

    static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-(\\d*)");
    static final Pattern VIDEO_TYPE_PATTERN1 = Pattern.compile("^archive_(?:[^_]+)_(\\d+)kbps_([^_]+)$");
    static final Pattern VIDEO_TYPE_PATTERN2 = Pattern.compile("^archive_(?:[^_]+)_([^_]+(?:_low)?)$");
    static final Pattern AUDIO_TYPE_PATTERN = Pattern.compile("^archive_(?:[^_]+)_(\\d+)kbps$");

    private final Executor executor;

    public DmcCachingProcessor(Executor executor) {
        this.executor = executor;
    }

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


    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        NicoCachingProcessor.giantLock.lock();
        try {
            return onRequestCore(requestHeader, browser);
        } finally {
            NicoCachingProcessor.giantLock.unlock();
        }
    }
    protected Resource onRequestCore(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        Matcher m = PROCESSOR_SUPPORTED_PATTERN.matcher(requestHeader.getURI());
        if (!m.find()) {
            // it must not happen...
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        MovieData data;
        try {
            data = new MovieData(m);
        } catch (NoIdInfoException e) {
            Logger.info("idInfo is not found");
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        } catch (NoHt2EntryException e) {
            Logger.info("ht2 information is not found");
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        } catch (InvalidHt2EntryException e) {
            Logger.warning(e.getMessage());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        String smid = data.getSmid();
        VideoDescriptor video = data.getVideoDescriptor();
        String postfix = data.getPostfix();

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

        VideoDescriptor cachedSmile = CacheManager.getPreferredCachedVideo(smid, false, postfix);
        VideoDescriptor cachedDmc = CacheManager.getPreferredCachedVideo(smid, true, postfix);

        // 再エンコードされていない非dmcキャッシュを持っているときは応答する (useNotReEncodedCache)
        // または高ビットレートな非dmcキャッシュを持っているときに応答する (useHighBitrateReEncodedCache)
        // または非dmcキャッシュを持っていてdmcエコノミーの時に応答する (useSmileCacheInsteadOfDmcEconomy)
        // または単に非dmcキャッシュを持っている時に応答する (useSmileCacheInsteadOfDmc)
        if (Boolean.getBoolean("useNotReEncodedCache") ||
                Boolean.getBoolean("useSmileCacheInsteadOfDmc") ||
                Boolean.getBoolean("useSmileCacheInsteadOfDmcEconomy") && video.isLow() ||
                Boolean.getBoolean("useHighBitrateReEncodedCache")) {
            if (cachedSmile != null && !cachedSmile.isLow() && cachedSmile.getPostfix().equals(Cache.MP4)) {
                Cache cache = new Cache(cachedSmile);
                boolean condForce = Boolean.getBoolean("useSmileCacheInsteadOfDmc");
                boolean condRaw = Boolean.getBoolean("useNotReEncodedCache") && !cache.isReEncoded();
                boolean condDmcEconomy = Boolean.getBoolean("useSmileCacheInsteadOfDmcEconomy") && video.isLow();
                if (condDmcEconomy) {
                    condDmcEconomy = condDmcEconomy && (cachedDmc == null || !cachedDmc.isSuperiorThan(video));
                }
                boolean condHighBitrate = Boolean.getBoolean("useHighBitrateReEncodedCache") && cache.isReEncoded();
                if (condHighBitrate && video.hasBitrate()) {
                    int dmcBitrate = video.getVideoBitrate() + video.getAudioBitrate();
                    int smileBitrate = ReEncodingInfo.getBitrate(cachedSmile.getId());
                    condHighBitrate = dmcBitrate < smileBitrate;
                }
                if (cache.exists()) {
                    if (condForce || condRaw || condDmcEconomy || condHighBitrate) {
                        // cvcacheにmp4ファイルを手動で配置した場合などに対応
                        // flv2mp4の変換処理は別途
                        {
                            File convertedFile = CacheManager.video2ConvertedMp4_get(cachedSmile);
                            if (convertedFile != null) {
                                Logger.info("using cvcache: " +  convertedFile.getName());
                                if (Boolean.getBoolean("touchCache")) {
                                    cache.touch();
                                }
                                Resource r = new LocalCacheFileResource(convertedFile);
                                r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
                                setCors(r, requestHeader);
                                return r;
                            }
                        }

                        if (condRaw) {
                            Logger.info("using raw smile cache: " + cache.getCacheFileName());
                        } else if (condForce || condDmcEconomy || condHighBitrate) {
                            Logger.info("using smile cache: " + cache.getCacheFileName());
                        }
                        if (Boolean.getBoolean("touchCache")) {
                            cache.touch();
                        }
                        Resource r = new LocalCacheFileResource(cache.getCacheFile());
                        r.addCacheControlResponseHeaders(12960000);
                        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
                        setCors(r, requestHeader);

                        LimitFlvSpeedListener.addTo(r);
                        return r;
                    } else {
                        Logger.info("ingoring reencoded smile cache: " + cache.getCacheFileName());
                    }
                }
            } else if (cachedSmile != null && !cachedSmile.isLow() && cachedSmile.getPostfix().equals(Cache.FLV)) {
                // flv2mp4
                Cache cache = new Cache(cachedSmile);
                String cachedPostfix = cache.getPostfix();
                boolean formatMismatch = !data.getPostfix().equals(cachedPostfix);
                if (formatMismatch && Boolean.getBoolean("convertFlv2Mp4")) {
                    File convertedFile;
                    Logger.info("using smile cache: " + cache.getCacheFileName());
                    // 変換処理は時間がかかるので一旦アンロックする
                    NicoCachingProcessor.giantLock.unlock();
                    try {
                        convertedFile = cache.getConvertedMp4FromFlv();
                    } finally {
                        NicoCachingProcessor.giantLock.lock();
                    }
                    if (convertedFile == null) {
                        Logger.info("Error in flv2mp4: " + cache.getCacheFileName());
                        return Resource.get(Resource.Type.URL, requestHeader.getURI());
                    }
                    Resource r = new LocalCacheFileResource(convertedFile);
                    r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
                    setCors(r, requestHeader);
                    return r;
                }
            }
        }

        // 既にキャッシュを持っていたらキャッシュから応答する
        // TODO: この方法だとZenzaWatchなどで解像度切り替えが出来ないのでapiを乗っ取る
        Logger.debug("Preferred cache: " + cachedDmc);
        if (cachedDmc != null && !video.isPreferredThan(cachedDmc, true, null)) {
            Cache cache = new Cache(cachedDmc);
            if (cache.exists()) {
                if (!data.isLowAccess() && cache.isLow()) {
                    Logger.info("unmark low: " + cache.getCacheFileName());
                    cache.unmarkLow();
                }
                Logger.info("using cache: " + cache.getCacheFileName());
                if (Boolean.getBoolean("touchCache")) {
                    cache.touch();
                }
                Resource r = new LocalCacheFileResource(cache.getCacheFile());
                r.addCacheControlResponseHeaders(12960000);
                r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
                setCors(r, requestHeader);

                LimitFlvSpeedListener.addTo(r);
                return r;
            }
        }

        // swf,flvのsmileキャッシュを持っている場合dmcをキャッシュしない
        if (cachedSmile != null && !cachedSmile.isLow()
                && !Cache.MP4.equals(cachedSmile.getPostfix())
                && !Boolean.getBoolean("workaroundNoDisableDoubleCacheImported")) {
            Logger.info("disable cache: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
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
                            data.getType(), data.getVideoId()));
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
        if (!Cache.getDLFlag(video) && mr != null && mr.isMapped(0, mr.getSize())) {
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
            setCors(r, requestHeader);
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

            // *.dmc.nicoではないホストへリダイレクトされることがある．
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

    private void setCors(Resource r, HttpRequestHeader requestHeader) {
        if (requestHeader.getMessageHeader("Origin") != null) {
            r.setResponseHeader("Access-Control-Allow-Credentials", "true");
            r.setResponseHeader("Access-Control-Allow-Origin", requestHeader.getMessageHeader("Origin"));
        }
    }


    @SuppressWarnings("serial")
    static class NoIdInfoException extends Exception { }
    @SuppressWarnings("serial")
    static class NoHt2EntryException extends Exception { }
    @SuppressWarnings("serial")
    static class InvalidHt2EntryException extends Exception {
        public InvalidHt2EntryException(String message) {
            super(message);
        }
    }

    /**
     * Class for manage various data for a movie.
     *
     */
    static final class MovieData {
        private String smid;
        private String type;
        private String videoId;
        private String srcId;
        private String videoMode;
        private int videoBitrate, audioBitrate;
        private String postfix;
        private boolean economy;
        private String ht2Key;
        private Ht2Entry ht2Entry;
        private NicoIdInfoCache.Entry idInfo;
        private VideoDescriptor video;
        private Cache cache;

        MovieData(Matcher m) throws NoIdInfoException, NoHt2EntryException, InvalidHt2EntryException {
            initializeTypeIdHt2Key(m);
            initializeIdInfo(getVideoId());
            initializeHt2Entry(ht2Key);
            initializePostfix(ht2Entry);
            initializeModeAndBitrate(ht2Entry);
            initializeEconomy(ht2Entry, getIdInfo());
            initializeCache(getIdInfo());
        }

        private void initializeTypeIdHt2Key(Matcher m) {
            type = m.group(1);
            videoId = NLShared.INSTANCE.vid2cid(m.group(2));
            smid = type + videoId;
            srcId = m.group(3);
            ht2Key = m.group(4);
        }

        private void initializeIdInfo(String id) throws NoIdInfoException {
            idInfo = NicoIdInfoCache.getInstance().get(id);
            if (idInfo == null) {
                throw new NoIdInfoException();
            }
        }

        private void initializeHt2Entry(String ht2Key) throws NoHt2EntryException {
            ht2Entry = NLShared.INSTANCE.getHt2Manager().get(ht2Key);
            if (ht2Entry == null) {
                throw new NoHt2EntryException();
            }
        }

        private void initializePostfix(Ht2Entry ht2Entry) {
            postfix = Cache.MP4;
        }

        private void initializeModeAndBitrate(Ht2Entry ht2Entry)
                throws InvalidHt2EntryException {
            Matcher mv;
            if ((mv = VIDEO_TYPE_PATTERN1.matcher(ht2Entry.getVideoType())).matches()) {
                videoBitrate = Integer.parseInt(mv.group(1));
                videoMode = mv.group(2);
            } else if ((mv = VIDEO_TYPE_PATTERN2.matcher(ht2Entry.getVideoType())).matches()) {
                videoBitrate = 0;
                videoMode = mv.group(1);
            } else {
                throw new InvalidHt2EntryException("Unknown video type syntax: " + ht2Entry.getVideoType());
            }

            Matcher ma = AUDIO_TYPE_PATTERN.matcher(ht2Entry.getAudioType());
            if (!ma.matches()) {
                throw new InvalidHt2EntryException("Unknown audio type syntax: " + ht2Entry.getAudioType());
            }
            audioBitrate = Integer.parseInt(ma.group(1));
        }

        private void initializeEconomy(Ht2Entry ht2Entry, NicoIdInfoCache.Entry idInfo) {
            Boolean videoEconomy = idInfo.getDmcVideoEconomy(ht2Entry.getVideoType());
            Boolean audioEconomy = idInfo.getDmcAudioEconomy(ht2Entry.getAudioType());
            if (videoEconomy == null || audioEconomy == null) {
                economy = false;
                return;
            }
            economy = videoEconomy || audioEconomy;
        }

        private void initializeCache(NicoIdInfoCache.Entry idInfo) {
            video = VideoDescriptor.newDmc(smid, postfix, isLowAccess(),
                    videoMode, videoBitrate, audioBitrate, srcId);
            VideoDescriptor rvideo = Cache.getRegisteredVideoDescriptor(video);
            if (rvideo != null) {
                video = rvideo;
            }
            if (idInfo == null) {
                cache = new Cache(video);
            } else {
                cache = new Cache(video, idInfo.getTitle());
            }
            if (!isLowAccess()) {
                cache.unmarkLow();
            }
        }

        public Cache getCache() {
            return cache;
        }

        public NicoIdInfoCache.Entry getIdInfo() {
            return idInfo;
        }

        public String getSmid() {
            return smid;
        }

        public String getType() {
            return type;
        }

        public String getVideoId() {
            return videoId;
        }

        public String getPostfix() {
            return postfix;
        }

        public boolean isLowAccess() {
            return economy;
        }

        public VideoDescriptor getVideoDescriptor() {
            return video;
        }
    }

}
