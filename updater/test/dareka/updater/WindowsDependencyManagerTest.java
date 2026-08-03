package dareka.updater;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Regression tests for Windows command output decoding and WinGet version parsing. */
public final class WindowsDependencyManagerTest {
    private WindowsDependencyManagerTest() {
    }

    public static void main(String[] args) throws Exception {
        Charset japanese = Charset.forName("windows-31j");
        String message = "Apache Ant の版情報";
        assertEquals(message,
                WindowsDependencyManager.decodeCommandOutput(
                        message.getBytes(japanese), japanese),
                "Windows command output decoding");
        assertEquals(message,
                WindowsDependencyManager.decodeCommandOutput(
                        message.getBytes(StandardCharsets.UTF_8), japanese),
                "UTF-8 command output detection");
        byte[] utf16 = ("\uFEFF" + message).getBytes(StandardCharsets.UTF_16LE);
        assertEquals(message,
                WindowsDependencyManager.decodeCommandOutput(utf16, japanese),
                "UTF-16 command output BOM detection");

        assertEquals("8.1.2", WindowsDependencyManager.parseWingetVersion(
                "Name: FFmpeg\r\nVersion: 8.1.2\r\nRelease Date: 2026.08.02\r\n"),
                "WinGet FFmpeg version");
        assertEquals("8.1.2", WindowsDependencyManager.parseWingetVersion(
                "名前: FFmpeg\nバージョン: 8.1.2\n"),
                "localized WinGet version");
        if (WindowsDependencyManager.parseWingetVersion(
                "Name: FFmpeg\nRelease Date: 2026.08.02\n") != null) {
            throw new AssertionError("A release date must not be treated as an FFmpeg version");
        }

        List<String> show = WindowsDependencyManager.wingetShowArguments(
                "winget", "Gyan.FFmpeg");
        assertTrue(show.contains("show"), "WinGet show operation missing");
        assertTrue(show.contains("--source") && show.contains("winget"),
                "WinGet source was not fixed");
        assertTrue(!show.contains("--scope"), "WinGet show unexpectedly fixed an install scope");

        List<String> ant = WindowsDependencyManager.commandInvocation(
                Path.of("C:\\Users\\tester\\NicoCache_nl Dependencies\\ant.bat"),
                Arrays.asList("ant", "-version"));
        assertEquals("cmd.exe", ant.get(0), "Ant batch launcher");
        assertEquals("/d", ant.get(1), "Ant batch command extensions");
        assertEquals("/c", ant.get(2), "Ant batch command shell");
        assertTrue(ant.size() == 4 && ant.get(3).contains("-version"),
                "Ant version argument was not included in the cmd command line");
        assertBatchCommandRunsWithSpaces();

        String mergedPath = WindowsDependencyManager.mergePathStrings(
                "C:\\Windows\\System32;C:\\Java\\bin",
                "C:\\UserTools\\bin;C:\\JAVA\\BIN\\");
        assertTrue(mergedPath.split(";").length == 3,
                "Machine and user PATH entries were not preserved without duplicates: " + mergedPath);
        System.out.println("Windows dependency manager tests passed");
    }

    private static void assertBatchCommandRunsWithSpaces() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        Path directory = Files.createTempDirectory("NicoCache ant test ");
        Path batch = directory.resolve("ant.bat");
        try {
            Files.write(batch, Arrays.asList("@echo off", "@echo Version 9.9.9"),
                    StandardCharsets.US_ASCII);
            List<String> invocation = WindowsDependencyManager.commandInvocation(
                    batch, Arrays.asList("ant", "-version"));
            Process process = new ProcessBuilder(invocation).redirectErrorStream(true).start();
            String output = WindowsDependencyManager.decodeCommandOutput(
                    process.getInputStream().readAllBytes(),
                    WindowsDependencyManager.commandOutputCharset());
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Batch command invocation timed out");
            }
            assertTrue(process.exitValue() == 0 && output.contains("Version 9.9.9"),
                    "Batch command invocation failed: " + String.join(" | ", invocation)
                            + " -> " + output);
        } finally {
            Files.deleteIfExists(batch);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
