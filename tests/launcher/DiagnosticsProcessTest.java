package nicocache.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Verifies the one-way launcher-to-watchdog start contract. */
public final class DiagnosticsProcessTest {
    private DiagnosticsProcessTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nicocache-diagnostics-process-");
        try {
            Path application = root.resolve("application");
            Path data = root.resolve("data");
            Files.createDirectories(application.resolve("jre/bin"));
            Files.createDirectories(data);
            Files.createFile(application.resolve("NicoCache_nl.jar"));
            Files.createFile(application.resolve("NicoCacheDiagnostics.jar"));
            String javaName = System.getProperty("os.name", "")
                    .toLowerCase().contains("win") ? "javaw.exe" : "java";
            Files.createFile(application.resolve("jre/bin").resolve(javaName));

            LauncherPaths paths = LauncherPaths.resolve(application, data);
            DiagnosticsProcess diagnostics = new DiagnosticsProcess(paths);
            List<String> command = diagnostics.buildStartCommand();
            assertTrue(command.contains("--hidden"),
                    "watchdog must start hidden");
            assertTrue(command.contains("--app-root=" + application),
                    "application root must be explicit");
            assertTrue(command.contains("--data-root=" + data),
                    "data root must be explicit");
            int jarIndex = command.indexOf("-jar");
            assertEquals(application.resolve("NicoCacheDiagnostics.jar")
                    .toString(), command.get(jarIndex + 1),
                    "diagnostics JAR must be launched");
            assertFalse(command.stream().anyMatch(value ->
                    value.contains("restart") || value.contains("--start")),
                    "watchdog must not receive a core start or restart option");
            System.out.println("Diagnostics process launch tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) { throw new AssertionError(message); }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
