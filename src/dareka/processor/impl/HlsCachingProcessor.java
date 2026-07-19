package dareka.processor.impl;

import dareka.common.CloseUtil;
import dareka.common.Logger;
import dareka.common.Pair;
import dareka.extensions.CompleteCache;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.LocalCacheFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.TransferListener;
import dareka.processor.URLResource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HlsCachingProcessor implements Processor {

    private static final Pattern MASTER_PLAYLIST_URL_PATTERN = Pattern.compile(
        "^https?://[^.]++\\.dmc\\.nico/hlsvod/ht2_nicovideo/nicovideo-(\\w+?)(\\d+)_([a-z0-9]++)/master\\.m3u8\\?ht2_nicovideo=([^&]+)");
    private static final Pattern PLAYLIST_URL_PATTERN = Pattern.compile(
        "^https?://[^.]++\\.dmc\\.nico/hlsvod/ht2_nicovideo/nicovideo-(\\w+?)(\\d+)_([a-z0-9]++)/(\\d++)/ts/playlist\\.m3u8\\?ht2_nicovideo=([^&]+)");
    private static final Pattern TS_URL_PATTERN = Pattern.compile(
        "^https?://[^.]++\\.dmc\\.nico/hlsvod/ht2_nicovideo/nicovideo-(\\w+?)(\\d+)_([a-z0-9]++)/(\\d++)/ts/(\\d++\\.ts)\\?ht2_nicovideo=([^&]+)");

    static final Pattern VIDEO_TYPE_PATTERN1 = Pattern.compile("^archive_(?:[^_]+)_(\\d+)kbps_([^_]+)$");
    static final Pattern VIDEO_TYPE_PATTERN2 = Pattern.compile("^archive_(?:[^_]+)_([^_]+(?:_low)?)$");
    static final Pattern AUDIO_TYPE_PATTERN = Pattern.compile("^archive_(?:[^_]+)_(\\d+)kbps$");

    private final Executor executor;
    public final static ReentrantLock giantLock = new ReentrantLock();

    private static final String[] PROCESSOR_SUPPORTED_METHODS = new String[]{"GET"};
    private static final Pattern PROCESSOR_SUPPORTED_PATTERN = Pattern.compile(
        MASTER_PLAYLIST_URL_PATTERN.pattern() + "|" +
            PLAYLIST_URL_PATTERN.pattern() + "|" +
            TS_URL_PATTERN.pattern());

    public HlsCachingProcessor(Executor executor) { this.executor = executor; }

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
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) throws IOException {
        String uri = requestHeader.getURI();

        // Logger.info("hls url " + uri);
        Matcher m;
        if ((m = MASTER_PLAYLIST_URL_PATTERN.matcher(uri)).matches()) {
            return processMasterPlaylist(requestHeader, m.group(1), m.group(2), m.group(3), m.group(4));
        } else if ((m = PLAYLIST_URL_PATTERN.matcher(uri)).matches()) {
            return processPlaylist(requestHeader, m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
        } else if ((m = TS_URL_PATTERN.matcher(uri)).matches()) {
            return processTs(requestHeader, m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
        }
        return null;
    }

    private MovieData getMovieData(String type, String videoId, String srcId, String ht2Key, boolean logging) {
        MovieData data;
        try {
            data = new MovieData(type, videoId, srcId, ht2Key);
        } catch (NoIdInfoException e) {
            if (logging)
                Logger.info("(hls)idInfo is not found");
            return null;
        } catch (NoHt2EntryException e) {
            if (logging)
                Logger.info("(hls)ht2 information is not found");
            return null;
        } catch (InvalidHt2EntryException e) {
            if (logging)
                Logger.warning("(hls)" + e.getMessage());
            return null;
        }
        return data;
    }

    private boolean isCacheableMovieData(MovieData data, boolean logging) {
        if (data.getEncrypted()) {
            if (logging)
                Logger.info("(hls)暗号化ストリーミング動画のためキャッシュしません: " + data.getSmid());
            return false;
        }
        if (data.getMultipleStreams()) {
            if (logging)
                Logger.info("(hls)画質が自動になっているのでキャッシュしません: " + data.getSmid());
            return false;
        }
        if (data.getSeparatedAudioStream()) {
            if (logging)
                Logger.info("(hls)音声が分離されているためキャッシュできません: " + data.getSmid());
            return false;
        }
        return true;
    }

    private boolean isAcceptableStream(MovieData data, String streamId, boolean logging) {
        if (!streamId.equals("1")) {
            if (logging)
                Logger.warning("(hls)lesser stream detected streamId=" + streamId + ": " + data.getSmid());
            return false;
        }
        return true;
    }

    private static File getStreamTsDirectory(Cache cache, String streamId) {
        File cacheDir = cache.getCacheFile();
        if (cacheDir == null || !cacheDir.exists()) {
            return null;
        }
        File streamDir = new File(cacheDir, streamId);
        File streamTsDir = new File(streamDir, "ts");
        return streamTsDir;
    }

    private static File getTmpStreamTsDirectory(Cache cache, String streamId) {
        File tmpCacheDir = cache.getCacheTmpFile();
        if (tmpCacheDir == null || !tmpCacheDir.exists()) {
            return null;
        }
        File streamDir = new File(tmpCacheDir, streamId);
        File streamTsDir = new File(streamDir, "ts");
        return streamTsDir;
    }

    private boolean hasSuperiorIncompatibleCache(MovieData data) {
        String smid = data.getSmid();
        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        VideoDescriptor cachedSmile = CacheManager.getPreferredCachedVideo(smid, false, null);
        VideoDescriptor cachedDmc = CacheManager.getPreferredCachedVideo(smid, true, null);
        if ((cachedSmile != null && !cachedSmile.isLow()
            || cachedDmc != null && !cachedDmc.isLow())
            && !Boolean.getBoolean("workaroundNoDisableDoubleCacheImported")) {
            return true;
        }
        return false;
    }

    private int notifyAndCheckResult(NLEventSource eventSource, int eventId) {
        if (eventSource != null) {
            int result = NLShared.INSTANCE.notifySystemEvent(eventId, eventSource, true);
            if (result != SystemEventListener.RESULT_OK) {
                return result;
            }
        }
        return SystemEventListener.RESULT_OK;
    }

    private static final Pattern PLAYLIST_QUERY_STRING_PATTERN = Pattern.compile("^(.*?)\\?.*");

    private static String removeQueryStringsFromPlaylist(String content) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            while (true) {
                String line = br.readLine();
                if (line == null) break;
                Matcher m;
                if (line.startsWith("#") || "".equals(line)) {
                    sb.append(line);
                } else if ((m = PLAYLIST_QUERY_STRING_PATTERN.matcher(line)).matches()) {
                    sb.append(m.group(1));
                } else {
                    sb.append(line);
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    private Pattern PLAYLIST_PATTERN = Pattern.compile("^(\\d++/ts/playlist\\.m3u8).*");

    private Resource processMasterPlaylist(HttpRequestHeader requestHeader, String type, String videoId,
                                           String srcId, String ht2Key) throws IOException {
        MovieData data = getMovieData(type, videoId, srcId, ht2Key, true);
        if (data == null) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader, data.getCache());
        }

        // [nl] Extensionにキャッシュ要求イベントを通知する
        if (notifyAndCheckResult(eventSource, SystemEventListener.CACHE_REQUEST) != SystemEventListener.RESULT_OK) {
            Logger.debug("(hls)" + requestHeader.getURI() + " pass-through by extension");
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // 既にキャッシュを持っていたらキャッシュから応答する
        Resource r = processMasterPlaylistFromCacheIfExists(requestHeader, data);
        if (r != null) {
            return r;
        }

        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        if (hasSuperiorIncompatibleCache(data)) {
            Logger.info("(hls)disable cache: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        if (!isCacheableMovieData(data, true)) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // [nl] Extensionがキャッシュを禁止する場合はキャッシュしない
        if (notifyAndCheckResult(eventSource, SystemEventListener.CACHE_STARTING) != SystemEventListener.RESULT_OK) {
            Logger.info("(hls)disable cache: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        scheduleTitleRetrieverIfNeeded(data);

        Logger.info("(hls)no cache found: " + data.getCache().getCacheFileName());
        return processMasterPlaylistFromServer(requestHeader, data);
    }

    private void scheduleTitleRetrieverIfNeeded(MovieData data) {
        FutureTask<String> retrieveTitlteTask = null;
        if (Boolean.getBoolean("title")
            && (data.getIdInfo() == null || !data.getIdInfo().isTitleValid())) {
            retrieveTitlteTask =
                new FutureTask<>(new NicoCachingTitleRetriever(
                    data.getType(), data.getVideoId()));
            executor.execute(retrieveTitlteTask);
        }
    }

    private Resource processMasterPlaylistFromCacheIfExists(HttpRequestHeader requestHeader, MovieData data)
        throws IOException {
        String smid = data.getSmid();
        VideoDescriptor video = data.getVideoDescriptor();
        String postfix = data.getPostfix();

        // TODO: この方法だと解像度切り替えが出来ないのでapiを乗っ取る
        VideoDescriptor cachedHls = CacheManager.getPreferredCachedVideo(smid, true, Cache.HLS);
        Logger.debug("(hls)Preferred cache: " + cachedHls);
        if (cachedHls == null || video.isPreferredThan(cachedHls, true, postfix)) {
            return null;
        }

        Cache cache = new Cache(cachedHls);
        if (!cache.exists()) {
            return null;
        }

        Logger.info("(hls)using cache: " + cache.getCacheFileName());
        if (Boolean.getBoolean("touchCache")) {
            cache.touch();
        }

        File cacheDir = cache.getCacheFile();
        File file = new File(cacheDir, "master.m3u8");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            while (true) {
                String line = br.readLine();
                if (line == null) break;
                if (!line.startsWith("#") && !"".equals(line)) {
                    line += "?ht2_nicovideo=" + data.ht2Key;
                }
                baos.write(line.getBytes(StandardCharsets.UTF_8));
                baos.write('\n');
            }
        } catch (IOException e) {
            Logger.warning("(hls#MPFCIE)Failed to load playlist: " + file.getPath());
        }

        Resource r = new StringResource(baos.toByteArray());
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(r, requestHeader);

        LimitFlvSpeedListener.addTo(r);
        return r;
    }

    private URLResource processMasterPlaylistFromServer(HttpRequestHeader requestHeader, MovieData data) throws IOException {
        Pair<URLResource, byte[]> rr = fetchBinaryContent(requestHeader);
        URLResource r = rr.first;
        byte[] bcontent = rr.second;

        if (bcontent == null) {
            return r;
        }

        String content;
        try {
            content = new String(bcontent, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Logger.warning("(hls)failed to access to playlist: " + data.getSmid() + " (cannot decode UTF-8)");
            return r;
        }

        giantLock.lock();
        try {
            // ここで一時キャッシュのディレクトリ作成
            File dir = data.getCache().prepareTmpHlsDirectory();

            // master.m3u8の改行コードをLFにするためバイナリIO
            File playlist = new File(dir, "master.m3u8");
            try (FileOutputStream fos = new FileOutputStream(playlist, false)) {
                content = removeQueryStringsFromPlaylist(content);
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Logger.info("(hls)Failed to write list: " + playlist.getPath());
            }
        } finally {
            giantLock.unlock();
        }
        return r;
    }

    private Pattern X_KEY_PATTERN = Pattern.compile("^#EXT-X-KEY:METHOD=AES-128,.*", Pattern.MULTILINE);

    private Resource processPlaylist(HttpRequestHeader requestHeader, String type, String videoId,
                                     String srcId, String streamId, String ht2Key) throws IOException {
        MovieData data = getMovieData(type, videoId, srcId, ht2Key, true);
        if (data == null) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader, data.getCache());
        }

        // 既にキャッシュを持っていたらキャッシュから応答する
        Resource r = processPlaylistFromCacheIfExists(requestHeader, data, streamId);
        if (r != null) {
            return r;
        }

        // キャッシュのディレクトリが存在しないなら processMasterPlaylist で
        // キャッシュしない決定をしている
        File tmpCacheFile = data.getCache().getCacheTmpFile();
        if (tmpCacheFile == null || !tmpCacheFile.exists()) {
            Logger.debug("(hls)disable cache playlist: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        if (hasSuperiorIncompatibleCache(data)) {
            Logger.debug("(hls)disable cache playlist: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        if (!isCacheableMovieData(data, true) || !isAcceptableStream(data, streamId, true)) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        return processPlaylistFromServer(requestHeader, data, streamId);
    }

    private Resource processPlaylistFromCacheIfExists(HttpRequestHeader requestHeader, MovieData data, String streamId)
        throws IOException {
        String smid = data.getSmid();
        VideoDescriptor video = data.getVideoDescriptor();
        String postfix = data.getPostfix();

        // TODO: この方法だと解像度切り替えが出来ないのでapiを乗っ取る
        VideoDescriptor cachedHls = CacheManager.getPreferredCachedVideo(smid, true, Cache.HLS);
        Logger.debug("(hls)Preferred cache: " + cachedHls);
        if (cachedHls == null || video.isPreferredThan(cachedHls, true, postfix)) {
            return null;
        }

        Cache cache = new Cache(cachedHls);
        if (!cache.exists()) {
            return null;
        }

        Logger.debug("(hls)using cache playlist: " + cache.getCacheFileName() + "/" + streamId + "/ts/playlist.m3u8");
        if (Boolean.getBoolean("touchCache")) {
            cache.touch();
        }

        File streamTsDir = getStreamTsDirectory(cache, streamId);
        File file = new File(streamTsDir, "playlist.m3u8");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            while (true) {
                String line = br.readLine();
                if (line == null) break;
                if (!line.startsWith("#") && !"".equals(line)) {
                    line += "?ht2_nicovideo=" + data.ht2Key;
                }
                baos.write(line.getBytes(StandardCharsets.UTF_8));
                baos.write('\n');
            }
        } catch (IOException e) {
            Logger.warning("(hls#PFCIE)Failed to load playlist: " + file.getPath());
        }

        Resource r = new StringResource(baos.toByteArray());
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(r, requestHeader);

        LimitFlvSpeedListener.addTo(r);
        return r;
    }

    private URLResource processPlaylistFromServer(HttpRequestHeader requestHeader, MovieData data, String streamId) throws IOException {
        Pair<URLResource, byte[]> rr = fetchBinaryContent(requestHeader);
        URLResource r = rr.first;
        byte[] bcontent = rr.second;

        if (bcontent == null) {
            return r;
        }

        String content;
        try {
            content = new String(bcontent, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Logger.warning("(hls)failed to access to playlist: " + data.getSmid() + " (cannot decode UTF-8)");
            return r;
        }

        // ignore encrypted media
        Matcher m = X_KEY_PATTERN.matcher(content);
        if (m.find()) {
            return r;
        }

        giantLock.lock();
        try {
            File dir = getTmpStreamTsDirectory(data.getCache(), streamId);
            if (dir == null) {
                return r;
            }
            dir.mkdirs();

            content = removeQueryStringsFromPlaylist(content);
            bcontent = content.getBytes(StandardCharsets.UTF_8);

            // playlistを比較して変化していたらtsファイルをすべて消す
            File playlist = new File(dir, "playlist.m3u8");
            if (playlist.exists()) {
                byte[] prevBcontent = new byte[(int) playlist.length()];
                try (FileInputStream fis = new FileInputStream(playlist)) {
                    fis.read(prevBcontent);
                }
                if (!Arrays.equals(bcontent, prevBcontent)) {
                    // "m3mu"と書いてあったがたぶん誤字.
                    Logger.info("(hls)Playlist.m3u8 mismatch: " + playlist.getPath());
                    deleteContainedFiles(dir);
                }
            }

            // playlist.m3u8の改行コードをLFにするためバイナリIO
            try (FileOutputStream fos = new FileOutputStream(playlist, false)) {
                fos.write(bcontent);
            } catch (IOException e) {
                Logger.info("(hls)Failed to write list: " + playlist.getPath());
            }
        } finally {
            giantLock.unlock();
        }
        return r;
    }

    private Pair<URLResource, byte[]> fetchBinaryContent(HttpRequestHeader requestHeader) throws IOException {
        String uri = requestHeader.getURI();
        requestHeader.removeHopByHopHeaders();

        // 解凍できないEncodingは削除
        String acceptEncoding = requestHeader.getMessageHeader(HttpHeader.ACCEPT_ENCODING);
        if (acceptEncoding != null) {
            acceptEncoding = acceptEncoding.toLowerCase().replaceAll(
                "(?: *, *)?(?:bzip2|sdch|br|compress|zstd|dcb|dcz)(?:;[^,]*)?", "");
            acceptEncoding = acceptEncoding.replaceFirst("^ *, *", "");
            requestHeader.setMessageHeader(HttpHeader.ACCEPT_ENCODING, acceptEncoding);
        }

        // ヘッダを受信して、Bodyも受信するか判断
        URLResource r = new URLResource(uri);
        HttpResponseHeader responseHeader = r.getResponseHeader(null, requestHeader);
        if (responseHeader == null) {
            Logger.warning("(hls)failed to access to: " + uri + " (no responseHeader)");
            return new Pair<>(r, null);
        }
        responseHeader.removeHopByHopHeaders();

        if (responseHeader.getMessageHeader(HttpHeader.CONTENT_ENCODING) != null) {
            Logger.warning("(hls)failed to access to: " + uri + " (cannot decode)");
            return new Pair<>(r, null);
        }

        responseHeader.removeMessageHeader("Vary");
        responseHeader.removeMessageHeader("Accept-Ranges");

        // Bodyの取得
        byte[] bcontent = r.getResponseBody();
        if (bcontent == null) {
            Logger.warning("(hls)failed to access to: " + uri + " (no responseBody)");
            return new Pair<>(r, null);
        }

        return new Pair<>(r, bcontent);
    }

    private static void deleteContainedFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }

    private Resource processTs(HttpRequestHeader requestHeader, String type, String videoId, String srcId,
                               String streamId, String segment, String ht2Key) throws IOException {
        MovieData data = getMovieData(type, videoId, srcId, ht2Key, false);
        if (data == null) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader, data.getCache());
        }

        // 既にキャッシュを持っていたらキャッシュから応答する
        Resource r = processTsFromCacheIfExists(requestHeader, data, streamId, segment);
        if (r != null) {
            return r;
        }

        // キャッシュのディレクトリが存在しないなら processPlaylist で
        // キャッシュしない決定をしている
        File streamTsDirectory = getTmpStreamTsDirectory(data.getCache(), streamId);
        if (streamTsDirectory == null || !streamTsDirectory.exists()) {
            Logger.debug("(hls)disable cache ts: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        if (hasSuperiorIncompatibleCache(data)) {
            Logger.debug("(hls)disable cache ts: " + data.getCache().getCacheFileName());
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        if (!isCacheableMovieData(data, false) || !isAcceptableStream(data, streamId, false)) {
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        return processTsFromServer(requestHeader, data, streamId, segment, eventSource);
    }

    private Resource processTsFromCacheIfExists(HttpRequestHeader requestHeader, MovieData data,
                                                String streamId, String segment)
        throws IOException {
        String smid = data.getSmid();
        VideoDescriptor video = data.getVideoDescriptor();
        String postfix = data.getPostfix();

        // TODO: この方法だと解像度切り替えが出来ないのでapiを乗っ取る
        VideoDescriptor cachedHls = CacheManager.getPreferredCachedVideo(smid, true, Cache.HLS);
        Logger.debug("(hls)Preferred cache: " + cachedHls);
        if (cachedHls == null || video.isPreferredThan(cachedHls, true, postfix)) {
            return null;
        }

        Cache cache = new Cache(cachedHls);
        if (!cache.exists()) {
            return null;
        }

        File streamTsDir = getStreamTsDirectory(cache, streamId);
        File file = new File(streamTsDir, segment);
        if (!file.exists()) {
            Logger.info("(hls)キャッシュが壊れています: " + cachedHls);
            return null;
        }

        Logger.debug("(hls)using cache ts: " + cache.getCacheFileName() + "/" + streamId + "/ts/" + segment);
        if (Boolean.getBoolean("touchCache")) {
            cache.touch();
        }

        Resource r = new LocalCacheFileResource(file);
        r.addCacheControlResponseHeaders(12960000);
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "video/mp4");
        setCors(r, requestHeader);

        LimitFlvSpeedListener.addTo(r);
        return r;
    }

    private URLResource processTsFromServer(HttpRequestHeader requestHeader, MovieData data, String streamId,
                                            String segment, NLEventSource eventSource) throws IOException {
        VideoDescriptor video = data.getVideoDescriptor();
        String ht2Key = data.ht2Key;

        URLResource r;
        try {
            Cache.incrementDL(video);

            r = new URLResource(requestHeader.getURI());

            // *.dmc.nicoではないホストへリダイレクトされることがある．
            // 転送先は http://ISP内部のIPアドレス:80/data/16桁英数字/元のURLのホスト名以降 らしい．
            // CDNのようなものだろうか．
            r.setFollowRedirects(true);

            r.addTransferListener(new TsListener(data.getCache(), streamId, segment, ht2Key, eventSource));
        } catch (RuntimeException e) {
            Logger.error(e);
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

    private static class TsListener implements TransferListener {

        Cache cache;
        VideoDescriptor video;
        String streamId;
        String segment;
        String ht2Key;
        File tmpCacheDir;
        File dir;
        File file;
        File tmpFile;
        FileChannel cacheChannel = null;
        NLEventSource eventSource;

        boolean errorOccurred;
        long fileLength = 0;
        long currentPosition = 0;

        public TsListener(Cache cache, String streamId, String segment, String ht2Key, NLEventSource eventSource) {
            this.cache = cache;
            this.video = cache.getVideoDescriptor();
            this.segment = segment;
            this.streamId = streamId;
            this.ht2Key = ht2Key;
            this.tmpCacheDir = cache.getCacheTmpFile();
            this.dir = getTmpStreamTsDirectory(cache, streamId);
            this.file = new File(dir, segment);
            this.tmpFile = new File(dir, "hlstmp_" + segment);
            this.eventSource = eventSource;
        }

        @Override
        public void onResponseHeader(HttpResponseHeader responseHeader) {
            if (dir == null || !dir.exists()) {
                return;
            }

            if (tmpFile.exists()) {
                tmpFile.delete();
            }

            long contentLength = responseHeader.getContentLength();
            int statusCode = responseHeader.getStatusCode();

            fileLength = contentLength;

            if (contentLength <= 0) {
                Logger.warning("(hls)Invalid Content-Length: " + contentLength);
                errorOccurred = true;
            }

            if (statusCode == 206) {
                Logger.warning("(hls)Ranged request");
                errorOccurred = true;
                return;
            } else if (statusCode != 200) {
                Logger.warning("(hls)Invalid status code: " + statusCode);
                errorOccurred = true;
                return;
            }

            try {
                RandomAccessFile fOut = new RandomAccessFile(tmpFile, "rw");
                cacheChannel = fOut.getChannel();
            } catch (FileNotFoundException e) {
                Logger.error(e);
                errorOccurred = true;
                return;
            }
        }

        @Override
        public void onTransferBegin(OutputStream receiverOut) {
            // NOP
        }

        @Override
        public void onTransferring(byte[] buf, int length) {
            if (errorOccurred || cacheChannel == null) {
                return;
            }

            try {
                cacheChannel.write(ByteBuffer.wrap(buf, 0, length), currentPosition);
                currentPosition += length;
            } catch (IOException e) {
                Logger.warning("(hls)" + tmpFile + ": " + e.toString());
                errorOccurred = true;
            }
        }

        @Override
        public void onTransferEnd(boolean completed) {
            giantLock.lock();
            try {
                try {
                    onTransferEndCore(completed);
                } finally {
                    // [nl] DL中フラグを消す
                    // Cache#store()後じゃないとExtension等に不具合が出るので注意
                    // Cache#store()でVideoDescriptorが差し替わることに注意
                    Cache.decrementDL(video);
                }
            } catch (IOException e) {
                Logger.debugWithThread(e);
                Logger.warning(e.toString());
            } finally {
                giantLock.unlock();
            }
        }

        private void onTransferEndCore(boolean completed) throws IOException {
            if (cacheChannel == null) {
                return;
            }

            if (CloseUtil.close(cacheChannel) == false) {
                errorOccurred = true;
            }

            boolean cacheCompleted = !errorOccurred && currentPosition == fileLength;
            try {
                if (!cacheCompleted) {
                    return;
                }

                // remove old file
                if (file.exists()) {
                    file.delete();
                }

                tmpFile.renameTo(file);
            } finally {
                tmpFile.delete();
            }

            CacheManager.HlsTmpSegments hlsTmpSegments = CacheManager.HlsTmpSegments.get(video);
            if (hlsTmpSegments == null || !hlsTmpSegments.addCachedSegmentAndCheckComplete(streamId, segment)) {
                return;
            }

            // 一時キャッシュを途中で削除するなどの原因でhlsTmpSegmentsにキャッシュ済みと記録されていても
            // 実際にはファイルが存在しない可能性があるので確認する
            if (!checkPlaylistExistence(hlsTmpSegments)) {
                return;
            }
            if (!checkSegmentsExistence(hlsTmpSegments)) {
                if (!reloadHlsTmpSegments(hlsTmpSegments)) {
                    return;
                }
            }

            cache.store();

            // [nl] 存在チェックと完了時のイベント通知
            if (!cache.exists()) {
                Logger.debug("(hls)no complete cache exists: " + cache.getId());
                return;
            }
            notifyCompleted();

            Logger.info("(hls)cache completed: " + cache.getCacheFileName());
        }

        private boolean checkPlaylistExistence(CacheManager.HlsTmpSegments hlsTmpSegments) {
            for (String playlistPath : hlsTmpSegments.getPlaylists()) {
                if (!new File(tmpCacheDir, playlistPath).exists()) {
                    return false;
                }
            }
            return true;
        }

        private boolean checkSegmentsExistence(CacheManager.HlsTmpSegments hlsTmpSegments) {
            for (String segmentPath : hlsTmpSegments.getCachedSegments()) {
                if (!new File(tmpCacheDir, segmentPath).exists()) {
                    return false;
                }
            }
            return true;
        }

        private boolean reloadHlsTmpSegments(CacheManager.HlsTmpSegments hlsTmpSegments) {
            hlsTmpSegments.clearCachedSegments();
            for (String segmentPath : hlsTmpSegments.getPlaylistSegments()) {
                if (new File(tmpCacheDir, segmentPath).exists()) {
                    if (hlsTmpSegments.addCachedSegmentAndCheckComplete(segmentPath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void notifyCompleted() {
            for (CompleteCache entry : NLShared.INSTANCE.getCompleteEntries()) {
                try {
                    if (entry.onComplete(cache)) {
                        break; // trueが返ってきた時点で終了
                    }
                } catch (Throwable t) {
                    Logger.error(t); // エラーは無視して続行
                }
            }
            if (eventSource != null) {
                NLShared.INSTANCE.notifySystemEvent(
                    SystemEventListener.CACHE_COMPLETED, eventSource, false);
            }
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
        private boolean multipleStreams;
        private boolean separatedAudioStream;
        private String videoMode;
        private int videoBitrate, audioBitrate;
        private String postfix;
        private boolean lowAccess;
        private String ht2Key;
        private Ht2Entry ht2Entry;
        private NicoIdInfoCache.Entry idInfo;
        private VideoDescriptor video;
        private Cache cache;
        private boolean encrypted;

        private MovieData(String type, String videoId, String srcId, String ht2Key)
            throws NoIdInfoException, NoHt2EntryException, InvalidHt2EntryException {
            this.type = type;
            this.videoId = NLShared.INSTANCE.vid2cid(videoId);
            this.smid = type + this.videoId;
            this.srcId = srcId;
            this.ht2Key = ht2Key;

            Logger.info("(hls)getVideoId: " + getVideoId());
            initializeIdInfo(getVideoId());
            initializeHt2Entry(ht2Key);
            initializePostfix(ht2Entry);
            initializeModeAndBitrate(ht2Entry);
            initializeEconomy(ht2Entry, getIdInfo());
            initializeEncrypted(ht2Entry);
            initializeCache(getIdInfo());
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
            postfix = Cache.HLS;
        }

        private void initializeModeAndBitrate(Ht2Entry ht2Entry)
            throws InvalidHt2EntryException {
            multipleStreams = ht2Entry.getMultiStream();
            separatedAudioStream = ht2Entry.getSeparatedAudioStream();

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

            // 引数は"archive_h264_2000kbps_720p" , "archive_aac_64kbps"のような.
            Boolean videoEconomy = idInfo.getDmcVideoEconomy(ht2Entry.getVideoType());
            Boolean audioEconomy = idInfo.getDmcAudioEconomy(ht2Entry.getAudioType());

            // || ではなく && の間違いではないか?
            if (videoEconomy == null || audioEconomy == null) {
                lowAccess = false;
                return;
            }
            lowAccess = videoEconomy || audioEconomy;
        }

        private void initializeEncrypted(Ht2Entry ht2Entry) {
            encrypted = ht2Entry.getEncrypted();
        }

        private void initializeCache(NicoIdInfoCache.Entry idInfo) {
            // 例: videoMode: 720p
            // 例: srcId: 16進数64桁
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

        public boolean getMultipleStreams() {
            return multipleStreams;
        }

        public boolean getSeparatedAudioStream() {
            return separatedAudioStream;
        }

        public String getPostfix() {
            return postfix;
        }

        public boolean isLowAccess() {
            return lowAccess;
        }

        public VideoDescriptor getVideoDescriptor() {
            return video;
        }

        public boolean getEncrypted() {
            return encrypted;
        }
    }
}
