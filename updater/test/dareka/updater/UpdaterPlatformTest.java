package dareka.updater;

import java.nio.file.Path;

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

        Path macLauncher = Path.of("Applications/NicoCache_nl.app/Contents/MacOS/NicoCache_nl");
        Path macRoot = UpdaterPlatform.applicationRootFromLauncher(
                macLauncher, UpdaterPlatform.Kind.MACOS);
        assertEquals(Path.of("Applications/NicoCache_nl.app/Contents").toAbsolutePath().normalize(), macRoot,
                "macOS launcher root");
        Path linuxLauncher = Path.of("opt/NicoCache_nl/bin/NicoCache_nl");
        Path linuxRoot = UpdaterPlatform.applicationRootFromLauncher(
                linuxLauncher, UpdaterPlatform.Kind.LINUX);
        assertEquals(Path.of("opt/NicoCache_nl").toAbsolutePath().normalize(), linuxRoot,
                "Linux launcher root");
        assertEquals(Path.of("opt/NicoCache_nl/bin/NicoCache_nl").toAbsolutePath().normalize(),
                UpdaterPlatform.launcherPath(linuxRoot, UpdaterPlatform.Kind.LINUX),
                "Linux launcher path");
        System.out.println("Updater platform tests passed");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
