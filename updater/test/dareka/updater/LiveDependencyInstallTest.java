package dareka.updater;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Live Windows integration test for all three installation shapes: files, ZIP and 7z. */
public final class LiveDependencyInstallTest {
    private LiveDependencyInstallTest() {}

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.out.println("Live dependency install test skipped outside Windows");
            return;
        }
        Path root = Files.createTempDirectory("dependency-live-install-");
        try {
            DependencyEngine engine = new DependencyEngine(root);
            Class<?> releaseClass = Class.forName("dareka.updater.DependencyEngine$Release");
            Method install = DependencyEngine.class.getDeclaredMethod("install", releaseClass);
            install.setAccessible(true);

            Path lib = root.resolve("lib");
            Files.createDirectories(lib);
            Files.writeString(lib.resolve("unrelated.jar"), "keep", StandardCharsets.UTF_8);
            Files.writeString(lib.resolve("bcprov.jar"), "old-bcprov", StandardCharsets.UTF_8);
            install.invoke(engine, resolve(engine, "resolveBouncyCastle"));
            assertFile(lib.resolve("bcprov.jar"), 1_000_000);
            assertFile(lib.resolve("bcpkix.jar"), 100_000);
            assertFile(lib.resolve("bcutil.jar"), 100_000);
            assertText(lib.resolve("unrelated.jar"), "keep", "Bouncy Castle removed unrelated library");
            assertBackupContains(root, "bouncycastle-", "bcprov.jar", "old-bcprov");

            Path ant = root.resolve("tools/ant");
            Files.createDirectories(ant);
            Files.writeString(ant.resolve("old-marker.txt"), "old-ant", StandardCharsets.UTF_8);
            install.invoke(engine, resolve(engine, "resolveAnt"));
            assertFile(ant.resolve("bin/ant.bat"), 100);
            assertBackupContains(root, "ant-", "old-marker.txt", "old-ant");

            Path sevenZip = root.resolve("tools/7zip");
            Files.createDirectories(sevenZip);
            Files.writeString(sevenZip.resolve("old-marker.txt"), "old-7zip", StandardCharsets.UTF_8);
            install.invoke(engine, resolve(engine, "resolveSevenZip"));
            boolean executable;
            try (java.util.stream.Stream<Path> stream = Files.walk(sevenZip)) {
                executable = stream.anyMatch(path -> path.getFileName().toString().equalsIgnoreCase("7za.exe"));
            }
            assertTrue(executable, "7-Zip extra archive did not produce 7za.exe");
            assertBackupContains(root, "7zip-", "old-marker.txt", "old-7zip");

            assertTrue(!Files.exists(root.resolve("tools/selftest")), "Live install leaked self-test files");
            System.out.println("Live Bouncy Castle, Ant and 7-Zip install E2E passed");
        } finally {
            deleteTree(root);
        }
    }

    private static Object resolve(DependencyEngine engine, String methodName) throws Exception {
        Method method = DependencyEngine.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(engine);
    }

    private static void assertBackupContains(Path root, String prefix, String fileName,
            String expected) throws Exception {
        Path backups = root.resolve(".runtime-dependency-updater/backups");
        boolean found = false;
        if (Files.isDirectory(backups)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(backups)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file)
                            && file.getFileName().toString().equals(fileName)
                            && file.getParent().getFileName().toString().startsWith(prefix)
                            && expected.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                        found = true;
                        break;
                    }
                }
            }
        }
        assertTrue(found, "Expected backup not found for " + prefix + fileName);
    }

    private static void assertFile(Path file, long minimumSize) throws Exception {
        assertTrue(Files.isRegularFile(file), "Missing file: " + file);
        assertTrue(Files.size(file) >= minimumSize, "File is unexpectedly small: " + file);
    }

    private static void assertText(Path file, String expected, String message) throws Exception {
        assertTrue(Files.isRegularFile(file), "Missing file: " + file);
        assertTrue(expected.equals(Files.readString(file, StandardCharsets.UTF_8)), message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
