package dareka.updater;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Verifies the real NicoCache_nl jpackage launcher layout and compatibility fallbacks. */
public final class InstalledVersionDetectorTest {
    private InstalledVersionDetectorTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("installed-version-test-");
        try {
            Path app = root.resolve("lib/app");
            Files.createDirectories(app);
            Files.writeString(app.resolve("NicoCache_nl.cfg"),
                    "[Application]\n"
                            + "app.mainmodule=NicoCache_nl.jar\n"
                            + "\n[JavaOptions]\n"
                            + "java-options=-Djpackage.app-version=1.0.1\n",
                    StandardCharsets.UTF_8);
            assertEquals("1.0.1", InstalledVersionDetector.detect(root), "real launcher config version");

            Files.writeString(root.resolve("version.txt"), "9.9.9\n", StandardCharsets.US_ASCII);
            assertEquals("1.0.1", InstalledVersionDetector.detect(root), "launcher config precedence");

            Files.writeString(root.resolve("NicoCache_nl.version"), "0.1.0\n",
                    StandardCharsets.US_ASCII);
            assertEquals("0.1.0", InstalledVersionDetector.detect(root),
                    "macOS-compatible public version metadata precedence");
            Files.delete(root.resolve("NicoCache_nl.version"));

            Files.writeString(app.resolve("NicoCache_nl.cfg"),
                    "[JavaOptions]\njava-options=-Djpackage.app-version=broken\n",
                    StandardCharsets.UTF_8);
            assertEquals("9.9.9", InstalledVersionDetector.detect(root), "invalid launcher fallback");

            Files.delete(root.resolve("version.txt"));
            Files.writeString(app.resolve(".jpackage.xml"),
                    "<?xml version=\"1.0\"?><jpackage-state><app-version>1.0.2</app-version></jpackage-state>",
                    StandardCharsets.UTF_8);
            assertEquals("1.0.2", InstalledVersionDetector.detect(root), "jpackage xml compatibility fallback");

            Path flatRoot = root.resolve("flat");
            Files.createDirectories(flatRoot);
            Files.write(flatRoot.resolve("NicoCache_nl.jar"), new byte[] { 0 });
            Files.writeString(flatRoot.resolve("NicoCache_nl.cmd"), "@echo off\n",
                    StandardCharsets.US_ASCII);
            Files.writeString(flatRoot.resolve("version.txt"), "9.9.9\n",
                    StandardCharsets.US_ASCII);
            assertEquals("不明", InstalledVersionDetector.detect(flatRoot),
                    "flat launcher rejects stale compatibility marker");
            Files.writeString(flatRoot.resolve("NicoCache_nl.version"), "1.3.0\n",
                    StandardCharsets.US_ASCII);
            assertEquals("1.3.0", InstalledVersionDetector.detect(flatRoot),
                    "flat launcher public version metadata");
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
