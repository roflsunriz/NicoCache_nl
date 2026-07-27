package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Unit tests for the winget-first user dependency policy. */
public final class DependencyEngineTest {
    private DependencyEngineTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dependency-engine-test-");
        try {
            System.setProperty("nicocache.updater.userProgramsRoot", root.resolve("programs").toString());
            DependencyEngine engine = new DependencyEngine(root);
            String result = engine.selfTestTransactions();
            assertContains(result, "SYSTEM_DEPENDENCY_SELF_TEST_OK", "system dependency self-test");
            assertContains(result, "winget-first", "winget priority");
            assertContains(result, "fallback", "fallback policy");

            String merged = SystemDependencyManager.mergePath(
                    "C:\\Windows\\System32;C:\\Tools\\bin;C:\\TOOLS\\BIN\\",
                    Path.of("C:\\Tools\\bin"));
            int count = 0;
            for (String entry : merged.split(";")) {
                if (SystemDependencyManager.normalizePathEntry(entry)
                        .equals(SystemDependencyManager.normalizePathEntry("C:\\Tools\\bin"))) count++;
            }
            assertTrue(count == 1, "User PATH contained duplicate entries: " + merged);

            boolean invalidLts = false;
            try {
                engine.checkAll(25);
            } catch (IOException expected) {
                invalidLts = expected.getMessage().contains("未検証のTemurin");
            }
            assertTrue(invalidLts, "Unvalidated Temurin LTS was accepted");
            System.out.println("Winget-first dependency policy tests passed");
        } finally {
            System.clearProperty("nicocache.updater.userProgramsRoot");
            deleteTree(root);
        }
    }

    private static void assertContains(String value, String expected, String label) {
        assertTrue(value.contains(expected), label + " missing: " + expected + " in " + value);
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
}
