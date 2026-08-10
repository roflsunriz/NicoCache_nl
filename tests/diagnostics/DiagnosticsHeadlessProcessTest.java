package nicocache.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Verifies that hidden monitoring remains alive without a graphics device. */
public final class DiagnosticsHeadlessProcessTest {
    private DiagnosticsHeadlessProcessTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("diagnostics-headless-");
        Process process = null;
        try {
            Path application = root.resolve("app");
            Path data = root.resolve("data");
            Files.createDirectories(application);
            Files.createDirectories(data);
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    isWindows() ? "java.exe" : "java");
            process = new ProcessBuilder(java.toString(),
                    "-Djava.awt.headless=true", "-cp",
                    System.getProperty("java.class.path"),
                    DiagnosticsMain.class.getName(),
                    "--app-root=" + application,
                    "--data-root=" + data, "--hidden")
                    .redirectErrorStream(true).start();

            Path statusPath = data.resolve(
                    "data/nicocache-diagnostics-status.properties");
            waitForStatus(statusPath, process, Duration.ofSeconds(10));
            Properties status = new Properties();
            try (var input = Files.newInputStream(statusPath)) {
                status.load(input);
            }
            assertEquals(Long.toString(process.pid()),
                    status.getProperty("pid"),
                    "status PID must identify the hidden watchdog");
            assertTrue(process.isAlive(),
                    "headless hidden watchdog must remain running");
            System.out.println("Diagnostics headless process tests passed");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5L, TimeUnit.SECONDS);
                }
            }
            deleteTree(root);
        }
    }

    private static void waitForStatus(Path statusPath, Process process,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(statusPath)) {
                return;
            }
            if (!process.isAlive()) {
                throw new AssertionError(
                        "headless watchdog exited before writing status");
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("headless watchdog status timed out");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase()
                .contains("win");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
