package nicocache.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Regression tests for GUI and headless core launch modes. */
public final class CoreProcessTest {
    private CoreProcessTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nicocache-core-process-test-");
        try {
            Path application = root.resolve("application");
            Path data = root.resolve("data");
            Files.createDirectories(application);
            Files.createDirectories(data);
            Files.createFile(application.resolve("NicoCache_nl.jar"));
            Path jreBin = application.resolve("jre/bin");
            Files.createDirectories(jreBin);
            Files.createFile(jreBin.resolve(
                    System.getProperty("os.name", "").toLowerCase()
                            .contains("win") ? "java.exe" : "java"));

            LauncherPaths paths = LauncherPaths.resolve(application, data);
            CoreProcess core = new CoreProcess(paths);
            List<String> guiCommand = core.buildStartCommand(false);
            assertTrue(guiCommand.get(0).startsWith(jreBin.toString()),
                    "core must use the bundled JRE");
            assertFalse(guiCommand.contains("--headless"),
                    "GUI launch must not disable the core GUI");
            assertTrue(guiCommand.contains(
                            "--enable-native-access=ALL-UNNAMED"),
                    "zstd-jni native access must be explicitly enabled");

            List<String> headlessCommand = core.buildStartCommand(true);
            assertTrue(headlessCommand.contains("--headless"),
                    "headless launch must disable the core GUI");
            assertEquals(1, count(headlessCommand, "--headless"),
                    "headless flag must be passed exactly once");
            assertEquals(paths.getCoreJar().toString(),
                    headlessCommand.get(headlessCommand.indexOf("-jar") + 1),
                    "core JAR must be launched in both modes");

            Files.delete(jreBin.resolve(
                    System.getProperty("os.name", "").toLowerCase()
                            .contains("win") ? "java.exe" : "java"));
            assertTrue(core.buildStartCommand(false).get(0).startsWith(
                            Path.of(System.getProperty("java.home"), "bin")
                                    .toString()),
                    "development checkout must fall back to the current JRE");
            System.out.println("Core process mode tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static int count(List<String> values, String expected) {
        int count = 0;
        for (String value : values) {
            if (expected.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static void deleteTree(Path root) throws IOException {
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

    private static void assertFalse(boolean value, String message) {
        if (value) {
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
