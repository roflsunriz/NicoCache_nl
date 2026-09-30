package functional;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.regex.Matcher;

import dareka.processor.HttpResponseHeader;
import dareka.processor.impl.CmafCachingProcessor;
import dareka.processor.impl.WatchRewriter;
import dareka.processor.impl.WatchVars;

/** 公開watchV4の構造に基づく、秘密情報を含まない合成fixture。 */
public final class WatchV4UnitTest {
    private WatchV4UnitTest() {
    }

    static String data(String id, String playlist) {
        return "{\"video\":{\"id\":\"" + id + "\",\"title\":\"Watch V4\","
                + "\"duration\":1,\"isDeleted\":false},\"media\":{\"contents\":[{"
                + "\"videos\":[{\"id\":\"video-h264-1080p\",\"isAvailable\":false},"
                + "{\"id\":\"video-h264-720p\",\"isAvailable\":true}],"
                + "\"audios\":[{\"id\":\"audio-aac-192kbps\",\"isAvailable\":true},"
                + "{\"id\":\"audio-aac-128kbps\",\"isAvailable\":true}]},{"
                + "\"videos\":[{\"id\":\"video-h264-480p\",\"isAvailable\":true}],"
                + "\"audios\":[{\"id\":\"audio-aac-64kbps\",\"isAvailable\":true}]}],"
                + "\"hls\":{\"url\":\"" + playlist + "?session=fixture\"}},"
                + "\"system\":{\"isPeakTime\":false},\"viewer\":{\"isPremium\":false}}";
    }

    static String html(String data) {
        String envelope = "{\"meta\":{\"status\":200},\"data\":{\"response\":{"
                + "\"$watchV4\":{\"data\":" + data + "},\"pcweb\":{}}}}";
        return "<!doctype html><meta name=\"server-response\" content=\""
                + envelope.replace("&", "&amp;").replace("\"", "&quot;") + "\">";
    }

    public static void run() throws Exception {
        String url = "https://delivery.domand.nicovideo.jp/hlsbid/abcdef"
                + "/playlists/variants/1234abcd.m3u8";
        WatchVars vars = WatchVars.get(html(data("sm990040", url)));
        equal("sm990040", vars.getVideoId(), "watchV4 HTML video ID");
        equal("Watch V4", vars.getVideoTitle(), "watchV4 title");
        equal("sm990040", mappings().get(url), "initial HTML registers playlist without access-rights");
        Map<?, ?> qualities = quality(vars, "qualityVideos");
        equal(false, qualities.get("video-h264-1080p"), "unavailable quality remains unavailable");
        equal(true, qualities.get("video-h264-480p"), "all content groups contribute qualities");
        equal(true, quality(vars, "qualityAudios").get("audio-aac-64kbps"), "audio qualities");
        String next = url.replace("1234abcd", "abcd1234");
        WatchVars raw = WatchVars.get("{\"meta\":{\"status\":200},\"data\":"
                + data("sm990041", next) + "}");
        equal("sm990041", raw.getVideoId(), "raw watchV4 API video ID");
        equal("sm990041", mappings().get(next), "raw API registers refreshed playlist");
        String refresh = url.replace("1234abcd", "abcddcba");
        String partial = "{\"meta\":{\"status\":200},\"data\":{\"responseType\":\"media\","
                + "\"media\":{\"hls\":{\"url\":\"" + refresh + "?session=refresh\"}}}}";
        rewrite("https://nvapi.nicovideo.jp/v4/watch/sm990040?__retry=1", partial, 200);
        equal("sm990040", mappings().get(refresh), "media-only API gets video ID from URL");
        equal("Watch V4", WatchVars.get("sm990040").getVideoTitle(),
                "media-only update preserves cached metadata");
        rewrite("https://nvapi.nicovideo.jp/v4/watch/sm990040", partial.replace(
                refresh + "?session=refresh", "opaque-fixture"), 200);
        equal("sm990040", mappings().get(refresh),
                "opaque API value cannot replace a valid playlist mapping");
        String failed = partial.replace("abcddcba", "aaaabbbb");
        rewrite("https://nvapi.nicovideo.jp/v4/watch/sm990040", failed, 403);
        equal(false, mappings().containsKey(refresh.replace("abcddcba", "aaaabbbb")),
                "HTTP failure cannot register playlist");
        String foreign = "https://example.invalid/playlists/variants/1234abcd.m3u8";
        WatchVars.get(html(data("sm990042", foreign)));
        equal(false, mappings().containsKey(foreign), "unrelated playlist is not registered");
        WatchVars.get(html("{\"media\":{\"hls\":{\"url\":\"" + url + "\"}}}"));
        equal("sm990040", mappings().get(url), "missing video ID cannot overwrite mapping");
    }

    private static void rewrite(String url, String body, int status) throws Exception {
        WatchRewriter rewriter = new WatchRewriter();
        Matcher match = rewriter.getRewriterSupportedURLAsPattern().matcher(url);
        equal(true, match.matches(), "watchV4 API matches rewriter");
        equal(body, rewriter.onMatch(match,
                new HttpResponseHeader("HTTP/1.1 " + status + " Fixture\r\n\r\n"), body),
                "API response body passes through unchanged");
    }

    private static Map<?, ?> quality(WatchVars vars, String name) throws Exception {
        Field field = WatchVars.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>)field.get(vars);
    }

    private static Map<?, ?> mappings() throws Exception {
        Field field = CmafCachingProcessor.class.getDeclaredField("masterPlaylistToSmid");
        field.setAccessible(true);
        return (Map<?, ?>)field.get(null);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
