package nicocache.cmaftomp4;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 外部ライブラリなしで実行できる独立アプリの単体テスト。 */
public final class CmafToMp4Tests {
    private static int assertions;

    private CmafToMp4Tests() {
    }

    public static void main(String[] args) throws Exception {
        testCacheLocation();
        testMultipleCacheLocation();
        testCliOptions();
        testFfmpegCommand();
        testMissingFfmpegRemovesTemporaryFile();
        System.out.println("CMAF/Domand converter tests passed (" + assertions + " assertions)");
    }

    private static void testCacheLocation() throws Exception {
        Path root = Files.createTempDirectory("cmaf-to-mp4-test-");
        Path cache = Files.createDirectories(root.resolve("sm12345[720p,128]_Title.hls"));
        Path master = cache.resolve("master.m3u8");
        Files.write(master, "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(master.toAbsolutePath().normalize(), CacheLocator.locatePlaylist(cache));
        assertEquals(master.toAbsolutePath().normalize(), CacheLocator.locatePlaylist(master));
        assertEquals(
                cache.resolveSibling("sm12345[720p,128]_Title.mp4").toAbsolutePath().normalize(),
                CacheLocator.defaultOutput(master));

        Path nestedRoot = Files.createDirectories(root.resolve("single-root"));
        Path nestedCache = Files.createDirectories(nestedRoot.resolve("cache"));
        Path nestedMaster = nestedCache.resolve("master.m3u8");
        Files.write(nestedMaster, "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(nestedMaster.toAbsolutePath().normalize(), CacheLocator.locatePlaylist(nestedRoot));
    }

    private static void testMultipleCacheLocation() throws Exception {
        Path root = Files.createTempDirectory("cmaf-to-mp4-multiple-");
        for (int index = 1; index <= 2; index++) {
            Path cache = Files.createDirectories(root.resolve("cache-" + index));
            Files.write(cache.resolve("master.m3u8"), "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        }
        boolean failed = false;
        try {
            CacheLocator.locatePlaylist(root);
        } catch (java.io.IOException e) {
            failed = e.getMessage().contains("2");
        }
        assertTrue(failed, "multiple master playlists must require an explicit selection");
    }

    private static void testCliOptions() throws Exception {
        CliOptions options = CliOptions.parse(new String[] {
            "--headless", "--input", "cache", "--output", "out.mp4",
            "--ffmpeg", "custom-ffmpeg", "--force", "--title", "Title",
            "--lang", "en", "--verbose"
        });
        assertTrue(options.isHeadless(), "headless option");
        assertEquals(Path.of("cache"), options.getInput());
        assertEquals(Path.of("out.mp4"), options.getOutput());
        assertEquals("custom-ffmpeg", options.getFfmpeg());
        assertTrue(options.isForce(), "force option");
        assertTrue(options.isVerbose(), "verbose option");
        assertEquals("Title", options.getTitle());

        CliOptions inline = CliOptions.parse(new String[] {
            "--input=cache", "--output=out.mp4", "--lang=ja", "--open-output"
        });
        assertEquals(Path.of("cache"), inline.getInput());
        assertEquals(Path.of("out.mp4"), inline.getOutput());
        assertTrue(inline.isOpenOutput(), "inline open-output option");
    }

    private static void testFfmpegCommand() throws Exception {
        Path root = Files.createTempDirectory("cmaf-to-mp4-command-");
        Path playlist = root.resolve("master.m3u8");
        Path output = root.resolve("result.mp4");
        Path temporary = root.resolve(".result.mp4.part.mp4");
        ConversionRequest request = new ConversionRequest(
                playlist, output, "ffmpeg-test", false, "Test title");
        List<String> command = FfmpegConverter.buildCommand(request, temporary);
        assertEquals("ffmpeg-test", command.get(0));
        assertContains(command, "-protocol_whitelist");
        assertContains(command, "file,crypto,data");
        assertContains(command, "0:v:0?");
        assertContains(command, "0:a:0?");
        assertContains(command, "title=Test title");
        assertEquals(temporary.toString(), command.get(command.size() - 1));
    }

    private static void testMissingFfmpegRemovesTemporaryFile() throws Exception {
        Path root = Files.createTempDirectory("cmaf-to-mp4-start-");
        Path playlist = root.resolve("master.m3u8");
        Files.write(playlist, "#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        Path output = root.resolve("result.mp4");
        ConversionRequest request = new ConversionRequest(
                playlist, output, root.resolve("missing-ffmpeg").toString(), false, null);
        boolean failed = false;
        try {
            new FfmpegConverter().convert(request, null, () -> false);
        } catch (ConversionException e) {
            failed = e.getKind() == ConversionException.Kind.TOOL_NOT_FOUND;
        }
        assertTrue(failed, "missing FFmpeg must be reported");
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            assertEquals(1L, files.count());
        }
    }

    private static void assertContains(List<String> values, String expected) {
        assertTrue(values.contains(expected), "command contains " + expected);
    }

    private static void assertEquals(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(long expected, long actual) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        assertions++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
