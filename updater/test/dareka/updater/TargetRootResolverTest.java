package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Tests target-root defaults, markers and rejection of arbitrary directories. */
public final class TargetRootResolverTest {
    private TargetRootResolverTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("updater-target-root-");
        try {
            assertTrue(!TargetRootResolver.isInstallation(root), "empty directory was accepted");
            expectFailure(() -> TargetRootResolver.requireInstallation(root),
                    "empty directory was not rejected");

            Files.writeString(root.resolve("NicoCache_nl.jar"), "marker");
            assertTrue(TargetRootResolver.isInstallation(root), "NicoCache_nl.jar marker was ignored");
            assertTrue(TargetRootResolver.requireInstallation(root).equals(root.toAbsolutePath().normalize()),
                    "validated root was changed");

            Path preferred = root.resolve("preferred/NicoCache_nl");
            Path oldProgramsRoot = root.resolve("Programs/NicoCache_nl");
            Files.createDirectories(oldProgramsRoot);
            Files.writeString(oldProgramsRoot.resolve("NicoCache_nl.jar"), "jar");
            Files.writeString(oldProgramsRoot.resolve("NicoCache_nl.version"), "1.3.0");
            Path selected = TargetRootResolver.selectInstalledRoot(preferred, oldProgramsRoot);
            assertTrue(selected.equals(oldProgramsRoot.toAbsolutePath().normalize()),
                    "released LOCALAPPDATA\\Programs installation was not discovered");
            assertTrue("1.3.0".equals(InstalledVersionDetector.detect(selected)),
                    "version in discovered Programs installation was not detected");
            Files.createDirectories(preferred);
            Files.writeString(preferred.resolve("NicoCache_nl.jar"), "jar");
            assertTrue(TargetRootResolver.selectInstalledRoot(preferred, oldProgramsRoot)
                    .equals(preferred.toAbsolutePath().normalize()),
                    "preferred LOCALAPPDATA installation did not take precedence");

            assertTrue(TargetRootResolver.selectPostUpdateRoot(oldProgramsRoot, preferred,
                    oldProgramsRoot, UpdaterPlatform.Kind.WINDOWS)
                    .equals(preferred.toAbsolutePath().normalize()),
                    "released Programs installation did not migrate to the new default root");
            Path customRoot = root.resolve("custom/NicoCache_nl");
            Files.createDirectories(customRoot);
            Files.writeString(customRoot.resolve("NicoCache_nl.jar"), "jar");
            assertTrue(TargetRootResolver.selectPostUpdateRoot(customRoot, preferred,
                    oldProgramsRoot, UpdaterPlatform.Kind.WINDOWS)
                    .equals(customRoot.toAbsolutePath().normalize()),
                    "custom Windows installation was replaced by the default root");
            assertTrue(TargetRootResolver.selectPostUpdateRoot(oldProgramsRoot, preferred,
                    oldProgramsRoot, UpdaterPlatform.Kind.LINUX)
                    .equals(oldProgramsRoot.toAbsolutePath().normalize()),
                    "non-Windows installation unexpectedly used Windows migration policy");

            Files.delete(root.resolve("NicoCache_nl.jar"));
            Files.writeString(root.resolve("NicoCache_nl.exe"), "marker");
            assertTrue(TargetRootResolver.isInstallation(root), "NicoCache_nl.exe marker was ignored");

            Path linuxImage = root.resolve("linux-image");
            Files.createDirectories(linuxImage.resolve("lib/app"));
            Files.createDirectories(linuxImage.resolve("bin"));
            Files.writeString(linuxImage.resolve("lib/app/NicoCache_nl.jar"), "jar");
            Files.writeString(linuxImage.resolve("bin/NicoCache_nl"), "launcher");
            assertTrue(TargetRootResolver.isInstallation(linuxImage),
                    "Linux jpackage app image was not detected");

            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                Path expected = Path.of(localAppData, "NicoCache_nl").toAbsolutePath().normalize();
                assertTrue(TargetRootResolver.defaultRoot().equals(expected),
                        "default root is not LOCALAPPDATA\\NicoCache_nl");
            }
            System.out.println("Target root resolution tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void expectFailure(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private interface Action { void run() throws Exception; }
}
