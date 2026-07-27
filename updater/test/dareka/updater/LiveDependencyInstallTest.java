package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Disposable-runner E2E: install/update dependencies and resolve them from a fresh shell. */
public final class LiveDependencyInstallTest {
    private LiveDependencyInstallTest() {}

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.out.println("Live dependency install test skipped outside Windows");
            return;
        }
        Path root = Files.createTempDirectory("dependency-live-install-");
        try {
            Files.writeString(root.resolve("NicoCache_nl.jar"), "test marker", StandardCharsets.UTF_8);
            DependencyEngine engine = new DependencyEngine(root);
            String result = engine.updateAll(21);
            assertContains(result, "Eclipse Temurin JDK", "Temurin result");
            assertContains(result, "FFmpeg", "FFmpeg result");
            assertContains(result, "Apache Ant", "Ant result");
            assertContains(result, "7-Zip", "7-Zip result");
            assertContains(result, "Bouncy Castle", "Bouncy Castle result");

            verifyFreshShell(Arrays.asList("java", "-version"));
            verifyFreshShell(Arrays.asList("javac", "-version"));
            verifyFreshShell(Arrays.asList("ffmpeg", "-version"));
            verifyFreshShell(Arrays.asList("ffprobe", "-version"));
            verifyFreshShell(Arrays.asList("ant", "-version"));
            verifyFreshShell(Arrays.asList("7z"));

            assertTrue(Files.isRegularFile(root.resolve("lib/bcprov.jar")), "Bouncy Castle was not installed locally");
            assertTrue(!Files.exists(root.resolve("runtime")), "Temurin leaked into the NicoCache_nl runtime directory");
            assertTrue(!Files.exists(root.resolve("tools/ffmpeg")), "FFmpeg leaked into the NicoCache_nl directory");
            assertTrue(!Files.exists(root.resolve("tools/ant")), "Ant leaked into the NicoCache_nl directory");
            assertTrue(!Files.exists(root.resolve("tools/7zip")), "7-Zip leaked into the NicoCache_nl directory");
            System.out.println("User-wide dependency installation and fresh-shell command E2E passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyFreshShell(List<String> command) throws Exception {
        StringBuilder line = new StringBuilder();
        for (String value : command) {
            if (line.length() > 0) line.append(' ');
            line.append('"').append(value.replace("\"", "\\\"")).append('"');
        }
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", line.toString())
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { input.transferTo(output); }
            catch (Exception ignored) { }
        });
        reader.start();
        if (!process.waitFor(Duration.ofMinutes(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Fresh-shell command timed out: " + command);
        }
        reader.join(5000);
        assertTrue(process.exitValue() == 0,
                "Fresh-shell command failed: " + command + "\n" + output.toString(StandardCharsets.UTF_8));
    }

    private static void assertContains(String value, String expected, String label) {
        assertTrue(value.contains(expected), label + " missing: " + expected + " in " + value);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
