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

            Files.delete(root.resolve("NicoCache_nl.jar"));
            Files.writeString(root.resolve("NicoCache_nl.exe"), "marker");
            assertTrue(TargetRootResolver.isInstallation(root), "NicoCache_nl.exe marker was ignored");

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
