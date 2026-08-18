package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

/** 専用ホストの内部メディア配信と旧経路の遮断を担当するProcessor。 */
public class CacheDirProcessor implements Processor {
    private static final String PLAYBACK_MEDIA_VERSION =
            "nicocachenl_media_version=2";
    private static final String[] SUPPORTED_METHODS = { null };
    private static final Pattern CACHE_DIR_PATTERN = Pattern.compile(
            "^(?:https?://" + Pattern.quote(NicoCacheWebProcessor.HOST)
                    + "/media/v1(?:/|$)"
                    + "|https?://[^/]+\\.nicovideo\\.jp/cache(?:[/?]|$))");
    private static final Pattern CACHE_ENTRY_FILES_PATTERN = Pattern.compile(
            "^/media/v1/cache-entries/([^/]+)/files(?:/(.*))?$");
    private static final Pattern PLAYBACK_SESSION_FILES_PATTERN = Pattern.compile(
            "^/media/v1/playback-sessions/([A-Za-z0-9._~,=-]{1,256})/files(?:/(.*))?$");

    private final CmafUseCacheProcessor cmafCacheProcessor;

    public CacheDirProcessor() {
        this(new CmafUseCacheProcessor());
    }

    CacheDirProcessor(CmafUseCacheProcessor cmafCacheProcessor) {
        this.cmafCacheProcessor = cmafCacheProcessor;
    }

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
        if (requestPath == null) {
            return StringResource.getNotFound();
        }
        // 旧配信経路を上流のnicovideo.jpへ誤転送しない。
        if (requestPath.equals("/cache") || requestPath.startsWith("/cache/")) {
            return StringResource.getNotFound();
        }

        Matcher cacheEntry = CACHE_ENTRY_FILES_PATTERN.matcher(requestPath);
        if (cacheEntry.matches()) {
            String cacheId = decodePathSegment(cacheEntry.group(1));
            Specifier specifier = new Specifier(cacheId);
            if (specifier.cache == null || specifier.video == null
                    || !specifier.altid.equals(cacheId)
                    || !Cache.HLS.equals(specifier.cache.getPostfix())) {
                return StringResource.getNotFound();
            }
            return cmafCacheProcessor.onCacheEntryPath(
                    specifier.cache, cacheEntry.group(2), request, browser);
        }

        Matcher playbackSession = PLAYBACK_SESSION_FILES_PATTERN.matcher(requestPath);
        if (playbackSession.matches()) {
            return cmafCacheProcessor.onPlaybackSessionPath(
                    playbackSession.group(1), playbackSession.group(2),
                    request, browser);
        }

        return StringResource.getNotFound();
    }

    static String playbackSessionFileUrl(String sessionId, String relativePath) {
        String separator = relativePath.contains("?") ? "&" : "?";
        return "https://" + NicoCacheWebProcessor.HOST
                + "/media/v1/playback-sessions/" + sessionId
                + "/files/" + relativePath + separator
                + PLAYBACK_MEDIA_VERSION;
    }

    private static String decodePathSegment(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "";
        }
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
