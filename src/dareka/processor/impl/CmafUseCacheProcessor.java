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
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

// 個別HLSキャッシュと再生中CMAFセッションのファイルを
// nicocachenl.test/media/v1 配下から配信する。
public class CmafUseCacheProcessor implements Processor {

    private static final Pattern PROCESSOR_SUPPORTED_PATTERN = Pattern.compile(
            "^https?://" + Pattern.quote(NicoCacheWebProcessor.HOST)
                    + "/media/v1(?:/|$)");

    public final static ReentrantLock giantLock = new ReentrantLock();

    private static final String[] PROCESSOR_SUPPORTED_METHODS =
            new String[] { "GET", "HEAD" };
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

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) {
        try {
            return new CacheDirProcessor(this).onRequest(requestHeader, browser);
        } catch (Exception e) {
            Logger.error(e);
        }
        return StringResource.getNotFound();
    }

    /** @deprecated 旧内部配信経路は廃止済み。 */
    @Deprecated
    public Resource onRequestSubPath(
        String path, HttpRequestHeader requestHeader, Socket browser) {
        return StringResource.getNotFound();
    }

    /** 完成済みHLSキャッシュのファイルを専用ホストから配信する。 */
    Resource onCacheEntryPath(Cache cache, String subpath,
            HttpRequestHeader requestHeader, Socket browser) {
        if (cache == null || !cache.exists()
                || !Cache.HLS.equals(cache.getPostfix())) {
            return StringResource.getNotFound();
        }
        Resource resource = responseFile(cache, normalizeSubpath(subpath),
                requestHeader, browser);
        return resource == null ? StringResource.getNotFound() : resource;
    }

    /** 再生中CMAFセッションが選んだHLSキャッシュのファイルを配信する。 */
    Resource onPlaybackSessionPath(String sessionId, String subpath,
            HttpRequestHeader requestHeader, Socket browser) {
        Cache cache = getCacheByDomandCVIKey(sessionId);
        if (cache == null || !cache.exists()) {
            return StringResource.getNotFound();
        }
        Resource resource = responseFile(cache, normalizeSubpath(subpath),
                requestHeader, browser);
        if (resource != null) {
            resource.setResponseHeader("Cache-Control", "no-store");
        }
        return resource == null ? StringResource.getNotFound() : resource;
    }

    private static String normalizeSubpath(String subpath) {
        return subpath == null || subpath.isEmpty() ? "master.m3u8" : subpath;
    }

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

        File cacheroot;
        File file;
        try {
            cacheroot = cache.getCacheFile().getCanonicalFile();
            file = new File(cacheroot, subpath).getCanonicalFile();
            if (!file.toPath().startsWith(cacheroot.toPath())
                    || file.equals(cacheroot)) {
                Logger.warning("(CmafUseCacheProcessor)invalid cache subpath: "
                        + subpath);
                return null;
            }
        } catch (IOException error) {
            Logger.warning("(CmafUseCacheProcessor)failed to resolve cache subpath: "
                    + subpath);
            return null;
        }
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

    private static Cache getCacheByDomandCVIKey(String key) {

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
        return r;
    };


};
