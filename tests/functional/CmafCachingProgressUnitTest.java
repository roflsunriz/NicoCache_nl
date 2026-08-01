package functional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import dareka.processor.impl.CacheManager;

/** CMAF進捗ログのサイズ集計に関する回帰テスト。 */
public final class CmafCachingProgressUnitTest {
    private CmafCachingProgressUnitTest() {
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("nicocache-cmaf-progress-");
        try {
            writeSized(root.resolve("video/01.cmfv"), 4096);
            writeSized(root.resolve("audio/01.cmfa"), 512);

            // 復号前・復号中の作業ファイルが大きく変化しても、保存済みサイズは変えない。
            writeSized(root.resolve("tmpcmfP_01.cmfv"), 2_000_000);
            writeSized(root.resolve("tmpcmfD_01.cmfv"), 1_000_000);
            writeSized(root.resolve("untracked.bin"), 32_768);

            Set<String> cachedSegments = new LinkedHashSet<>();
            cachedSegments.add("video/01.cmfv");
            cachedSegments.add("audio/01.cmfa");

            long initial = getCachedSegmentBytes(root, cachedSegments);
            assertEquals(4608L, initial,
                    "完成済みセグメントだけをサイズ集計する");

            writeSized(root.resolve("tmpcmfP_01.cmfv"), 4_000_000);
            writeSized(root.resolve("tmpcmfD_01.cmfv"), 128);
            long afterWorkFileChange = getCachedSegmentBytes(root, cachedSegments);
            assertEquals(initial, afterWorkFileChange,
                    "作業ファイルの増減で表示サイズが変化しない");

            writeSized(root.resolve("video/02.cmfv"), 8192);
            cachedSegments.add("video/02.cmfv");
            long afterSegmentCompletion = getCachedSegmentBytes(root, cachedSegments);
            assertEquals(12800L, afterSegmentCompletion,
                    "セグメント完了時だけ保存済みサイズが増える");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void writeSized(Path path, int size) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[size]);
    }

    private static long getCachedSegmentBytes(Path root, Set<String> cachedSegments)
            throws Exception {
        Method method = CacheManager.class.getDeclaredMethod(
                "getCachedSegmentBytes", java.io.File.class, Set.class);
        method.setAccessible(true);
        return (Long)method.invoke(null, root.toFile(), cachedSegments);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException)e.getCause();
            }
            throw e;
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
