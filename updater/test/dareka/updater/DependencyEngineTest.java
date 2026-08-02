package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for the winget-first dependency policy and automatic install scope selection. */
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
            assertContains(result, "winget-auto-scope", "winget automatic scope selection");
            assertContains(result, "fallback", "fallback policy");

            List<String> winget = SystemDependencyManager.wingetArguments(
                    "winget", "install", "EclipseAdoptium.Temurin.25.JDK");
            assertTrue(!winget.contains("--scope"),
                    "WinGet user scope requirement rejected machine-only packages: " + winget);
            int source = winget.indexOf("--source");
            assertTrue(source >= 0 && source + 1 < winget.size() && "winget".equals(winget.get(source + 1)),
                    "WinGet source was not fixed to the community repository: " + winget);
            assertWingetAppExecutionAliasResolution();

            String merged = SystemDependencyManager.mergePath(
                    "C:\\Windows\\System32;C:\\Tools\\bin;C:\\TOOLS\\BIN\\",
                    Path.of("C:\\Tools\\bin"));
            int count = 0;
            for (String entry : merged.split(";")) {
                if (SystemDependencyManager.normalizePathEntry(entry)
                        .equals(SystemDependencyManager.normalizePathEntry("C:\\Tools\\bin"))) count++;
            }
            assertTrue(count == 1, "User PATH contained duplicate entries: " + merged);

            String java25 = engine.checkAll(25);
            assertContains(java25, "Eclipse Temurin JDK", "Java 25 LTS dependency check");
            assertContains(java25, "GPAC / MP4Box", "GPAC dependency check");

            boolean invalidLts = false;
            try {
                engine.checkAll(29);
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

    private static void assertWingetAppExecutionAliasResolution() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) return;
        Path alias = Path.of(localAppData, "Microsoft", "WindowsApps", "winget.exe");
        if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) return;
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("LOCALAPPDATA", localAppData);
        assertTrue(alias.toString().equals(SystemDependencyManager.resolveWingetExecutable(environment)),
                "WinGet App Execution Alias reparse point was ignored: " + alias);
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
