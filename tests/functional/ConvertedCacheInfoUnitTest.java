package functional;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dareka.common.json.JsonObject;
import dareka.common.json.JsonNumber;
import dareka.common.json.JsonString;
import dareka.processor.impl.Cache;
import dareka.processor.impl.CmafCachingProcessor;
import dareka.processor.impl.DomandCVIEntry;
import dareka.processor.impl.VideoDescriptor;

/** ファイル名・索引・REST表示を確認する小さな合成キャッシュ。実動画の変換は行わない。 */
public final class ConvertedCacheInfoUnitTest {
    private ConvertedCacheInfoUnitTest() { }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Converted MP4 cache information regression tests passed");
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("nicocache-converted-cache-");
        Map<String, String> properties = new LinkedHashMap<>();
        for (String key : new String[] {"cacheFolder", "convertedCacheFolder", "convertFlv2Mp4",
                "checkRealCache", "nicocache.userDataRoot", "nicocache.applicationRoot",
                "useNotReEncodedCache", "workaroundNoDisableDoubleCacheImported"}) {
            properties.put(key, System.getProperty(key));
        }
        try {
            System.setProperty("cacheFolder", root.toString());
            System.setProperty("convertedCacheFolder", root.resolve("converted").toString());
            System.setProperty("nicocache.userDataRoot", root.toString());
            System.setProperty("nicocache.applicationRoot", root.toString());
            System.setProperty("convertFlv2Mp4", "false");
            System.setProperty("checkRealCache", "true");
            System.setProperty("useNotReEncodedCache", "false");
            System.setProperty("workaroundNoDisableDoubleCacheImported", "false");
            write(root, "sm991001[720p,192]_Converted.mp4");
            write(root, "sm991002_Legacy.mp4");
            write(root, "sm991003[720p,128]_Converted.mp4");
            write(root, "sm991003[1080p,192]_Hls.hls/master.m3u8");
            write(root, "nltmp_sm991004[720p,192]_Partial.mp4");
            write(root, "sm991005[0p,192]_Audio.mp4");
            write(root, "sm991006low[360p-lowest,64]_Economy.mp4");
            write(root, "sm991007[720p,2000,128]_Dmc.mp4");
            write(root, "nltmp_sm991008[720p,128]_Partial.hls/master.m3u8");
            write(root, "sm991009_Legacy.mp4");
            write(root, "sm991009_Legacy.flv");
            write(root, "nltmp_sm991009_Partial.swf");
            Cache.init();

            JsonObject converted = info("sm991001");
            equal("sm991001[720p,192].mp4", converted.getString("preferred"), "converted MP4 preferred");
            JsonObject mp4 = converted.getObject("caches").getObject("sm991001[720p,192].mp4");
            equal(true, mp4.getBoolean("complete"), "converted MP4 completed");
            equal("MP4", mp4.getString("format"), "converted MP4 format");
            equal("720p", mp4.getString("videoMode"), "converted MP4 video quality");
            equal(192L, ((JsonNumber) mp4.get("audioBitrate")).getLong(), "converted MP4 audio quality");
            equal("sm991001[720p,192].mp4", ((JsonString) converted.getArray("completes").getList().get(0)).value(), "completed list");
            equal("sm991002", info("sm991002").getString("preferred"), "classic MP4 compatible id");
            equal("MP4", info("sm991002").getObject("caches").getObject("sm991002").getString("format"), "classic MP4 format");
            equal("sm991003[1080p,192].hls", info("sm991003").getString("preferred"), "existing HLS preference preserved");
            equal(2, info("sm991003").getObject("caches").getMap().size(), "MP4 and HLS coexist");
            equal(0, info("sm991004").getArray("completes").getList().size(), "partial MP4 is not complete");
            equal(null, info("sm991004").getString("preferred"), "partial MP4 is not preferred");
            equal(false, info("sm991004").getObject("caches").getObject("sm991004[720p,192].mp4").getBoolean("complete"), "partial entry state");
            equal("0p", info("sm991005").getObject("caches").getObject("sm991005[0p,192].mp4").getString("videoMode"), "audio-only mode preserved");
            equal(true, info("sm991006").getObject("caches").getObject("sm991006low[360p-lowest,64].mp4").getBoolean("legacyLow"), "legacy low marker preserved");
            equal("sm991007[720p,2000,128].mp4", info("sm991007").getString("preferred"), "old DMC bitrate naming supported");
            equal(0, info("sm991008").getArray("completes").getList().size(), "partial HLS is not complete");
            JsonObject classicFormats = info("sm991009");
            equal(1, classicFormats.getArray("cacheIds").getList().size(), "classic compatible IDs are unique");
            equal(1, classicFormats.getArray("completes").getList().size(), "partial classic variant cannot hide completion");
            equal(Cache.getPreferredCachedVideo("sm991009").getPostfix().substring(1).toUpperCase(java.util.Locale.ROOT),
                    classicFormats.getObject("caches").getObject("sm991009").getString("format"),
                    "classic entry describes the actually preferred media format");

            equal("sm991001", incompatible("sm991001").getId(), "completed imported MP4 retains HLS saving suppression");
            equal(null, incompatible("sm991004"), "partial MP4 does not suppress HLS saving");
            equal(null, incompatible("sm991006"), "low MP4 does not suppress HLS saving");
            System.setProperty("workaroundNoDisableDoubleCacheImported", "true");
            equal(null, incompatible("sm991001"), "explicit double-cache setting remains effective");

            Files.delete(root.resolve("sm991001[720p,192]_Converted.mp4"));
            equal(0, info("sm991001").getArray("completes").getList().size(), "removed converted MP4 is not reported complete");
        } finally {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static void write(Path root, String name) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        if (name.endsWith(".m3u8")) {
            Files.writeString(file, "#EXTM3U\n#EXT-X-VERSION:7\n#EXTINF:1,\nsegment.cmfv\n"
                    + (name.startsWith("nltmp_") ? "" : "#EXT-X-ENDLIST\n"));
            Files.writeString(file.getParent().resolve("segment.cmfv"), "small segment fixture");
        } else {
            Files.writeString(file, "small cache fixture");
        }
    }

    private static JsonObject info(String id) throws Exception {
        Method method = Class.forName("dareka.processor.impl.CmafCacheInfo").getDeclaredMethod("create", String.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, id);
    }

    private static VideoDescriptor incompatible(String id) throws Exception {
        VideoDescriptor video = VideoDescriptor.newDmc(id, Cache.HLS, false, "720p", 0, 128, "");
        DomandCVIEntry entry = new DomandCVIEntry("fixture", "sm", id.substring(2), 720, 128,
                "720p", "video-h264-720p", null, false, Cache.HLS, null, video, new Cache(video));
        Method method = CmafCachingProcessor.class.getDeclaredMethod("superiorIncompatibleCache", DomandCVIEntry.class);
        method.setAccessible(true);
        return (VideoDescriptor) method.invoke(null, entry);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
}
