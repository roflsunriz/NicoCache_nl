package functional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** 初期設定の分類と廃止済み設定の再混入を防ぐ。 */
public final class DefaultsLayoutUnitTest {
    private static final List<String> EXPECTED_FILES = List.of(
            "application.properties",
            "https-mitm.properties",
            "legacy-cache-compatibility.properties",
            "network.properties",
            "rewriting.properties",
            "thumbnail-cache.properties",
            "video-cache.properties");

    private static final Set<String> REMOVED_KEYS = Set.of(
            "cacheAllocateFirst",
            "deletedVideoId",
            "flv2Mp4AdaptToFlash",
            "insertSearchResultToTagPage",
            "localFlv",
            "niconicoMode",
            "quickThumbnailCache",
            "reportCachingProgress",
            "resumeDownload",
            "scriptOn",
            "scriptTarget",
            "scriptText",
            "searchResultMax",
            "swfDebug",
            "thcacheFixEpoch",
            "thcacheMode",
            "useSearchExtension",
            "useWorkaroundFastFinalize",
            "useWorkaroundForEncoding");

    private DefaultsLayoutUnitTest() {
    }

    public static void run(Path repository) throws Exception {
        Path defaults = repository.resolve("defaults");
        List<Path> files;
        try (var paths = Files.list(defaults)) {
            files = paths.filter(path -> path.getFileName().toString()
                            .endsWith(".properties"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        List<String> names = files.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        assertEquals(EXPECTED_FILES, names, "初期設定ファイルの分類");

        Properties merged = new Properties();
        Set<String> loadedKeys = new HashSet<>();
        for (Path file : files) {
            Properties current = load(file);
            for (String key : current.stringPropertyNames()) {
                if (!loadedKeys.add(key)) {
                    throw new AssertionError("初期設定キーが重複しています: " + key);
                }
                if (REMOVED_KEYS.contains(key)) {
                    throw new AssertionError("廃止済み設定が残っています: " + key);
                }
            }
            merged.putAll(current);
        }

        assertEquals("true", merged.getProperty("enableMitm"),
                "現行動画に必要な HTTPS MitM");
        assertEquals("true", merged.getProperty("cacheThumbnail"),
                "現行サムネイルのファイルキャッシュ");
        assertEquals("true", merged.getProperty("cacheGetThumbInfo"),
                "稼働中 getthumbinfo のメモリーキャッシュ");
        assertEquals("true", merged.getProperty("useNotReEncodedCache"),
                "既存の旧形式キャッシュ互換");
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
