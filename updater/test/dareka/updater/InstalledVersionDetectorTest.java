package dareka.updater;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Verifies real jpackage metadata version detection and marker precedence. */
public final class InstalledVersionDetectorTest {
    private InstalledVersionDetectorTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("installed-version-test-");
        try {
            Path app = root.resolve("app");
            Files.createDirectories(app);
            Files.writeString(app.resolve(".jpackage.xml"),
                    "<?xml version=\"1.0\"?><jpackage-state><app-version>1.0.1</app-version></jpackage-state>",
                    StandardCharsets.UTF_8);
            assertEquals("1.0.1", InstalledVersionDetector.detect(root), "jpackage version");

            Files.writeString(root.resolve("version.txt"), "1.0.2\n", StandardCharsets.US_ASCII);
            assertEquals("1.0.2", InstalledVersionDetector.detect(root), "version marker precedence");

            Files.writeString(root.resolve("version.txt"), "broken", StandardCharsets.US_ASCII);
            assertEquals("1.0.1", InstalledVersionDetector.detect(root), "invalid marker fallback");
            System.out.println("Installed version detection tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
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
