package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.util.Hls2SingleConverter;

/** `/cache/*` のキャッシュメディア配信専用Processor。 */
public class CacheDirProcessor implements Processor {
    private static final String[] SUPPORTED_METHODS = { null };
    private static final Pattern CACHE_DIR_PATTERN = Pattern.compile(
            "^https?://[^/]+\\.nicovideo\\.jp/cache(?:/|$)");
    private static final Pattern SINGLE_FILE_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*)?"
                    + "(?:\\.(?:flv|swf|mp4|webm|mkv)){1,2}$");
    private static final Pattern HLS_CACHE_PATTERN = Pattern.compile(
            "^([a-z]{2}[0-9]+(?:low)?(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\]\\w*)?\\.hls)$");
    private static final Pattern HLS_CONVERT_PATTERN = Pattern.compile(
            "^[a-z]{2}[0-9]+(?:low)?(?:(?:\\[[\\w-]+(?:,\\d+)?,\\d+\\])?\\w*\\.hls)?"
                    + "\\.(?:mp4|mkv|webm|flv)$");

    private final CmafUseCacheProcessor cmafCacheProcessor =
            new CmafUseCacheProcessor();

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

    @Override
    public Resource onRequest(HttpRequestHeader request, Socket browser)
            throws IOException {
        if (!HttpHeader.GET.equals(request.getMethod())
                && !HttpHeader.HEAD.equals(request.getMethod())) {
            return StringResource.getMethodNotAllowed();
        }
        String requestPath = request.getPathWithoutQuery();
        if (requestPath == null || !requestPath.startsWith("/cache/")) {
            return StringResource.getNotFound();
        }
        String path = requestPath.substring("/cache/".length());

        if (path.startsWith("file/")) {
            return cmafCacheProcessor.onRequestSubPath(
                    path.substring("file/".length()), request, browser);
        }

        Specifier specifier = new Specifier(path);
        VideoDescriptor video = specifier.video;
        Cache cache = specifier.cache;
        if (video != null && cache != null && !cache.exists()) {
            VideoDescriptor preferred = Cache.getPreferredCachedVideo(specifier.smid);
            if (preferred != null) {
                video = preferred;
                cache = new Cache(preferred);
            }
        }

        if (SINGLE_FILE_PATTERN.matcher(path).matches()) {
            if (cache == null || !cache.exists()) {
                return StringResource.getNotFound();
            }
            if (Cache.HLS.equals(cache.getPostfix())) {
                return new Hls2SingleConverter(cache, path,
                        request.getMessageHeader("User-Agent")).convert();
            }
            Logger.info("Local cache: " + cache.getId() + " " + cache.getTitle());
            if (Boolean.getBoolean("touchCache")) {
                cache.touch();
            }
            Resource resource = new LocalFileResource(cache.getCacheFile());
            LimitFlvSpeedListener.addTo(resource);
            resource.addCacheControlResponseHeaders(12960000);
            return resource;
        }

        Matcher hls = HLS_CACHE_PATTERN.matcher(path);
        if (hls.matches()) {
            return StringResource.getRedirect(request.getScheme()
                    + "://www.nicovideo.jp/cache/file/nicocachenl_refcache="
                    + hls.group(1) + "//");
        }

        if (HLS_CONVERT_PATTERN.matcher(path).matches()) {
            if (cache == null || !cache.exists()) {
                return StringResource.getNotFound();
            }
            return new Hls2SingleConverter(cache, path,
                    request.getMessageHeader("User-Agent")).convert();
        }

        return StringResource.getNotFound();
    }

    /** 単一キャッシュ指定を厳密に解析する。 */
    static final class Specifier {
        final String altid;
        final String smid;
        final VideoDescriptor video;
        final Cache cache;

        Specifier(String input) {
            AltVideoIdParser.ParsedId parsed = AltVideoIdParser.parse(input);
            if (parsed == null) {
                altid = "";
                smid = "";
                video = null;
                cache = null;
            } else {
                altid = parsed.altId;
                smid = parsed.smid;
                video = parsed.video;
                cache = new Cache(video);
            }
        }
    }
}
