package nicocache.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            DiagnosticsPaths paths = DiagnosticsPaths.resolve(
                    application, data);
            String classPathCommand = "java -cp diagnostics-tests "
                    + DiagnosticsMain.class.getName()
                    + " --app-root=" + paths.applicationRoot()
                    + " --data-root=" + paths.dataRoot() + " --hidden";
            assertTrue(DiagnosticsControl.matchesCommandLine(
                            classPathCommand, paths),
                    "test classpath launch must match its exact roots");
            assertTrue(!DiagnosticsControl.matchesCommandLine(
                            classPathCommand.replace(
                                    "--data-root=" + paths.dataRoot(),
                                    "--data-root=" + root.resolve("other")),
                            paths),
                    "classpath launch with another data root must not match");
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
            Process shutdown = new ProcessBuilder(java.toString(),
                    "-Djava.awt.headless=true", "-cp",
                    System.getProperty("java.class.path"),
                    DiagnosticsMain.class.getName(),
                    "--app-root=" + application,
                    "--data-root=" + data, "--shutdown")
                    .redirectErrorStream(true).start();
            assertTrue(shutdown.waitFor(15L, TimeUnit.SECONDS),
                    "planned shutdown command must finish");
            String shutdownOutput = new String(
                    shutdown.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, shutdown.exitValue(),
                    "planned shutdown command exit code; output="
                            + shutdownOutput.trim());
            assertTrue(process.waitFor(5L, TimeUnit.SECONDS),
                    "planned shutdown must stop the watchdog");
            assertTrue(!Files.exists(statusPath),
                    "planned shutdown must remove diagnostics status");
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
