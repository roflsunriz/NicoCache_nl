package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Verifies platform and app-image naming policy without requiring another OS. */
public final class UpdaterPlatformTest {
    private UpdaterPlatformTest() {
    }

    public static void main(String[] args) {
        assertEquals(UpdaterPlatform.Kind.WINDOWS, UpdaterPlatform.detect("Windows 11"),
                "Windows detection");
        assertEquals(UpdaterPlatform.Kind.LINUX, UpdaterPlatform.detect("Linux"),
                "Linux detection");
        assertEquals(UpdaterPlatform.Kind.MACOS, UpdaterPlatform.detect("Mac OS X"),
                "macOS detection");
        assertEquals(UpdaterPlatform.Kind.OTHER, UpdaterPlatform.detect("Solaris"),
                "Solaris is outside the release target");
        assertEquals("linux", UpdaterPlatform.platformId(UpdaterPlatform.Kind.LINUX),
                "Linux package ID");
        assertEquals("macos", UpdaterPlatform.platformId(UpdaterPlatform.Kind.MACOS),
                "macOS package ID");

        Path macLauncher = Path.of("Applications/NicoCache_nl/NicoCache_nl");
        Path macRoot = UpdaterPlatform.applicationRootFromLauncher(
                macLauncher, UpdaterPlatform.Kind.MACOS);
        assertEquals(Path.of("Applications/NicoCache_nl").toAbsolutePath().normalize(), macRoot,
                "macOS launcher root");
        Path linuxLauncher = Path.of("opt/nicocache-nl/NicoCache_nl");
        Path linuxRoot = UpdaterPlatform.applicationRootFromLauncher(
                linuxLauncher, UpdaterPlatform.Kind.LINUX);
        assertEquals(Path.of("opt/nicocache-nl").toAbsolutePath().normalize(), linuxRoot,
                "Linux launcher root");
        assertEquals(Path.of("opt/nicocache-nl/NicoCache_nl").toAbsolutePath().normalize(),
                UpdaterPlatform.launcherPath(linuxRoot, UpdaterPlatform.Kind.LINUX),
                "Linux launcher path");
        try {
            Path macContents = Files.createTempDirectory("updater-platform-test-");
            try {
                Files.createDirectories(macContents.resolve("jre/lib"));
                assertEquals(macContents.resolve("jre"),
                        UpdaterPlatform.runtimeDirectory(macContents),
                        "flat bundled runtime directory");
            } finally {
                try (Stream<Path> paths = Files.walk(macContents)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException error) {
                                    throw new RuntimeException(error);
                                }
                            });
                }
            }
        } catch (IOException error) {
            throw new AssertionError("macOS runtime directory test setup failed", error);
        }
        System.out.println("Updater platform tests passed");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
